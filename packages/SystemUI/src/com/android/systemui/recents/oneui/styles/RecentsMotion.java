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
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Interpolators and small animation helpers shared by the recents styles.
 *
 * <p>The AOSP curves are {@code com.android.systemui.Interpolators} as of 7.1.1. The Samsung ones
 * are the control points of the stock sine-in-out family, read from the decoded resources of a
 * Grace UX (A310F) and a One UI 6 (A528B) SystemUI; only the numbers are reused.
 */
final class RecentsMotion {

    private RecentsMotion() {}

    // AOSP 7.1.1.
    static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final Interpolator FAST_OUT_LINEAR_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
    static final Interpolator ALPHA_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    static final Interpolator ALPHA_OUT = new PathInterpolator(0f, 0f, 0.8f, 1f);
    /** TaskStackAnimationHelper's exit-to-home curve. */
    static final Interpolator EXIT_TO_HOME = new PathInterpolator(0.4f, 0f, 0.6f, 1f);

    // Samsung sine-in-out family.
    static final Interpolator SINE_IN_OUT_33 = new PathInterpolator(0.33f, 0f, 0.67f, 1f);
    static final Interpolator SINE_IN_OUT_80 = new PathInterpolator(0.17f, 0.17f, 0.2f, 1f);
    static final Interpolator SINE_IN_OUT_90 = new PathInterpolator(0.33f, 0f, 0.1f, 1f);
    static final Interpolator SINE_IN_OUT_100 = new PathInterpolator(0.33f, 0f, 0f, 1f);
    /** One UI Home's default settle curve ("b"). */
    static final Interpolator ONEUI_SETTLE = new PathInterpolator(0.22f, 0.25f, 0f, 1f);
    /** One UI Home's alpha curve ("c"). */
    static final Interpolator ONEUI_ALPHA = new PathInterpolator(0.33f, 0f, 0.2f, 1f);

    /**
     * The recents root, i.e. the view that carries the scrim. Styles fade it with their cards so
     * the backdrop comes and goes with them, as the stock window animations did.
     */
    static View root(RecyclerView recyclerView) {
        return recyclerView.getParent() instanceof View ? (View) recyclerView.getParent() : null;
    }

    /** Fades the scrim (the root's background) from {@code from} to {@code to} (0..1). */
    static ValueAnimator fadeScrim(RecyclerView recyclerView, float from, float to, long duration,
            Interpolator interpolator) {
        final View root = root(recyclerView);
        final Drawable bg = root != null ? root.getBackground() : null;
        final ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        if (bg != null) {
            bg.setAlpha(Math.round(from * 255));
            anim.addUpdateListener(a -> bg.setAlpha(Math.round((float) a.getAnimatedValue() * 255)));
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    bg.setAlpha(255);
                }
            });
        }
        anim.start();
        return anim;
    }

    /** Resets a scrim that an exit left at zero, for the next showing of the singleInstance host. */
    static void resetScrim(RecyclerView recyclerView) {
        final View root = root(recyclerView);
        if (root != null && root.getBackground() != null) {
            root.getBackground().setAlpha(255);
        }
    }

    /** Front-to-back index of a child: 0 for the most recent task (adapter position 0). */
    static int indexFromFront(RecyclerView recyclerView, View child) {
        final int position = recyclerView.getChildAdapterPosition(child);
        return position == RecyclerView.NO_POSITION ? 0 : position;
    }

    /** Runs {@code onEnd} once, after {@code delayMs}, on the list's thread. */
    static void runAfter(ViewGroup view, long delayMs, Runnable onEnd) {
        view.postDelayed(onEnd, Math.max(0, delayMs));
    }
}
