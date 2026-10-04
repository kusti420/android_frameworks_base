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

package com.android.systemui.qs.tiles.impl.guard.ui.mapper

import android.content.res.Resources
import android.text.TextUtils
import android.text.format.DateUtils
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.guard.data.repository.IchthysGuardRepository
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardPauseTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/**
 * Maps [GuardPauseTileModel] to [QSTileState]: "Pause Guard · 5 min" while Guard enforces,
 * "Guard paused · 4:32 left" (counting down) during a pause.
 */
class GuardPauseTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<GuardPauseTileModel> {

    override fun map(config: QSTileConfig, data: GuardPauseTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val iconRes = R.drawable.ic_qs_ichthys_guard_pause
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            when {
                !data.isReachable -> {
                    activationState = QSTileState.ActivationState.UNAVAILABLE
                    label = resources.getString(R.string.ichthys_guard_tile_pause)
                    secondaryLabel = resources.getString(R.string.ichthys_guard_tile_unavailable)
                }
                data.isPaused -> {
                    activationState = QSTileState.ActivationState.ACTIVE
                    label = resources.getString(R.string.ichthys_guard_tile_paused)
                    // Round up: the last second reads "0:01", never "0:00".
                    val seconds = (data.remainingMs + 999) / 1000
                    secondaryLabel =
                        resources.getString(
                            R.string.ichthys_guard_tile_paused_left,
                            DateUtils.formatElapsedTime(seconds),
                        )
                }
                else -> {
                    activationState = QSTileState.ActivationState.INACTIVE
                    label = resources.getString(R.string.ichthys_guard_tile_pause)
                    secondaryLabel =
                        resources.getString(
                            R.string.ichthys_guard_tile_pause_duration,
                            IchthysGuardRepository.PAUSE_MINUTES,
                        )
                }
            }
            contentDescription =
                if (TextUtils.isEmpty(secondaryLabel)) label
                else TextUtils.concat(label, ", ", secondaryLabel)
            sideViewIcon = QSTileState.SideViewIcon.None
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }
}
