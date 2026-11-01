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

package com.android.systemui.statusbar.notification.ichthys.quiet

import android.app.PendingIntent
import android.graphics.drawable.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How ongoing system notifications leave the stack (notifications.quiet.foldOngoing). */
enum class QuietFoldOngoing {
    /** A strip of small chips under the quick tiles, above the first notification. */
    CHIPS,
    /** One dashed "System · ..." row at the end of the shade; tap unfolds. */
    ONE_ROW,
    /** Stock: they stay in the stack as cards. */
    OFF,
}

/** How the Silent section shows its notifications (notifications.quiet.silentStyle). */
enum class QuietSilentStyle {
    /** One line each (icon · app · title) until expanded: the minimized template. */
    ONE_LINE,
    /** The whole section folds into one dashed row at the end of the shade; tap unfolds. */
    FOLDED,
    /** Stock cards. */
    CARDS,
}

/** What a folded notification is, for the fold row's summary and the chip label. */
enum class QuietKind {
    USB_DEBUGGING,
    USB_CHARGING,
    USB_OTHER,
    BACKSTAGE,
    SYSTEM,
    APP,
    SILENT,
}

/**
 * A notification folded out of the stack by IchthysQuietNotificationsCoordinator: what the chip
 * strip and the fold rows need, nothing of the row.
 */
data class QuietNotificationModel(
    val key: String,
    val packageName: String,
    val groupKey: String,
    /** The hidden-list entry a long-press adds: "pkg:channel[:tag][:id]". */
    val hideId: String,
    /** Short chip text ("USB debugging", "Charging", the app's name). */
    val label: String,
    /** The notification's title, for accessibility. */
    val title: String,
    val kind: QuietKind,
    /** The notification's small icon (a tintable mask for every system one). */
    val icon: Icon?,
    /** Status bar icon, for when hideOngoingIcons is off. */
    val statusBarIcon: Icon?,
    val shelfIcon: Icon?,
    val aodIcon: Icon?,
    val contentIntent: PendingIntent?,
)

/**
 * IchthysOS: the quiet-notification options (ichthys.nix notifications.quiet.*, Settings.Secure
 * keys bound in IchthysOptions; unset means the default) and the live state the coordinator
 * publishes for the shade: which notifications are folded out of the stack right now and whether
 * the user has unfolded a fold row.
 */
@SysUISingleton
class IchthysQuietNotificationsRepository
@Inject
constructor(secureSettings: SecureSettingsRepository) {
    /** notifications.quiet.foldOngoing */
    val foldOngoing: Flow<QuietFoldOngoing> =
        secureSettings
            .intSetting(KEY_FOLD_ONGOING, defaultValue = FOLD_CHIPS)
            .map {
                when (it) {
                    FOLD_ONE_ROW -> QuietFoldOngoing.ONE_ROW
                    FOLD_OFF -> QuietFoldOngoing.OFF
                    else -> QuietFoldOngoing.CHIPS
                }
            }
            .distinctUntilChanged()

    /** notifications.quiet.hideOngoingIcons */
    val hideOngoingIcons: Flow<Boolean> =
        secureSettings.boolSetting(KEY_HIDE_ICONS, defaultValue = true)

    /** notifications.quiet.peekOnChange */
    val peekOnChange: Flow<Boolean> = secureSettings.boolSetting(KEY_PEEK, defaultValue = true)

    /** notifications.quiet.silentStyle */
    val silentStyle: Flow<QuietSilentStyle> =
        secureSettings
            .intSetting(KEY_SILENT_STYLE, defaultValue = SILENT_ONE_LINE)
            .map {
                when (it) {
                    SILENT_FOLDED -> QuietSilentStyle.FOLDED
                    SILENT_CARDS -> QuietSilentStyle.CARDS
                    else -> QuietSilentStyle.ONE_LINE
                }
            }
            .distinctUntilChanged()

    /** notifications.quiet.alwaysQuiet: apps whose ongoing notifications are always folded. */
    val alwaysQuietPackages: Flow<Set<String>> =
        secureSettings.stringSetting(KEY_ALWAYS_QUIET).map { it.toEntrySet() }.distinctUntilChanged()

    /** Hidden-list entries ([QuietNotificationModel.hideId]) from long-pressed chips. */
    val hiddenEntries: Flow<Set<String>> =
        secureSettings.stringSetting(KEY_HIDDEN).map { it.toEntrySet() }.distinctUntilChanged()

    private val _foldedOngoing = MutableStateFlow<List<QuietNotificationModel>>(emptyList())
    /** Ongoing notifications folded out of the stack, in pipeline order. */
    val foldedOngoing: StateFlow<List<QuietNotificationModel>> = _foldedOngoing.asStateFlow()

    private val _foldedSilent = MutableStateFlow<List<QuietNotificationModel>>(emptyList())
    /** Silent-section notifications folded out of the stack (silentStyle = folded). */
    val foldedSilent: StateFlow<List<QuietNotificationModel>> = _foldedSilent.asStateFlow()

    /** The "System" fold row was tapped: its notifications are back in the stack. */
    val ongoingUnfolded = MutableStateFlow(false)
    /** The "Silent" fold row was tapped: its notifications are back in the stack. */
    val silentUnfolded = MutableStateFlow(false)

    /** Coordinator only: publish what this pipeline run folded. */
    fun setFolded(ongoing: List<QuietNotificationModel>, silent: List<QuietNotificationModel>) {
        if (_foldedOngoing.value != ongoing) _foldedOngoing.value = ongoing
        if (_foldedSilent.value != silent) _foldedSilent.value = silent
    }

    companion object {
        const val KEY_FOLD_ONGOING = "ichthys_quiet_fold_ongoing"
        const val KEY_HIDE_ICONS = "ichthys_quiet_hide_icons"
        const val KEY_PEEK = "ichthys_quiet_peek"
        const val KEY_SILENT_STYLE = "ichthys_quiet_silent_style"
        /** Ends in _pkgs: SettingsProvider hides it from apps like the other per-app lists. */
        const val KEY_ALWAYS_QUIET = "ichthys_quiet_notif_pkgs"
        const val KEY_HIDDEN = "ichthys_quiet_hidden"

        // Stored enum values, as in IchthysOptions notifications.quiet.*.
        const val FOLD_CHIPS = 0
        const val FOLD_ONE_ROW = 1
        const val FOLD_OFF = 2
        const val SILENT_ONE_LINE = 0
        const val SILENT_FOLDED = 1
        const val SILENT_CARDS = 2

        /** How long a changed chip shows as a normal card before it settles (peekOnChange). */
        const val PEEK_MILLIS = 3000L

        /** Comma-separated list setting to a set; blank entries dropped. */
        fun String?.toEntrySet(): Set<String> =
            this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }
}
