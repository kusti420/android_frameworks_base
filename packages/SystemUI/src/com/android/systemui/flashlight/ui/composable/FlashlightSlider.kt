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

package com.android.systemui.flashlight.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.flashlight.ui.viewmodel.FlashlightSliderViewModel
import com.android.systemui.res.R

@Composable
fun FlashlightSliderContainer(viewModel: FlashlightSliderViewModel, modifier: Modifier = Modifier) {
    val currentState = viewModel.currentFlashlightLevel ?: return
    val levelValue =
        if (currentState.enabled) {
            currentState.level
        } else { // flashlight off
            0 // even if the "level" has been reset to "default" on the backend
        }

    Column(modifier = modifier.fillMaxWidth().sysuiResTag("flashlight_slider")) {
        // IchthysOS: on/off row like the Bluetooth / Internet dialogs; the slider below sets the
        // exact level (dragging it while off turns the torch on at that level).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quick_settings_flashlight_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                modifier = Modifier.sysuiResTag("flashlight_switch"),
                checked = currentState.enabled,
                onCheckedChange = viewModel::setFlashlightEnabled,
            )
        }
        VerticalFlashlightSlider(
            levelValue = levelValue,
            valueRange = 0..currentState.max,
            onValueChange = viewModel::setFlashlightLevelTemporary,
            onValueChangeFinished = viewModel::setFlashlightLevel,
            isEnabled = viewModel.isFlashlightAdjustable,
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
    }
}
