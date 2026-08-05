/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.globalactions;

import static com.android.systemui.shared.system.BlurUtils.isVolumeAndPowerBlurEnabled;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ListAdapter;

/**
 * Creates a customized Dialog for displaying the Shut Down and Restart actions.
 */
public class GlobalActionsPowerDialog {

    /**
     * IchthysOS advanced restart: columns in the "Restart…" submenu grid. Matches the
     * flow_maxElementsWrap of the main power menu (global_actions_grid_lite).
     */
    private static final int RESTART_COLUMNS = 2;

    /**
     * Create a dialog for displaying Shut Down and Restart actions.
     */
    public static Dialog create(@NonNull Context context, ListAdapter adapter) {
        ViewGroup listView = (ViewGroup) LayoutInflater.from(context).inflate(
                com.android.systemui.res.R.layout.global_actions_power_dialog, null);

        for (int i = 0; i < adapter.getCount(); i++) {
            View action = adapter.getView(i, null, listView);
            listView.addView(action);
        }

        Resources res = context.getResources();

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(listView);

        Window window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.setTitle(""); // prevent Talkback from speaking first item name twice
        window.setBackgroundDrawable(res.getDrawable(
                com.android.systemui.res.R.drawable.control_background, context.getTheme()));
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        return dialog;
    }

    /**
     * IchthysOS advanced restart: create a dialog for the "Restart…" submenu (System / Recovery
     * / Bootloader / SystemUI).
     *
     * <p>Unlike {@link #create}, the items are laid out as a two-column grid of power-menu-style
     * tiles on the same rounded surface the main power menu uses, so the submenu reads as part of
     * the power menu rather than as a separate list. A single column of
     * {@code global_actions_power_dialog} rows would also be far too tall for four entries.
     */
    public static Dialog createRestart(@NonNull Context context, ListAdapter adapter) {
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup gridView = (ViewGroup) inflater.inflate(
                com.android.systemui.res.R.layout.global_actions_restart_dialog, null);

        final int count = adapter.getCount();
        for (int i = 0; i < count; i += RESTART_COLUMNS) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            gridView.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int j = i; j < Math.min(i + RESTART_COLUMNS, count); j++) {
                View action = adapter.getView(j, null, row);
                row.addView(action);
            }
        }

        Resources res = context.getResources();

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(gridView);

        Window window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.setTitle(""); // prevent Talkback from speaking first item name twice
        // Same surface colour and 28dp corner radius as the main power menu's container, and
        // theme-aware (materialColorSurfaceContainerLow) so light and dark both track it.
        window.setBackgroundDrawable(res.getDrawable(
                com.android.systemui.res.R.drawable.global_actions_lite_background,
                context.getTheme()));
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        // IchthysOS: blur behind the submenu exactly as the main power menu does, so opening
        // "Restart…" does not drop the frosted backdrop. Same gate and same radius as
        // GlobalActionsDialogLite.handleShow() - config_translucentStandalonePowerMenu is a
        // resource bool, so it is readable here without plumbing the dialog's field through.
        if (isVolumeAndPowerBlurEnabled()
                && res.getBoolean(
                        com.android.systemui.res.R.bool.config_translucentStandalonePowerMenu)) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attrs.setBlurBehindRadius(res.getDimensionPixelSize(
                    com.android.systemui.res.R.dimen.global_actions_blur_radius));
            window.setAttributes(attrs);
        }

        return dialog;
    }
}
