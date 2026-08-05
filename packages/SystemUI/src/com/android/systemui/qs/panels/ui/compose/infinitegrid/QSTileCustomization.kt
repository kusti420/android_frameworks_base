/*
 * Copyright (C) 2026 The IchthysOS Project
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

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * IchthysOS: the crDroid Quick Settings *appearance* keys and their SystemUI consumers.
 *
 * Every key here is a `Settings.System` one — the preferences in
 * `crDroidSettings/res/xml/crdroid_settings_quicksettings.xml` are `SystemSetting*Preference`
 * subclasses, so they are backed by `android.provider.Settings.System`, NOT `LineageSettings`.
 *
 * Reads go through the `*ForUser` overloads with [UserHandle.USER_CURRENT]. The shade composes in a
 * per-display window context whose `getUserId()` is not the foreground user, so the plain overloads
 * can silently resolve to the wrong user and return the default with no log at all.
 *
 * All readers here are *reactive*: they observe their key and recompose on a write, so changing a
 * preference updates the shade immediately instead of on the next SystemUI restart (unlike the
 * older `qs_tile_label_*` reads in `CommonTile.kt`, which are plain lookups).
 */

// region Reactive Settings.System readers

@Composable
private fun rememberSystemSettingInt(key: String, default: Int): Int {
    val resolver = LocalContext.current.contentResolver
    val current by
        produceState(initialValue = readInt(resolver, key, default), resolver, key, default) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readInt(resolver, key, default)
                    }
                }
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                /* notifyForDescendants= */ false,
                observer,
            )
            value = readInt(resolver, key, default)
            awaitDispose { resolver.unregisterContentObserver(observer) }
        }
    return current
}

@Composable
private fun rememberSystemSettingString(key: String, default: String): String {
    val resolver = LocalContext.current.contentResolver
    val current by
        produceState(initialValue = readString(resolver, key, default), resolver, key, default) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readString(resolver, key, default)
                    }
                }
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                /* notifyForDescendants= */ false,
                observer,
            )
            value = readString(resolver, key, default)
            awaitDispose { resolver.unregisterContentObserver(observer) }
        }
    return current
}

private fun readInt(resolver: ContentResolver, key: String, default: Int): Int =
    Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)

private fun readString(resolver: ContentResolver, key: String, default: String): String =
    Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: default

// endregion

// region qs_panel_style

/**
 * crDroid `qs_panel_style` — "Card style" (default) vs "Classic style".
 *
 * This tree renders an Android 8.1-style panel: the tile container is transparent and the active
 * highlight is a circular bubble behind the icon ([QsTileIconBubble]). That bubbled look is what
 * [CARD] means here. [CLASSIC] drops the bubble and paints the tile container itself, which is the
 * classic full-pill Quick Settings tile.
 *
 * [CARD] is the SystemUI default so an untouched device keeps the ROM's signature look — it also
 * matches `android:defaultValue="0"` on the preference, so Settings and the shade agree.
 */
object QSPanelStyle {
    const val CARD = 0
    const val CLASSIC = 1
}

@Composable
fun rememberQsPanelStyle(): Int =
    rememberSystemSettingInt(Settings.System.QS_PANEL_STYLE, QSPanelStyle.CARD)

// endregion

// region qs_tile_shape

/** crDroid `qs_tile_shape` — the shape of the tile *container*. */
object QSTileShapeStyle {
    /** Follow the stock animated active/inactive corner radius. */
    const val DYNAMIC = 0
    const val ROUNDED_SQUARE_SMOOTH = 1
    const val ROUNDED_SQUARE = 2
    const val SQUARE = 3
    const val CIRCLE = 4
}

/**
 * The container shape the user picked, or `null` for [QSTileShapeStyle.DYNAMIC] (keep the stock
 * state-animated radius).
 *
 * Corner radii are Compose `dp` literals on purpose: adding a new `<dimen>` to SystemUI shifts the
 * dimen resource-ID table and breaks `WindowManager-Shell`'s already-linked R constants, which
 * crash-loops SystemUI at app-bind with an "is not valid" resource error.
 */
@Composable
fun rememberQsTileShapeOverride(): RoundedCornerShape? {
    val style = rememberSystemSettingInt(Settings.System.QS_TILE_SHAPE, QSTileShapeStyle.DYNAMIC)
    return remember(style) {
        when (style) {
            QSTileShapeStyle.ROUNDED_SQUARE_SMOOTH -> RoundedCornerShape(28.dp)
            QSTileShapeStyle.ROUNDED_SQUARE -> RoundedCornerShape(16.dp)
            QSTileShapeStyle.SQUARE -> RoundedCornerShape(0.dp)
            QSTileShapeStyle.CIRCLE -> RoundedCornerShape(percent = 50)
            else -> null
        }
    }
}

// endregion

// region qs_tile_icon_shape

/**
 * crDroid `qs_tile_icon_shape` — the shape of the icon bubble. Unlike the other keys in this file
 * this one persists a *String* key (see `QSTileIconShapePreference`), resolved against the ported
 * [QSTileIconShapes] path table.
 */
@Composable
fun rememberQsTileIconShape(): Shape {
    val key =
        rememberSystemSettingString(
            Settings.System.QS_TILE_ICON_SHAPE,
            QSTileIconShapes.DEFAULT_KEY,
        )
    return remember(key) { QSTileIconShapes.shapeForKey(key) }
}

// endregion

// region qs_tile_haptic / qs_tile_gradient

/**
 * crDroid `qs_tile_haptic` — haptic feedback when a tile is tapped. The preference declares
 * `android:defaultValue="true"`, so the SystemUI default must be 1: defaulting to 0 would show the
 * switch as on while nothing buzzed until the user toggled it twice.
 */
