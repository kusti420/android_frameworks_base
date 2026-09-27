/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.ringer.ui.viewmodel

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.R as internalR
import com.android.settingslib.Utils
import com.android.systemui.res.R

/** Models the UI state of ringer button */
data class RingerButtonUiModel(
    /** Icon color. */
    val tintColor: Int,
    val backgroundColor: Int,
    val cornerRadius: Int,
) {
    companion object {
        // Custom Options: One UI ringer buttons are neutral-dark wells in both states; only the
        // active icon is accent-tinted. Explicit colors (no Monet). Matches the drawables/tints the
        // non-animated path in VolumeDialogRingerViewBinder applies, so animated transitions look
        // identical.
        private val ONEUI_BUTTON_BACKGROUND = 0xFF2A2A2C.toInt()
        private val ONEUI_ICON_NEUTRAL = 0xFFCCCCCC.toInt()

        private fun Context.isOneUiVolume(): Boolean =
            Settings.System.getIntForUser(
                contentResolver,
                "volume_panel_style",
                1,
                UserHandle.USER_CURRENT,
            ) == 1

        private fun Context.accentColor(): Int {
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
            val color = ta.getColor(0, 0xFFE91E63.toInt())
            ta.recycle()
            return color
        }

        fun getUnselectedButton(context: Context): RingerButtonUiModel {
            val oneUi = context.isOneUiVolume()
            return RingerButtonUiModel(
                tintColor =
                    if (oneUi) ONEUI_ICON_NEUTRAL
                    else context.getColor(internalR.color.materialColorOnSurface),
                backgroundColor =
                    if (oneUi) ONEUI_BUTTON_BACKGROUND
                    else context.getColor(internalR.color.materialColorSurfaceContainerHighest),
                cornerRadius = context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_background_square_corner_radius),
            )
        }

        fun getSelectedButton(context: Context): RingerButtonUiModel {
            val oneUi = context.isOneUiVolume()
            return RingerButtonUiModel(
                tintColor =
                    if (oneUi) context.accentColor()
                    else context.getColor(internalR.color.materialColorOnPrimary),
                backgroundColor =
                    if (oneUi) ONEUI_BUTTON_BACKGROUND
                    else context.getColor(internalR.color.materialColorPrimary),
                cornerRadius = context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_ringer_selected_button_background_radius),
            )
        }
    }
}
