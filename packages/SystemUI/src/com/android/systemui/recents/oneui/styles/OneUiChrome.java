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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

/**
 * What the One UI styles (grid, stack, vertical list, slim list) share: One UI Home's "Clear all"
 * pill and its enter animations, from the measured values of One UI 6 (SM-A528B) in
 * {@code SPEC_RECENTS.md} Part 2 (§7, §9). Re-implemented from those numbers; no Samsung code or
 * asset is used.
 */
final class OneUiChrome {

    private OneUiChrome() {}

    // clear_all_layout.xml / clear_all_bg.xml.
    private static final float PILL_MIN_WIDTH_DP = 190f;
    private static final float PILL_MAX_WIDTH_DP = 250f;
    private static final float PILL_HEIGHT_DP = 44f;
    private static final float PILL_PADDING_H_DP = 22f;
    private static final float PILL_RADIUS_DP = 22f;
    private static final int PILL_TEXT = 0xFFFAFAFA;
    private static final int PILL_BG_LIGHT = 0x33FCFCFC;
    private static final int PILL_BG_DARK = 0x59000000;
    private static final int PILL_STROKE_DARK = 0x1FFFFFFF;
    /** The ripple is white; drawn at the platform's usual highlight strength. */
    private static final int PILL_RIPPLE = 0x3DFFFFFF;

    /** Default One UI Home animation length where a style has no specific one. */
    static final long ENTER_MS = 300;

    /** One UI Home's Clear all: a 44dp pill, 190-250dp wide, 16sp regular text. */
    static void styleClearAll(TextView pill) {
        final float density = pill.getResources().getDisplayMetrics().density;
        final boolean dark = (pill.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(PILL_RADIUS_DP * density);
        shape.setColor(dark ? PILL_BG_DARK : PILL_BG_LIGHT);
        if (dark) {
            shape.setStroke(Math.max(1, Math.round(density)), PILL_STROKE_DARK);
        }
        pill.setBackground(new RippleDrawable(ColorStateList.valueOf(PILL_RIPPLE), shape, null));
        pill.setTextColor(PILL_TEXT);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        pill.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        pill.setAllCaps(false);
        pill.setLetterSpacing(0f);
        final int padH = Math.round(PILL_PADDING_H_DP * density);
        pill.setPadding(padH, 0, padH, 0);
        pill.setMinWidth(Math.round(PILL_MIN_WIDTH_DP * density));
        pill.setMaxWidth(Math.round(PILL_MAX_WIDTH_DP * density));
        pill.setMinHeight(Math.round(PILL_HEIGHT_DP * density));
        final ViewGroup.LayoutParams lp = pill.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = Math.round(PILL_HEIGHT_DP * density);
        pill.setLayoutParams(lp);
    }

    /**
     * Grid: the whole list fades in with curve "c" while sliding in from one screen width to the
     * left with curve "b", 300ms.
     */
    static void enterGrid(RecyclerView rv) {
        RecentsMotion.fadeScrim(rv, 0f, 1f, ENTER_MS, RecentsMotion.ONEUI_ALPHA);
        rv.setAlpha(0f);
        rv.setTranslationX(-rv.getWidth());
        rv.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(RecentsMotion.ONEUI_ALPHA)
                .start();
        final ObjectAnimator slide = ObjectAnimator.ofFloat(rv, View.TRANSLATION_X,
                -rv.getWidth(), 0f);
        slide.setDuration(ENTER_MS);
        slide.setInterpolator(RecentsMotion.ONEUI_SETTLE);
        slide.start();
    }

    /**
     * Stack: every card slides in from -80% of the screen width, the front card in 220ms and the
     * rest in 320ms, with curve "d" (0.33,0,0.67,1).
     */
    static void enterStack(RecyclerView rv) {
        RecentsMotion.fadeScrim(rv, 0f, 1f, ENTER_MS, RecentsMotion.ONEUI_ALPHA);
        final float from = -0.8f * rv.getWidth();
        for (int i = 0, n = rv.getChildCount(); i < n; i++) {
            final View card = rv.getChildAt(i);
            final boolean front = RecentsMotion.indexFromFront(rv, card) == 0;
            final ObjectAnimator slide = ObjectAnimator.ofFloat(card, View.TRANSLATION_X,
                    from, card.getTranslationX());
            slide.setDuration(front ? 220 : 320);
            slide.setInterpolator(RecentsMotion.SINE_IN_OUT_33);
            slide.start();
        }
    }

    /**
     * Vertical and slim lists: the list drops in from one screen height above, fading in, with
     * curve "e" (0.17,0.17,0.2,1), 300ms.
     */
    static void enterVertical(RecyclerView rv) {
        RecentsMotion.fadeScrim(rv, 0f, 1f, ENTER_MS, RecentsMotion.ONEUI_ALPHA);
        rv.setAlpha(0f);
        rv.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(RecentsMotion.SINE_IN_OUT_80)
                .start();
        final ObjectAnimator drop = ObjectAnimator.ofFloat(rv, View.TRANSLATION_Y,
                -rv.getHeight(), 0f);
        drop.setDuration(ENTER_MS);
        drop.setInterpolator(RecentsMotion.SINE_IN_OUT_80);
        drop.start();
    }

    /** One UI dismisses on a fling of 2000 px/s or more. */
    static final float SWIPE_ESCAPE_PX_PER_S = 2000f;
}
