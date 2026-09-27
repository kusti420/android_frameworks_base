/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.oneui.styles;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;

import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.res.R;

/**
 * The Android 7.1.1 recents stack, as a {@link RecyclerView.LayoutManager}.
 *
 * <p>This is an adaptation of {@code com.android.systemui.recents.views.TaskStackLayoutAlgorithm}
 * (AOSP 7.1.1, Apache-2.0) in its <em>unfocused</em> state — the state recents is always in unless
 * you arrive with alt-tab or with paging debug flags on, neither of which exists here. Everything
 * below is that class's arithmetic; only the plumbing (RecyclerView instead of TaskStackView) is
 * new. The focused curve, freeform workspace, per-task position overrides and the single-task
 * special case are deliberately not ported — see the notes on the individual members.
 *
 * <h3>The model</h3>
 *
 * <p>The whole stack is one float, {@link #mScrollP}, measured in <em>task-index units</em>, not
 * pixels. Each task sits at a fixed progress {@code taskP} equal to its index from the back of the
 * stack, and its position on screen is a pure function of {@code relP = taskP - mScrollP}: that
 * value is normalised into the visible window {@code [-2, +2.5]} and fed to a fixed bezier whose y
 * is the card's top as a fraction of the stack height. So the cards bunch up towards the top of the
 * screen (16dp, then 32dp apart) and fan out towards the bottom (201dp, then 322dp on a 760dp
 * stack) with no per-card state at all.
 *
 * <p>Two consequences worth stating, because getting them wrong is what the previous
 * {@code LinearLayoutManager}-plus-negative-{@code ItemDecoration} implementation did:
 *
 * <ul>
 *   <li><b>The scroll range depends only on the task count and the stack height</b>, never on the
 *       sum of the card heights. {@code mMaxScrollP = (n-1) - K}, where {@code K} is a constant of
 *       the curve (~0.511 on a 760dp stack). Deriving the extent from decorated child heights, as
 *       RecyclerView does for you, gave four tall-but-negatively-offset cards a total extent
 *       smaller than the viewport, so the list refused to scroll at all.
 *   <li><b>Cards never scale and never fade.</b> {@code TaskStackLayoutAlgorithm#getStackTransform}
 *       hard-assigns {@code transformOut.scale = 1f} and {@code transformOut.alpha = 1f} on every
 *       path. Depth reads purely from translationZ (3dp…24dp) and from a dim that follows its own
 *       curve.
 * </ul>
 *
 * <h3>Ordering</h3>
 *
 * <p>AOSP index {@code n-1} is the most recent task, at the front, at the bottom of the screen. Our
 * adapter is the other way round (position 0 is the most recent), so {@code taskP = (n-1) - pos}.
 * Children are attached in <em>descending position order</em>, which makes child index order equal
 * Z order. That is what lets the host's stock {@link androidx.recyclerview.widget.ItemTouchHelper}
 * work: it resolves the swiped card with {@link RecyclerView#findChildViewUnder}, which walks
 * children from the last index down and tests raw bounds with no notion of Z, so the last child has
 * to be the front-most card or the wrong (possibly invisible) task gets dismissed.
 */
class CascadeLayoutManager extends RecyclerView.LayoutManager {

    /**
     * The unfocused visible range, {@code R.integer.recents_layout_unfocused_range_min/max} in
     * 7.1.1. A task more than 2 units behind the scroll position, or more than 2.5 ahead of it, is
     * off the curve.
     */
    private static final float RANGE_MIN = -2.0f;
    private static final float RANGE_MAX = 2.5f;

    /** {@code TaskStackLayoutAlgorithm.MAX_DIM} / {@code MED_DIM}. */
    private static final float MAX_DIM = 0.25f;
    private static final float MED_DIM = 0.15f;

