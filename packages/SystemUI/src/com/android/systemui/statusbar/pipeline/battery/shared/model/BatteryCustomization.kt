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

package com.android.systemui.statusbar.pipeline.battery.shared.model

/**
 * IchthysOS: user options for the status bar / QS battery icon, beyond the crDroid style and
 * percent-mode keys (kusti420/ichthysos-tracker#37). All values come from [android.provider.Settings.System]
 * raw keys (see the KEY_ constants) written by crDroidSettings > Status bar > Battery.
 */
data class BatteryCustomization(
    /** Percent drawn to the right of the icon ("next to the icon (right)") in the status bar. */
    val percentAfterIcon: Boolean = false,
    /** Same, for the QS header, which has its own `qs_show_battery_percent` key. */
    val qsPercentAfterIcon: Boolean = false,
    /** QS header style override; [QS_STYLE_FOLLOW] = use the status bar style. */
    val qsStyle: Int = QS_STYLE_FOLLOW,
    /** Only show the percentage (inside or beside) while charging. */
    val percentChargingOnly: Boolean = false,
    /** Bold percentage text beside the icon. */
    val percentBold: Boolean = false,
    /** Draw the bolt while charging. */
    val showChargingIndicator: Boolean = true,
    /** Draw the plus while battery saver is on. */
    val showPowerSaveIndicator: Boolean = true,
    /** [FILL_MODE_TINT], [FILL_MODE_CUSTOM] or [FILL_MODE_ACCENT]. */
    val fillMode: Int = FILL_MODE_TINT,
    /**
     * ARGB used when [fillMode] is [FILL_MODE_CUSTOM]; [FILL_COLOR_UNSET] while the user never
     * picked one, in which case the accent is used (same as [FILL_MODE_ACCENT]).
     */
    val fillColor: Int = FILL_COLOR_UNSET,
    /** [STATE_COLORS_OFF], [STATE_COLORS_THEME] or [STATE_COLORS_CUSTOM]. */
    val stateColorMode: Int = STATE_COLORS_THEME,
    val chargingColor: Int = DEFAULT_CHARGING_COLOR,
    val powerSaveColor: Int = DEFAULT_POWER_SAVE_COLOR,
    val lowColor: Int = DEFAULT_LOW_COLOR,
    /** At or below this level the battery counts as low (colored as an error). */
    val lowLevel: Int = DEFAULT_LOW_LEVEL,
    /** Icon size in percent of the stock size. */
    val scalePercent: Int = DEFAULT_SCALE,
    /** Frame / ring thickness step, [MIN_FRAME_THICKNESS]..[MAX_FRAME_THICKNESS]. */
    val frameThickness: Int = DEFAULT_FRAME_THICKNESS,
) {
    companion object {
        const val KEY_QS_STYLE = "qs_battery_style"
        const val KEY_PERCENT_CHARGING_ONLY = "ichthys_battery_percent_charging_only"
        const val KEY_PERCENT_BOLD = "ichthys_battery_percent_bold"
        const val KEY_CHARGING_INDICATOR = "ichthys_battery_charging_indicator"
        const val KEY_POWER_SAVE_INDICATOR = "ichthys_battery_powersave_indicator"
        const val KEY_FILL_MODE = "ichthys_battery_fill_mode"
        const val KEY_FILL_COLOR = "ichthys_battery_fill_color"
        const val KEY_STATE_COLORS = "ichthys_battery_state_colors"
        const val KEY_CHARGING_COLOR = "ichthys_battery_charging_color"
        const val KEY_POWER_SAVE_COLOR = "ichthys_battery_powersave_color"
        const val KEY_LOW_COLOR = "ichthys_battery_low_color"
        const val KEY_LOW_LEVEL = "ichthys_battery_low_level"
        const val KEY_SCALE = "ichthys_battery_scale"
        const val KEY_FRAME_THICKNESS = "ichthys_battery_frame_thickness"

        const val QS_STYLE_FOLLOW = -1

        const val FILL_MODE_TINT = 0
        const val FILL_MODE_CUSTOM = 1
        const val FILL_MODE_ACCENT = 2

        const val STATE_COLORS_OFF = 0
        const val STATE_COLORS_THEME = 1
        const val STATE_COLORS_CUSTOM = 2

        /** `ichthys_battery_fill_color` not written yet: follow the accent, not a fixed hue. */
        const val FILL_COLOR_UNSET = 0
        const val DEFAULT_CHARGING_COLOR = 0xFF18CC47.toInt()
        const val DEFAULT_POWER_SAVE_COLOR = 0xFFFFC917.toInt()
        const val DEFAULT_LOW_COLOR = 0xFFFF0E01.toInt()
        const val DEFAULT_LOW_LEVEL = 20
        const val MIN_LOW_LEVEL = 5
        const val MAX_LOW_LEVEL = 30
        const val DEFAULT_SCALE = 100
        const val MIN_SCALE = 80
        const val MAX_SCALE = 130
        const val DEFAULT_FRAME_THICKNESS = 3
        const val MIN_FRAME_THICKNESS = 1
        const val MAX_FRAME_THICKNESS = 5
    }
}
