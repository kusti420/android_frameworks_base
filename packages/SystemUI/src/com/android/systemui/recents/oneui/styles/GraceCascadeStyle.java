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

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.graphics.ColorUtils;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * The Android 7.1.1 recents stack — the cascade of task cards that Samsung's Grace UX shipped
 * essentially unchanged, and that AOSP dropped when Quickstep landed.
 *
 * <p>Almost nothing lives here: the arrangement is all in {@link CascadeLayoutManager}, which is an
 * adaptation of 7.1.1's {@code TaskStackLayoutAlgorithm} (Apache-2.0). That is the point — in 7.1.1
 * a card's position, depth and dim are a pure function of one scroll value and the card's index, so
 * there is no per-bind transform to apply and nothing for the style to remember. This class only
 * picks the card layout, hands back the layout manager, and turns off the one item animation that
 * looks wrong on overlapping cards.
 *
 * <p>Nothing here is derived from Samsung's framework; it is AOSP's own algorithm, restored.
 */
public class GraceCascadeStyle implements RecentsStyle {

    // --- 7.1.1 values (refs/aosp-7.1.1-recents), see the individual uses.
    /** RecentsView scrim: black at 0.33. */
    private static final int SCRIM = 0x54000000;
    /** Header text / dismiss colours for dark and light task colours (colors.xml). */
    private static final int HEADER_TEXT_LIGHT = 0xFFEEEEEE;
    private static final int HEADER_TEXT_DARK = 0xCC000000;
    private static final int DISMISS_LIGHT = 0xCCFFFFFF;
    private static final int DISMISS_DARK = 0x99000000;
    /** TaskViewHeader: the 1dp highlight is the header colour 7.5% lighter in HSL. */
    private static final float HIGHLIGHT_LIGHTNESS = 0.075f;
    /** TaskStackView: the stack action button shows while stackScroll < 0.3. */
    private static final float CLEAR_ALL_SHOW_SCROLL = 0.3f;
    /** RecentsView: show 134ms (alpha + translateY from -0.25h), hide 100ms. */
    private static final long CLEAR_ALL_SHOW_MS = 134;
    private static final long CLEAR_ALL_HIDE_MS = 100;
    /** TaskStackAnimationHelper: enter-from-home 300ms, exit-to-home 200ms, 33ms per card. */
    private static final long ENTER_TRANSLATION_MS = 300;
    private static final long ENTER_ALPHA_MS = 100;
    private static final long ENTER_ALPHA_STAGGER_MS = 16;
    private static final long EXIT_MS = 200;
    private static final long STAGGER_MS = 33;
    private static final int STAGGER_CAP = 5;
    /** recents_from_launcher_enter: 150ms; the scrim goes with RecentsView's 200ms ALPHA_OUT. */
    private static final long SCRIM_IN_MS = 150;
    /** SwipeHelper as TaskStackViewTouchHandler configured it. */
    private static final float SWIPE_SIZE_FACTOR = 1.5f;
    private static final float SWIPE_DISMISS_FRACTION = 0.4f;
    private static final float SWIPE_ESCAPE_DP_PER_S = 800f;
    private static final long SWIPE_SNAP_BACK_MS = 150;

