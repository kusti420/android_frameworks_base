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

package com.android.systemui.qs.tiles.dialog.colordisplay

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.compose.dialog.AlertDialogContent
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/**
 * IchthysOS Extra Dim dialog (long-press on the "reduce_brightness" tile): on/off, intensity
 * slider, "Keep on after restart" and a link to the Extra Dim settings.
 */
@SysUISingleton
class ExtraDimDialogDelegate
@Inject
constructor(
    @Main mainCoroutineContext: CoroutineContext,
    sysuiDialogFactory: SystemUIDialogFactory,
    shadeDialogContextInteractor: ShadeDialogContextInteractor,
    dialogTransitionAnimator: DialogTransitionAnimator,
    private val controller: IchthysExtraDimController,
    private val activityStarter: ActivityStarter,
) :
    IchthysSliderDialogDelegate(
        mainCoroutineContext,
        sysuiDialogFactory,
        shadeDialogContextInteractor,
        dialogTransitionAnimator,
        jankTag = "extra_dim",
    ) {

    @Composable
    override fun Content(dialog: SystemUIDialog) {
        val initial = remember { controller.readState() }
        val stateFlow = remember { controller.state() }
        val state by stateFlow.collectAsState(initial = initial)
        val min = IchthysExtraDimController.PERCENT_MIN
        val max = IchthysExtraDimController.PERCENT_MAX
        val res = dialog.context.resources

        AlertDialogContent(
            title = {
                Text(stringResource(com.android.internal.R.string.reduce_bright_colors_feature_name))
            },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IchthysSwitchRow(
                        label =
                            stringResource(
                                com.android.internal.R.string.reduce_bright_colors_feature_name
                            ),
                        checked = state.activated,
                        onCheckedChange = { controller.setActivated(it) },
                    )
                    IchthysPillSlider(
                        label = stringResource(R.string.ichthys_extra_dim_intensity),
                        // Same direction, range and number as Settings: 0 % (dimmer) on the start
                        // side, 100 % (brighter) on the end side.
                        value = state.intensityPercent.coerceIn(min, max),
                        valueRange = min..max,
                        valueText = { res.getString(R.string.ichthys_extra_dim_intensity_value, it) },
                        startLabel = stringResource(R.string.ichthys_extra_dim_dimmer),
                        endLabel = stringResource(R.string.ichthys_extra_dim_brighter),
                        // Adjusting the intensity while off turns Extra Dim on, like the
                        // flashlight dialog.
                        onDragStarted = { if (!state.activated) controller.setActivated(true) },
                        onValueChange = { controller.setIntensityPercent(it) },
                    )
                    IchthysSwitchRow(
                        label = stringResource(R.string.ichthys_extra_dim_keep_on),
                        checked = state.persist,
                        onCheckedChange = { controller.setPersist(it) },
                    )
                    IchthysSwitchRow(
                        label = stringResource(R.string.ichthys_extra_dim_volume_shortcut),
                        checked = state.volumeShortcut,
                        onCheckedChange = { controller.setVolumeShortcut(it) },
                    )
                    IchthysLinkRow(
                        label = stringResource(R.string.ichthys_extra_dim_settings),
                        onClick = {
                            dialog.dismiss()
                            activityStarter.postStartActivityDismissingKeyguard(
                                Intent(Settings.ACTION_REDUCE_BRIGHT_COLORS_SETTINGS),
                                0,
                            )
                        },
                    )
                }
            },
            positiveButton = {
                PlatformButton(onClick = { dialog.dismiss() }) {
                    Text(stringResource(R.string.quick_settings_done))
                }
            },
            contentTopPadding = 12.dp,
            contentBottomPadding = 8.dp,
        )
    }
}
