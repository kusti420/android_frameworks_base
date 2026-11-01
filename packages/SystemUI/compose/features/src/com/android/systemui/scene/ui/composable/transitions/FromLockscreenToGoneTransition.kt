/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.systemui.scene.ui.composable.transitions

import com.android.systemui.keyguard.ui.composable.elements.IchthysDockElementKey
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import com.android.systemui.keyguard.ICHTHYS_DEPTH_UNLOCK_MS
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Easings
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys

/**
 * IchthysOS depth lock screen (tracker#58): with depth on, the unlock is slow enough for the layers
 * to come apart (first half, IchthysDepthUnlock.kt) before everything fades into the home screen
 * (second half), and linear, so the layers' own easing shapes it. Without depth, the stock timing.
 * Decided when the transition starts, so turning depth on or off needs no restart.
 */
private object IchthysUnlockSpec : FiniteAnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, V>
    ): VectorizedFiniteAnimationSpec<V> = current().vectorize(converter)

    private fun current(): FiniteAnimationSpec<Float> {
        val depth =
            try {
                android.provider.Settings.Secure.getIntForUser(
                    android.app.AppGlobals.getInitialApplication().contentResolver,
                    "ichthys_lockscreen_depth",
                    0,
                    android.os.UserHandle.USER_CURRENT,
                ) != 0
            } catch (e: RuntimeException) {
                false
            }
        return if (depth) {
            tween(durationMillis = ICHTHYS_DEPTH_UNLOCK_MS.toInt(), easing = LinearEasing)
        } else {
            tween(durationMillis = 500)
        }
    }
}

/**
 * IchthysOS: the fade of the lock screen elements, over the whole unlock. With depth on it runs in
 * 0.6..0.97, after the layers have come apart; without depth in the stock 0..0.3. Easings are
 * evaluated per frame, so one transition covers both and follows depth without a restart.
 */
private object IchthysUnlockFade : Easing {
    override fun transform(fraction: Float): Float {
        val progress = fraction * UNLOCK_FADE_END
        val local =
            if (IchthysDepthRepository.enabledNow) (progress - 0.6f) / (UNLOCK_FADE_END - 0.6f)
            else progress / 0.3f
        return Easings.PredictiveBack.transform(local.coerceIn(0f, 1f))
    }
}

/** IchthysOS: the stock motion (the clock's lift), standing still with depth on. */
private object IchthysStockOnly : Easing {
    override fun transform(fraction: Float): Float =
        if (IchthysDepthRepository.enabledNow) 0f else Easings.PredictiveBack.transform(fraction)
}

private const val UNLOCK_FADE_END = 0.97f

fun TransitionBuilder.lockscreenToGoneWithAnimationOverLockscreenTransition() {
    spec = IchthysUnlockSpec

    fractionRange(end = UNLOCK_FADE_END, easing = IchthysUnlockFade) {
        // Fade out all lock screen elements, except the status bar.
        fade(LockscreenElementKeys.Region.Upper)

        fade(LockscreenElementKeys.BehindScrim)
    }
    // IchthysOS: the notification dock and the "Customize lock screen" button are chrome, not
    // part of the depth reveal: they go first, before the layers start to move.
    fractionRange(end = 0.08f) {
        fade(IchthysDockElementKey)
        fade(LockscreenElementKeys.SettingsMenu)
        // The fingerprint icon and the media player are not part of the reveal either.
        fade(LockscreenElementKeys.LockIcon)
        fade(LockscreenElementKeys.MediaCarousel)
        // Nor the shortcuts and the indication line. (All of these also carry
        // ichthysUnlockChrome, which fades them from the transition's progress directly: the
        // media player sits in a nested layout this fade does not reach.)
        fade(LockscreenElementKeys.AmbientIndicationArea)
        fade(LockscreenElementKeys.Region.Lower)
    }
}

fun TransitionBuilder.lockscreenToGoneTransition() {
    lockscreenToGoneWithAnimationOverLockscreenTransition()

    fractionRange(end = UNLOCK_FADE_END, easing = IchthysUnlockFade) {
        translate(LockscreenElementKeys.Notifications.Stack, y = (-72).dp)
    }
    fractionRange(end = 0.3f, easing = IchthysStockOnly) {
        translate(LockscreenElementKeys.Region.Upper, y = (-48).dp)
    }
}

fun TransitionBuilder.aodToGoneTransition() {
    spec = tween(durationMillis = 500)

    fractionRange(end = 0.2f) { fade(LockscreenElementKeys.Root) }
}