    private CascadeLayoutManager mLayoutManager;
    private TextView mClearAll;
    private boolean mClearAllShown = true;

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mLayoutManager = new CascadeLayoutManager(context);
        mLayoutManager.setStackChangedListener(this::updateClearAll);
        return mLayoutManager;
    }

    // --- Backdrop: 7.1.1 had no blur; the wallpaper under a flat 0.33 black scrim.

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

    // --- "Clear all": recents_stack_action_button.xml, in the band at the top of the stack.

    @Override
    public void styleClearAll(TextView clearAll) {
        mClearAll = clearAll;
        final float density = clearAll.getResources().getDisplayMetrics().density;
        clearAll.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                roundRect(Color.WHITE, 2f * density)));
        clearAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        clearAll.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clearAll.setAllCaps(true);
        clearAll.setLetterSpacing(0f);
        clearAll.setTextColor(Color.WHITE);
        clearAll.setShadowLayer(5f, 0f, 2f, 0x99000000);
        clearAll.setPadding(Math.round(14 * density), Math.round(12 * density),
                Math.round(14 * density), Math.round(12 * density));
        final ViewGroup.LayoutParams lp = clearAll.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            final FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            flp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            flp.gravity = Gravity.TOP | Gravity.END;
            flp.setMarginEnd(Math.round(16 * density));
            clearAll.setLayoutParams(flp);
        }
    }

    /**
     * Keeps the button vertically centred in 7.1.1's action-button rect,
     * {@code (stackTop - topMargin) .. (stackTop + topPeek)}, and shows it only while the stack is
     * scrolled to (nearly) its back, as TaskStackView did.
     */
    private void updateClearAll() {
        final TextView clearAll = mClearAll;
        final CascadeLayoutManager lm = mLayoutManager;
        if (clearAll == null || lm == null) {
            return;
        }
        final int bandTop = lm.getStackTop() - lm.getMarginPx();
        final int bandHeight = lm.getMarginPx() + lm.getTopPeekPx();
        final int h = clearAll.getHeight() > 0 ? clearAll.getHeight() : clearAll.getMeasuredHeight();
        final int top = bandTop + Math.max(0, (bandHeight - h) / 2);
        final ViewGroup.LayoutParams lp = clearAll.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams
                && ((ViewGroup.MarginLayoutParams) lp).topMargin != top) {
            ((ViewGroup.MarginLayoutParams) lp).topMargin = top;
            clearAll.setLayoutParams(lp);
        }
        final boolean show = lm.getScrollFromBack() < CLEAR_ALL_SHOW_SCROLL;
        if (show == mClearAllShown) {
            return;
        }
        mClearAllShown = show;
        clearAll.setClickable(show);
        clearAll.animate().cancel();
        if (show) {
            clearAll.setTranslationY(-0.25f * h);
            clearAll.animate().alpha(1f).translationY(0f).setDuration(CLEAR_ALL_SHOW_MS)
                    .setInterpolator(RecentsMotion.FAST_OUT_SLOW_IN).start();
        } else {
            clearAll.animate().alpha(0f).setDuration(CLEAR_ALL_HIDE_MS)
                    .setInterpolator(RecentsMotion.FAST_OUT_SLOW_IN).start();
        }
    }

    // --- Motion: TaskStackAnimationHelper's enter-from-home and exit-to-home.

    /**
     * Cards rise from one stack height below their place: 300ms {@code LINEAR_OUT_SLOW_IN}, with
     * each card behind the front one started {@code 33ms} further into its animation (so the back
     * of the stack arrives first), and fade in over 100ms staggered by 16ms. The scrim comes up
     * over 150ms as recents_from_launcher_enter did.
     */
    @Override
    public void animateEnter(RecyclerView recyclerView) {
        final float offset = mLayoutManager != null ? mLayoutManager.getStackHeight()
                : recyclerView.getHeight();
        RecentsMotion.fadeScrim(recyclerView, 0f, 1f, SCRIM_IN_MS, RecentsMotion.LINEAR_OUT_SLOW_IN);
        for (int i = 0, n = recyclerView.getChildCount(); i < n; i++) {
            final View card = recyclerView.getChildAt(i);
            final int idx = Math.min(STAGGER_CAP, RecentsMotion.indexFromFront(recyclerView, card));
            card.setTranslationY(offset);
            card.setAlpha(0f);
            final ObjectAnimator move = ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, offset, 0f);
            move.setDuration(ENTER_TRANSLATION_MS);
            move.setInterpolator(RecentsMotion.LINEAR_OUT_SLOW_IN);
            move.start();
            move.setCurrentPlayTime(idx * STAGGER_MS);
            card.animate().alpha(1f).setDuration(ENTER_ALPHA_MS)
                    .setStartDelay(idx * ENTER_ALPHA_STAGGER_MS).setInterpolator(null).start();
        }
    }

    /**
     * Cards drop one stack height, 200ms {@code (0.4,0,0.6,1)}, the front card first and each card
     * behind it 33ms later; the scrim fades with {@code ALPHA_OUT}.
     */
    @Override
    public boolean animateExit(RecyclerView recyclerView, Runnable onEnd) {
        final float offset = mLayoutManager != null ? mLayoutManager.getStackHeight()
                : recyclerView.getHeight();
        long longest = 0;
        for (int i = 0, n = recyclerView.getChildCount(); i < n; i++) {
            final View card = recyclerView.getChildAt(i);
            final long delay = Math.min(STAGGER_CAP,
                    RecentsMotion.indexFromFront(recyclerView, card)) * STAGGER_MS;
            card.animate().translationY(offset).setDuration(EXIT_MS).setStartDelay(delay)
                    .setInterpolator(RecentsMotion.EXIT_TO_HOME).start();
            longest = Math.max(longest, delay + EXIT_MS);
        }
        if (mClearAll != null) {
            mClearAll.animate().alpha(0f).setDuration(CLEAR_ALL_HIDE_MS).start();
        }
        RecentsMotion.fadeScrim(recyclerView, 1f, 0f, EXIT_MS, RecentsMotion.ALPHA_OUT);
        RecentsMotion.runAfter(recyclerView, longest, onEnd);
        return true;
    }

    // --- Swipe: SwipeHelper as TaskStackViewTouchHandler set it up.

    /**
     * Dismiss past {@code 0.4 x size}, where the swipe "size" is {@code 1.5 x max(stackW, stackH)}
     * — deliberately far, so a 7.1.1 dismissal is mostly a fling. Expressed as a fraction of the
     * card width, which is what ItemTouchHelper measures against.
     */
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

    /** 800dp/s escape velocity instead of SwipeHelper's usual 100. */
    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return SWIPE_ESCAPE_DP_PER_S * mDensity;
    }

    /** Snap back in 150ms (FAST_OUT_SLOW_IN in the original). */
    @Override
    public long getSwipeCancelDuration(long defaultDuration) {
        return SWIPE_SNAP_BACK_MS;
    }

    private float mDensity = 1f;

    private static GradientDrawable roundRect(int color, float radius) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_grace;
    }

    /**
     * Sideways, either way, as in 7.1.1 — and using the host's own {@link ItemTouchHelper} rather
     * than a bespoke one.
     *
     * <p>This used to have to return 0. {@code ItemTouchHelper} picks the swiped card with
     * {@link RecyclerView#findChildViewUnder}, which walks children from the last index down and
     * tests raw bounds, ignoring Z entirely; the old {@code LinearLayoutManager} attached children
     * in ascending adapter-position order, so the walk ran from the <em>back</em> of the cascade
     * forwards, against draw order, and a swipe on the front card's header could dismiss a card
     * five layers behind it. {@link CascadeLayoutManager}
     * attaches children in descending position order instead, so child index order is Z order and
     * the stock hit test lands on exactly the card the user can see.
     */
    @Override
    public int getSwipeDirs() {
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        // The host re-binds the whole tail after a dismissal. With overlapping cards the default
        // change animation cross-fades every one of them, which reads as a flicker through the
        // entire cascade. Only the change animation is disabled: the remove and move animations are
        // wanted, and they are what slides the cards behind a dismissed one into its place.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Position, depth and dim are all scroll-driven and belong to the layout manager. The only
        // thing left to do at bind time is undo a swipe: a card dismissed by a fling is left
        // translated off-screen when it goes back to the pool, and would come back that way.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setAlpha(1f);
        mDensity = card.getResources().getDisplayMetrics().density;

        // TaskViewHeader: task colour with a 1dp highlight 7.5% lighter along the top, and text /
        // dismiss colours picked for contrast against it.
        final View header = card.findViewById(R.id.oneui_recents_card_header);
        if (header != null) {
            final float[] hsl = new float[3];
            ColorUtils.colorToHSL(item.color, hsl);
            hsl[2] = Math.min(1f, hsl[2] + HIGHLIGHT_LIGHTNESS);
            final LayerDrawable bg = new LayerDrawable(new Drawable[] {
                    new ColorDrawable(item.color), new ColorDrawable(ColorUtils.HSLToColor(hsl))});
            bg.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
            bg.setLayerHeight(1, Math.max(1, Math.round(mDensity)));
            header.setBackground(bg);
        }
        final boolean dark = RecentsTaskSource.isDarkColor(item.color);
        final TextView title = card.findViewById(R.id.oneui_recents_card_title);
        if (title != null) {
            title.setTextColor(dark ? HEADER_TEXT_LIGHT : HEADER_TEXT_DARK);
        }
        final View dismiss = card.findViewById(R.id.oneui_recents_card_dismiss);
        if (dismiss instanceof TextView) {
            ((TextView) dismiss).setTextColor(dark ? DISMISS_LIGHT : DISMISS_DARK);
        }
    }
}