    // The 7.1.1 phone dimens, in dp. All of these were resources; they are inlined because this
    // style has to look the same regardless of what the host ROM's recents dimens say.
    /** recents_layout_top_margin_phone == side_margin_phone == bottom_margin == min_margin. */
    private static final float MARGIN_DP = 16f;
    /**
     * {@code AnimateableViewBounds.MIN_ALPHA} / {@code MAX_ALPHA}: the range the depth-driven
     * {@code viewOutlineAlpha} (0..2) is mapped into, so the shadows of cards further back are
     * fainter. The outline alpha is what scales the elevation shadow's opacity.
     */
    private static final float OUTLINE_ALPHA_MIN = 0.1f;
    private static final float OUTLINE_ALPHA_MAX = 0.8f;
    /** recents_layout_top_peek_size: how far below the stack top the scroll-origin card sits. */
    private static final float TOP_PEEK_DP = 48f;
    /** recents_layout_initial_top_offset_phone_port / _phone_land. */
    private static final float INITIAL_TOP_OFFSET_PORT_DP = 128f;
    private static final float INITIAL_TOP_OFFSET_LAND_DP = 72f;
    /** recents_layout_z_min / _z_max. */
    private static final float Z_MIN_DP = 3f;
    private static final float Z_MAX_DP = 24f;

    /**
     * Card height / card width on the screen 7.1.1's numbers were written for, and the ceiling this
     * layout holds the card to.
     *
     * <p>Every constant above is an absolute dp, so the card size the formula
     * {@code height = stackHeight - initialTopOffset - stackBottomOffset} produces is a function of
     * how tall the screen is. On the 360x640dp phone the dimens were authored for — 24dp status bar,
     * 48dp navigation bar — that works out at a 600dp stack and a <b>328 x 408dp</b> card, i.e.
     * 1:1.244. On a 20:9 screen the same arithmetic gives a card half as long again (990 x 1715px,
     * 1:1.73, on this device), which is not what 7.1.1 looked like: its cards were near enough
     * square-ish portrait, and stretching them is exactly the "why are these so long" complaint.
     *
     * <p>The card is held to this ratio by <em>shrinking the stack rect from the top</em>, never by
     * shortening the card in place. See {@link #updateGeometry()}: the surplus goes into
     * {@link #mStackTop}, which keeps
     * {@code cardHeight == stackHeight - initialTopOffset - stackBottomOffset} exactly true, and
     * that identity is what the entire curve, the scroll bounds and the dim depend on. The front
     * card's bottom edge therefore still lands at the bottom of the stack, clear of the navigation
     * bar by the bottom margin; it is the fan above it that moves down.
     */
    private static final float REFERENCE_CARD_ASPECT = 408f / 328f;

    private final Context mContext;
    private final float mDensity;

    private RecyclerView mRecyclerView;

    // --- Geometry, all in px, all derived in updateGeometry(). Nothing here is declared in XML;
    // the card size falls out of the stack rect exactly as mTaskRect does in 7.1.1.
    /**
     * Top of the stack rect: status bar inset + 16dp, pushed further down on a screen tall enough
     * that the derived card would exceed {@link #REFERENCE_CARD_ASPECT}. The stack runs from here to
     * the view bottom, so the whole arrangement is bottom-anchored.
     */
    private int mStackTop;
    private int mStackHeight;
    /** mStackBottomOffset: nav bar inset + 16dp. */
    private int mStackBottomOffset;
    private int mInitialTopOffset;
    private int mCardLeft;
    private int mCardWidth;
    private int mCardHeight;
    private int mMinTranslationZ;
    private int mMaxTranslationZ;

    // --- The curves. Rebuilt only when the stack rect changes, because Path.approximate() is not
    // cheap and the curve is a function of the stack height alone.
    private CascadePathInterpolator mCurve;
    private CascadePathInterpolator mDimCurve;
    private int mCurveStackHeight = -1;
    private int mCurveTopOffset = -1;

    /**
     * Pixels of finger travel per 1.0 of {@link #mScrollP}: {@code stackHeight / arcLength}. The
     * curve is close enough to flat that 7.1.1 maps screen distance to curve distance with this one
     * constant rather than walking the path ({@code getDeltaPForY}), so the stack tracks the finger
     * at a uniform rate everywhere.
     */
    private float mPxPerUnit;

