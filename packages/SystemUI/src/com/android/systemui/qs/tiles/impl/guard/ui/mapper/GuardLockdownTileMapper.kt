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
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardLockdownTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/** Maps [GuardLockdownTileModel] to [QSTileState]. */
class GuardLockdownTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<GuardLockdownTileModel> {

    override fun map(config: QSTileConfig, data: GuardLockdownTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val iconRes = R.drawable.ic_qs_ichthys_guard_lockdown
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            label = resources.getString(R.string.ichthys_guard_tile_lockdown)
            when (data.isEnabled) {
                true -> {
                    activationState = QSTileState.ActivationState.ACTIVE
                    secondaryLabel = resources.getString(R.string.ichthys_guard_tile_lockdown_on)
                }
                false -> {
                    activationState = QSTileState.ActivationState.INACTIVE
                    secondaryLabel = resources.getString(R.string.ichthys_guard_tile_off)
                }
                null -> {
                    activationState = QSTileState.ActivationState.UNAVAILABLE
                    secondaryLabel = resources.getString(R.string.ichthys_guard_tile_unavailable)
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
