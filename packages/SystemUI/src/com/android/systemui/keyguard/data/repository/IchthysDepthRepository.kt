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

package com.android.systemui.keyguard.data.repository

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * IchthysOS depth lock screen (kusti420/ichthysos-tracker#58): the two pictures the lock screen
 * draws around the clock, made from the lock wallpaper.
 * - [DepthLayers.backdrop]: the lock wallpaper itself, cut to the screen, drawn under the clock. The
 *   real wallpaper is in its own window that the window manager places and zooms; drawing our own
 *   copy keeps the cut-out exactly on top of the picture it was cut from.
 * - [DepthLayers.cutout]: the same picture with only the subject left (the mask IchthysDepth makes,
 *   org.ichthys.depth), drawn over the clock. Null until the mask is ready.
 *
 * Both are hardware bitmaps of the screen's size. Nothing is loaded while Settings.Secure
 * [KEY_DEPTH] is off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class IchthysDepthRepository
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettings,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val userTracker: UserTracker,
) {
    /**
     * @property subject the cut-out's coverage at [SUBJECT_GRID] px per cell, row-major, for
     *   [hitsSubject]; null without a cut-out
     */
    class DepthLayers(
        val backdrop: Bitmap,
        val cutout: Bitmap?,
        val subject: ByteArray? = null,
        val gridWidth: Int = 0,
        /** For AOD: a faint glow along the subject's edge, nothing else. Null without a cut-out. */
        val night: Bitmap? = null,
    )

    /** A finger on the subject (the X-ray lens), in lock screen pixels. */
    data class Lens(val position: androidx.compose.ui.geometry.Offset, val pressure: Float)

    private val _lens = MutableStateFlow<Lens?>(null)
    val lens: StateFlow<Lens?> = _lens.asStateFlow()

    fun setLens(lens: Lens?) {
        _lens.value = lens
    }

    /**
     * How far the lock screen is into unlocking (0..1, the Lockscreen to Gone transition), read at
     * draw time: the depth layers come apart as it goes. Set by the lock screen root.
     */
    @Volatile var unlockProgress: () -> Float = { 0f }

    /** Whether ([x], [y]) on the lock screen is on the wallpaper's subject. */
    fun hitsSubject(x: Float, y: Float): Boolean {
        val l = layers.value ?: return false
        val grid = l.subject ?: return false
        if (l.gridWidth == 0) return false
        val gx = (x / SUBJECT_GRID).toInt()
        val gy = (y / SUBJECT_GRID).toInt()
        val i = gy * l.gridWidth + gx
        if (gx < 0 || gx >= l.gridWidth || i < 0 || i >= grid.size) return false
        return (grid[i].toInt() and 0xff) > SUBJECT_HIT_ALPHA
    }

    /** Settings.Secure [KEY_DEPTH] != 0. */
    val enabled: StateFlow<Boolean> =
        secureSettings
            .observerFlow(names = arrayOf(KEY_DEPTH), userId = UserHandle.USER_ALL)
            .onStart { emit(Unit) }
            .map { isEnabled() }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    /** The pictures, while enabled; null while off or loading, or for a live wallpaper. */
    val layers: StateFlow<DepthLayers?> =
        enabled
            .flatMapLatest { on ->
                if (!on) flowOf(null)
                else
                    merge(wallpaperChanges(), maskChanges(), userChanges())
                        .onStart { emit(Unit) }
                        .map { load() }
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private fun isEnabled(): Boolean =
        secureSettings.getIntForUser(KEY_DEPTH, 0, UserHandle.USER_CURRENT) != 0

    private fun wallpaperChanges(): Flow<Unit> =
        broadcastDispatcher.broadcastFlow(
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            UserHandle.ALL,
        )

    private fun userChanges(): Flow<Unit> =
        callbackFlow {
            val callback =
                object : UserTracker.Callback {
                    override fun onUserChanged(newUser: Int, userContext: Context) {
                        trySend(Unit)
                    }
                }
            userTracker.addCallback(callback) { it.run() }
            awaitClose { userTracker.removeCallback(callback) }
        }

    /** IchthysDepth notifies when a mask is ready. */
    private fun maskChanges(): Flow<Unit> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            try {
                context.contentResolver.registerContentObserver(
                    Uri.parse("content://$AUTHORITY/mask"),
                    true,
                    observer,
                    UserHandle.USER_ALL,
                )
            } catch (e: RuntimeException) {
                // IchthysDepth is missing: the backdrop is drawn without a cut-out.
                Log.w(TAG, "cannot watch the masks", e)
            }
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    private fun load(): DepthLayers? =
        try {
            loadInternal()
        } catch (e: Exception) {
            // A wallpaper or mask that can't be read leaves the plain lock screen.
            Log.w(TAG, "depth layers not loaded", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "depth layers too large", e)
            null
        }

    private fun loadInternal(): DepthLayers? {
        val user = userTracker.userId
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return null
        val lockId = wm.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, user)
        val key = "L" + lockId + "S" + wm.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, user)
        // The same picture IchthysDepth's LockWallpaper.bitmap() cuts the mask out of.
        val picture =
            wm.getBitmapAsUser(user, false, WallpaperManager.FLAG_LOCK, false)
                ?: wm.getBitmapAsUser(user, false, WallpaperManager.FLAG_SYSTEM, true)
                ?: return null
        val screen = screenSize()
        val region = region(wm, picture, screen, if (lockId > 0) WallpaperManager.FLAG_LOCK
            else WallpaperManager.FLAG_SYSTEM)
        val dst = Rect(0, 0, screen.x, screen.y)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val backdrop = Bitmap.createBitmap(screen.x, screen.y, Bitmap.Config.ARGB_8888)
        Canvas(backdrop).drawBitmap(picture, region, dst, paint)

        val mask = openMask(user, key)
        val cutout =
            mask?.let {
                val out = Bitmap.createBitmap(screen.x, screen.y, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                // The mask has the picture's aspect ratio at its own resolution.
                val sx = it.width.toFloat() / picture.width
                val sy = it.height.toFloat() / picture.height
                val maskRegion =
                    Rect(
                        (region.left * sx).roundToInt(),
                        (region.top * sy).roundToInt(),
                        (region.right * sx).roundToInt(),
                        (region.bottom * sy).roundToInt(),
                    )
                c.drawBitmap(it, maskRegion, dst, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                c.drawBitmap(backdrop, 0f, 0f, paint)
                it.recycle()
                out
            }
        val gridW = screen.x / SUBJECT_GRID
        var subject =
            cutout?.let {
                val small = Bitmap.createScaledBitmap(it, gridW, screen.y / SUBJECT_GRID, true)
                val px = IntArray(small.width * small.height)
                small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                small.recycle()
                ByteArray(px.size) { i -> (px[i] ushr 24).toByte() }
            }
        // A picture without a clear subject (a pattern, a gradient) leaves a speck: no depth then.
        var keep = cutout
        if (subject != null) {
            val covered = subject.count { (it.toInt() and 0xff) > SUBJECT_HIT_ALPHA }
            if (covered < subject.size * MIN_SUBJECT_SHARE) {
                Log.i(TAG, "depth: subject covers $covered/${subject.size} cells, ignored")
                keep?.recycle()
                keep = null
                subject = null
            }
        }
        val night = keep?.let { night(it) }
        picture.recycle()
        Log.i(TAG, "depth layers for $key: ${screen.x}x${screen.y}, cut-out ${keep != null}")
        return DepthLayers(backdrop.toHardware(), keep?.toHardware(), subject, gridW, night)
    }

    /** See [DepthLayers.night]. */
    private fun night(cutout: Bitmap): Bitmap {
        val alpha = cutout.extractAlpha()
        val out = Bitmap.createBitmap(cutout.width, cutout.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val density = context.resources.displayMetrics.density
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(120, 246, 241, 234)
                maskFilter = android.graphics.BlurMaskFilter(
                    2.5f * density, android.graphics.BlurMaskFilter.Blur.OUTER)
            }
        c.drawBitmap(alpha, 0f, 0f, p)
        alpha.recycle()
        return out.toHardware()
    }

    /**
     * The part of [picture] the lock screen shows: the wallpaper's crop for this screen (what the
     * user framed in the wallpaper picker; may be wider, for parallax), cut to the screen's aspect
     * ratio around its centre.
     */
    private fun region(wm: WallpaperManager, picture: Bitmap, screen: Point, which: Int): Rect {
        val crop =
            try {
                wm.getBitmapCrops(listOf(screen), which, false).firstOrNull()
            } catch (e: RuntimeException) {
                null
            }
        val r =
            crop?.takeIf { !it.isEmpty && it.right <= picture.width && it.bottom <= picture.height }
                ?: Rect(0, 0, picture.width, picture.height)
        val aspect = screen.x.toFloat() / screen.y
        return if (r.width().toFloat() / r.height() > aspect) {
            val w = (r.height() * aspect).roundToInt()
            val left = r.left + (r.width() - w) / 2
            Rect(left, r.top, left + w, r.bottom)
        } else {
            val h = (r.width() / aspect).roundToInt()
            val top = r.top + (r.height() - h) / 2
            Rect(r.left, top, r.right, top + h)
        }
    }

    /** The mask from IchthysDepth, or null while it is being made ([maskChanges] tells when). */
    private fun openMask(user: Int, key: String): Bitmap? =
        try {
            context.contentResolver
                .openFileDescriptor(Uri.parse("content://$AUTHORITY/mask/$user/$key"), "r")
                ?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "IchthysDepth refused the mask", e)
            null
        } catch (e: IllegalArgumentException) {
            // IchthysDepth is not installed.
            null
        }

    /** The screen in its natural (portrait) orientation, which is how the lock screen is drawn. */
    private fun screenSize(): Point {
        val b = context.getSystemService(WindowManager::class.java)!!.maximumWindowMetrics.bounds
        return Point(minOf(b.width(), b.height()), maxOf(b.width(), b.height()))
    }

    private fun Bitmap.toHardware(): Bitmap {
        val hw = copy(Bitmap.Config.HARDWARE, false) ?: return this
        recycle()
        return hw
    }

    companion object {
        private const val TAG = "IchthysDepth"
        private const val AUTHORITY = "org.ichthys.depth"

        /** Lock screen pixels per cell of [DepthLayers.subject]. */
        private const val SUBJECT_GRID = 8
        /** Below this share of the screen covered, a mask has no real subject. */
        private const val MIN_SUBJECT_SHARE = 0.01f
        /** Coverage above which a touch counts as on the subject. */
        private const val SUBJECT_HIT_ALPHA = 128

        /** 0 off, 1 on: the subject of the lock wallpaper is drawn over the large clock. */
        const val KEY_DEPTH = "ichthys_lockscreen_depth"
    }
}