    // --- Scroll state.
    private float mScrollP;
    private float mMinScrollP;
    private float mMaxScrollP;
    private float mInitialScrollP;
    /**
     * The curve constant: the amount of progress between a card's top being at the initial top
     * offset (128dp below the stack top) and the scroll origin. It shows up twice in 7.1.1, once
     * measured from the top and once from the bottom, and the two are algebraically the same number
     * because {@code cardHeight == stackHeight - initialTopOffset - stackBottomOffset}.
     */
    private float mScrollOffsetK;

    private int mPendingScrollPosition = RecyclerView.NO_POSITION;
    private boolean mHasLaidOut;

    /** Told after every layout or scroll pass; the style moves its "Clear all" with the stack. */
    private Runnable mStackChangedListener;

    void setStackChangedListener(Runnable listener) {
        mStackChangedListener = listener;
    }

    /** Top of the stack rect, in list coordinates. */
    int getStackTop() {
        return mStackTop;
    }

    /** How far the stack is from its back-most scroll position, in task units. */
    float getScrollFromBack() {
        return mScrollP - mMinScrollP;
    }

    /** Height of the stack rect, i.e. how far a card has to travel to leave it downwards. */
    int getStackHeight() {
        return mStackHeight;
    }

    int getMarginPx() {
        return Math.round(MARGIN_DP * mDensity);
    }

    int getTopPeekPx() {
        return Math.round(TOP_PEEK_DP * mDensity);
    }

    CascadeLayoutManager(Context context) {
        mContext = context;
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * False: the RecyclerView is match_parent inside the recents window, so its size never depends
     * on its children, and auto-measure would only cost an extra layout pass.
     */
    @Override
    public boolean isAutoMeasureEnabled() {
        return false;
    }

    @Override
    public boolean canScrollVertically() {
        return true;
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        mRecyclerView = view;
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        super.onDetachedFromWindow(view, recycler);
        mRecyclerView = null;
    }

    @Override
    public void onAdapterChanged(RecyclerView.Adapter oldAdapter, RecyclerView.Adapter newAdapter) {
        removeAllViews();
        mHasLaidOut = false;
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        final int itemCount = state.getItemCount();
        if (itemCount == 0) {
            removeAndRecycleAllViews(recycler);
            return;
        }

        updateGeometry();
        updateScrollBounds(itemCount);

        if (mPendingScrollPosition != RecyclerView.NO_POSITION) {
            mScrollP = scrollPForPosition(mPendingScrollPosition, itemCount);
            mPendingScrollPosition = RecyclerView.NO_POSITION;
        } else if (!mHasLaidOut) {
            mScrollP = mInitialScrollP;
        }
        // After a dismissal the item count drops by one, so mMaxScrollP drops by one unit. Clamping
        // is all the correction that is needed: a user parked at the front of the stack is pulled
        // back by exactly the unit the removed card occupied (so the front cards stay put), and a
        // user parked at the back is not moved at all (so the back cards stay put).
        mScrollP = clamp(mScrollP, mMinScrollP, mMaxScrollP);
        mHasLaidOut = true;

        detachAndScrapAttachedViews(recycler);
        fill(recycler, itemCount);
    }

    @Override
    public void scrollToPosition(int position) {
        mPendingScrollPosition = position;
        requestLayout();
    }

    /**
     * The scroll that brings {@code position} into view.
     *
     * <p>Position 0 is special. It is the only value the host ever asks for — {@code loadTasks()}
     * calls {@code scrollToPosition(0)} on every entry and every resume, meaning "reset to the
     * state recents opens in" — and in 7.1.1 that state is not "the newest task at the top of the
     * curve" but {@link #mInitialScrollP}, which leaves the second-newest card at the initial top
     * offset and the newest one fanned out below it. Any other position gets 7.1.1's
     * {@code getStackScrollForTaskAtInitialOffset}: the scroll that puts that task's top at the
     * initial top offset, which is its brightest, least dimmed point on the curve.
     */
    private float scrollPForPosition(int position, int itemCount) {
        if (position <= 0) {
            return mInitialScrollP;
        }
        final float taskP = (itemCount - 1) - position;
        return clamp(taskP - mScrollOffsetK, mMinScrollP, mMaxScrollP);
    }

    private void fill(RecyclerView.Recycler recycler, int itemCount) {
        // Drop anything that has left the visible range. Done first so the scrap/pool has views to
        // hand back to the loop below.
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final View child = getChildAt(i);
            final int position = getPosition(child);
            if (position < 0 || position >= itemCount || !isInVisibleRange(position, itemCount)) {
                removeAndRecycleViewAt(i, recycler);
            }
        }

        // Descending position == ascending taskP == back of the stack first, so the front-most card
        // ends up as the last child. See the class doc for why that matters to ItemTouchHelper.
        for (int position = itemCount - 1; position >= 0; position--) {
            if (!isInVisibleRange(position, itemCount)) {
                continue;
            }
            View child = findViewByPosition(position);
            if (child == null) {
                child = recycler.getViewForPosition(position);
                addViewInStackOrder(child, position);
            }
            measureCard(child);
            applyTransform(child, position, itemCount);
        }
        if (mStackChangedListener != null) {
            mStackChangedListener.run();
        }
    }

