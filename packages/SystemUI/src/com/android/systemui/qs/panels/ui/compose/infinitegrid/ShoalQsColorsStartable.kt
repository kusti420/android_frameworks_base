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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

/**
 * IchthysOS "Shoal": keeps [ShoalQsColors] in step with the theme. The tokens change with the
 * night mode and whenever the theme engine rewrites its overlays (accent, surface style, ramp).
 */
@SysUISingleton
class ShoalQsColorsStartable
@Inject
constructor(
    @Application private val context: Context,
    private val configurationController: ConfigurationController,
) : CoreStartable {
    override fun start() {
        ShoalQsColors.update(context)
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onUiModeChanged() = ShoalQsColors.update(context)

                override fun onThemeChanged() = ShoalQsColors.update(context)
            }
        )
    }
}
