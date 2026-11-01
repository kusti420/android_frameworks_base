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

import android.content.Context
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.ichthys.quiet.IchthysQuietNotificationsRepository.Companion.toEntrySet
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IchthysOS: what the shade's quiet-notification UI (the chip strip above the stack, the fold rows
 * under it) reads and does. The pipeline side is IchthysQuietNotificationsCoordinator.
 */
@SysUISingleton
class IchthysQuietNotificationsInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val repository: IchthysQuietNotificationsRepository,
    private val activityStarter: ActivityStarter,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
) {
    /** notifications.quiet.foldOngoing, as a state for the composables. */
    val foldOngoing: StateFlow<QuietFoldOngoing> =
        repository.foldOngoing.stateIn(scope, SharingStarted.Eagerly, QuietFoldOngoing.CHIPS)

    /** notifications.quiet.silentStyle */
    val silentStyle: StateFlow<QuietSilentStyle> =
        repository.silentStyle.stateIn(scope, SharingStarted.Eagerly, QuietSilentStyle.ONE_LINE)

    /** Ongoing notifications folded out of the stack: the chips, or the "System" row's content. */
    val foldedOngoing: StateFlow<List<QuietNotificationModel>> = repository.foldedOngoing

    /** Silent notifications folded out of the stack (silentStyle = folded). */
    val foldedSilent: StateFlow<List<QuietNotificationModel>> = repository.foldedSilent

    val ongoingUnfolded: StateFlow<Boolean> = repository.ongoingUnfolded
    val silentUnfolded: StateFlow<Boolean> = repository.silentUnfolded

    /** notifications.quiet.hideOngoingIcons */
    val hideOngoingIcons: Flow<Boolean> = repository.hideOngoingIcons

    /** Chip tap: the notification's contentIntent, as tapping its row would. */
    fun onChipClicked(model: QuietNotificationModel) {
        val intent = model.contentIntent ?: return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    /** Chip long-press: "hide this one". The entry leaves the strip and the stack for good. */
    fun hide(model: QuietNotificationModel) {
        scope.launch(bgDispatcher) {
            val user = userTracker.userId
            val current = secureSettings.getStringForUser(
                IchthysQuietNotificationsRepository.KEY_HIDDEN, user).toEntrySet()
            if (model.hideId in current) return@launch
            val next = (current + model.hideId).joinToString(",")
            secureSettings.putStringForUser(IchthysQuietNotificationsRepository.KEY_HIDDEN, next, user)
            Log.i(TAG, "hidden ${model.hideId}")
        }
    }

    /** The "System" fold row: tap unfolds (its notifications return to the stack) or folds. */
    fun toggleOngoingUnfolded() {
        repository.ongoingUnfolded.value = !repository.ongoingUnfolded.value
    }

    /** The "Silent" fold row. */
    fun toggleSilentUnfolded() {
        repository.silentUnfolded.value = !repository.silentUnfolded.value
    }

    /**
     * The fold row's summary: "USB debugging, Charging, 2 in Backstage, Spotify". Backstage
     * entries are counted, the rest named once each, in pipeline order.
     */
    fun summary(models: List<QuietNotificationModel>): String {
        if (models.isEmpty()) return ""
        val parts = ArrayList<String>()
        val backstage = models.count { it.kind == QuietKind.BACKSTAGE }
        var backstageDone = false
        for (m in models) {
            if (m.kind == QuietKind.BACKSTAGE) {
                if (!backstageDone) {
                    parts.add(
                        if (backstage == 1) m.label
                        else context.getString(R.string.ichthys_quiet_in_backstage, backstage)
                    )
                    backstageDone = true
                }
                continue
            }
            if (m.label !in parts) parts.add(m.label)
        }
        return parts.joinToString(", ")
    }

    companion object {
        private const val TAG = "IchthysQuietNotifs"
    }
}