    /**
     * Inserts {@code child} so that children stay sorted by descending adapter position.
     * {@code addView(child)} alone would append, which is only correct while filling a whole layout
     * pass front-to-back; an incremental fill during a scroll can add a card in the middle.
     */
    private void addViewInStackOrder(View child, int position) {
        int index = getChildCount();
        for (int i = 0, n = getChildCount(); i < n; i++) {
            if (getPosition(getChildAt(i)) < position) {
                index = i;
                break;
            }
        }
        addView(child, index);
    }

    /**
     * {@code Range#isInRange}: a task is laid out while its progress is within the visible window
     * rounded outwards to whole indices, so the card about to slide in from either end is already
     * attached and measured.
     */
    private boolean isInVisibleRange(int position, int itemCount) {
        final float taskP = (itemCount - 1) - position;
        return taskP >= Math.floor(mScrollP + RANGE_MIN)
                && taskP <= Math.ceil(mScrollP + RANGE_MAX);
    }

    /**
     * Every card is the same size and that size is derived, not declared — 7.1.1 hands every
     * TaskView the single {@code mTaskRect}. Measuring with exact specs rather than through
     * {@code measureChildWithMargins} is what enforces that; the card layout's own
     * {@code layout_width}/{@code layout_height} are ignored on purpose.
     */
    private void measureCard(View child) {
        child.measure(
                View.MeasureSpec.makeMeasureSpec(mCardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(mCardHeight, View.MeasureSpec.EXACTLY));
    }

    /** {@code TaskStackLayoutAlgorithm#getStackTransform}, unfocused branch. */
    private void applyTransform(View child, int position, int itemCount) {
        final float taskP = (itemCount - 1) - position;

        // Position: straight off the curve at the live scroll.
        final float normX = getNormalizedX(taskP - mScrollP);
        final int top = mStackTop + (int) ((1f - mCurve.getInterpolation(normX)) * mStackHeight);
        if (child.getLeft() == mCardLeft && child.getWidth() == mCardWidth
                && child.getHeight() == mCardHeight && !child.isLayoutRequested()) {
            // Same card, same size, new place. Shifting the frame is what offsetChildrenVertical
            // does for the linear layout managers; a full layoutDecorated() would run FrameLayout's
            // onLayout through every card's subtree on every scroll frame for no gain.
            child.offsetTopAndBottom(top - child.getTop());
        } else {
            layoutDecorated(child, mCardLeft, top, mCardLeft + mCardWidth, top + mCardHeight);
        }

        // Depth: 7.1.1 reads Z off the scroll clamped to both bounds, and the dim off the scroll
        // clamped only at the front, because its touch handler let the stack scroll past its bounds
        // with a rubber band and it did not want the shadows to run away while that happened. Here
        // scrollVerticallyBy clamps at source (that is what lets it report a truthful consumed
        // distance, so flings stop and the platform's own stretch overscroll takes over), which
        // makes both of these no-ops today. They are kept because the distinction is the source's
        // and losing it would quietly change what happens if a future overscroll model is added.
        final float boundedNormX =
                clamp01(getNormalizedX(taskP - clamp(mScrollP, mMinScrollP, mMaxScrollP)));
        // AOSP sets translationZ; elevation is used here because ItemTouchHelper lifts the card it
        // is swiping to max(elevation) + 1 and only inspects elevation when finding that maximum.
        // Both feed View.getZ(), which is what hwui sorts draws and touch dispatch by.
        child.setElevation(mMinTranslationZ + boundedNormX * (mMaxTranslationZ - mMinTranslationZ));
        // viewOutlineAlpha = mapRange(boundedNormX, 0, 2), then mapRange into 0.1..0.8.
        DepthOutline.of(child).setAlpha(Math.min(1f,
                OUTLINE_ALPHA_MIN + boundedNormX * 2f * (OUTLINE_ALPHA_MAX - OUTLINE_ALPHA_MIN)));

        final float dimNormX = getNormalizedX(taskP - Math.min(mScrollP, mMaxScrollP));
        final View dim = child.findViewById(R.id.oneui_recents_grace_dim);
        if (dim != null) {
            dim.setAlpha(mDimCurve.getInterpolation(dimNormX));
        }

        // No scale and no alpha, ever. getStackTransform assigns 1f to both unconditionally.
    }

    // ---------------------------------------------------------------------------------------
    // Scrolling
    // ---------------------------------------------------------------------------------------

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        if (dy == 0 || getChildCount() == 0 || mPxPerUnit <= 0f) {
            return 0;
        }
        // Positive dy is "towards the end of the list", which here means towards the front of the
        // stack: raising mScrollP lowers every card's relP and slides the whole cascade upwards.
        final float unclamped = mScrollP + dy / mPxPerUnit;
        final float target = clamp(unclamped, mMinScrollP, mMaxScrollP);
        if (target == mScrollP) {
            return 0;
        }

        // Report what was actually consumed rather than dy. RecyclerView treats the remainder as
        // overscroll: it is what stops a fling at the bounds and what feeds the edge effect, which
        // on S+ is the stretch that gives the stack its rubber-band feel at either end.
        final int consumed = (target == unclamped)
                ? dy
                : Math.round((target - mScrollP) * mPxPerUnit);
        mScrollP = target;

        fill(recycler, state.getItemCount());
        return consumed;
    }

