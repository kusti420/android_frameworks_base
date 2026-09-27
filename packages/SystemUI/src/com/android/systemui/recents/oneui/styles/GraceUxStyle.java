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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

import java.util.WeakHashMap;

/**
 * Samsung Grace UX recents — the Android 7.0 card stack as it shipped on the Galaxy A-series
 * (SM-A310F, Samsung Experience 8.0).
 *
 * <p>Grace UX recents is AOSP 7.x's {@code TaskStackLayoutAlgorithm} with Samsung's
 * "second view expanded", "snap action", "more round" and "lower app bar" changes switched on.
 * This is a re-implementation of that behaviour from its measured parameters (the resource values
 * and the formulas, as documented in {@code SPEC_RECENTS.md} Part 1 (b)); no Samsung code or
 * image is used. Against the AOSP cascade ({@link GraceCascadeStyle}) it differs in that:
 *
 * <ul>
 *   <li>the visible range is {@code [-1.82, +1.82]} and the curve is a nearly flat cubic into a
 *       straight steep line: {@code M0,1 C0,1 fx/2,(1+fy)/2+.001 fx,fy L1,0} with
 *       {@code fx = 0.65, fy = 0.85} (the with-navigation-key values);
 *   <li>cards scale with depth: {@code ((1 - s) * 0.07 + 0.93) * 0.96} about their centre, where
 *       {@code s} follows {@code M0,1 L.4,.6 L1,0}, so 0.893 at the back and 0.96 at the front;
 *   <li>scrolling snaps to whole cards, offset by {@code minScrollP = -0.24}, and recents opens with
 *       the <em>previous</em> app as the large card and the current one peeking below it;
 *   <li>nothing is dimmed; the 7.x dim curve is still evaluated, but only to fade out cards that
 *       have gone far back ({@code alpha = (0.30 - dim) * 20} once the dim passes 0.25);
 *   <li>translationZ is {@code lerp(3, 24dp, clamp01(0.3 + x)) * 0.75};
 *   <li>the card is taller: {@code stackH - initialTopOffset * 7/8 - 57px - navBar};
 *   <li>a 44dp header with 6dp corners, a 200 x 34dp "CLOSE ALL" bar at the bottom, a 0.20 scrim,
 *       and the "spread" enter/exit animation.
 * </ul>
 */
public class GraceUxStyle implements RecentsStyle {

    /** RecentsView: black at 51/255. */
    private static final int SCRIM = 0x33000000;
    /** recents_close_all_button (values-sw360dp): 200 x 34dp, 6dp corners. */
    private static final float CLOSE_ALL_WIDTH_DP = 200f;
    private static final float CLOSE_ALL_HEIGHT_DP = 34f;
    private static final float CLOSE_ALL_RADIUS_DP = 6f;
    /**
     * The button is centred in a 54dp container, 16dp above the navigation bar. The host already
     * puts the button's bottom 14dp above it; the rest is a translation.
     */
    private static final float CLOSE_ALL_EXTRA_LIFT_DP = 16f + (54f - 34f) / 2f - 14f;
    /** recents_show/hide_close_all_button: translateY 110%->0 in 300ms, 0->210% in 370ms. */
    private static final long CLOSE_ALL_SHOW_MS = 300;
    private static final long CLOSE_ALL_HIDE_MS = 370;
    /** Spread enter 400ms, exit 333ms; alpha 233ms / 300ms; z delayed 200ms over 200ms. */
    private static final long SPREAD_ENTER_MS = 400;
    private static final long SPREAD_ENTER_ALPHA_MS = 233;
    private static final long SPREAD_EXIT_MS = 333;
    private static final long SPREAD_EXIT_ALPHA_MS = 300;
    private static final long SPREAD_Z_DELAY_MS = 200;
    private static final long SPREAD_Z_MS = 200;
    /** recents_from_launcher_enter_spread: window alpha 300ms sine-in-out-80. */
    private static final long SCRIM_IN_MS = 300;
    /** Spread start: cards collapse to the stack origin at 0.7 x displayW / taskW. */
    private static final float SPREAD_START_WIDTH_FRACTION = 0.7f;
    /** SwipeHelper, unchanged from AOSP. */
    private static final float SWIPE_SIZE_FACTOR = 1.5f;
    private static final float SWIPE_DISMISS_FRACTION = 0.4f;
    private static final float SWIPE_ESCAPE_DP_PER_S = 800f;

