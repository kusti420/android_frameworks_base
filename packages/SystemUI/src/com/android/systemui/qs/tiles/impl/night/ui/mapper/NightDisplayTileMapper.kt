/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.night.ui.mapper

import android.content.res.Resources
import android.text.TextUtils
import androidx.annotation.StringRes
import com.android.systemui.accessibility.qs.QSAccessibilityModule
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.shared.logging.QSTileLogger
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.night.domain.model.NightDisplayTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import java.time.DateTimeException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Maps [NightDisplayTileModel] to [QSTileState]. */
class NightDisplayTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
    private val logger: QSTileLogger,
) : QSTileDataToStateMapper<NightDisplayTileModel> {
    override fun map(config: QSTileConfig, data: NightDisplayTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.quick_settings_night_display_label)
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            sideViewIcon = QSTileState.SideViewIcon.None
            // IchthysOS: Off / On / Scheduled. The tile reads as active whenever Night Light is not
            // off (a schedule is armed even while it is currently inactive); the icon shows whether
            // the screen is tinted right now.
            val iconRes =
                if (data.isActivated) R.drawable.ic_qs_ichthys_nightlight
                else R.drawable.ic_qs_ichthys_nightlight_off
            activationState =
                if (data is NightDisplayTileModel.AutoModeOff && !data.isActivated)
                    QSTileState.ActivationState.INACTIVE
                else QSTileState.ActivationState.ACTIVE

            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)

            secondaryLabel = getSecondaryLabel(data, resources)

            contentDescription =
                if (TextUtils.isEmpty(secondaryLabel)) label
                else TextUtils.concat(label, ", ", secondaryLabel)
        }

    private fun getSecondaryLabel(
        data: NightDisplayTileModel,
        resources: Resources,
    ): CharSequence? {
        when (data) {
            is NightDisplayTileModel.AutoModeTwilight -> {
                if (!data.isLocationEnabled) {
                    // No location, no sunset/sunrise times: just the mode.
                    return resources.getString(R.string.ichthys_night_light_mode_scheduled)
                }
                return resources.getString(
                    if (data.isActivated) R.string.ichthys_night_light_scheduled_until_sunrise
                    else R.string.ichthys_night_light_scheduled_from_sunset
                )
            }
            is NightDisplayTileModel.AutoModeOff -> {
                return resources.getString(
                    if (data.isActivated) R.string.ichthys_night_light_mode_on
                    else R.string.ichthys_night_light_mode_off
                )
            }
            is NightDisplayTileModel.AutoModeCustom -> {
                @StringRes val toggleTimeStringRes: Int
                val toggleTime: LocalTime?
                if (data.isActivated) {
                    toggleTime = data.endTime
                    toggleTimeStringRes = R.string.ichthys_night_light_scheduled_until
                } else {
                    toggleTime = data.startTime
                    toggleTimeStringRes = R.string.ichthys_night_light_scheduled_from
                }
                val scheduled = resources.getString(R.string.ichthys_night_light_mode_scheduled)
                if (toggleTime == null) return scheduled

                try {
                    val formatter = if (data.is24HourFormat) formatter24Hour else formatter12Hour
                    val formatArg = formatter.format(toggleTime)
                    return resources.getString(toggleTimeStringRes, formatArg)
                } catch (exception: DateTimeException) {
                    logger.logWarning(spec, exception.message.toString())
                    return scheduled
                }
            }
        }
    }

    private companion object {
        val formatter12Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a")
        val formatter24Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val spec = TileSpec.create(QSAccessibilityModule.NIGHT_DISPLAY_TILE_SPEC)
    }
}
