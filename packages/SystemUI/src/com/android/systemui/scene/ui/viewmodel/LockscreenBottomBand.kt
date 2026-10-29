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

package com.android.systemui.scene.ui.viewmodel

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector

/**
 * IchthysOS: the bottom band of the lock screen (the lowest [LockscreenBottomBandDetector.SHARE]
 * of it: the lock / fingerprint icon and the shortcuts), the only place a swipe up unlocks from.
 */
data object LockscreenBottomBand : SwipeSource {
    override fun resolve(layoutDirection: LayoutDirection): SwipeSource.Resolved = Resolved

    data object Resolved : SwipeSource.Resolved
}

/**
 * IchthysOS: a [SwipeSourceDetector] like [inner], except that a swipe starting in the bottom band
 * (and not on the bottom edge itself, which [inner] keeps) resolves to [LockscreenBottomBand].
 */
class LockscreenBottomBandDetector(private val inner: SwipeSourceDetector) : SwipeSourceDetector {
    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved? {
        val source = inner.source(layoutSize, position, density, orientation)
        if (source != null) return source
        return if (position.y >= layoutSize.height * (1f - SHARE)) LockscreenBottomBand.Resolved
        else null
    }

    companion object {
        /** The band's share of the screen height. */
        const val SHARE = 0.22f
    }
}