    /** A screenful, in the pixel space the scroll offsets below are expressed in. */
    @Override
    public int computeVerticalScrollExtent(RecyclerView.State state) {
        return mStackHeight;
    }

    @Override
    public int computeVerticalScrollOffset(RecyclerView.State state) {
        if (mPxPerUnit <= 0f) {
            return 0;
        }
        return Math.round((mScrollP - mMinScrollP) * mPxPerUnit);
    }

    /**
     * One extent plus the travel between the bounds. Note what is <em>not</em> in here: the height
     * of the cards. The scrollable amount is a property of the curve and the task count only.
     */
    @Override
    public int computeVerticalScrollRange(RecyclerView.State state) {
        if (mPxPerUnit <= 0f) {
            return 0;
        }
        return mStackHeight + Math.round((mMaxScrollP - mMinScrollP) * mPxPerUnit);
    }

    // ---------------------------------------------------------------------------------------
    // Geometry and curves
    // ---------------------------------------------------------------------------------------

    private void updateGeometry() {
        final int margin = Math.round(MARGIN_DP * mDensity);

        // The stack rect is measured against the window and its system bars, not against the
        // RecyclerView's padding: the host pads the list by the bar insets plus a base of its own,
        // and that base is not part of the 7.1.1 geometry. The padding does no harm because the
        // host also sets clipToPadding=false.
        int statusBarInset = 0;
        int navBarInset = 0;
        if (mRecyclerView != null) {
            final WindowInsets insets = mRecyclerView.getRootWindowInsets();
            if (insets != null) {
                final Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                statusBarInset = bars.top;
                navBarInset = bars.bottom;
            }
        }

        final boolean landscape = mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        mStackTop = statusBarInset + margin;
        mStackHeight = Math.max(1, getHeight() - mStackTop);
        // 7.1.1's own mStackBottomOffset: nav bar inset + the 16dp bottom margin. The "Clear all"
        // button is back where 7.1.1 had it, in the band at the top of the stack (see
        // GraceCascadeStyle#styleClearAll), so nothing has to be reserved at the bottom.
        mStackBottomOffset = navBarInset + margin;
        mInitialTopOffset = Math.round(
                (landscape ? INITIAL_TOP_OFFSET_LAND_DP : INITIAL_TOP_OFFSET_PORT_DP) * mDensity);

        mCardLeft = margin;
        mCardWidth = Math.max(1, getWidth() - 2 * margin);

        // mTaskRect: top-aligned to the stack rect, tall enough that when the front card is
        // scrolled all the way down its bottom clears the nav bar by the bottom margin. On a screen
        // taller than the 16:9 one this arithmetic was written for that comes out far longer than
        // 7.1.1's card ever was, so the surplus is taken off the TOP of the stack rect rather than
        // off the card: the whole arrangement slides down the screen, the front card's bottom edge
        // stays exactly where it was, and cardHeight remains identically
        // stackHeight - initialTopOffset - stackBottomOffset — which is the identity the curve, the
        // scroll bounds (mScrollOffsetK) and the dim focus are all built on. See
        // REFERENCE_CARD_ASPECT.
        final int naturalCardHeight = mStackHeight - mInitialTopOffset - mStackBottomOffset;
        final int maxCardHeight = Math.max(1, Math.round(mCardWidth * REFERENCE_CARD_ASPECT));
        if (naturalCardHeight > maxCardHeight) {
            final int surplus = naturalCardHeight - maxCardHeight;
            mStackTop += surplus;
            mStackHeight = Math.max(1, mStackHeight - surplus);
        }
        mCardHeight = Math.max(1, mStackHeight - mInitialTopOffset - mStackBottomOffset);

        mMinTranslationZ = Math.round(Z_MIN_DP * mDensity);
        mMaxTranslationZ = Math.round(Z_MAX_DP * mDensity);

        if (mCurve == null || mCurveStackHeight != mStackHeight
                || mCurveTopOffset != mInitialTopOffset) {
            buildCurves();
            mCurveStackHeight = mStackHeight;
            mCurveTopOffset = mInitialTopOffset;
        }
    }

