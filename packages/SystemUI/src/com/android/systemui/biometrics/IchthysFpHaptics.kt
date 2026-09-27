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

package com.android.systemui.biometrics

import android.app.AppGlobals
import android.os.UserHandle
import android.os.VibrationEffect
import android.provider.Settings
import com.android.systemui.statusbar.VibratorHelper

/**
 * IchthysOS: device-entry (fingerprint) haptics options.
 * - crDroid's `fp_success_vibrate` / `fp_error_vibrate` switches existed in Settings but nothing
 *   read them; they now turn the success/error haptic off.
 * - `ichthys_udfps_haptic_success` / `_error` choose a pattern; 0 keeps the system one.
 *
 * Callers play their stock haptic only when these return false.
 */
object IchthysFpHaptics {

    private fun get(key: String, def: Int): Int {
        val context = AppGlobals.getInitialApplication() ?: return def
        return Settings.System.getIntForUser(context.contentResolver, key, def,
            UserHandle.USER_CURRENT)
    }

    /** Returns true if the success haptic was handled here (suppressed or custom). */
    @JvmStatic
    fun handleSuccess(vibratorHelper: VibratorHelper): Boolean {
        if (get(IchthysUdfpsSettings.HAPTIC_SUCCESS_ENABLED, 1) == 0) return true
        val effect =
            when (get(IchthysUdfpsSettings.HAPTIC_SUCCESS, 0)) {
                1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                2 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                3 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                4 -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                else -> return false
            }
        vibratorHelper.vibrate(effect)
        return true
    }

    /** Returns true if the error haptic was handled here (suppressed or custom). */
    @JvmStatic
    fun handleError(vibratorHelper: VibratorHelper): Boolean {
        if (get(IchthysUdfpsSettings.HAPTIC_ERROR_ENABLED, 1) == 0) return true
        val effect =
            when (get(IchthysUdfpsSettings.HAPTIC_ERROR, 0)) {
                1 -> VibrationEffect.createWaveform(longArrayOf(0, 45, 60, 45), -1)
                2 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                3 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                4 -> VibrationEffect.createOneShot(320, VibrationEffect.DEFAULT_AMPLITUDE)
                else -> return false
            }
        vibratorHelper.vibrate(effect)
        return true
    }
}