/**
 * IchthysOS depth lock screen, touch heat (kusti420/ichthysos-tracker#58): counts where the screen
 * is touched into [com.android.settingslib.ichthys.clock.TouchHeat], which the Depth face draws.
 * - Only touch-downs (where a finger lands), on the default display, while the device is unlocked:
 *   nothing on the lock screen or the bouncer is counted, so the map can't give a PIN or pattern
 *   away.
 * - Watches through an input monitor and never takes the touches: apps get them as always.
 * - Saved in SystemUI's device-protected files every few minutes, so a SystemUI restart keeps the
 *   day; starts over at local midnight.
 */
@SysUISingleton
class IchthysTouchHeatCollector
@Inject
constructor(
    @Application private val context: Context,
    @com.android.systemui.dagger.qualifiers.Background
    private val bgLooper: Looper,
    private val keyguardStateController:
        com.android.systemui.statusbar.policy.KeyguardStateController,
) : com.android.systemui.CoreStartable {

    private val handler by lazy { Handler(bgLooper) }
    private val file by lazy {
        java.io.File(context.createDeviceProtectedStorageContext().filesDir, "touch_heat.bin")
    }
    private var dirty = false
    private var receiver: android.view.InputEventReceiver? = null

    private val saver =
        object : Runnable {
            override fun run() {
                if (dirty) save()
                handler.postDelayed(this, SAVE_MS)
            }
        }

    override fun start() {
        handler.post {
            load()
            val monitor =
                context.getSystemService(android.hardware.input.InputManager::class.java)
                    ?.monitorGestureInput("ichthys-touch-heat", android.view.Display.DEFAULT_DISPLAY)
                    ?: return@post
            receiver =
                object : android.view.InputEventReceiver(monitor.inputChannel, bgLooper) {
                    override fun onInputEvent(event: android.view.InputEvent) {
                        try {
                            if (event is android.view.MotionEvent) onMotion(event)
                        } finally {
                            finishInputEvent(event, false)
                        }
                    }
                }
            handler.postDelayed(saver, SAVE_MS)
        }
    }

    private fun onMotion(e: android.view.MotionEvent) {
        val down =
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> 0
                android.view.MotionEvent.ACTION_POINTER_DOWN -> e.actionIndex
                else -> return
            }
        // The lock screen and the bouncer: never.
        if (keyguardStateController.isShowing) return
        val b = context.getSystemService(WindowManager::class.java)!!.maximumWindowMetrics.bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        var fx = e.getX(down) / w
        var fy = e.getY(down) / h
        // Counted in the screen's natural (portrait) orientation, like the face draws it.
        if (w > h) {
            val t = fx
            fx = 1f - fy
            fy = t
        }
        com.android.settingslib.ichthys.clock.TouchHeat.add(
            fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f), today())
        dirty = true
    }

    private fun today(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 10000 +
            (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    private fun load() {
        try {
            if (file.isFile) {
                java.io.DataInputStream(file.inputStream().buffered()).use {
                    com.android.settingslib.ichthys.clock.TouchHeat.load(it)
                }
            }
            if (com.android.settingslib.ichthys.clock.TouchHeat.day != today()) {
                com.android.settingslib.ichthys.clock.TouchHeat.reset(today())
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "touch heat not loaded", e)
            com.android.settingslib.ichthys.clock.TouchHeat.reset(today())
        }
    }

    private fun save() {
        try {
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use {
                com.android.settingslib.ichthys.clock.TouchHeat.save(it)
            }
            tmp.renameTo(file)
            dirty = false
        } catch (e: java.io.IOException) {
            Log.w(TAG, "touch heat not saved", e)
        }
    }

    private companion object {
        const val TAG = "IchthysTouchHeat"
        const val SAVE_MS = 3 * 60_000L
    }
}