    /**
     * {@code constructUnfocusedCurve} and {@code constructUnfocusedDimCurve}.
     *
     * <p>The position curve is two cubics through (0,1), (0.5, 1 - topPeek) and (1,0). The second
     * cubic's first control point is solved rather than fixed so that the join at x=0.5 is smooth:
     * take the line through the first cubic's control point (0.4, 0.975) and the join, and put the
     * control point on it at x=0.65. That is why the top-peek height can be changed by a resource
     * without kinking the curve.
     */
    private void buildCurves() {
        final float topPeekPct = (TOP_PEEK_DP * mDensity) / mStackHeight;
        final float cpoint1X = 0.4f;
        final float cpoint1Y = 0.975f;
        final float slope = ((1f - topPeekPct) - cpoint1Y) / (0.5f - cpoint1X);
        final float b = 1f - slope * cpoint1X;
        final float cpoint2X = 0.65f;
        final float cpoint2Y = slope * cpoint2X + b;

        final Path curve = new Path();
        curve.moveTo(0f, 1f);
        curve.cubicTo(0f, 1f, cpoint1X, cpoint1Y, 0.5f, 1f - topPeekPct);
        curve.cubicTo(0.5f, 1f - topPeekPct, cpoint2X, cpoint2Y, 1f, 0f);
        mCurve = new CascadePathInterpolator(curve);
        mPxPerUnit = mStackHeight / mCurve.getArcLength();

        // The dim is darkest at the back of the stack, clears completely at the point on the curve
        // where a card's top is at the initial top offset, and comes back to a medium dim towards
        // the front — so the card you are about to launch and the card behind it are the bright
        // ones and everything else recedes.
        final float focusX = normalizedXFromTop(mInitialTopOffset);
        final float dimCpoint2X = focusX + (1f - focusX) / 2f;
        final Path dim = new Path();
        dim.moveTo(0f, MAX_DIM);
        dim.cubicTo(focusX * 0.5f, MAX_DIM, focusX * 0.75f, MAX_DIM * 0.75f, focusX, 0f);
        dim.cubicTo(dimCpoint2X, 0f, dimCpoint2X, MED_DIM, 1f, MED_DIM);
        mDimCurve = new CascadePathInterpolator(dim);
    }