    private GraceUxLayoutManager mLayoutManager;
    private TextView mCloseAll;
    private float mDensity = 1f;

    /** Required by {@code RecentsStyles.forType}. */
    public GraceUxStyle() {}

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mLayoutManager = new GraceUxLayoutManager(context);
        return mLayoutManager;
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_graceux;
    }

    /**
     * Sideways, as on the device. Safe with the host's stock {@link ItemTouchHelper} because the
     * layout manager attaches children in descending position order, so child index order is Z
     * order and {@link RecyclerView#findChildViewUnder} lands on the card that is actually on top.
     */
    @Override
    public int getSwipeDirs() {
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        // The host re-binds the whole tail after a dismissal; with overlapping cards the change
        // animation would cross-fade every one of them. Remove and move stay.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        // EnableSnapAction: flings and drag releases settle on whole cards.
        recyclerView.setOnFlingListener(new RecyclerView.OnFlingListener() {
            @Override
            public boolean onFling(int velocityX, int velocityY) {
                return mLayoutManager != null && mLayoutManager.snapFling(recyclerView, velocityY);
            }
        });
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private boolean mDragged;

            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    mDragged = true;
                    if (mLayoutManager != null) {
                        mLayoutManager.cancelSnap();
                    }
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && mDragged) {
                    mDragged = false;
                    if (mLayoutManager != null) {
                        mLayoutManager.snapToNearest(rv);
                    }
                }
            }
        });
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Undo whatever the recycled view died wearing (a fling parks a card off to the side, a
        // card that fell off the back is scaled down and faded); the layout manager re-applies the
        // stack transform right after this.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setVisibility(View.VISIBLE);
        // Samsung picks the header text colour by luminance; the light one is #fafafa.
        final int fg = RecentsTaskSource.isDarkColor(item.color) ? 0xFFFAFAFA : 0xDE000000;
        final TextView title = card.findViewById(R.id.oneui_recents_card_title);
        if (title != null) {
            title.setTextColor(fg);
        }
        final View dismiss = card.findViewById(R.id.oneui_recents_card_dismiss);
        if (dismiss instanceof TextView) {
            ((TextView) dismiss).setTextColor(fg);
        }
    }

    // --- Backdrop: no blur, the wallpaper under a 0.20 black scrim.

    @Override
    public boolean usesBackdropBlur() {
        return false;
    }

    @Override
    public Integer getScrimColor(boolean blurEnabled) {
        return SCRIM;
    }

    @Override
    public boolean showsWallpaper() {
        return true;
    }

    // --- CLOSE ALL (recents_close_button_bg.xml).

    @Override
    public void styleClearAll(TextView closeAll) {
        mCloseAll = closeAll;
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x5A000000);
        shape.setStroke(Math.max(1, Math.round(mDensity)), 0x40FAFAFA);
        shape.setCornerRadius(CLOSE_ALL_RADIUS_DP * mDensity);
        closeAll.setBackground(new RippleDrawable(ColorStateList.valueOf(0x29000000), shape, null));
        closeAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        closeAll.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        closeAll.setAllCaps(true);
        closeAll.setLetterSpacing(0f);
        closeAll.setTextColor(0xFFFAFAFA);
        final ViewGroup.LayoutParams lp = closeAll.getLayoutParams();
        lp.width = Math.round(CLOSE_ALL_WIDTH_DP * mDensity);
        lp.height = Math.round(CLOSE_ALL_HEIGHT_DP * mDensity);
        closeAll.setLayoutParams(lp);
        closeAll.setTranslationY(-CLOSE_ALL_EXTRA_LIFT_DP * mDensity);
    }

    // --- Spread animation (EnableSpreadAnimation).

    /**
     * Every card starts collapsed onto the stack origin — its top at the stack top, scaled to
     * {@code 0.7 x displayW / taskW}, transparent, flat — and spreads to its place in the stack
     * over 400ms {@code (0.33,0,0.1,1)}, fading in over 233ms, its shadow rising after 200ms.
     * CLOSE ALL slides up from 110% of its height over 300ms.
     */
    @Override
    public void animateEnter(RecyclerView recyclerView) {
        final GraceUxLayoutManager lm = mLayoutManager;
        if (lm == null) {
            return;
        }
        RecentsMotion.fadeScrim(recyclerView, 0f, 1f, SCRIM_IN_MS, RecentsMotion.SINE_IN_OUT_80);
        final float startScale = lm.spreadStartScale(recyclerView.getWidth());
        for (int i = 0, n = recyclerView.getChildCount(); i < n; i++) {
            final View card = recyclerView.getChildAt(i);
            final float[] target = lm.targetOf(card);
            if (target == null) {
                continue;
            }
            final float startY = lm.spreadTranslationY(card, startScale);
            final ObjectAnimator spread = ObjectAnimator.ofPropertyValuesHolder(card,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, startY, 0f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, startScale, target[0]),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, startScale, target[0]));
            spread.setDuration(SPREAD_ENTER_MS);
            spread.setInterpolator(RecentsMotion.SINE_IN_OUT_90);
            card.setAlpha(0f);
            card.setElevation(0f);
            spread.start();
            card.animate().alpha(target[1]).setDuration(SPREAD_ENTER_ALPHA_MS)
                    .setInterpolator(RecentsMotion.SINE_IN_OUT_90).start();
            final ObjectAnimator z = ObjectAnimator.ofFloat(card, "elevation", 0f, target[2]);
            z.setStartDelay(SPREAD_Z_DELAY_MS);
            z.setDuration(SPREAD_Z_MS);
            z.start();
        }
        if (mCloseAll != null && mCloseAll.getVisibility() == View.VISIBLE) {
            final float base = -CLOSE_ALL_EXTRA_LIFT_DP * mDensity;
            final float h = CLOSE_ALL_HEIGHT_DP * mDensity;
            mCloseAll.setTranslationY(base + 1.1f * h);
            mCloseAll.animate().translationY(base).setDuration(CLOSE_ALL_SHOW_MS)
                    .setInterpolator(RecentsMotion.SINE_IN_OUT_90).start();
        }
    }

    /**
     * The spread in reverse: back to the stack origin at the start scale over 333ms, fading out
     * over 300ms; CLOSE ALL drops by 210% of its height over 370ms.
     */
    @Override
    public boolean animateExit(RecyclerView recyclerView, Runnable onEnd) {
        final GraceUxLayoutManager lm = mLayoutManager;
        if (lm == null) {
            return false;
        }
        final float startScale = lm.spreadStartScale(recyclerView.getWidth());
        for (int i = 0, n = recyclerView.getChildCount(); i < n; i++) {
            final View card = recyclerView.getChildAt(i);
            card.animate()
                    .translationY(lm.spreadTranslationY(card, startScale))
                    .scaleX(startScale).scaleY(startScale)
                    .setDuration(SPREAD_EXIT_MS)
                    .setInterpolator(RecentsMotion.SINE_IN_OUT_90).start();
            final ValueAnimator fade = ObjectAnimator.ofFloat(card, View.ALPHA, card.getAlpha(), 0f);
            fade.setDuration(SPREAD_EXIT_ALPHA_MS);
            fade.setInterpolator(RecentsMotion.SINE_IN_OUT_90);
            fade.start();
        }
        if (mCloseAll != null) {
            mCloseAll.animate()
                    .translationY(-CLOSE_ALL_EXTRA_LIFT_DP * mDensity
                            + 2.1f * CLOSE_ALL_HEIGHT_DP * mDensity)
                    .setDuration(CLOSE_ALL_HIDE_MS)
                    .setInterpolator(RecentsMotion.SINE_IN_OUT_90).start();
        }
        RecentsMotion.fadeScrim(recyclerView, 1f, 0f, SPREAD_EXIT_MS, RecentsMotion.SINE_IN_OUT_100);
        RecentsMotion.runAfter(recyclerView, Math.max(SPREAD_EXIT_MS, CLOSE_ALL_HIDE_MS), onEnd);
        return true;
    }

    // --- Swipe: AOSP's SwipeHelper values (Samsung left them alone).

    @Override
    public float getSwipeThreshold(RecyclerView.ViewHolder holder) {
        final View card = holder.itemView;
        final View parent = (View) card.getParent();
        if (parent == null || card.getWidth() == 0) {
            return RecentsStyle.super.getSwipeThreshold(holder);
        }
        final float size = SWIPE_SIZE_FACTOR * Math.max(parent.getWidth(), parent.getHeight());
        return SWIPE_DISMISS_FRACTION * size / card.getWidth();
    }

    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return SWIPE_ESCAPE_DP_PER_S * mDensity;
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The Grace UX stack: {@code TaskStackLayoutAlgorithm} in its unfocused state with Samsung's
     * "second view expanded" parameters. Structurally the same as {@link CascadeLayoutManager}: one
     * float of scroll progress in task units, card positions and transforms as pure functions of
     * {@code relP = taskP - scrollP}, children attached back to front so child index order is Z
     * order.
     */
    private static final class GraceUxLayoutManager extends RecyclerView.LayoutManager {

        // recents_layout_expanded_view_*_with_navi_key and friends (integers.xml).
        private static final float RANGE_MIN = -1.82f;
        private static final float RANGE_MAX = 1.82f;
        private static final float FIRST_X = 0.65f;
        private static final float FIRST_Y = 0.85f;
        private static final float MIN_SCROLL_P = -0.24f;
        /** recents_layout_inset_for_close_all_button_margin_with_navi_key: a raw pixel count. */
        private static final int CLOSE_ALL_INSET_PX = 57;
        /** recents_layout_task_view_scale_full_inset and the 0.96 overall factor. */
        private static final float SCALE_INSET = 0.93f;
        private static final float SCALE_FACTOR = 0.96f;
        /** Fade-out check point (phone portrait with a navigation key) and its window. */
        private static final float FADE_CHECK_POINT = 0.25f;
        private static final float FADE_WINDOW = 0.05f;
        private static final float FADE_GAIN = 20f;
        /** 7.x dim curve extremes; only used to drive the fade. */
        private static final float MAX_DIM = 0.25f;
        private static final float MED_DIM = 0.15f;

        // Phone dimens, dp.
        private static final float MARGIN_DP = 16f;
        private static final float INITIAL_TOP_OFFSET_PORT_DP = 128f;
        private static final float INITIAL_TOP_OFFSET_LAND_DP = 72f;
        private static final float Z_MIN_DP = 3f;
        private static final float Z_MAX_DP = 24f;

        /**
         * Card height / width as the algorithm produces it on the 360 x 640dp phone it shipped on:
         * {@code 600 - 112 - 25 = 463} by {@code 328}. On a 20:9 screen the same arithmetic gives a
         * much longer card, so the surplus is taken off the top of the stack rect instead (the
         * whole arrangement slides down as a rigid body), as the cascade does.
         */
        private static final float REFERENCE_CARD_ASPECT = 463f / 328f;

        /** Snap fling: target = int(v / 3000) cards; duration clamp(|v| / 2000 * 300, 300, 1100). */
        private static final float FLING_PX_PER_CARD = 3000f;
        private static final long SNAP_MIN_MS = 300;
        private static final long SNAP_MAX_MS = 1100;
        /** A settle after a drag with no fling: the 200ms stack-scroll duration. */
        private static final long SETTLE_MS = 200;
        /**
         * QuintOut80: a quint-out ease. The original is a two-segment spline through the same
         * shape; this is the closed form.
         */
        private static final Interpolator QUINT_OUT = t -> {
            final float u = 1f - t;
            return 1f - u * u * u * u * u;
        };

        private final Context mContext;
        private final float mDensity;
        private RecyclerView mRecyclerView;

        private int mStackTop;
        private int mStackHeight;
        private int mCardLeft;
        private int mCardWidth;
        private int mCardHeight;
        private int mInitialTopOffset;
        private float mMinZ;
        private float mMaxZ;

        private CascadePathInterpolator mCurve;
        private CascadePathInterpolator mDimCurve;
        private CascadePathInterpolator mScaleCurve;
        private int mCurveStackHeight = -1;
        private float mPxPerUnit;

        private float mScrollP;
        private float mMinScrollP;
        private float mMaxScrollP;
        private float mInitialScrollP;

        private int mPendingScrollPosition = RecyclerView.NO_POSITION;
        private boolean mHasLaidOut;
        private ValueAnimator mSnapAnimator;

        /** The transform the stack wants for each attached card: {scale, alpha, elevation}. */
        private final WeakHashMap<View, float[]> mTargets = new WeakHashMap<>();

        GraceUxLayoutManager(Context context) {
            mContext = context;
            mDensity = context.getResources().getDisplayMetrics().density;
        }

        @Override
        public RecyclerView.LayoutParams generateDefaultLayoutParams() {
            return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

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
            cancelSnap();
            mRecyclerView = null;
        }

        @Override
        public void onAdapterChanged(RecyclerView.Adapter oldAdapter,
                RecyclerView.Adapter newAdapter) {
            removeAllViews();
            mHasLaidOut = false;
        }

        // --- Layout -----------------------------------------------------------------------

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
                mScrollP = mPendingScrollPosition <= 0 ? mInitialScrollP
                        : clamp((itemCount - 1) - mPendingScrollPosition + MIN_SCROLL_P,
                                mMinScrollP, mMaxScrollP);
                mPendingScrollPosition = RecyclerView.NO_POSITION;
            } else if (!mHasLaidOut) {
                mScrollP = mInitialScrollP;
            }
            mScrollP = clamp(mScrollP, mMinScrollP, mMaxScrollP);
            mHasLaidOut = true;
            detachAndScrapAttachedViews(recycler);
            fill(recycler, itemCount);
        }

        @Override
        public void scrollToPosition(int position) {
            cancelSnap();
            mPendingScrollPosition = position;
            requestLayout();
        }

        private void fill(RecyclerView.Recycler recycler, int itemCount) {
            for (int i = getChildCount() - 1; i >= 0; i--) {
                final View child = getChildAt(i);
                final int position = getPosition(child);
                if (position < 0 || position >= itemCount || !isInVisibleRange(position, itemCount)) {
                    removeAndRecycleViewAt(i, recycler);
                }
            }
            for (int position = itemCount - 1; position >= 0; position--) {
                if (!isInVisibleRange(position, itemCount)) {
                    continue;
                }
                View child = findViewByPosition(position);
                if (child == null) {
                    child = recycler.getViewForPosition(position);
                    addViewInStackOrder(child, position);
                }
                child.measure(
                        View.MeasureSpec.makeMeasureSpec(mCardWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(mCardHeight, View.MeasureSpec.EXACTLY));
                applyTransform(child, position, itemCount);
            }
        }

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

        private boolean isInVisibleRange(int position, int itemCount) {
            final float taskP = (itemCount - 1) - position;
            return taskP >= Math.floor(mScrollP + RANGE_MIN)
                    && taskP <= Math.ceil(mScrollP + RANGE_MAX);
        }

        /** {@code getStackTransform}, unfocused branch, with the Samsung additions. */
        private void applyTransform(View child, int position, int itemCount) {
            final float taskP = (itemCount - 1) - position;
            final float normX = normalizedX(taskP - mScrollP);
            final int top = mStackTop + (int) ((1f - mCurve.getInterpolation(normX)) * mStackHeight);
            if (child.getLeft() == mCardLeft && child.getWidth() == mCardWidth
                    && child.getHeight() == mCardHeight && !child.isLayoutRequested()) {
                child.offsetTopAndBottom(top - child.getTop());
            } else {
                layoutDecorated(child, mCardLeft, top, mCardLeft + mCardWidth, top + mCardHeight);
            }

            // Depth scale about the centre (View's default pivot, as TaskView used).
            final float s = mScaleCurve.getInterpolation(normX);
            final float scale = ((1f - s) * (1f - SCALE_INSET) + SCALE_INSET) * SCALE_FACTOR;

            // Z from the bounded scroll, shifted by 0.3 and scaled by 3/4.
            final float boundedNormX =
                    normalizedX(taskP - clamp(mScrollP, mMinScrollP, mMaxScrollP));
            final float z = (mMinZ + clamp01(0.3f + boundedNormX) * (mMaxZ - mMinZ)) * 0.75f;

            // No dim; the dim curve drives a fade-out of cards far back instead.
            final float dim = mDimCurve.getInterpolation(
                    normalizedX(taskP - Math.min(mScrollP, mMaxScrollP)));
            final float alpha = dim > FADE_CHECK_POINT
                    ? clamp01((FADE_CHECK_POINT + FADE_WINDOW - dim) * FADE_GAIN) : 1f;

            float[] target = mTargets.get(child);
            if (target == null) {
                target = new float[3];
                mTargets.put(child, target);
            }
            target[0] = scale;
            target[1] = alpha;
            target[2] = z;
            // During the spread animation a layout pass (a snapshot arriving) writes the targets
            // once; the running property animators overwrite them again on the next frame.
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(alpha);
            child.setElevation(z);
            child.setVisibility(alpha <= 0f ? View.INVISIBLE : View.VISIBLE);
        }

        float[] targetOf(View child) {
            return mTargets.get(child);
        }

        /** The spread animation's collapsed scale, {@code 0.7 x displayW / taskW}. */
        float spreadStartScale(int displayWidth) {
            return mCardWidth > 0 ? SPREAD_START_WIDTH_FRACTION * displayWidth / mCardWidth : 1f;
        }

        /** Translation that puts {@code child}, scaled about its centre, at the stack origin. */
        float spreadTranslationY(View child, float scale) {
            return mStackTop - child.getTop() - (1f - scale) * child.getHeight() / 2f;
        }

        // --- Scrolling --------------------------------------------------------------------

        @Override
        public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
                RecyclerView.State state) {
            if (dy == 0 || getChildCount() == 0 || mPxPerUnit <= 0f) {
                return 0;
            }
            final float unclamped = mScrollP + dy / mPxPerUnit;
            final float target = clamp(unclamped, mMinScrollP, mMaxScrollP);
            if (target == mScrollP) {
                return 0;
            }
            final int consumed = (target == unclamped)
                    ? dy : Math.round((target - mScrollP) * mPxPerUnit);
            mScrollP = target;
            fill(recycler, state.getItemCount());
            return consumed;
        }

        @Override
        public int computeVerticalScrollExtent(RecyclerView.State state) {
            return mStackHeight;
        }

        @Override
        public int computeVerticalScrollOffset(RecyclerView.State state) {
            return mPxPerUnit <= 0f ? 0 : Math.round((mScrollP - mMinScrollP) * mPxPerUnit);
        }

        @Override
        public int computeVerticalScrollRange(RecyclerView.State state) {
            return mPxPerUnit <= 0f ? 0
                    : mStackHeight + Math.round((mMaxScrollP - mMinScrollP) * mPxPerUnit);
        }

        /**
         * EnableSnapAction's fling: advance {@code int(v / 3000)} cards (at least one in the fling
         * direction) and animate there in {@code clamp(|v| / 2000 x 300, 300, 1100)}ms with a
         * quint-out ease.
         */
        boolean snapFling(RecyclerView rv, int velocityY) {
            if (getChildCount() == 0 || velocityY == 0) {
                return false;
            }
            final float rel = mScrollP - mMinScrollP;
            final int cards = Math.max(1, (int) (Math.abs(velocityY) / FLING_PX_PER_CARD));
            final float targetRel = velocityY > 0
                    ? (float) Math.floor(rel + 1e-3f) + cards
                    : (float) Math.ceil(rel - 1e-3f) - cards;
            final long duration = (long) clamp(Math.abs(velocityY) / 2000f * 300f,
                    SNAP_MIN_MS, SNAP_MAX_MS);
            animateScrollTo(rv, targetRel + mMinScrollP, duration);
            return true;
        }

        /** A drag released without a fling settles on the nearest whole card. */
        void snapToNearest(RecyclerView rv) {
            if (getChildCount() == 0 || (mSnapAnimator != null && mSnapAnimator.isRunning())) {
                return;
            }
            final float rel = mScrollP - mMinScrollP;
            animateScrollTo(rv, Math.round(rel) + mMinScrollP, SETTLE_MS);
        }

        void cancelSnap() {
            if (mSnapAnimator != null) {
                mSnapAnimator.cancel();
                mSnapAnimator = null;
            }
        }

        private void animateScrollTo(RecyclerView rv, float targetP, long duration) {
            cancelSnap();
            final float to = clamp(targetP, mMinScrollP, mMaxScrollP);
            final float from = mScrollP;
            if (Math.abs(to - from) < 1e-4f) {
                return;
            }
            final ValueAnimator anim = ValueAnimator.ofFloat(from, to);
            anim.setDuration(duration);
            anim.setInterpolator(QUINT_OUT);
            anim.addUpdateListener(a -> {
                final float p = (float) a.getAnimatedValue();
                final int dy = Math.round((p - mScrollP) * mPxPerUnit);
                if (dy != 0) {
                    rv.scrollBy(0, dy);
                }
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mSnapAnimator == animation) {
                        mSnapAnimator = null;
                        // Remove the pixel rounding left by scrollBy.
                        mScrollP = clamp(to, mMinScrollP, mMaxScrollP);
                        requestLayout();
                    }
                }
            });
            mSnapAnimator = anim;
            anim.start();
        }

        // --- Geometry and curves ----------------------------------------------------------

        private void updateGeometry() {
            final int margin = Math.round(MARGIN_DP * mDensity);
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
            mInitialTopOffset = Math.round(
                    (landscape ? INITIAL_TOP_OFFSET_LAND_DP : INITIAL_TOP_OFFSET_PORT_DP)
                            * mDensity);
            mCardLeft = getPaddingLeft() + margin;
            mCardWidth = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight() - 2 * margin);

            // EnableSecondViewExpanded's card height; the navigation bar comes off it in portrait.
            final int reserve = mInitialTopOffset / 8 * 7 + CLOSE_ALL_INSET_PX
                    + (landscape ? 0 : navBarInset);
            final int natural = mStackHeight - reserve;
            final int maxCardHeight = Math.max(1, Math.round(mCardWidth * REFERENCE_CARD_ASPECT));
            if (natural > maxCardHeight) {
                final int surplus = natural - maxCardHeight;
                mStackTop += surplus;
                mStackHeight = Math.max(1, mStackHeight - surplus);
            }
            mCardHeight = Math.max(1, mStackHeight - reserve);

            mMinZ = Z_MIN_DP * mDensity;
            mMaxZ = Z_MAX_DP * mDensity;

            if (mCurve == null || mCurveStackHeight != mStackHeight) {
                buildCurves();
                mCurveStackHeight = mStackHeight;
            }
        }

        private void buildCurves() {
            final Path curve = new Path();
            curve.moveTo(0f, 1f);
            curve.cubicTo(0f, 1f, FIRST_X / 2f, (1f + FIRST_Y) / 2f + 0.001f, FIRST_X, FIRST_Y);
            curve.lineTo(1f, 0f);
            mCurve = new CascadePathInterpolator(curve);
            mPxPerUnit = mStackHeight / mCurve.getArcLength();

            final Path scale = new Path();
            scale.moveTo(0f, 1f);
            scale.lineTo(0.4f, 0.6f);
            scale.lineTo(1f, 0f);
            mScaleCurve = new CascadePathInterpolator(scale);

            final float focusX = mCurve.getX((mStackHeight - mInitialTopOffset)
                    / (float) mStackHeight);
            final float cp2X = focusX + (1f - focusX) / 2f;
            final Path dim = new Path();
            dim.moveTo(0f, MAX_DIM);
            dim.cubicTo(focusX * 0.5f, MAX_DIM, focusX * 0.75f, MAX_DIM * 0.75f, focusX, 0f);
            dim.cubicTo(cp2X, 0f, cp2X, MED_DIM, 1f, MED_DIM);
            mDimCurve = new CascadePathInterpolator(dim);
        }

        /** Snap bounds: {@code [min, int(max(min, n - 1)) + min]}, opening one card back. */
        private void updateScrollBounds(int itemCount) {
            mMinScrollP = MIN_SCROLL_P;
            mMaxScrollP = ((int) Math.max(mMinScrollP, itemCount - 1)) + mMinScrollP;
            mInitialScrollP = Math.max(mMinScrollP,
                    Math.min(mMaxScrollP, (itemCount - 2) + mMinScrollP));
        }

        private static float normalizedX(float relP) {
            if (relP < 0f) {
                return 0.5f + 0.5f * relP / -RANGE_MIN;
            }
            return 0.5f + 0.5f * relP / RANGE_MAX;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }
}
