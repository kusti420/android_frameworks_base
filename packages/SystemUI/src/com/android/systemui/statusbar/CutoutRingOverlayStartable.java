/*
 * Copyright (C) 2026 IchthysOS
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;

import javax.inject.Inject;

/**
 * SPIKE / PLACEHOLDER (IchthysOS): the "separate overlay window" half of the cutout-ring spike.
 *
 * <p>Adds its own {@link WindowManager} window, anchored to the top of the display with
 * {@code LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS}, and hosts a {@link CutoutRingView} in
 * {@link CutoutRingView#MODE_OVERLAY_WINDOW}. Deliberately taller than the status bar window so we
 * can tell, in a single screenshot, whether an overlay window escapes the status bar's height
 * limit.
 *
 * <p>Touch-transparent ({@code FLAG_NOT_TOUCHABLE}), non-focusable, and does not participate in
 * insets, so it cannot steal input from the status bar or apps.
 *
 * <p>Guarded by {@link CutoutRingView#SPIKE_ENABLED}.
 */
@SysUISingleton
public class CutoutRingOverlayStartable implements CoreStartable {

    private static final String TAG = "CutoutRingOverlay";

    /** Height of the overlay window in px. Must comfortably exceed the widest test ring. */
    private static final int WINDOW_HEIGHT_PX = 400;

    private final Context mContext;

    @Inject
    public CutoutRingOverlayStartable(@Application Context context) {
        mContext = context;
    }

    @Override
    public void start() {
        if (!CutoutRingView.SPIKE_ENABLED) {
            return;
        }
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        if (wm == null) {
            Log.w(TAG, "no WindowManager");
            return;
        }

        CutoutRingView view = new CutoutRingView(mContext);
        view.setMode(CutoutRingView.MODE_WEATHER_PLACEHOLDER);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WINDOW_HEIGHT_PX,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        lp.setFitInsetsTypes(0);
        lp.setTitle("CutoutRingOverlay");

        try {
            wm.addView(view, lp);
            Log.i(TAG, "cutout ring overlay window added");
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to add cutout ring overlay window", e);
        }
    }
}
