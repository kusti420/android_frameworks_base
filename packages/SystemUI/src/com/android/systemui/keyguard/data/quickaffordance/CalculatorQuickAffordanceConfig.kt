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
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * IchthysOS: lock screen shortcut that opens the default calculator (Vanilla on IchthysOS), found
 * through the standard APP_CALCULATOR category so any calculator the user installs works. It opens
 * over the lock screen without unlocking; only the calculator's own screen is shown.
 */
@SysUISingleton
class CalculatorQuickAffordanceConfig
@Inject
constructor(@ShadeDisplayAware private val context: Context) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.CALCULATOR

    override fun pickerName(): String = context.getString(R.string.ichthys_keyguard_calculator)

    override val pickerIconResourceId = R.drawable.ic_ichthys_calculator

    // Resolved on collection (not at construction) so a calculator installed later is picked up.
    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> = flow {
        emit(
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
    }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState =
        if (isAvailable()) {
            KeyguardQuickAffordanceConfig.PickerScreenState.Default()
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        val intent = calculatorIntent()
        return if (intent != null) {
            KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
                // The window manager lets this one activity show over the keyguard when SystemUI
                // launches it with this marker (ActivityRecord); nothing else is unlocked.
                intent = intent.putExtra(EXTRA_LOCKSCREEN_SHORTCUT, true),
                canShowWhileLocked = true,
            )
        } else {
            KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled(actionLaunched = false)
        }
    }

    /**
     * The calculator's launcher activity, resolved like a launcher does. Calculator apps declare
     * APP_CALCULATOR next to MAIN/LAUNCHER without CATEGORY_DEFAULT, so an implicit
     * MATCH_DEFAULT_ONLY lookup (or startActivity) finds nothing; launch it explicitly instead.
     */
    private fun calculatorIntent(): Intent? {
        val query =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_APP_CALCULATOR)
        val activity =
            context.packageManager.queryIntentActivities(query, 0).firstOrNull()?.activityInfo
                ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isAvailable(): Boolean = calculatorIntent() != null

    private companion object {
        const val EXTRA_LOCKSCREEN_SHORTCUT = "org.ichthys.extra.LOCKSCREEN_SHORTCUT"
    }
}
