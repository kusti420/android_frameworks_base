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

package com.android.systemui.keyguard.ui.composable.elements

import com.android.compose.animation.scene.ElementKey
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.ichthys.clock.LockNotifications
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.data.repository.IchthysLockNotifRepository
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.shade.ui.ShoalShade
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** IchthysOS: the dock as a scene element, so the unlock transitions can fade it. */
val IchthysDockElementKey = ElementKey("IchthysNotificationDock")

/**
 * IchthysOS lock screen notifications, the Quiet dock (tracker#58, concept G): what the lock screen
 * shows of its notifications at rest. No cards. Above the shortcuts, bare app icons (at most five,
 * then "+N"), a hairline under them and one faint line with the newest notification; the line
 * fades in on arrival and settles dim. After ten seconds without a touch the icons dim; on AOD the
 * icon row stays at 40 %, hairline and line gone. A tap or a swipe up on it opens the stack as a
 * sheet over the clock (the upper region's [SheetHeader] + the stack); the glance mode shows
 * counts only and a tap asks for the unlock instead.
 *
 * The dock is anchored to the fingerprint sensor's keep-out by the lock screen root
 * ([LockscreenSceneLayout]): never to the screen's bottom, never over the sensor.
 */
@SysUISingleton
class IchthysNotificationDockProvider
@Inject
constructor(
    @Application private val appContext: Context,
    private val repository: IchthysLockNotifRepository,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val notifPipeline: Lazy<NotifPipeline>,
    private val deviceEntryInteractor: Lazy<DeviceEntryInteractor>,
    private val falsingManager: Lazy<FalsingManager>,
) {
    /** App icons drawn once per package, small. */
    private val icons = LruCache<String, ImageBitmap>(24)

    /** One app in the dock: its first (highest ranked) notification stands for it. */
    private class App(val packageName: String, val appName: String, val count: Int, val icon: android.graphics.drawable.Icon?)

    private class Model(val apps: List<App>, val total: Int, val newest: ActiveNotificationModel?) {
        val shownApps: List<App>
            get() = apps.take(LockNotifications.DOCK_MAX_ICONS)

        /** Notifications of apps that got no icon. */
        val more: Int
            get() = apps.drop(LockNotifications.DOCK_MAX_ICONS).sumOf { it.count }
    }

    private fun model(list: List<ActiveNotificationModel>): Model {
        val byApp = LinkedHashMap<String, MutableList<ActiveNotificationModel>>()
        for (n in list) byApp.getOrPut(n.packageName) { ArrayList() }.add(n)
        val apps =
            byApp.map { (pkg, ns) ->
                val first = ns.first()
                App(pkg, first.appName, ns.size, first.statusBarIcon ?: first.shelfIcon ?: first.aodIcon)
            }
        return Model(apps, list.size, list.maxByOrNull { it.whenTime })
    }

    /**
     * What the newest notification says, as the lock screen would show it (null: nothing to say).
     * IchthysOS: [sensitive] is the system's own decision for this notification (the entry's
     * isSensitive, from SensitiveContentCoordinator: the lock screen is public and the
     * notification needs redaction, because the user hides private content, the app marked it
     * private, a per-app override or detected sensitive content). Content shows whenever the
     * stack itself would show it; when it is hidden, the app's public version if it has one, like
     * the stack's redacted row. (A global "public mode and private content not allowed" check
     * read once at first composition said "unlock to read" with content allowed: asked before
     * the user's setting was loaded it answers "redact", and it was only re-read on a new list.)
     */
    private fun line(newest: ActiveNotificationModel?, sensitive: Boolean): String? {
        newest ?: return null
        val entry = notifPipeline.get().getEntry(newest.key) ?: return null
        val notification =
            if (sensitive) entry.sbn.notification.publicVersion ?: return null
            else entry.sbn.notification
        val extras = notification.extras ?: return null
        val title =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                ?: newest.appName
        val text =
            extras.getCharSequence(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }
        val first = text?.toString()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return if (first != null) "$title  ·  $first" else title.toString()
    }

    private suspend fun appIcon(app: App): ImageBitmap? {
        icons.get(app.packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val pm = appContext.packageManager
                val d =
                    try {
                        pm.getApplicationIcon(app.packageName)
                    } catch (e: Exception) {
                        app.icon?.loadDrawable(appContext)
                    } ?: return@withContext null
                // Twice the shown size: crisp on the 1080 px panel, small in memory.
                val px = (LockNotifications.DOCK_ICON_DP * 2 * appContext.resources.displayMetrics.density).toInt()
                    .coerceAtLeast(24)
                val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, px, px)
                d.draw(Canvas(b))
                b.asImageBitmap().also { icons.put(app.packageName, it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun bodyFamily(context: Context, weight: Int): FontFamily =
        FontFamily(Typeface(ShoalShade.text(context, weight)))

    private fun monoFamily(context: Context): FontFamily = FontFamily(Typeface(ShoalShade.mono(context, 500)))

    /** The dock's ink: the lock screen's off-white, like the clock's default. */
    private val ink = Color(0xFFF6F1EA)

    /**
     * The dock. Takes no height while there is nothing to show (no notifications, or the cards
     * mode), so the root's layout knows whether to make room for it. [dozeAmount] 0 awake .. 1 AOD.
     */
    @Composable
    fun Dock(dozeAmount: Float, modifier: Modifier = Modifier) {
        val settings by repository.settings.collectAsStateWithLifecycle()
        if (settings.cards) return
        val list by
            activeNotificationsInteractor.topLevelRepresentativeNotifications.collectAsStateWithLifecycle(
                initialValue = emptyList()
            )
        if (list.isEmpty()) return
        val model = remember(list) { model(list) }
        val sheetOpen by repository.sheetOpen.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current

        // Idle: ten seconds after the last touch or the last arrival the icons dim (if asked).
        var touched by remember { mutableIntStateOf(0) }
        var idle by remember { mutableStateOf(false) }
        LaunchedEffect(touched, model.newest?.key, dozeAmount > 0f, sheetOpen) {
            idle = false
            if (dozeAmount > 0f || sheetOpen) return@LaunchedEffect
            delay(LockNotifications.IDLE_MS)
            idle = true
        }
        val awake =
            if (idle && settings.dockDim) LockNotifications.ALPHA_IDLE else LockNotifications.ALPHA_AWAKE
        val targetAlpha = awake + (LockNotifications.ALPHA_AOD - awake) * dozeAmount
        val iconsAlpha by animateFloatAsState(targetAlpha, tween(600), label = "dockIcons")
        // The sheet is open: the dock stays as the handle, quieter.
        val sheetDim by animateFloatAsState(if (sheetOpen) 0.5f else 1f, tween(260), label = "dockSheet")

        // The newest line: in on arrival, dim after three seconds; nothing of it on AOD.
        val lineAlpha = remember { Animatable(LockNotifications.LINE_DIM) }
        var seen by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(model.newest?.key) {
            val key = model.newest?.key
            if (seen != null && key != null && key != seen) {
                lineAlpha.snapTo(0f)
                lineAlpha.animateTo(1f, tween(240))
                delay(LockNotifications.ARRIVAL_MS)
                lineAlpha.animateTo(LockNotifications.LINE_DIM, tween(900))
            }
            seen = key
        }
        // IchthysOS: followed live, so the line changes as the system's decision does (a setting
        // loaded or changed, a face unlock that keeps the lock screen, the device locking again).
        val newestEntry =
            remember(model.newest?.key) { model.newest?.let { notifPipeline.get().getEntry(it.key) } }
        val sensitive by
            remember(newestEntry) { newestEntry?.isSensitive ?: MutableStateFlow(false) }
                .collectAsStateWithLifecycle()
        val text =
            remember(model.newest, sensitive, settings.glance, model.total) {
                if (settings.glance) null else line(model.newest, sensitive)
            }
        val unlockToRead = stringResource(R.string.ichthys_dock_unlock_to_read)
        val counted = pluralStringResource(R.plurals.ichthys_dock_notifications, model.total, model.total)
        val shownLine =
            text ?: if (settings.glance || sensitive) "$counted  ·  $unlockToRead" else counted

        fun act() {
            if (settings.glance) {
                deviceEntryInteractor.get().attemptDeviceEntry("IchthysOS lock screen dock")
            } else {
                repository.setSheetOpen(!sheetOpen)
            }
        }

        val description = stringResource(R.string.ichthys_dock_a11y, model.total)
        Column(
            modifier
                .padding(horizontal = LockNotifications.DOCK_SIDE_DP.dp)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
                .pointerInput(settings.glance, sheetOpen) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touched++
                        var taken = false
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!moved && !falsingManager.get().isFalseTap(FalsingManager.LOW_PENALTY)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    act()
                                }
                                break
                            }
                            val travel = change.position - down.position
                            if (!moved && travel.getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                // Up: the dock's; down or sideways: the scene's swipe.
                                if (travel.y < 0f && kotlin.math.abs(travel.y) > kotlin.math.abs(travel.x)) {
                                    taken = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (!sheetOpen) act()
                                }
                            }
                            if (taken) change.consume()
                        }
                    }
                }
                .graphicsLayer { alpha = sheetDim },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var rowWidth by remember { mutableIntStateOf(0) }
            Row(
                Modifier.onSizeChanged { rowWidth = it.width }.graphicsLayer { alpha = iconsAlpha },
                horizontalArrangement = Arrangement.spacedBy(LockNotifications.DOCK_ICON_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (app in model.shownApps) {
                    if (settings.glance) {
                        CountBubble(app.count, context)
                    } else {
                        AppIcon(app)
                    }
                }
                if (model.more > 0) {
                    Text(
                        stringResource(R.string.ichthys_dock_more, model.more),
                        fontFamily = monoFamily(context),
                        fontSize = 11.sp,
                        color = ink,
                        maxLines = 1,
                    )
                }
            }
            // Hairline and the line: awake only (they go with the doze).
            val awakeAlpha = (1f - dozeAmount).coerceIn(0f, 1f)
            Spacer(Modifier.height(LockNotifications.DOCK_HAIRLINE_GAP_DP.dp))
            val hairW = with(density) { maxOf(rowWidth.toFloat(), 96.dp.toPx()).toDp() }
            Box(
                Modifier.width(hairW)
                    .height(1.dp)
                    .graphicsLayer { alpha = awakeAlpha * 0.38f }
                    .then(Modifier.drawHairline(ink))
            )
            Spacer(Modifier.height(LockNotifications.DOCK_LINE_GAP_DP.dp))
            Text(
                shownLine,
                fontFamily = bodyFamily(context, 500),
                fontSize = LockNotifications.DOCK_LINE_SP.sp,
                lineHeight = 14.sp,
                color = ink,
                // IchthysOS: a soft dark shadow so the line reads on a bright wallpaper too.
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                        blurRadius = 6f,
                    )
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.widthIn(max = 300.dp).graphicsLayer { alpha = awakeAlpha * lineAlpha.value },
            )
        }
    }

    @Composable
    private fun AppIcon(app: App) {
        val image by produceState<ImageBitmap?>(icons.get(app.packageName), app.packageName) {
            if (value == null) value = appIcon(app)
        }
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = app.appName, Modifier.size(LockNotifications.DOCK_ICON_DP.dp))
        } else {
            // Not loaded yet: a quiet disc of the same size, so the row doesn't jump.
            DrawCanvas(Modifier.size(LockNotifications.DOCK_ICON_DP.dp)) {
                drawCircle(ink.copy(alpha = 0.25f), size.minDimension / 2f)
            }
        }
    }

    /** Glance mode: a hairline ring with the app's count, no icon, no name. */
    @Composable
    private fun CountBubble(count: Int, context: Context) {
        Box(Modifier.size(LockNotifications.DOCK_ICON_DP.dp), contentAlignment = Alignment.Center) {
            DrawCanvas(Modifier.size(LockNotifications.DOCK_ICON_DP.dp)) {
                drawCircle(ink.copy(alpha = 0.7f), size.minDimension / 2f - 0.75f * density, style = Stroke(1f * density))
            }
            Text(
                if (count > 9) "9+" else "$count",
                fontFamily = monoFamily(context),
                fontSize = 10.sp,
                color = ink,
                maxLines = 1,
            )
        }
    }

    private fun Modifier.drawHairline(color: Color): Modifier =
        this.drawBehind {
            drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), size.height)
        }

    /**
     * The sheet's header, over the blurred clock when the dock is open: how many notifications,
     * a hint that a swipe down puts the sheet away, and a hairline. The stack comes under it.
     */
    @Composable
    fun SheetHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val count by
            activeNotificationsInteractor.allNotificationsCount.collectAsStateWithLifecycle(initialValue = 0)
        Column(
            modifier.fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!moved && !falsingManager.get().isFalseTap(FalsingManager.LOW_PENALTY)) onClose()
                            break
                        }
                        val travel = change.position - down.position
                        if (!moved && travel.getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            if (travel.y > 0f) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onClose()
                            }
                        }
                        if (moved) change.consume()
                    }
                }
            }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.ichthys_dock_notifications, count, count).uppercase(),
                    fontFamily = monoFamily(context),
                    fontSize = 11.sp,
                    letterSpacing = 1.1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ichthys_sheet_put_away),
                    fontFamily = bodyFamily(context, 400),
                    fontSize = 11.sp,
                    color = ink.copy(alpha = 0.6f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                // A chevron down: the close handle.
                DrawCanvas(Modifier.size(18.dp)) {
                    val u = size.width / 24f
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(6 * u, 10 * u)
                        lineTo(12 * u, 16 * u)
                        lineTo(18 * u, 10 * u)
                    }
                    drawPath(p, ink, style = Stroke(1.6f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).alpha(0.38f).drawHairline(ink))
            Spacer(Modifier.height(10.dp))
        }
    }
}
