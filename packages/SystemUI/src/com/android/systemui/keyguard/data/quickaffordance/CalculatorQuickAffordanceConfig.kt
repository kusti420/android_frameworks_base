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

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * IchthysOS: lock screen shortcut that opens the default calculator (Vanilla on IchthysOS), found
 * through the standard APP_CALCULATOR category so any calculator the user installs works. The
 * calculator is a normal app, so it opens after unlocking.
 */
@SysUISingleton
class CalculatorQuickAffordanceConfig
@Inject
constructor(@ShadeDisplayAware private val context: Context) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.CALCULATOR

    override fun pickerName(): String = context.getString(R.string.ichthys_keyguard_calculator)

    override val pickerIconResourceId = R.drawable.ic_ichthys_calculator

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        flowOf(
            if (isAvailable()) {
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon =
                        Icon.Resource(
                            resId = R.drawable.ic_ichthys_calculator,
                            contentDescription =
                                ContentDescription.Resource(
                                    res = R.string.ichthys_keyguard_calculator
                                ),
                        )
                )
            } else {
                KeyguardQuickAffordanceConfig.LockScreenState.Hidden
            }
        )

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState =
        if (isAvailable()) {
            KeyguardQuickAffordanceConfig.PickerScreenState.Default()
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult =
        KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
            intent = calculatorIntent(),
            canShowWhileLocked = false,
        )

    private fun calculatorIntent(): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun isAvailable(): Boolean =
        context.packageManager.resolveActivity(
            calculatorIntent(),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
}