    /** {@code update()}: min/max/initial scroll for a stack of {@code itemCount} tasks. */
    private void updateScrollBounds(int itemCount) {
        // The scroll that puts the front-most card's bottom edge at the bottom of the stack, i.e.
        // clear of the nav bar by the bottom margin. Measured from the bottom, the card's top is
        // then at cardHeight + stackBottomOffset above the stack bottom — which, by the definition
        // of cardHeight, is the same y as the initial top offset measured from the top. Hence one
        // constant serves both the max scroll and the entry scroll.
        mScrollOffsetK = Math.max(0f,
                getAbsoluteX(normalizedXFromBottom(mStackBottomOffset + mCardHeight)));

        mMinScrollP = 0f;
        mMaxScrollP = Math.max(mMinScrollP, (itemCount - 1) - mScrollOffsetK);
        // Recents in 7.1.1 opens showing the second-newest task at the initial top offset with the
        // newest one fanned out below it, ready to be tapped. (7.1.1 additionally overrides the
        // positions of the front two tasks so the newest card peeks exactly 128dp above the nav
        // bar; those overrides have to decay as the user scrolls, and that state machine is not
        // ported here. The curve puts the newest card a little higher than 7.1.1 did as a result.)
        mInitialScrollP = Math.max(mMinScrollP,
                Math.min(mMaxScrollP, itemCount - 2f) - mScrollOffsetK);
    }

    /** {@code Range#getNormalizedX} about the current scroll: relP -> [0,1] across the window. */
    private static float getNormalizedX(float relP) {
        if (relP < 0f) {
            return 0.5f + 0.5f * relP / -RANGE_MIN;
        }
        return 0.5f + 0.5f * relP / RANGE_MAX;
    }

    /** {@code Range#getAbsoluteX}: the inverse of {@link #getNormalizedX}. */
    private static float getAbsoluteX(float normX) {
        if (normX < 0.5f) {
            return (normX - 0.5f) / 0.5f * -RANGE_MIN;
        }
        return (normX - 0.5f) / 0.5f * RANGE_MAX;
    }

    /** {@code getNormalizedXFromUnfocusedY(y, FROM_TOP)}. */
    private float normalizedXFromTop(float y) {
        return mCurve.getX((mStackHeight - y) / mStackHeight);
    }

    /** {@code getNormalizedXFromUnfocusedY(y, FROM_BOTTOM)}. */
    private float normalizedXFromBottom(float y) {
        return mCurve.getX(y / mStackHeight);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * The card's background outline with a settable alpha, 7.1.1's {@code AnimateableViewBounds}
     * minus the animated clip. Installed on first use and kept on the recycled view.
     */
    static final class DepthOutline extends ViewOutlineProvider {
        private final View mView;
        private float mAlpha = 1f;

        private DepthOutline(View view) {
            mView = view;
        }

        static DepthOutline of(View view) {
            final ViewOutlineProvider current = view.getOutlineProvider();
            if (current instanceof DepthOutline) {
                return (DepthOutline) current;
            }
            final DepthOutline outline = new DepthOutline(view);
            view.setOutlineProvider(outline);
            return outline;
        }

        void setAlpha(float alpha) {
            if (Float.compare(mAlpha, alpha) != 0) {
                mAlpha = alpha;
                mView.invalidateOutline();
            }
        }

        @Override
        public void getOutline(View view, Outline outline) {
            ViewOutlineProvider.BACKGROUND.getOutline(view, outline);
            outline.setAlpha(mAlpha);
        }
    }
}