@Composable
fun rememberQsTileHapticEnabled(): Boolean =
    rememberSystemSettingInt(Settings.System.QS_TILE_HAPTIC, 1) != 0

/**
 * crDroid `qs_tile_gradient` — paint the tile's active surface with the shared gradient palette
 * instead of a flat color. The palette itself (auto vs custom, and the two custom colors) lives in
 * `com.android.systemui.gradient` and is shared with the other gradient toggles on that screen.
 */
@Composable
fun rememberQsTileGradientEnabled(): Boolean =
    rememberSystemSettingInt(Settings.System.QS_TILE_GRADIENT, 0) != 0

// endregion

// region qs_tile_animation_style

/**
 * crDroid `qs_tile_animation_style` — a one-shot animation played on the tile content when the tile
 * is tapped. [NONE] keeps the stock bounce and adds nothing.
 */
object QSTileAnimationStyle {
    const val NONE = 0
    const val BOUNCE = 1
    const val RIPPLE = 2
    const val FLIP = 3
    const val FADE = 4
    const val PULSE = 5
    const val SHAKE = 6
    const val WOBBLE = 7
    const val SPIN = 8
    const val SQUISH = 9
    const val TILT = 10
    const val HEARTBEAT = 11
    const val SWING = 12
}

/** Duration of one play-through, in ms. Short enough to never feel like input lag. */
private const val ANIMATION_DURATION_MS = 450

/**
 * Drives the [QSTileAnimationStyle] effect. [play] restarts the animation from 0; [progress] is the
 * 0..1 position the [qsTileClickAnimation] modifier reads.
 */
@Stable
class QsTileClickAnimation(
    val style: Int,
    private val animatable: Animatable<Float, AnimationVector1D>,
) {

    val progress: Float
        get() = animatable.value

    val isEnabled: Boolean
        get() = style != QSTileAnimationStyle.NONE

    fun play(scope: CoroutineScope) {
        if (!isEnabled) return
        scope.launch {
            animatable.snapTo(0f)
            animatable.animateTo(1f, tween(durationMillis = ANIMATION_DURATION_MS))
        }
    }
}

@Composable
fun rememberQsTileClickAnimation(): QsTileClickAnimation {
    val style =
        rememberSystemSettingInt(
            Settings.System.QS_TILE_ANIMATION_STYLE,
            QSTileAnimationStyle.NONE,
        )
    val animatable = remember { Animatable(0f) }
    return remember(style, animatable) { QsTileClickAnimation(style, animatable) }
}

/**
 * Applies the running [animation] to this element.
 *
 * Everything is expressed through [graphicsLayer] so the effect is a draw-time transform: it never
 * relayouts the grid and therefore cannot fight the QQS <-> QS shared-element morph.
 */
fun Modifier.qsTileClickAnimation(animation: QsTileClickAnimation): Modifier {
    if (!animation.isEnabled) return this
    return graphicsLayer {
        val p = animation.progress
        if (p <= 0f || p >= 1f) return@graphicsLayer
        // Half-sine: 0 at both ends, 1 at the middle. Guarantees the tile always returns to rest.
        val swell = sin(p * PI).toFloat()
        // Damped oscillation for the "shaky" styles: strong at the start, settled at the end.
        val decay = 1f - p
        // Push the camera back a little from Compose's DefaultCameraDistance (8f) so the FLIP/TILT
        // rotations read as a gentle 3D turn instead of a fisheye. Compose's own unit, not pixels —
        // do not scale it by density.
        cameraDistance = 12f

        when (animation.style) {
            QSTileAnimationStyle.BOUNCE -> {
                scaleX = 1f + 0.15f * swell
                scaleY = 1f + 0.15f * swell
            }
            QSTileAnimationStyle.RIPPLE -> {
                // No overlay layer to draw into here, so "ripple" reads as a quick outward swell
                // that fades as it grows, which is the same visual language.
                scaleX = 1f + 0.22f * p
                scaleY = 1f + 0.22f * p
                alpha = 1f - 0.55f * p
            }
            QSTileAnimationStyle.FLIP -> rotationY = 360f * p
            QSTileAnimationStyle.FADE -> alpha = 1f - 0.75f * swell
            QSTileAnimationStyle.PULSE -> {
                val beat = sin(p * 2f * PI).toFloat()
                scaleX = 1f + 0.1f * beat
                scaleY = 1f + 0.1f * beat
            }
            QSTileAnimationStyle.SHAKE ->
                translationX = 6f * density * sin(p * 4f * PI).toFloat() * decay
            QSTileAnimationStyle.WOBBLE -> rotationZ = 10f * sin(p * 4f * PI).toFloat() * decay
            QSTileAnimationStyle.SPIN -> rotationZ = 360f * p
            QSTileAnimationStyle.SQUISH -> {
                scaleX = 1f + 0.14f * swell
                scaleY = 1f - 0.14f * swell
            }
            QSTileAnimationStyle.TILT -> rotationX = 24f * swell
            QSTileAnimationStyle.HEARTBEAT -> {
                // Two beats, the second smaller, like a real double-thump.
                val beat =
                    if (p < 0.5f) sin(p * 2f * PI).toFloat()
                    else 0.6f * sin((p - 0.5f) * 2f * PI).toFloat()
                scaleX = 1f + 0.14f * beat
                scaleY = 1f + 0.14f * beat
            }
            QSTileAnimationStyle.SWING -> {
                // Pivot at the top edge so it reads as hanging and swinging, not spinning.
                transformOrigin = TransformOrigin(0.5f, 0f)
                rotationZ = 14f * sin(p * 3f * PI).toFloat() * decay
            }
        }
    }
}

// endregion
