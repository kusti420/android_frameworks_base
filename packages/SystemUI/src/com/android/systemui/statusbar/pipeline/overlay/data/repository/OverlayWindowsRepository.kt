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

package com.android.systemui.statusbar.pipeline.overlay.data.repository

import android.app.Notification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * IchthysOS: which non-system apps are showing SYSTEM_ALERT_WINDOW overlays right now (W8b
 * decision 33, overlay-active indicator).
 *
 * The source is what WindowManager reports. The app op can't be used: WMS only notes
 * OP_SYSTEM_ALERT_WINDOW when a window is added and never starts it, so it is never "active".
 * WMS instead tracks every visible alert/overlay window per session and, for apps without
 * INTERNAL_SYSTEM_WINDOW / SYSTEM_APPLICATION_OVERLAY (i.e. non-system apps), posts an ongoing
 * notification as "android" for as long as the app has one (AlertWindowNotification, tag
 * "com.android.server.wm.AlertWindowNotification - <package>", EXTRA_FOREGROUND_APPS = package).
 * SystemUI's notification collection sees it even though its channel is IMPORTANCE_MIN.
 *
 * Caveat: the user can turn off that per-app channel in the app's notification settings; WMS
 * still tracks the window, but nothing is posted then, and so no indicator shows for that app.
 */
@SysUISingleton
class OverlayWindowsRepository
@Inject
constructor(
    // Lazy: the status bar icon controller (which owns the indicator) is built early.
    private val notifCollection: Lazy<CommonNotifCollection>,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) {
    /** Packages currently showing an overlay, oldest first (the newest is last). */
    val overlayPackages: StateFlow<List<String>> =
        conflatedCallbackFlow {
                // Notification key -> package. Insertion order = order the overlays appeared.
                val byKey = LinkedHashMap<String, String>()
                fun publish() = trySend(byKey.values.distinct())

                val listener =
                    object : NotifCollectionListener {
                        override fun onEntryAdded(entry: NotificationEntry) {
                            overlayPackage(entry)?.let {
                                byKey[entry.key] = it
                                publish()
                            }
                        }

                        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                            if (byKey.remove(entry.key) != null) publish()
                        }
                    }
                // The collection is main-thread only.
                val collection = notifCollection.get()
                collection.addCollectionListener(listener)
                for (entry in collection.allNotifs) {
                    overlayPackage(entry)?.let { byKey[entry.key] = it }
                }
                publish()
                awaitClose { collection.removeCollectionListener(listener) }
            }
            .flowOn(mainDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    private fun overlayPackage(entry: NotificationEntry): String? {
        val sbn = entry.sbn
        if (sbn.packageName != ANDROID_PACKAGE) return null
        val tag = sbn.tag ?: return null
        if (!tag.startsWith(ALERT_WINDOW_TAG_PREFIX)) return null
        return sbn.notification.extras
            .getStringArray(Notification.EXTRA_FOREGROUND_APPS)
            ?.firstOrNull()
            ?: tag.removePrefix(ALERT_WINDOW_TAG_PREFIX).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val ANDROID_PACKAGE = "android"

        /** AlertWindowNotification.CHANNEL_PREFIX in services/core (com.android.server.wm). */
        const val ALERT_WINDOW_TAG_PREFIX = "com.android.server.wm.AlertWindowNotification - "
    }
}
