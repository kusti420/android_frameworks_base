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

package com.android.settingslib.ichthys.clock

import android.content.ContentResolver
import android.provider.Settings

/**
 * IchthysOS lock screen notifications (the "Quiet dock", kusti420/ichthysos-tracker#58): the
 * settings keys and the geometry SystemUI's lock screen and the editor's preview share, so the
 * preview is the lock screen.
 *
 * Three ways notifications sit on the lock screen ([KEY_MODE]):
 * - [MODE_DOCK]: no cards at rest. Above the shortcuts a quiet dock: bare app icons (at most
 *   [DOCK_MAX_ICONS], then "+N"), a hairline under them and one faint line with the newest
 *   notification. A tap or a swipe up on it opens the normal stack as a sheet over the clock.
 * - [MODE_CARDS]: the stack of cards under the clock, its top at [KEY_ANCHOR].
 * - [MODE_GLANCE]: the dock with counts only; the content comes after the unlock.
 *
 * Everything at the bottom of the screen keeps to ONE keep-out ([bottomZone]): the fingerprint
 * sensor's bounds (on UDFPS; the lock icon's otherwise) plus [KEEP_OUT_MARGIN_DP]. The dock hangs
 * [DOCK_GAP_DP] above it; the clock region (clock, cards, media) ends [UPPER_GAP_DP] above the
 * dock, or at the keep-out when the dock takes no height. Nothing is ever anchored to the screen's
 * bottom edge, so nothing can meet the sensor whatever its position.
 */
object LockNotifications {
    /** Settings.Secure: [MODE_DOCK], [MODE_CARDS] or [MODE_GLANCE]. */
    const val KEY_MODE = "ichthys_lockscreen_notif_mode"
    const val MODE_DOCK = 0
    const val MODE_CARDS = 1
    const val MODE_GLANCE = 2

    /**
     * Settings.Secure: where the stack of cards starts, in percent of the screen's height from the
     * top; [ANCHOR_AUTO] puts it [CARDS_GAP_DP] under the clock and its date. Never above the clock,
     * never into the keep-out (the lock screen clamps it).
     */
    const val KEY_ANCHOR = "ichthys_lockscreen_notif_anchor"
    const val ANCHOR_AUTO = 0
    const val ANCHOR_MIN = 20
    const val ANCHOR_MAX = 85

    /** Settings.Secure: the dock's icons dim to [ALPHA_IDLE] after [IDLE_MS] without a touch. */
    const val KEY_DOCK_DIM = "ichthys_lockscreen_dock_dim"

    /** Around the sensor (or lock icon): nothing else is laid out in here. */
    const val KEEP_OUT_MARGIN_DP = 12f
    /** The dock's bottom edge above the keep-out. */
    const val DOCK_GAP_DP = 10f
    /** The upper region (clock, cards, media) ends this far above the dock (or the keep-out). */
    const val UPPER_GAP_DP = 12f
    /** [ANCHOR_AUTO]: the cards start this far under the clock as you see it (and its date). */
    const val CARDS_GAP_DP = 28f
    /** The media player keeps this much above whatever is under it (the dock or the keep-out). */
    const val MEDIA_GAP_DP = 8f

    const val DOCK_ICON_DP = 20f
    const val DOCK_ICON_GAP_DP = 10f
    const val DOCK_MAX_ICONS = 5
    const val DOCK_HAIRLINE_GAP_DP = 8f
    const val DOCK_LINE_GAP_DP = 6f
    const val DOCK_LINE_SP = 11f
    /** The dock's whole height (icons, hairline, the line) when it shows a line. */
    const val DOCK_HEIGHT_DP = 50f
    /** The dock keeps this far from the screen's sides. */
    const val DOCK_SIDE_DP = 32f

    const val ALPHA_AWAKE = 0.92f
    const val ALPHA_IDLE = 0.60f
    const val ALPHA_AOD = 0.40f
    /** The newest line after its arrival fade. */
    const val LINE_DIM = 0.55f
    const val IDLE_MS = 10_000L
    const val ARRIVAL_MS = 3_000L

    fun readMode(cr: ContentResolver, user: Int): Int =
        Settings.Secure.getIntForUser(cr, KEY_MODE, MODE_DOCK, user).coerceIn(MODE_DOCK, MODE_GLANCE)

    fun readAnchor(cr: ContentResolver, user: Int): Int =
        clampAnchor(Settings.Secure.getIntForUser(cr, KEY_ANCHOR, ANCHOR_AUTO, user))

    fun clampAnchor(v: Int): Int = if (v <= 0) ANCHOR_AUTO else v.coerceIn(ANCHOR_MIN, ANCHOR_MAX)

    fun readDockDim(cr: ContentResolver, user: Int): Boolean =
        Settings.Secure.getIntForUser(cr, KEY_DOCK_DIM, 1, user) != 0

    /**
     * The one bottom model, px from the screen's top, for a sensor (or lock icon) whose bounds are
     * [sensorTop]..[sensorBottom]: the keep-out around it, and the dock [dockHeightPx] tall just
     * above (its top equals [keepOutTop] - [DOCK_GAP_DP] when the dock takes no height). The upper
     * region ends at [upperBottom]. SystemUI's lock screen root and the editor's preview both lay
     * the bottom of the screen out with this.
     */
    class BottomZone(
        val keepOutTop: Float,
        val keepOutBottom: Float,
        val dockTop: Float,
        val dockBottom: Float,
        val upperBottom: Float,
    )

    fun bottomZone(sensorTop: Float, sensorBottom: Float, density: Float, dockHeightPx: Float): BottomZone {
        val keepOutTop = sensorTop - KEEP_OUT_MARGIN_DP * density
        val dockBottom = keepOutTop - DOCK_GAP_DP * density
        val dockTop = dockBottom - dockHeightPx
        val upperBottom = if (dockHeightPx > 0f) dockTop - UPPER_GAP_DP * density else keepOutTop
        return BottomZone(keepOutTop, sensorBottom + KEEP_OUT_MARGIN_DP * density, dockTop, dockBottom,
            upperBottom)
    }
}
