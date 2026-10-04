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

package com.android.systemui.statusbar.ichthys.indicator.data

import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What turns the notification dot on and off (statusBar.notificationDot.logic). */
enum class NotificationDotLogic {
    /**
     * The notification light: the dot is the LED of a phone that has one. It is lit by
     * notifications whose channel may blink the light, only while the screen is off, and stays
     * until they are looked at. See NotificationLightRepository.
     */
    LIGHT,
    /** Any unseen, non-silent notification, with the screen on as well as off. */
    UNSEEN,
}

/** Where the notification dot takes its colour from (statusBar.notificationDot.color). */
enum class NotificationDotColor {
    /** The theme accent, the same one the Shoal status bar uses. */
    ACCENT,
    /** The status bar icon tint: white on dark bars, the dark tint on light ones. */
    WHITE,
    /** The colour of the newest unseen notification, the accent if it has none. */
    APP,
}

/** How the camera / microphone / location indicator is drawn (statusBar.privacyIndicator.style). */
enum class PrivacyIndicatorStyle {
    /** A ring around the front camera cutout. */
    CUTOUT_RING,
    /** The stock corner dot. */
    DOT,
}

/**
 * The ichthys.nix options of the notification dot and the privacy indicator, as live flows for the
 * current user. Every key is a Settings.System key bound in IchthysOptions (statusBar
 * .notificationDot.*, statusBar.privacyIndicator.*); an unset key means the default.
 */
@SysUISingleton
class IchthysIndicatorSettingsRepository
@Inject
constructor(systemSettings: SystemSettingsRepository, secureSettings: SecureSettingsRepository) {

    /** statusBar.notificationDot.enabled */
    val notificationDotEnabled: Flow<Boolean> =
        systemSettings.boolSetting(KEY_NOTIF_DOT_ENABLED, defaultValue = true)

    /** statusBar.notificationDot.logic */
    val notificationDotLogic: Flow<NotificationDotLogic> =
        systemSettings
            .intSetting(KEY_NOTIF_DOT_LOGIC, defaultValue = 0)
            .map { if (it == 1) NotificationDotLogic.UNSEEN else NotificationDotLogic.LIGHT }
            .distinctUntilChanged()

    /** statusBar.notificationDot.aod */
    val notificationDotOnAod: Flow<Boolean> =
        systemSettings.boolSetting(KEY_NOTIF_DOT_AOD, defaultValue = true)

    /** statusBar.notificationDot.color */
    val notificationDotColor: Flow<NotificationDotColor> =
        systemSettings
            .intSetting(KEY_NOTIF_DOT_COLOR, defaultValue = 0)
            .map {
                when (it) {
                    1 -> NotificationDotColor.WHITE
                    2 -> NotificationDotColor.APP
                    else -> NotificationDotColor.ACCENT
                }
            }
            .distinctUntilChanged()

    /** statusBar.notificationDot.replaceIcons, only while the dot itself is enabled. */
    val notificationDotReplacesIcons: Flow<Boolean> =
        systemSettings.boolSetting(KEY_NOTIF_DOT_REPLACE_ICONS, defaultValue = false)

    /** statusBar.privacyIndicator.style, as requested (the display may still have no cutout). */
    val privacyIndicatorStyle: Flow<PrivacyIndicatorStyle> =
        systemSettings
            .intSetting(KEY_PRIVACY_STYLE, defaultValue = 0)
            .map { if (it == 1) PrivacyIndicatorStyle.DOT else PrivacyIndicatorStyle.CUTOUT_RING }
            .distinctUntilChanged()

    /** statusBar.privacyIndicator.colorByType */
    val privacyIndicatorColorByType: Flow<Boolean> =
        systemSettings.boolSetting(KEY_PRIVACY_COLOR_BY_TYPE, defaultValue = false)

    /**
     * statusBar.privacyIndicator.ringWidth in dp (1..4), or null while unset: the consumer then
     * uses its dimen default.
     */
    val privacyRingWidthDp: Flow<Int?> =
        systemSettings
            .intSetting(KEY_PRIVACY_RING_WIDTH, defaultValue = 0)
            .map { if (it <= 0) null else it.coerceIn(RING_WIDTH_MIN_DP, RING_WIDTH_MAX_DP) }
            .distinctUntilChanged()

    /** Whether notifications are shown on the lock screen at all (the stock setting). */
    val lockscreenShowsNotifications: Flow<Boolean> =
        secureSettings
            .intSetting(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, defaultValue = 1)
            .map { it != 0 }
            .distinctUntilChanged()

    companion object {
        const val KEY_NOTIF_DOT_ENABLED = "ichthys_notif_dot_enabled"
        const val KEY_NOTIF_DOT_LOGIC = "ichthys_notif_dot_logic"
        const val KEY_NOTIF_DOT_AOD = "ichthys_notif_dot_aod"
        const val KEY_NOTIF_DOT_COLOR = "ichthys_notif_dot_color"
        const val KEY_NOTIF_DOT_REPLACE_ICONS = "ichthys_notif_dot_replace_icons"
        const val KEY_PRIVACY_STYLE = "ichthys_privacy_indicator_style"
        const val KEY_PRIVACY_COLOR_BY_TYPE = "ichthys_privacy_indicator_color_by_type"
        const val KEY_PRIVACY_RING_WIDTH = "ichthys_privacy_indicator_ring_width"

        const val RING_WIDTH_MIN_DP = 1
        const val RING_WIDTH_MAX_DP = 4
    }
}
