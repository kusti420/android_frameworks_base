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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.compose.dialog.AlertDialogContent
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.dialog.colordisplay.IchthysNightLightController.Mode
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.math.ln
import kotlin.math.pow

/**
 * IchthysOS Night Light dialog (long-press on the "night" tile): colour temperature slider with a
 * warm-to-cool track and live preview, an Off / On / Scheduled control and a link to the schedule
 * settings.
 */
@SysUISingleton
class NightLightDialogDelegate
@Inject
constructor(
    @Main mainCoroutineContext: CoroutineContext,
    sysuiDialogFactory: SystemUIDialogFactory,
    shadeDialogContextInteractor: ShadeDialogContextInteractor,
    dialogTransitionAnimator: DialogTransitionAnimator,
    private val controller: IchthysNightLightController,
    private val activityStarter: ActivityStarter,
) :
    IchthysSliderDialogDelegate(
        mainCoroutineContext,
        sysuiDialogFactory,
        shadeDialogContextInteractor,
        dialogTransitionAnimator,
        jankTag = "night_light",
    ) {

    override fun onDialogStopped() {
        controller.endPreview()
    }

    @Composable
    override fun Content(dialog: SystemUIDialog) {
        val initial = remember { controller.readState() }
        val stateFlow = remember { controller.state() }
        val state by stateFlow.collectAsState(initial = initial)
        val previewing by controller.previewing.collectAsState()

        val minK = controller.minTemperature
        val maxK = controller.maxTemperature
        val gradient = remember(minK, maxK) { temperatureGradient(minK, maxK) }

        val mode = state.mode
        // A preview only switches activation, so an otherwise-off Night Light still reads "Off".
        val shownMode = if (previewing && mode == Mode.ON) Mode.OFF else mode

        AlertDialogContent(
            title = { Text(stringResource(R.string.quick_settings_night_display_label)) },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    IchthysSegmentedControl(
                        options =
                            listOf(
                                Mode.OFF to stringResource(R.string.ichthys_night_light_mode_off),
                                Mode.ON to stringResource(R.string.ichthys_night_light_mode_on),
                                Mode.SCHEDULED to
                                    stringResource(R.string.ichthys_night_light_mode_scheduled),
                            ),
                        selected = shownMode,
                        onSelect = { controller.requestMode(it) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val valueFormat = R.string.ichthys_night_light_temperature_value
                        val res = dialog.context.resources
                        IchthysPillSlider(
                            label = stringResource(R.string.ichthys_night_light_temperature),
                            value = state.temperature.coerceIn(minK, maxK),
                            valueRange = minK..maxK,
                            step = TEMPERATURE_STEP,
                            valueText = { res.getString(valueFormat, it) },
                            gradient = gradient,
                            onDragStarted = { if (!state.activated) controller.requestPreview() },
                            onValueChange = { controller.setTemperature(it) },
                        )
                        if (previewing) {
                            IchthysNote(stringResource(R.string.ichthys_night_light_preview))
                        }
                    }
                    IchthysLinkRow(
                        label = stringResource(R.string.ichthys_night_light_schedule_settings),
                        onClick = {
                            dialog.dismiss()
                            activityStarter.postStartActivityDismissingKeyguard(
                                Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS),
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

    private companion object {
        const val TEMPERATURE_STEP = 50
        const val GRADIENT_STOPS = 6

        /**
         * Track colours for [minK]..[maxK]: the physical blackbody colour of each temperature (an
         * approximation of the Planckian locus), i.e. what the slider actually does to the screen.
         * Derived data, not a theme colour.
         */
        fun temperatureGradient(minK: Int, maxK: Int): List<Color> =
            (0 until GRADIENT_STOPS).map { i ->
                kelvinToColor(minK + (maxK - minK) * i / (GRADIENT_STOPS - 1).toFloat())
            }

        fun kelvinToColor(kelvin: Float): Color {
            val t = kelvin / 100f
            val r =
                if (t <= 66f) 255f else (329.699f * (t - 60f).toDouble().pow(-0.1332).toFloat())
            val g =
                if (t <= 66f) (99.4708f * ln(t) - 161.1196f)
                else (288.1222f * (t - 60f).toDouble().pow(-0.0755).toFloat())
            val b =
                when {
                    t >= 66f -> 255f
                    t <= 19f -> 0f
                    else -> (138.5177f * ln(t - 10f) - 305.0448f)
                }
            return Color(
                red = r.coerceIn(0f, 255f) / 255f,
                green = g.coerceIn(0f, 255f) / 255f,
                blue = b.coerceIn(0f, 255f) / 255f,
            )
        }
    }
}
