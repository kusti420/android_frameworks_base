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
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * A tilted fan of task cards. The most recent task is a tall card standing in the middle of the
 * screen with its app icon floating on its top edge; every older task is a step further to the
 * LEFT, a little smaller and a little darker, so the pile recedes sideways into the distance and
 * only a sliver of each card behind the front one is left showing.
 *
 * <p>The arrangement is a horizontal, reversed {@link LinearLayoutManager} whose items are
 * collapsed to one 72dp step by a negative {@link RecyclerView.ItemDecoration}; every card then
 * keeps its full 260dp width and overhangs its neighbours, which is what produces the overlap. The
 * list is padded so that a single step sits dead centre, so the card at the focus line is centred
 * on screen and a {@link LinearSnapHelper} settles the fan on whole cards. Scrolling deals the fan
 * through the focus line rather than dragging the whole pile off screen, so a twenty-task list is
 * still reachable even though only three cards are ever visible at once.
 *
 * <p>Every measurement here — the 72dp step, the 0.895 depth scale, the centre line the cards are
 * scaled about — was taken off a One UI 8 screenshot of this layout and is reproduced from those
 * numbers alone; the geometry is described in {@code oneui_recents_card_stack.xml}. Nothing here
 * is derived from Samsung code or assets.
 */
public class StackStyle implements RecentsStyle {

    /**
     * Distance between neighbouring card centres, and therefore how much of the layout each card
     * actually consumes. Measured at 188px on a 420dpi screen.
     */
    private static final int STEP_DP = 72;

    /**
     * Fallback for the width {@code oneui_recents_card_stack.xml} declares on its root; the real
     * value is read back off the inflated layout params, which stay the single source of truth.
     */
    private static final int CARD_WIDTH_DP = 260;

    /**
     * Depth falloff, per step, applied geometrically. The measured scales were 0.8948 one step
     * back and 0.802 two steps back, and 0.8948² = 0.8007 — so the real thing compounds rather than
     * subtracting a constant, which is also what a perspective projection would do.
     */
    private static final float DEPTH_SCALE = 0.895f;

    /**
     * Where the fan stops. With this geometry a card three steps back is already entirely hidden
     * behind the card in front of it (its right edge lands left of that card's left edge, and
     * everything to the left of that is off screen), so nothing is lost by cutting the fan here —
     * it only stops a twenty-task list from stacking twenty invisible cards.
     */
    private static final float MAX_DEPTH = 3.6f;

    /** Cards fade out over the last stretch of the fan rather than snapping to nothing. */
    private static final float TAIL_FADE_FROM = 2.8f;

    /**
     * How far a card that has been dealt PAST the focus line is followed before it is dropped. A
     * card at negative depth is in front of the fan and drawn over everything, so it is faded out
     * over one step instead of being left hanging around at the right of the screen — the fan's
     * linear spacing would take three or four steps to carry it off the edge on its own.
     */
    private static final float LEAD_FADE = 1f;

    /** Depth dimming, as a scrim over each card's snapshot. */
    private static final float DIM_STEP = 0.08f;
    private static final float MAX_DIM = 0.28f;

    /** Z spacing between neighbouring cards. Only the ordering matters, so it is kept small. */
    private static final float Z_STEP_DP = 2f;

    /** Swipe distance that dismisses, as a fraction of the item view — which is a full list tall. */
    private static final float SWIPE_THRESHOLD = 0.12f;

    private int mStepPx;
    private int mCardWidthPx;
    private float mZStepPx;

