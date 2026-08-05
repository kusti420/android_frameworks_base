/*
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
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * Samsung Grace UX recents — the Android 7.0 card stack, as it shipped on the Galaxy A-series.
 *
 * <p>Distinct from {@link GraceCascadeStyle}, which is AOSP 7.1.1's own
 * {@code TaskStackLayoutAlgorithm} restored. Samsung kept AOSP's activity class
 * ({@code com.android.systemui/.recents.RecentsActivity}) but did not keep its geometry, and the
 * differences are not cosmetic: they are what makes the stack read as Samsung rather than as
 * Nougat. Everything here was measured off photographs of an SM-A310F running stock firmware
 * A310FXXS5CTJ2 — 720x1280 at density 320, so 2.0 px/dp on a 360x640dp screen — and converted
 * through dp. Nothing is decompiled from, extracted from, or copied out of any Samsung binary.
 *
 * <h3>What the device actually does</h3>
 *
 * <p>Card edges, front to back, on that 720px-wide screen: left insets 55, 61, 67, 78 and 88.5px,
 * i.e. widths 610, 598, 586, 564 and 544px. Card tops: 305, 227, 149, 129 and 109px. Four facts
 * fall out of those two rows of numbers, and this class is those four facts:
 *
 * <ul>
 *   <li><b>Cards taper.</b> AOSP's {@code getStackTransform} hard-assigns {@code scale = 1f} on
 *       every path; Samsung shrinks each card behind by ~2% of the front card's width. This is the
 *       single biggest structural divergence.
 *   <li><b>The taper is a uniform scale, not a narrower layout.</b> The ✕'s centre sits 50.5px in
 *       from the front card's right edge and 48.5px in on the card two back — a ratio of 0.960
 *       against a card-width ratio of 0.961 — and the icon's centre gives 0.956. Every inset inside
 *       the header shrinks with the card, so this is a view transform. Hence
 *       {@link GraceUxLayoutManager#measureCard} measures one size for every card and
 *       {@link GraceUxLayoutManager#applyTransform} scales about (centreX, top).
 *   <li><b>The stack has two regimes.</b> The gaps between card tops are 78, 78, 20, 20px and the
 *       per-card width insets are 6, 6, 11, 10.5px. Both step at the same place: the front three
 *       cards fan at one header per card and taper gently, and everything behind them collapses
 *       into a bunched pile that shrinks about twice as fast per card. That is a knee, not a smooth
 *       bezier, so this uses piecewise-linear arithmetic rather than AOSP's {@code Path}
 *       interpolator — see {@link GraceUxLayoutManager#offsetFor}.
 *   <li><b>Nothing is dimmed.</b> Sampling the same app (Settings) at depth 1 in one reference shot
 *       and depth 2 in the other gives exactly (81,176,211) both times. AOSP fades the back of its
 *       stack to 25% black; Grace UX separates cards purely with elevation shadows, which is why
 *       there is no dim overlay in {@code oneui_recents_card_graceux.xml}.
 * </ul>
 *
 * <p>"CLOSE ALL" at the bottom centre — the other confirmed divergence from AOSP, which put "Clear
 * all" in a band at the top — needs nothing here: the shared host layout already puts its pill
 * there. The reference device's is 199x34dp with a 12dp gap above it; the host's is 188x40dp, and
 * {@link #BOTTOM_OFFSET_DP} reserves room for the host's.
 *
 * <p>There is no ⋮ overflow menu. The real one sits in the activity's chrome at the top right, not
 * on a card, so a style cannot place it without reaching outside the contract, and everything it
 * opened on Grace UX (multi-window, pop-up view, app info) is plumbing this recents host does not
 * have. A dead ⋮ would be worse than none.
 */
public class GraceUxStyle implements RecentsStyle {

