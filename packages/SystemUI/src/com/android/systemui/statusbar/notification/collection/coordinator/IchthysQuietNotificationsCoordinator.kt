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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.inflation.NotifUiAdjustmentProvider
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.ichthys.quiet.IchthysQuietNotificationsRepository
import com.android.systemui.statusbar.notification.ichthys.quiet.IchthysQuietNotificationsRepository.Companion.PEEK_MILLIS
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietFoldOngoing
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietKind
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietNotificationModel
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietSilentStyle
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * IchthysOS: persistent and silent notifications must stop filling the shade.
 *
 * Ongoing system notifications (USB debugging, charging over USB, hotspot, VPN, the screen
 * recorder, Backstage's "<app> is running in Backstage") and the ongoing notifications of apps the
 * user marked "always quiet" in Guard leave the stack (a finalize filter) and are published to
 * [IchthysQuietNotificationsRepository] for the shade to draw as a strip of chips above the first
 * notification (notifications.quiet.foldOngoing = chips) or as one dashed "System" row at the end
 * of the shade (oneRow). With silentStyle = folded the Silent section leaves the stack the same
 * way; with oneLine it stays, minimized to one line each ([SectionStyleProvider]).
 *
 * Peek on change: an entry whose content just changed (USB plugged, charging state) stays in the
 * stack as a normal card for [PEEK_MILLIS] and then settles into its chip. A heads-up entry is
 * never filtered while it is pinned. Rich ongoing notifications (colorized, calls, promoted,
 * media) are never touched: they have their own section and status bar chips.
 */
@CoordinatorScope
class IchthysQuietNotificationsCoordinator
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Main private val executor: DelayableExecutor,
    private val systemClock: SystemClock,
    private val dumpManager: DumpManager,
    private val headsUpManager: HeadsUpManager,
    private val shadeInteractor: ShadeInteractor,
    private val sectionStyleProvider: SectionStyleProvider,
    private val adjustmentProvider: NotifUiAdjustmentProvider,
    private val repository: IchthysQuietNotificationsRepository,
) : Coordinator, Dumpable {

    private var foldOngoing = QuietFoldOngoing.CHIPS
    private var silentStyle = QuietSilentStyle.ONE_LINE
    private var peekOnChange = true
    private var alwaysQuiet: Set<String> = emptySet()
    private var hidden: Set<String> = emptySet()
    private var ongoingUnfolded = false
    private var silentUnfolded = false

    /** key -> uptime until which the entry peeks as a card. */
    private val peekUntil = HashMap<String, Long>()
    /** key -> title + text last seen, to notice a content change. */
    private val lastContent = HashMap<String, String>()
    private var pendingPeekInvalidate: Runnable? = null

    /** Filled during a filter pass, published before render. */
    private val foldedOngoing = ArrayList<QuietNotificationModel>()
    private val foldedSilent = ArrayList<QuietNotificationModel>()
    private val appLabels = HashMap<String, String>()

    private val filter =
        object : NotifFilter("IchthysQuietNotifications") {
            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
                return shouldFold(entry, now)
            }
        }

    private val invalidator = object : Invalidator("IchthysQuietNotifications") {}

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addFinalizeFilter(filter)
        pipeline.addPreRenderInvalidator(invalidator)
        pipeline.addOnBeforeFinalizeFilterListener {
            foldedOngoing.clear()
            foldedSilent.clear()
        }
        pipeline.addOnBeforeRenderListListener { _: List<PipelineEntry> ->
            repository.setFolded(ArrayList(foldedOngoing), ArrayList(foldedSilent))
        }
        pipeline.addCollectionListener(collectionListener)
        dumpManager.registerDumpable(TAG, this)

        scope.launch {
            repository.foldOngoing.collectLatest {
                foldOngoing = it
                filter.invalidateList("foldOngoing = $it")
            }
        }
        scope.launch {
            repository.silentStyle.collectLatest {
                silentStyle = it
                // One line = the stock minimized template for the Silent section; the rows are
                // reinflated when the adjustment provider reports them dirty.
                sectionStyleProvider.setIchthysSilentMinimized(it == QuietSilentStyle.ONE_LINE)
                adjustmentProvider.ichthysMarkDirty()
                filter.invalidateList("silentStyle = $it")
            }
        }
        scope.launch { repository.peekOnChange.collectLatest { peekOnChange = it } }
        scope.launch {
            repository.alwaysQuietPackages.collectLatest {
                alwaysQuiet = it
                filter.invalidateList("alwaysQuiet changed")
            }
        }
        scope.launch {
            repository.hiddenEntries.collectLatest {
                hidden = it
                filter.invalidateList("hidden changed")
            }
        }
        scope.launch {
            repository.ongoingUnfolded.collectLatest {
                ongoingUnfolded = it
                filter.invalidateList("ongoing unfolded = $it")
            }
        }
        scope.launch {
            repository.silentUnfolded.collectLatest {
                silentUnfolded = it
                filter.invalidateList("silent unfolded = $it")
            }
        }
        scope.launch {
            // An unfolded row folds again when the shade closes.
            shadeInteractor.isAnyExpanded.collectLatest { expanded ->
                if (!expanded) {
                    repository.ongoingUnfolded.value = false
                    repository.silentUnfolded.value = false
                }
            }
        }
    }

    private fun shouldFold(entry: NotificationEntry, now: Long): Boolean {
        if (headsUpManager.isHeadsUpEntry(entry.key)) return false
        if (isQuietOngoing(entry)) {
            if (hideId(entry) in hidden) return true
            if (foldOngoing == QuietFoldOngoing.OFF) return false
            val until = peekUntil[entry.key]
            if (until != null) {
                if (until > now) {
                    schedulePeekEnd(until - now)
                    return false
                }
                peekUntil.remove(entry.key)
            }
            if (ongoingUnfolded && foldOngoing == QuietFoldOngoing.ONE_ROW) return false
            foldedOngoing.add(toModel(entry, kindOf(entry)))
            return true
        }
        if (silentStyle == QuietSilentStyle.FOLDED && !silentUnfolded && isInSilentSection(entry)) {
            foldedSilent.add(toModel(entry, QuietKind.SILENT))
            return true
        }
        return false
    }

    /**
     * Ongoing (or foreground service) and quiet: from the system, or from an app the user marked
     * always quiet. Never rich ongoing (colorized / call / promoted) or media.
     */
    private fun isQuietOngoing(entry: NotificationEntry): Boolean {
        val sbn = entry.sbn
        val n = sbn.notification
        if (!(sbn.isOngoing || n.isForegroundService)) return false
        if (ColorizedFgsCoordinator.isRichOngoing(entry)) return false
        if (n.isMediaNotification) return false
        if (sbn.packageName in alwaysQuiet) return true
        return sbn.uid < Process.FIRST_APPLICATION_UID || sbn.packageName in SYSTEM_PACKAGES
    }

    private fun isInSilentSection(entry: NotificationEntry): Boolean {
        val section = entry.section ?: (entry.parent as? GroupEntry)?.section ?: return false
        return sectionStyleProvider.isSilentSection(section)
    }

    private fun kindOf(entry: NotificationEntry): QuietKind {
        val sbn = entry.sbn
        if (sbn.packageName == "android" && sbn.tag == null) {
            when (sbn.id) {
                SystemMessage.NOTE_ADB_ACTIVE -> return QuietKind.USB_DEBUGGING
                SystemMessage.NOTE_USB_CHARGING, SystemMessage.NOTE_USB_SUPPLYING ->
                    return QuietKind.USB_CHARGING
                SystemMessage.NOTE_USB_MTP, SystemMessage.NOTE_USB_PTP, SystemMessage.NOTE_USB_MIDI,
                SystemMessage.NOTE_USB_TETHER, SystemMessage.NOTE_USB_ACCESSORY ->
                    return QuietKind.USB_OTHER
            }
        }
        if (entry.channel?.id == BACKSTAGE_CHANNEL) return QuietKind.BACKSTAGE
        if (sbn.uid < Process.FIRST_APPLICATION_UID || sbn.packageName in SYSTEM_PACKAGES) {
            return QuietKind.SYSTEM
        }
        return QuietKind.APP
    }

    /** "pkg:channel[:tag]" (+ ":id" for Backstage, whose notifications share one tag). */
    private fun hideId(entry: NotificationEntry): String {
        val sbn = entry.sbn
        val sb = StringBuilder(sbn.packageName).append(':').append(entry.channel?.id ?: "")
        sbn.tag?.let { sb.append(':').append(it) }
        if (entry.channel?.id == BACKSTAGE_CHANNEL) sb.append(':').append(sbn.id)
        return sb.toString()
    }

    private fun toModel(entry: NotificationEntry, kind: QuietKind): QuietNotificationModel {
        val sbn = entry.sbn
        val n = sbn.notification
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val label =
            when (kind) {
                QuietKind.USB_DEBUGGING -> context.getString(R.string.ichthys_quiet_usb_debugging)
                QuietKind.USB_CHARGING -> context.getString(R.string.ichthys_quiet_charging)
                // IchthysBackstageService: "<app> is running in Backstage".
                QuietKind.BACKSTAGE ->
                    title.substringBefore(" is running in Backstage").ifEmpty {
                        context.getString(R.string.ichthys_quiet_backstage)
                    }
                QuietKind.APP,
                QuietKind.SILENT -> appLabel(sbn.packageName, sbn.userId)
                else -> title.ifEmpty { appLabel(sbn.packageName, sbn.userId) }
            }
        val icons = entry.icons
        return QuietNotificationModel(
            key = entry.key,
            packageName = sbn.packageName,
            groupKey = sbn.groupKey,
            hideId = hideId(entry),
            label = label,
            title = title.ifEmpty { label },
            kind = kind,
            icon = n.smallIcon,
            statusBarIcon = icons.statusBarIcon?.sourceIcon,
            shelfIcon = icons.shelfIcon?.sourceIcon,
            aodIcon = icons.aodIcon?.sourceIcon,
            contentIntent = n.contentIntent,
        )
    }

    private fun appLabel(packageName: String, userId: Int): String =
        appLabels.getOrPut("$userId/$packageName") {
            try {
                val pm = context.createContextAsUser(UserHandle.of(userId), 0).packageManager
                pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            } catch (e: Exception) {
                packageName
            }
        }

    // ---- peek on change -------------------------------------------------------------------------

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) = noteContent(entry)

            override fun onEntryUpdated(entry: NotificationEntry) = noteContent(entry)

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                peekUntil.remove(entry.key)
                lastContent.remove(entry.key)
            }
        }

    /**
     * A new or changed quiet notification peeks as a card for [PEEK_MILLIS] from its post time.
     * Entries that were already old when SystemUI met them (a restart) don't peek: the strip would
     * flash as cards at every boot.
     */
    private fun noteContent(entry: NotificationEntry) {
        if (!isQuietOngoing(entry)) return
        val n = entry.sbn.notification
        val content =
            (n.extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString() +
                "\u0000" +
                (n.extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        val previous = lastContent.put(entry.key, content)
        if (!peekOnChange || foldOngoing == QuietFoldOngoing.OFF) return
        if (previous == content) return
        val postAge = systemClock.currentTimeMillis() - entry.sbn.postTime
        if (postAge > PEEK_MILLIS) return
        peekUntil[entry.key] = systemClock.uptimeMillis() + (PEEK_MILLIS - postAge.coerceAtLeast(0))
        filter.invalidateList("peek ${entry.key}")
    }

    private fun schedulePeekEnd(delayMillis: Long) {
        if (pendingPeekInvalidate != null) return
        pendingPeekInvalidate =
            executor.executeDelayed(
                {
                    pendingPeekInvalidate = null
                    filter.invalidateList("peek over")
                },
                delayMillis.coerceAtLeast(1L),
            )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("foldOngoing=$foldOngoing silentStyle=$silentStyle peekOnChange=$peekOnChange")
        pw.println("alwaysQuiet=$alwaysQuiet")
        pw.println("hidden=$hidden")
        pw.println("ongoingUnfolded=$ongoingUnfolded silentUnfolded=$silentUnfolded")
        pw.println("peeking=${peekUntil.keys}")
        pw.println("foldedOngoing:")
        for (m in repository.foldedOngoing.value) pw.println("  ${m.kind} ${m.label} ${m.key}")
        pw.println("foldedSilent:")
        for (m in repository.foldedSilent.value) pw.println("  ${m.label} ${m.key}")
    }

    companion object {
        private const val TAG = "IchthysQuietNotificationsCoordinator"
        /** IchthysBackstageService.CHANNEL_ID */
        private const val BACKSTAGE_CHANNEL = "ichthys_backstage"
        /** System packages above FIRST_APPLICATION_UID whose ongoing notifications are quiet. */
        private val SYSTEM_PACKAGES =
            setOf(
                "android",
                "com.android.systemui",
                "com.android.settings",
                "com.android.shell",
                "com.android.networkstack.tethering",
                "com.android.phone",
            )
    }
}
