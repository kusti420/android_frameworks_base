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

package com.android.systemui.volume.dialog.settings.ui.binder

import android.content.res.ColorStateList
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.settings.ui.viewmodel.VolumeDialogSettingsButtonViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogSettingsButtonViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSettingsButtonViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
) : ViewBinder {

    override fun CoroutineScope.bind(view: View) {
        val button = view.requireViewById<ImageButton>(R.id.volume_dialog_settings)

        // Custom Options: the horizontal (8.1-style) slider has no settings button.
        // IchthysOS: its level is drawn inside the slider row now (HorizontalVolumeSlider,
        // volume_horizontal_level), so the old step-number TextView beside the row stays hidden.
        if (
            Settings.Secure.getIntForUser(
                view.context.contentResolver,
                "custom_volume_horizontal",
                0,
                UserHandle.USER_CURRENT,
            ) == 1
        ) {
            button.visibility = View.GONE
            view.requireViewById<TextView>(R.id.volume_dialog_step_number).visibility = View.GONE
            return
        }

        // Custom Options: "volume_panel_style" == 1 ("One UI") makes the settings/expand affordance a
        // separate neutral-dark round pill that floats below the slider, with a neutral icon tint
        // (accent is reserved for active controls, and the layout's Monet materialColorPrimary tint
        // is overridden so nothing renders purple).
        val oneUiStyle =
            Settings.System.getIntForUser(
                view.context.contentResolver,
                "volume_panel_style",
                1,
                UserHandle.USER_CURRENT,
            ) == 1
        if (oneUiStyle) {
            button.setBackgroundResource(R.drawable.oneui_volume_settings_bg)
            button.imageTintList = ColorStateList.valueOf(ONEUI_VOLUME_SETTINGS_ICON)
        }

        launchTraced("VDSBVB#addTouchableBounds") { dialogViewModel.addTouchableBounds(button) }
        viewModel.isVisible
            .onEach { isVisible -> button.visibility = if (isVisible) View.VISIBLE else View.GONE }
            .launchInTraced("VDSBVB#isVisible", this)

        viewModel.icon.onEach { button.setImageDrawable(it) }.launchInTraced("VDSBVB#icon", this)

        button.setOnClickListener { viewModel.onButtonClicked() }
    }
}

// Custom Options: neutral light-grey tint for the One UI settings/expand icon (no Monet accent).
private val ONEUI_VOLUME_SETTINGS_ICON = 0xFFCCCCCC.toInt()