    /** Required by {@code RecentsStyles.forType}. */
    public GraceUxStyle() {}

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        return new GraceUxLayoutManager(context);
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_graceux;
    }

    /**
     * Sideways, either way, as on the device: Grace UX dismissed a task by flinging its card off
     * the side.
     *
     * <p>Safe to hand to the host's stock {@link ItemTouchHelper} even though these cards overlap
     * heavily. The helper picks its target with {@link RecyclerView#findChildViewUnder}, which walks
     * children from the last index down and tests raw bounds with no notion of Z or scale, so a
     * layout manager that attaches in ascending adapter order would have it scan the stack back to
     * front and dismiss a card buried five layers down.
     * {@link GraceUxLayoutManager#addViewInStackOrder} attaches in <em>descending</em> position
     * order instead, which makes child index order identical to Z order and the stock hit test land
     * on exactly the card the user can see.
     *
     * <p>The remaining inaccuracy is bounded and tiny: a back card's raw bounds are its unscaled
     * ones, so a touch in the ~3dp strip between a tucked card's real left edge and its unscaled one
     * resolves to that card anyway. Vertically it does not arise at all — a back card's unscaled
     * overhang is underneath the front card, which is a later child and wins.
     */
    @Override
    public int getSwipeDirs() {
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        // The host re-binds the whole tail after a dismissal so the depth transforms follow the new
        // positions. With cards this heavily overlapped the default change animation cross-fades
        // every one of them, which reads as a flicker through the entire stack. Remove and move are
        // kept: they are what slides the cards behind a dismissed one down into its place.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Position, depth, taper and shadow are all scroll-driven and belong to the layout manager,
        // which re-applies them immediately after this. What has to happen here is undoing whatever
        // transform the recycled view died wearing — a card dismissed by a fling is parked off the
        // side of the screen at some arbitrary alpha, and a card that fell off the back of the stack
        // is scaled down — because RecyclerView hands views back exactly as they were left and stale
        // transforms come back as ghost cards.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setAlpha(1f);
        card.setElevation(0f);
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The Grace UX stack arrangement.
     *
     * <p>Structurally a sibling of {@link CascadeLayoutManager} — it owns {@code onLayoutChildren}
     * and {@code scrollVerticallyBy}, reports {@code consumed} rather than {@code dy} so flings
     * terminate and the platform edge effect takes over at the bounds, implements the three
     * {@code computeVerticalScroll*} hooks because the scrollable amount is a property of the curve
     * and the task count rather than of the child heights, and attaches children back-to-front. The
     * arithmetic in between is Samsung's, not AOSP's.
     *
     * <h3>The model</h3>
     *
     * <p>One float, {@link #mScrollP}, in card-index units. A card at adapter position {@code i}
     * (0 == most recent == front) sits at {@code relP = mScrollP - i}. At {@code mScrollP == 0} the
     * newest card is at {@code relP == 0}, which is its resting place near the bottom of the stack,
     * and the cards behind it are at -1, -2, -3… Raising {@code mScrollP} walks older cards forward
     * through the stack and pushes newer ones down and off the bottom.
     *
     * <p>Both {@link #offsetFor} and {@link #scaleFor} are piecewise linear in {@code relP} with a
     * knee at the same place, because that is what the device does.
     */
    private static final class GraceUxLayoutManager extends RecyclerView.LayoutManager {

        // --- Geometry, in dp, measured from the reference device. Deliberately Java constants and
        // not <dimen> resources: adding a single dimen to SystemUI shifts its dimen resource-ID
        // table, and WindowManager-Shell's generated R constants do not get re-assigned to match,
        // which crash-loops SystemUI at app-bind with "Resource ID ... is not valid" while the build
        // still reports success. A bare constant cannot shift anything.

        /** Card left inset: 55px on a 720px screen. AOSP 7.1.1 used 16dp; Grace's cards are much
         *  narrower relative to the screen, and that is part of the look. */
        private static final float SIDE_MARGIN_DP = 27.5f;
        /** Stack top below the status bar, matching 7.1.1's {@code recents_layout_top_margin}. */
        private static final float STACK_MARGIN_DP = 16f;

        /** Where the front card's top rests, below the stack top: 305px screen y, less a 24dp status
         *  bar and the 16dp margin, is 112.5dp. */
        private static final float INITIAL_TOP_OFFSET_DP = 112.5f;
        /** Reserved at the bottom of the stack. On the device the front card's bottom is 56dp above
         *  the screen edge with the CLOSE ALL pill in the gap; the host's pill is bigger and sits
         *  48..88dp up, so this is that plus the device's measured 12dp breathing room. */
        private static final float BOTTOM_OFFSET_DP = 100f;

        /** Gap between the tops of two adjacent cards in the fanned region: 78px. One header
         *  (40.5dp) per card, less a hair, so each card behind shows its header and nothing else. */
        private static final float FRONT_STEP_DP = 39f;
        /** The offset at which the fan gives way to the bunched pile: 149px screen y, i.e. the third
         *  card, is 34.5dp below the stack top. */
        private static final float KNEE_OFFSET_DP = 34.5f;
        /** Gap between tops inside the bunched pile: 20px. */
        private static final float BACK_STEP_DP = 10f;

        /** The stack height these dp were measured on: 640dp screen, less a 24dp status bar and the
         *  16dp margin. Only used to shrink the whole vertical arrangement on a stack shorter than
         *  the reference one (landscape); a taller stack keeps the measured absolutes, which is what
         *  keeps a 20:9 phone showing the same five or six cards the 16:9 reference did. */
        private static final float REFERENCE_STACK_DP = 600f;
        private static final float MIN_VERTICAL_SQUEEZE = 0.3f;

        /** Width lost per card in the fanned region, as a fraction of the front card's width:
         *  610 -> 598 -> 586px is 0.0197 each time. */
        private static final float FRONT_SCALE_STEP = 0.0197f;
        /** And in the bunched pile: 586 -> 564 -> 544px is 0.0361 then 0.0328. */
        private static final float BACK_SCALE_STEP = 0.0345f;

        /** Units of {@link #mScrollP} a card takes to fall from its resting place clear off the
         *  bottom of the stack. Not measurable from a still — the references are both at rest — so
         *  it is chosen to match AOSP 7.1.1, whose curve drops the front card roughly a whole stack
         *  height in its last unit of progress. It doubles as the scroll rate; see
         *  {@link #mPxPerUnit}. */
        private static final float EXIT_UNITS = 1.5f;

        /**
         * Card height / card width, measured off {@code graceux_a310f_stack.png}: the front card
         * (My Files) spans x 55..664 and y 305..1168 on that 720x1280 screen, so 610 x 864px —
         * 84.7% of the screen width and 67.5% of its height, which in dp is <b>305 x 432</b>, i.e.
         * 1:1.416.
         *
         * <p>Everything else in this class is an absolute dp, so without this the card height falls
         * out of {@code stackHeight - topOffset - bottomOffset} and grows with the screen: on this
         * 20:9 device that is 926 x 1657px, 1:1.79, a card half as long again as Samsung's. The
         * ratio is restored by <em>shrinking the stack rect from the top</em> rather than by
         * shortening the card in place — the surplus goes into {@link #mStackTop} — which keeps the
         * front card's bottom edge where it was (and therefore the reserve for the host's CLOSE ALL
         * pill intact), keeps the fan/pile knee at the same card, and keeps
         * {@code cardHeight == stackHeight - topOffset - bottomOffset} exactly true.
         */
        private static final float REFERENCE_CARD_ASPECT = 864f / 610f;

        /** Shadow depth. Read off the darkening above each card's top edge on the reference: the
         *  front card's shadow spans 25px and reaches ~0.74x, the next 15px and ~0.88x, the next
         *  18px and ~0.95x — so it climbs steeply towards the front, hence the squared ramp in
         *  {@link #applyTransform}. */
        private static final float Z_MIN_DP = 1f;
        private static final float Z_MAX_DP = 14f;

        private final float mDensity;

        private RecyclerView mRecyclerView;

        // --- Derived in updateGeometry(); all px except the *P progress values.
        private int mStackTop;
        private int mStackHeight;
        private int mCardLeft;
        private int mCardWidth;
        private int mCardHeight;
        private float mTopOffset;
        private float mFrontStep;
        private float mKneeOffset;
        private float mBackStep;
        private float mExitStep;
        private float mMinZ;
        private float mMaxZ;

        /** Progress at the knee, and at the back of the pile where a card's top reaches the stack
         *  top and it is fully covered by the one in front of it. Both come out independent of the
         *  vertical squeeze (-2.0 and -5.45 with the measured constants) but are solved rather than
         *  written down so the constants above stay the single source of truth. */
        private float mKneeP;
        private float mMinP;

        /** Pixels of finger travel per unit of {@link #mScrollP}, i.e. per card. Equal to
         *  {@link #mExitStep}, so the front card — the big one the finger is nearly always on —
         *  moves exactly with the finger; see {@link #updateVerticalMetrics}. */
        private float mPxPerUnit;

        private float mScrollP;
        private float mMinScrollP;
        private float mMaxScrollP;

        private int mPendingScrollPosition = RecyclerView.NO_POSITION;
        private boolean mHasLaidOut;

        GraceUxLayoutManager(Context context) {
            mDensity = context.getResources().getDisplayMetrics().density;
        }

        @Override
        public RecyclerView.LayoutParams generateDefaultLayoutParams() {
            return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        /** False: the list is match_parent inside the recents window, so its size never depends on
         *  its children and auto-measure would only buy an extra layout pass. */
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
        public void onAdapterChanged(RecyclerView.Adapter oldAdapter,
                RecyclerView.Adapter newAdapter) {
            removeAllViews();
            mHasLaidOut = false;
        }

        // -----------------------------------------------------------------------------------
        // Layout
        // -----------------------------------------------------------------------------------

        @Override
        public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            final int itemCount = state.getItemCount();
            if (itemCount == 0) {
                removeAndRecycleAllViews(recycler);
                return;
            }

            updateGeometry();
            mMinScrollP = 0f;
            mMaxScrollP = Math.max(0f, itemCount - 1f);

            if (mPendingScrollPosition != RecyclerView.NO_POSITION) {
                // Bringing position i to the front is exactly mScrollP == i. The host only ever asks
                // for 0, on every entry and every resume, meaning "reset to how recents opens" —
                // which on Grace UX is the newest card at its resting place with the rest fanned and
                // bunched above it, i.e. scroll zero. No AOSP-style initial-offset special case.
                mScrollP = mPendingScrollPosition;
                mPendingScrollPosition = RecyclerView.NO_POSITION;
            } else if (!mHasLaidOut) {
                mScrollP = mMinScrollP;
            }
            // After a dismissal the item count drops by one and so does mMaxScrollP. Clamping is the
            // whole correction: a user parked at the back of the stack is pulled forward by exactly
            // the unit the removed card occupied, and a user at the front is not moved at all.
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

        private void fill(RecyclerView.Recycler recycler, int itemCount) {
            // Drop anything that has left the visible range first, so the loop below has views in
            // the scrap heap to reuse.
            for (int i = getChildCount() - 1; i >= 0; i--) {
                final View child = getChildAt(i);
                final int position = getPosition(child);
                if (position < 0 || position >= itemCount || !isInVisibleRange(position)) {
                    removeAndRecycleViewAt(i, recycler);
                }
            }

            // Descending position == back of the stack first, so the front-most card ends up as the
            // last child. See getSwipeDirs() for why that matters.
            for (int position = itemCount - 1; position >= 0; position--) {
                if (!isInVisibleRange(position)) {
                    continue;
                }
                View child = findViewByPosition(position);
                if (child == null) {
                    child = recycler.getViewForPosition(position);
                    addViewInStackOrder(child, position);
                }
                measureCard(child);
                applyTransform(child, position);
            }
        }

        /**
         * Inserts {@code child} so children stay sorted by descending adapter position. A plain
         * {@code addView} appends, which is only right while filling a whole layout pass
         * back-to-front; an incremental fill during a scroll can bring a card in in the middle.
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
         * Laid out while the card is anywhere between one step past the back of the pile — so the
         * card about to emerge is already attached and measured — and the point at which it has
         * fallen clear off the bottom.
         */
        private boolean isInVisibleRange(int position) {
            final float relP = mScrollP - position;
            return relP >= mMinP - 1f && relP <= EXIT_UNITS;
        }

        /**
         * Every card is measured to the same rect, exactly once per fill. Depth is a scale applied
         * afterwards, not a narrower measurement — see the class doc for the evidence — which also
         * means the thumbnail's measured width never changes, so the binder's MATRIX scale-to-width
         * matrix stays valid as a card moves back through the stack.
         */
        private void measureCard(View child) {
            child.measure(
                    View.MeasureSpec.makeMeasureSpec(mCardWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(mCardHeight, View.MeasureSpec.EXACTLY));
        }

        private void applyTransform(View child, int position) {
            // Cards past the back of the pile freeze there rather than continuing to climb and
            // shrink: they are wholly covered by the wider card in front of them either way, and
            // clamping keeps their scale from running away on a very long task list.
            final float relP = Math.max(mScrollP - position, mMinP);

            final int top = mStackTop + Math.round(offsetFor(relP));
            if (child.getLeft() == mCardLeft && child.getWidth() == mCardWidth
                    && child.getHeight() == mCardHeight && !child.isLayoutRequested()) {
                // Same card, same size, new place. Shifting the frame is what offsetChildrenVertical
                // does for the linear layout managers; a full layoutDecorated() would run the card's
                // whole subtree through onLayout on every scroll frame for nothing.
                child.offsetTopAndBottom(top - child.getTop());
            } else {
                layoutDecorated(child, mCardLeft, top, mCardLeft + mCardWidth, top + mCardHeight);
            }

            // The taper. Pivoted on the top edge's midpoint: horizontally because every card on the
            // reference is centred on the same axis (measured centres 360.0, 359.5, 360.0, 359.5,
            // 359.1 on a 720px screen), vertically because the tops are the positions the curve
            // above places and the bottoms are all hidden behind the card in front anyway.
            child.setPivotX(mCardWidth * 0.5f);
            child.setPivotY(0f);
            final float scale = scaleFor(relP);
            child.setScaleX(scale);
            child.setScaleY(scale);

            // Depth reads entirely from the shadow — Grace UX does not dim (class doc). Squared
            // because the measured shadows do not grow linearly: roughly 14dp, 7dp, 3dp of apparent
            // elevation over the front three cards.
            final float t = clamp01((relP - mMinP) / -mMinP);
            child.setElevation(mMinZ + (mMaxZ - mMinZ) * t * t);

            // translationX/Y are deliberately untouched: ItemTouchHelper owns translationX for the
            // length of a swipe and the item animator owns both while the stack closes up after a
            // dismissal. They are reset in onBindCard instead, which is the one moment neither does.
        }

        /**
         * A card's top, in px below the stack top, as a function of its progress.
         *
         * <p>Three linear pieces, joined continuously:
         *
         * <ul>
         *   <li>{@code relP < mKneeP} — the bunched pile at the top of the stack, one
         *       {@link #BACK_STEP_DP} per card.
         *   <li>{@code mKneeP <= relP < 0} — the fan, one {@link #FRONT_STEP_DP} (a header) per card.
         *   <li>{@code relP >= 0} — past its resting place and falling off the bottom, which only
         *       happens to cards the user has scrolled past.
         * </ul>
         */
        private float offsetFor(float relP) {
            if (relP >= 0f) {
                return mTopOffset + relP * mExitStep;
            }
            if (relP >= mKneeP) {
                return mTopOffset + relP * mFrontStep;
            }
            return mKneeOffset + (relP - mKneeP) * mBackStep;
        }

        /** The width taper, as a fraction of the front card's width. Same three pieces, same knee. */
        private float scaleFor(float relP) {
            if (relP >= 0f) {
                return 1f;
            }
            if (relP >= mKneeP) {
                return 1f + relP * FRONT_SCALE_STEP;
            }
            return 1f + mKneeP * FRONT_SCALE_STEP + (relP - mKneeP) * BACK_SCALE_STEP;
        }

        // -----------------------------------------------------------------------------------
        // Scrolling
        // -----------------------------------------------------------------------------------

        @Override
        public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
                RecyclerView.State state) {
            if (dy == 0 || getChildCount() == 0 || mPxPerUnit <= 0f) {
                return 0;
            }
            // Positive dy is the finger moving UP the screen, and the cards have to go with it.
            // Raising mScrollP walks the stack forwards and DOWNWARDS (offsetFor is increasing in
            // relP, and relP == mScrollP - position), so the finger drives mScrollP negatively.
            // This used to be a plus, which inverted the whole gesture — the reported "scrolling is
            // reversed". CascadeLayoutManager has a plus for the opposite reason: its relP is
            // taskP - mScrollP, the other sign, so there raising mScrollP moves cards up.
            //
            // The resting state is mScrollP == mMinScrollP == 0, with the newest card at the bottom
            // and nothing below it, so from rest only a downward drag does anything — it pulls the
            // newest card down off the bottom and brings the older ones out of the pile, which is
            // the direction the reference device recedes in.
            final float unclamped = mScrollP - dy / mPxPerUnit;
            final float target = clamp(unclamped, mMinScrollP, mMaxScrollP);
            if (target == mScrollP) {
                return 0;
            }

            // Report what was actually consumed rather than dy, with the same sign convention dy
            // came in on. RecyclerView treats the remainder as overscroll: it is what stops a fling
            // dead at the bounds and what feeds the edge effect, which on S+ is the stretch that
            // gives the stack its rubber-band feel.
            final int consumed = (target == unclamped)
                    ? dy
                    : Math.round((mScrollP - target) * mPxPerUnit);
            mScrollP = target;

            fill(recycler, state.getItemCount());
            return consumed;
        }

        /** A screenful, in the pixel space the two below are expressed in. */
        @Override
        public int computeVerticalScrollExtent(RecyclerView.State state) {
            return mStackHeight;
        }

        /**
         * How far the list has been scrolled in the +dy direction. Inverted with respect to
         * {@link #mScrollP} for the same reason {@link #scrollVerticallyBy} is: +dy walks the stack
         * <em>back</em> towards the newest card, so the resting state (mScrollP == 0) is the
         * fully-scrolled end. Only scrollbars — which the host disables — and accessibility scroll
         * events read this, but reporting it against the wrong sign would make the a11y events lie.
         */
        @Override
        public int computeVerticalScrollOffset(RecyclerView.State state) {
            if (mPxPerUnit <= 0f) {
                return 0;
            }
            return Math.round((mMaxScrollP - mScrollP) * mPxPerUnit);
        }

        /**
         * One extent plus the travel between the bounds. Note what is <em>not</em> in here: the
         * height of the cards. The scrollable amount is a property of the task count and the step,
         * and deriving it from decorated child heights — which is what RecyclerView would do for a
         * layout manager that did not override this — gives a handful of screen-tall, heavily
         * overlapped cards a total extent smaller than the viewport, at which point the list simply
         * refuses to scroll.
         */
        @Override
        public int computeVerticalScrollRange(RecyclerView.State state) {
            if (mPxPerUnit <= 0f) {
                return 0;
            }
            return mStackHeight + Math.round((mMaxScrollP - mMinScrollP) * mPxPerUnit);
        }

        // -----------------------------------------------------------------------------------
        // Geometry
        // -----------------------------------------------------------------------------------

        private void updateGeometry() {
            // Measured against the window and its system bars vertically, because the host's own
            // 24dp/104dp list padding is not part of this geometry (it is harmless: the host also
            // sets clipToPadding=false). Horizontally the padding IS used, because the host's
            // horizontal base is zero, so it is precisely the bar and cutout insets — which matters
            // in landscape, where this device puts a display cutout on one side and the navigation
            // bar on the other.
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

            final int stackMargin = Math.round(STACK_MARGIN_DP * mDensity);
            mStackTop = statusBarInset + stackMargin;
            mStackHeight = Math.max(1, getHeight() - mStackTop);

            // Not squeezed: this reserves room for the host's CLOSE ALL pill, which is a fixed
            // 40dp tall at a fixed 48dp from the window bottom whatever the orientation.
            final float bottomOffset = Math.max(BOTTOM_OFFSET_DP * mDensity,
                    navBarInset + stackMargin);

            final int sideMargin = Math.round(SIDE_MARGIN_DP * mDensity);
            mCardLeft = getPaddingLeft() + sideMargin;
            mCardWidth = Math.max(1,
                    getWidth() - getPaddingLeft() - getPaddingRight() - 2 * sideMargin);

            // Two passes, because the vertical arrangement depends on the stack height and the
            // stack height is about to be trimmed. On a screen taller than the reference one the
            // card the measured offsets imply is far longer than Samsung's (see
            // REFERENCE_CARD_ASPECT); the surplus is taken off the TOP of the stack rect, so the
            // whole fan-and-pile slides down as a rigid body, the front card's bottom edge and the
            // pill reserve below it stay put, and the knee stays at the same card. The second pass
            // matters only on a screen narrow enough that the trimmed stack drops under
            // REFERENCE_STACK_DP and the squeeze starts to bite.
            updateVerticalMetrics(bottomOffset);
            final int maxCardHeight = Math.max(1, Math.round(mCardWidth * REFERENCE_CARD_ASPECT));
            final int naturalCardHeight = Math.round(mStackHeight - mTopOffset - bottomOffset);
            if (naturalCardHeight > maxCardHeight) {
                final int surplus = naturalCardHeight - maxCardHeight;
                mStackTop += surplus;
                mStackHeight = Math.max(1, mStackHeight - surplus);
                updateVerticalMetrics(bottomOffset);
            }
            mCardHeight = Math.max(1, Math.min(maxCardHeight,
                    Math.round(mStackHeight - mTopOffset - bottomOffset)));

            mMinZ = Z_MIN_DP * mDensity;
            mMaxZ = Z_MAX_DP * mDensity;
        }

        /**
         * The vertical arrangement — offsets, steps, the two knee progresses and the scroll rate —
         * for the current {@link #mStackHeight}. Split out of {@link #updateGeometry()} because it
         * has to run once against the untrimmed stack (to find out how long the card would come out)
         * and again against the trimmed one.
         */
        private void updateVerticalMetrics(float bottomOffset) {
            // On a stack at least as tall as the reference one the measured dp are used as they
            // stand, so a 20:9 phone shows the same five or six cards a 16:9 one did rather than
            // fanning out a dozen. A shorter stack (landscape) squeezes the whole vertical
            // arrangement by one factor, which keeps the shape — the knee stays at the same card.
            final float squeeze = clamp(mStackHeight / (REFERENCE_STACK_DP * mDensity),
                    MIN_VERTICAL_SQUEEZE, 1f);
            mTopOffset = squeeze * INITIAL_TOP_OFFSET_DP * mDensity;
            mFrontStep = squeeze * FRONT_STEP_DP * mDensity;
            mKneeOffset = squeeze * KNEE_OFFSET_DP * mDensity;
            mBackStep = squeeze * BACK_STEP_DP * mDensity;

            // Solved rather than written down, so the measured constants stay the only inputs. With
            // them these come out at -2.0 (the third card is the first bunched one) and -5.45 (the
            // pile is about three and a half cards deep before a card's top reaches the stack top),
            // independently of the squeeze.
            mKneeP = -(mTopOffset - mKneeOffset) / mFrontStep;
            mMinP = mKneeP - mKneeOffset / mBackStep;

            // A card falls from its resting place to clear off the bottom of the stack over
            // EXIT_UNITS of progress.
            mExitStep = Math.max(1f, (mStackHeight - mTopOffset) / EXIT_UNITS);

            // The scroll rate, and the one number here that was never measurable from a still. It
            // used to be mFrontStep — 39dp of finger travel per card — which is direct manipulation
            // of the wrong card: the fanned headers are 39dp apart, but the card the finger is
            // actually on is the big front one, and it moves at mExitStep. At mFrontStep the front
            // card ran away from the finger about twelve times too fast, which is the "far too
            // sensitive" report. Using mExitStep makes the front card track the finger exactly 1:1
            // and lands at ~378dp per card on this device, which is also where AOSP 7.1.1's
            // stackHeight/arcLength rate lands (~400dp) for the same stack — a useful sanity check
            // given the two stacks are cousins.
            mPxPerUnit = mExitStep;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }
}