    /** Owned by this style rather than by the host; see {@link #getSwipeDirs()}. */
    private ItemTouchHelper mSwipeHelper;

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        ensureMetrics(context);
        // Reversed, so adapter position 0 — the most recent task — is laid out at the right-hand
        // end and the older tasks run away to the left, which is the direction the fan recedes in.
        return new FanLayoutManager(context, mCardWidthPx);
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_stack;
    }

    /**
     * Zero, which stops {@link com.android.systemui.recents.oneui.OneUiRecentsActivity} attaching
     * its own {@link ItemTouchHelper}. The gesture is not given up: this style attaches an
     * equivalent helper in {@link #onRecyclerViewCreated} and starts it by hand.
     *
     * <p>The host's helper cannot be used, for the reason {@link RecentsStyle#getSwipeDirs()}
     * gives, and this layout is close to the worst case for it. A card is 260dp wide but advances
     * the layout by 72dp, so up to four cards' bounds cover any point on the front card, and
     * {@code findChildViewUnder} walks children from the last index down — which, because child
     * index ascends with adapter position whatever {@code reverseLayout} says, is back to front.
     * It would answer with the card furthest back under the finger: usually one dimmed and scaled
     * down to a sliver, sometimes one this style has already set {@code INVISIBLE}.
     *
     * <p>So the swipe is kept and the hit test is replaced — see {@link DepthAwareSwipeStarter}.
     * It is dismissal by dragging the front card UP, which is both the One UI gesture and the one
     * axis this style has spare: left and right belong to the fan's own scrolling.
     */
    @Override
    public int getSwipeDirs() {
        return 0;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        ensureMetrics(recyclerView.getContext());

        // The app icon overhangs the top of its card, and a card's shadow spills past the item
        // view's edges, so nothing between the card face and the list may clip. (The item view and
        // the group inside it set clipChildren="false" in the layout for the same reason; each
        // ViewGroup only clips to its OWN bounds, so every level has to opt out.)
        recyclerView.setClipChildren(false);
        recyclerView.setClipToPadding(false);

        recyclerView.addItemDecoration(new FanDecoration());

        // Snapping on the viewport centre is exactly right here without any customisation:
        // LinearSnapHelper measures a child by its DECORATED bounds, which the decoration above has
        // reduced to the one step the card occupies and centred on the card, and it measures the
        // container as getStartAfterPadding() + getTotalSpace() / 2 — which the symmetric focus
        // padding below puts on the screen's centre line. Both agree with applyFan()'s focus line.
        new LinearSnapHelper().attachToRecyclerView(recyclerView);

        // Seed the padding from the display before the first layout so the fan is never drawn once
        // with the front card jammed against the right edge, then keep it honest from the real
        // width (the list is match_parent, but a resize or a display change would move it).
        applyFocusPadding(recyclerView,
                recyclerView.getContext().getResources().getDisplayMetrics().widthPixels);
        recyclerView.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> applyFocusPadding(recyclerView, r - l));

        // Keep the move and remove animations — with cards this heavily overlapped, the fan
        // closing up after a dismissal is most of what makes the dismissal legible — but drop the
        // change animation. The host re-binds the whole tail after a dismissal so the depth
        // transforms follow the new positions, and cross-fading every card in the fan for that
        // reads as a flicker through the whole pile.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        // Re-derive every card's depth from where it actually sits, once per frame. A scroll
        // listener alone would miss the first layout, the relayout after a dismissal, and the
        // frames of an item animation.
        recyclerView.getViewTreeObserver().addOnPreDrawListener(() -> {
            applyFan(recyclerView);
            return true;
        });

        // Swipe-to-dismiss, minus the broken hit test: the callback declares the direction so
        // ItemTouchHelper still runs the drag, the threshold, the fling velocity check and the
        // settle animation, but isItemViewSwipeEnabled() is false so it never selects a card off
        // its own findChildViewUnder call. DepthAwareSwipeStarter does that with a Z-aware test.
        mSwipeHelper = new ItemTouchHelper(new FanSwipeCallback());
        mSwipeHelper.attachToRecyclerView(recyclerView);
        recyclerView.addOnItemTouchListener(new DepthAwareSwipeStarter(recyclerView.getContext()));
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // RecyclerView hands back views still wearing whatever transform they died with — a
        // dismissed card is parked off the top of the screen, a card that fell off the back of the
        // fan is at alpha 0 and INVISIBLE — so everything this style touches is reset before the
        // new position's depth goes on. Without this, reuse shows up as ghost cards.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setAlpha(1f);
        card.setElevation(0f);
        card.setVisibility(View.VISIBLE);

        // Seed from the adapter position, which is the depth at rest, so a freshly bound card is
        // never drawn full size for a frame. The pre-draw pass takes over with the real geometry.
        applyDepth(card, position);
    }

    /** Gives every attached card the transform its current distance along the fan calls for. */
    private void applyFan(RecyclerView recyclerView) {
        final int childCount = recyclerView.getChildCount();
        if (childCount == 0 || mStepPx <= 0) {
            return;
        }
        // The focus line: the padding applied by applyFocusPadding is symmetric, so the one step
        // of unpadded viewport is centred and the card resting at the focus line is centred too.
        final float focus = recyclerView.getWidth() * 0.5f;

        for (int i = 0; i < childCount; i++) {
            final View card = recyclerView.getChildAt(i);
            // Detached mid-removal: no adapter position, no task, and the item animator owns its
            // alpha for the length of the remove animation. Leave it to the recycler.
            if (recyclerView.getChildAdapterPosition(card) == RecyclerView.NO_POSITION) {
                continue;
            }
            // Includes translationX, which this style never sets but the item animator does while
            // the fan closes up after a dismissal. Reading the drawn position rather than the laid
            // out one keeps the depths coherent with where the cards actually are for those frames.
            final float centre =
                    card.getLeft() + card.getTranslationX() + card.getWidth() * 0.5f;
            applyDepth(card, (focus - centre) / mStepPx);
        }
    }

    /**
     * @param depth distance back along the fan, in steps; 0 is the card at the focus line and
     *              negative means already dealt past it, towards the viewer.
     */
    private void applyDepth(View card, float depth) {
        final float clamped = Math.max(-LEAD_FADE, Math.min(depth, MAX_DEPTH));

        final View group = card.findViewById(R.id.oneui_recents_stack_group);
        if (group != null && group.getHeight() > 0) {
            // Shrink about the card FACE's centre, which is the group's centre by construction.
            // Not the item view's centre: the item view is a whole list tall so the fan can be
            // centred vertically without the layout manager's help, and the face rides above that
            // centre to stay clear of the host's "Close all" pill. Scaling about the item view's
            // middle would slide each card down as it receded; the real thing keeps every card's
            // centre on one horizontal line. Set here rather than at bind time on purpose — the
            // card has no height yet when it is bound, and a pivot set then would stick at zero.
            card.setPivotX(group.getLeft() + group.getWidth() * 0.5f);
            card.setPivotY(group.getTop() + group.getHeight() * 0.5f);
        }

        final float scale = (float) Math.pow(DEPTH_SCALE, clamped);
        card.setScaleX(scale);
        card.setScaleY(scale);

        // Two ramps bound the fan: a card dealt past the focus line fades out as it comes forward,
        // and a card falling off the back fades out over the last stretch. The tail fade is only
        // insurance — by then the card is behind an opaque one — but it costs nothing and covers
        // the mid-scroll frames.
        final float lead = clamp01(1f + Math.min(0f, depth) / LEAD_FADE);
        final float tail = clamp01(1f - (depth - TAIL_FADE_FROM) / (MAX_DEPTH - TAIL_FADE_FROM));
        // Snapped to 1 near the top of the range, not just rounded for tidiness: the card resting
        // at the focus line lands a fraction of a pixel off centre (the list's width and the step
        // do not divide evenly), which is enough to put it at alpha 0.997 — and any alpha below 1
        // on a view that reports overlapping rendering, as this one does with the icon lapping over
        // the card face, buys an offscreen layer for the front card on every single frame.
        final float alpha = lead * tail > 0.995f ? 1f : lead * tail;
        card.setAlpha(alpha);

        // INVISIBLE, not merely transparent: a card at alpha 0 still has bounds 260dp wide sitting
        // over the front of the fan, and would swallow the tap meant for the card you can see.
        card.setVisibility(alpha <= 0.01f ? View.INVISIBLE : View.VISIBLE);

        // Draw order and touch dispatch both follow Z, and RecyclerView's natural child order puts
        // the OLDEST task last, i.e. on top. Invert it so the front of the fan wins. Offset by
        // MAX_DEPTH so Z stays non-negative across the whole range.
        card.setTranslationZ((MAX_DEPTH - clamped) * mZStepPx);

        final View dim = card.findViewById(R.id.oneui_recents_stack_dim);
        if (dim != null) {
            dim.setAlpha(Math.min(MAX_DIM, Math.max(0f, depth) * DIM_STEP));
        }
    }

    /**
     * Pads the list so that the single step of viewport left between the paddings is centred on
     * screen. That places the card at either end of the fan on the centre line when the list is
     * scrolled to that end, and gives the snap helper the same centre line to work from.
     */
    private void applyFocusPadding(RecyclerView recyclerView, int width) {
        if (width <= 0 || mStepPx <= 0) {
            return;
        }
        final int side = Math.max(0, (width - mStepPx) / 2);
        if (recyclerView.getPaddingLeft() == side && recyclerView.getPaddingRight() == side) {
            return; // Already right; setPadding would only cost another layout pass.
        }
        // Vertical padding belongs to the host, which keeps it in step with the system bar insets.
        recyclerView.setPadding(side, recyclerView.getPaddingTop(), side,
                recyclerView.getPaddingBottom());
    }

    private void ensureMetrics(Context context) {
        if (mStepPx > 0) {
            return;
        }
        final float density = context.getResources().getDisplayMetrics().density;
        mStepPx = Math.round(STEP_DP * density);
        mCardWidthPx = Math.round(CARD_WIDTH_DP * density);
        mZStepPx = Z_STEP_DP * density;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /**
     * Collapses each card to a one-step slot, symmetrically, so the cards overlap.
     *
     * <p>Both insets are negative and equal, which matters: {@code layoutDecoratedWithMargins}
     * lays the view out at {@code left + insets.left} to {@code right - insets.right}, so equal
     * insets leave the card centred on the slot the layout manager gave it. The slot centres are
     * then one step apart — which is the whole of the fan's horizontal spacing, and why nothing in
     * this style ever sets {@code translationX} (leaving that property free for the item animator
     * to use, and for {@link #applyFan} to read back).
     */
    private final class FanDecoration extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                RecyclerView.State state) {
            final int cardWidth = cardWidth(view);
            if (cardWidth <= mStepPx) {
                return;
            }
            final int total = cardWidth - mStepPx;
            final int left = total / 2;
            outRect.left = -left;
            outRect.right = -(total - left); // Split odd remainders so the slot is exactly a step.
        }

        private int cardWidth(View view) {
            final ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && lp.width > 0) {
                mCardWidthPx = lp.width;
            } else if (view.getWidth() > 0) {
                mCardWidthPx = view.getWidth();
            }
            return mCardWidthPx;
        }
    }

    /**
     * Keeps a card attached while its body is still on screen. A card's decorated bounds are only
     * the one step it advances the layout by, so the stock recycler would drop it the moment that
     * step scrolled past the edge — while most of a 260dp card was still drawn over the fan.
     */
    private static final class FanLayoutManager extends LinearLayoutManager {

        private final int mNoRecycleMarginPx;

        FanLayoutManager(Context context, int noRecycleMarginPx) {
            super(context, HORIZONTAL, /* reverseLayout= */ true);
            mNoRecycleMarginPx = noRecycleMarginPx;
        }

        @Override
        protected void calculateExtraLayoutSpace(RecyclerView.State state, int[] extraLayoutSpace) {
            extraLayoutSpace[0] = mNoRecycleMarginPx;
            extraLayoutSpace[1] = mNoRecycleMarginPx;
        }
    }

    /**
     * The swipe half of dismissal: drag the front card up and off. Deliberately never selects a
     * card itself — see {@link DepthAwareSwipeStarter}.
     */
    private static final class FanSwipeCallback extends ItemTouchHelper.SimpleCallback {

        FanSwipeCallback() {
            super(0, ItemTouchHelper.UP);
        }

        @Override
        public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder a,
                RecyclerView.ViewHolder b) {
            return false;
        }

        /**
         * False, so {@code checkSelectForSwipe} bails out before it ever calls
         * {@code findSwipedView}. The swipe flag above is still reported by
         * {@code getMovementFlags}, which is what {@link ItemTouchHelper#startSwipe} checks.
         */
        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        /** No reordering in recents, and this keeps the long-press hit test out of the picture. */
        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        /**
         * A fraction of the ITEM VIEW, which here is as tall as the whole list rather than as tall
         * as the card — the item view has to be, so the fan can be centred vertically. The default
         * 0.5 would mean dragging half the screen; this is roughly a third of the card.
         */
        @Override
        public float getSwipeThreshold(RecyclerView.ViewHolder viewHolder) {
            return SWIPE_THRESHOLD;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
            // Dismissal belongs to the host, and the click listener it puts on
            // @id/oneui_recents_card_dismiss is the only handle a style is given onto it. This
            // layout has no ✕ — One UI's tilt stack does not — so it carries a zero-sized, GONE
            // view under that id purely to be that handle. callOnClick rather than performClick:
            // the gesture has already given its own feedback, so the click sound would be a second.
            final View dismiss = holder.itemView.findViewById(R.id.oneui_recents_card_dismiss);
            if (dismiss != null) {
                dismiss.callOnClick();
            }
        }
    }

    /**
     * Picks the card the finger is actually on and hands it to {@link ItemTouchHelper#startSwipe}.
     *
     * <p>This exists because {@link RecyclerView#findChildViewUnder} — which is how
     * {@code ItemTouchHelper} would otherwise choose — tests raw bounds in child-index order and so
     * picks the card furthest back in an overlapping fan rather than the one on top (the long
     * version is on {@link #getSwipeDirs()}). Here the candidates are filtered by
     * {@link View#getHitRect}, which applies the card's matrix so the depth scale counts, skipped
     * if this style has faded them out, and the winner is the one with the greatest
     * {@link View#getZ()} — the same ordering hwui draws and dispatches touches in, so the card
     * that dies is the card under the finger.
     *
     * <p>Only upward drags are claimed. Sideways belongs to the fan's own scrolling, and a
     * downward drag is left alone so it can fall through to the host.
     */
    private final class DepthAwareSwipeStarter implements RecyclerView.OnItemTouchListener {

        private final int mTouchSlop;
        private final Rect mHitRect = new Rect();

        private float mDownX;
        private float mDownY;
        private boolean mUndecided;

        DepthAwareSwipeStarter(Context context) {
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getX();
                    mDownY = e.getY();
                    mUndecided = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mUndecided) {
                        maybeStartSwipe(rv, e);
                    }
                    break;
                default:
                    mUndecided = false;
                    break;
            }
            // Never intercept. ItemTouchHelper has to be the listener that ends up owning the
            // gesture, and RecyclerView hands every other listener an ACTION_CANCEL when one of
            // them intercepts — which would cancel the swipe on the frame it started.
            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {}

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            mUndecided = false;
        }

        private void maybeStartSwipe(RecyclerView rv, MotionEvent e) {
            final float dx = e.getX() - mDownX;
            final float dy = e.getY() - mDownY;
            // Upwards and past the slop, and more up than sideways, or it is a tap or a scroll.
            if (dy > -mTouchSlop || Math.abs(dy) <= Math.abs(dx)) {
                return;
            }
            mUndecided = false;
            if (rv.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING) {
                return; // The fan already claimed this gesture.
            }
            final RecyclerView.ViewHolder holder = findFrontCardUnder(rv, mDownX, mDownY);
            if (holder != null && mSwipeHelper != null) {
                mSwipeHelper.startSwipe(holder);
            }
        }

        private RecyclerView.ViewHolder findFrontCardUnder(RecyclerView rv, float x, float y) {
            View front = null;
            float frontZ = 0f;
            for (int i = 0, n = rv.getChildCount(); i < n; i++) {
                final View child = rv.getChildAt(i);
                // getChildCount here is ViewGroup's, so it also returns views the recycler is only
                // holding on to for a disappearing animation. Those no longer stand for a task and
                // report no adapter position, and swiping one would dismiss whatever has since
                // shifted into its old slot.
                if (rv.getChildAdapterPosition(child) == RecyclerView.NO_POSITION) {
                    continue;
                }
                if (child.getVisibility() != View.VISIBLE) {
                    continue; // Off the back of the fan; not something the user can point at.
                }
                child.getHitRect(mHitRect);
                if (!mHitRect.contains((int) x, (int) y)) {
                    continue;
                }
                // >= so that, as in ViewGroup's own dispatch, the later child wins a Z tie.
                final float z = child.getZ();
                if (front == null || z >= frontZ) {
                    front = child;
                    frontZ = z;
                }
            }
            return front == null ? null : rv.getChildViewHolder(front);
        }
    }
}
