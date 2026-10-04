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

package com.android.systemui.statusbar.ichthys.indicator.domain

import android.app.Notification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.ichthys.indicator.data.IchthysIndicatorSettingsRepository
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.ActiveNotificationIconModel
import com.android.systemui.statusbar.notification.icon.domain.interactor.AlwaysOnDisplayNotificationIconsInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.StatusBarNotificationIconsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The notification the dot stands for: the newest unseen, non-silent one. */
data class NotificationDotModel(
    val key: String,
    val packageName: String,
    /** The notification's own colour ([Notification.color]), null when the app set none. */
    val appColor: Int?,
)

/**
 * Decides whether there is anything for the notification dot to announce.
 *
 * Nothing is tracked here. "Unseen" is [SeenNotificationsInteractor.unseenNotificationKeys] (the
 * pipeline's own seen tracking: cleared when the shade opens, when a notification is shown as
 * heads-up, dismissed, or has been on the awake lock screen for a few seconds). Which
 * notifications may be announced at all comes from the notification icon interactors, so the dot
 * follows the status bar / always-on display icons: Do Not Disturb visual effects, ambient and
 * dismissed notifications, bubbles and the lock screen rules are already applied there.
 *
 * Collect on the main thread: the app colour is read from the notification collection.
 */
@SysUISingleton
class NotificationDotInteractor
@Inject
constructor(
    statusBarIconsInteractor: StatusBarNotificationIconsInteractor,
    aodIconsInteractor: AlwaysOnDisplayNotificationIconsInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    settings: IchthysIndicatorSettingsRepository,
    private val activeNotificationsRepository: ActiveNotificationListRepository,
    private val notifCollection: CommonNotifCollection,
) {
    /**
     * Unseen keys as the dot understands them: a notification that sat on the awake lock screen
     * long enough counts as seen straight away, provided the lock screen shows notifications (the
     * pipeline itself only applies that on unlock).
     */
    private val unseenKeys: Flow<Set<String>> =
        combine(
                seenNotificationsInteractor.unseenNotificationKeys,
                seenNotificationsInteractor.seenOnLockscreenNotificationKeys,
                settings.lockscreenShowsNotifications,
            ) { unseen, seenOnLockscreen, lockscreenShowsNotifications ->
                if (lockscreenShowsNotifications) unseen - seenOnLockscreen else unseen
            }
            .distinctUntilChanged()

    /** The notification to announce while the device is awake, or null for no dot. */
    val statusBarDot: Flow<NotificationDotModel?> = dotFor(statusBarIconsInteractor.statusBarNotifs)

    /** The notification to announce on the always-on display / while dozing, or null. */
    val aodDot: Flow<NotificationDotModel?> = dotFor(aodIconsInteractor.aodNotifs)

    private fun dotFor(icons: Flow<Set<ActiveNotificationIconModel>>): Flow<NotificationDotModel?> =
        combine(icons, activeNotificationsRepository.activeNotifications, unseenKeys) {
                iconModels,
                store,
                unseen ->
                if (unseen.isEmpty()) {
                    null
                } else {
                    iconModels
                        .asSequence()
                        .flatMap { store.notificationsBehind(it) }
                        .filter { it.key in unseen && !it.isSilent && !it.isAmbient }
                        .maxByOrNull { it.whenTime }
                        ?.let { NotificationDotModel(it.key, it.packageName, appColorOf(it.key)) }
                }
            }
            .distinctUntilChanged()

    /** An icon stands for one notification, or for a group: its summary and all its children. */
    private fun ActiveNotificationsStore.notificationsBehind(
        icon: ActiveNotificationIconModel
    ): Sequence<ActiveNotificationModel> {
        val group = groups[icon.groupKey]
        return if (group != null && group.summary.key == icon.notifKey) {
            sequenceOf(group.summary) + group.children.asSequence()
        } else {
            // A bundle icon has no notification of its own: bundles are silent by definition.
            sequenceOf(individuals[icon.notifKey]).filterNotNull()
        }
    }

    private fun appColorOf(key: String): Int? =
        notifCollection.getEntry(key)?.sbn?.notification?.color?.takeIf {
            it != Notification.COLOR_DEFAULT
        }
}
