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
import android.os.PowerManager
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
import com.android.settingslib.ichthys.clock.LockWeather
import com.android.settingslib.ichthys.sky.LivingSky
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
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
 *
 * IchthysOS Living Sky: the backdrop is also what Living Sky ([LivingSky.KEY_ENABLED]) grades, so it
 * loads while either is on; with Living Sky the layers also carry the picture's sky (IchthysDepth's
 * sky mask, [DepthLayers.sky]) and what is known about it, and [livingSky] has the light and the
 * weather of the moment. With depth off there is no cut-out (nothing stands over the clock).
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
        /**
         * IchthysOS editor v2: the subject's top outline, made once with the layers, for the Depth
         * face's "Shape" mode (published as SubjectOutline.current). Null without a cut-out.
         */
        val outline: com.android.settingslib.ichthys.clock.SubjectOutline.Profile? = null,
        /** IchthysOS Living Sky: the sky's coverage (alpha), half the screen's size; null: none. */
        val sky: Bitmap? = null,
        /** The rest (alpha = not sky), the screen's size: the weather's far layers stay behind it. */
        val land: Bitmap? = null,
        /** What Living Sky knows of this picture; null without a sky mask. */
        val skyInfo: LivingSky.SkyInfo? = null,
    ) {
        /** Living Sky can grade this picture. */
        val livingSky: Boolean
            get() = sky != null && land != null && skyInfo?.hasSky == true
    }

    /**
     * IchthysOS Living Sky, this minute: the [options], the [sun] and the moon's phase, the
     * [weather] to show (null: none, or the weather switch is off) and whether battery saver is on
     * (then nothing moves).
     */
    class SkyNow(
        val options: LivingSky.Options,
        val sun: LivingSky.Sun,
        val moonPhase: Float,
        val weather: LivingSky.Weather?,
        val powerSave: Boolean,
    )

    /** A finger on the subject (the X-ray lens), in lock screen pixels. */
    data class Lens(val position: androidx.compose.ui.geometry.Offset, val pressure: Float)

    private val _lens = MutableStateFlow<Lens?>(null)
    val lens: StateFlow<Lens?> = _lens.asStateFlow()

    /** A ripple on the pond: where a finger touched the lock screen, and when (uptime ms). */
    data class Ripple(val position: androidx.compose.ui.geometry.Offset, val start: Long)

    private val _ripples = MutableStateFlow<List<Ripple>>(emptyList())
    val ripples: StateFlow<List<Ripple>> = _ripples.asStateFlow()

    /** A finger at [at]: a new ripple where it lands, and every so far as it moves. */
    fun ripple(at: androidx.compose.ui.geometry.Offset) {
        // IchthysOS editor v2: the pond's ripples belong to X-ray; Overlap and Shape stay still.
        if (mode != DEPTH_XRAY) return
        val now = android.os.SystemClock.uptimeMillis()
        val live = _ripples.value.filter { now - it.start < RIPPLE_MS }
        val last = live.lastOrNull()
        val step = 36f * context.resources.displayMetrics.density
        if (last != null && (at - last.position).getDistance() < step && now - last.start < 160) {
            _ripples.value = live
            return
        }
        _ripples.value = (live + Ripple(at, now)).takeLast(MAX_RIPPLES)
    }

    /** Where the lens has just been: the concept's fading rings behind a dragged lens. */
    private val _trail = MutableStateFlow<List<androidx.compose.ui.geometry.Offset>>(emptyList())
    val trail: StateFlow<List<androidx.compose.ui.geometry.Offset>> = _trail.asStateFlow()

    fun setLens(lens: Lens?) {
        _lens.value = lens
        if (lens == null) {
            _trail.value = emptyList()
            return
        }
        val last = _trail.value.lastOrNull()
        val step = 28f * context.resources.displayMetrics.density
        if (last == null || (lens.position - last).getDistance() > step) {
            _trail.value = (_trail.value + lens.position).takeLast(TRAIL)
        }
    }

    /**
     * How far the lock screen is into unlocking (0..1, the Lockscreen to Gone transition), read at
     * draw time: the depth layers come apart as it goes. Set by the lock screen root.
     */
    @Volatile var unlockProgress: () -> Float = { 0f }

    /**
     * The AOD burn-in shift (window pixels, zero while awake), read at draw time: the AOD layers
     * move with it like the rest of the AOD. Set by the lock screen root.
     */
    @Volatile
    var burnInShift: () -> androidx.compose.ui.geometry.Offset = {
        androidx.compose.ui.geometry.Offset.Zero
    }

    /** The depth look's own accent (pond light), for its strokes, whatever the theme's is. */
    val accent: Int
        get() = 0xFF9FE7DD.toInt()

    /**
     * Where the clock is on screen (window coordinates): pressing and holding inside it draws in the
     * Depth face's touch heat instead of swiping.
     */
    @Volatile var clockBounds: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero

    /**
     * Whether ([x], [y]) on the lock screen is on the wallpaper's subject, for the X-ray lens: only
     * in the X-ray depth mode (editor v2: Overlap and Shape have no lens).
     */
    fun hitsSubject(x: Float, y: Float): Boolean {
        if (mode != DEPTH_XRAY) return false
        val l = layers.value ?: return false
        val grid = l.subject ?: return false
        if (l.gridWidth == 0) return false
        val gx = (x / SUBJECT_GRID).toInt()
        val gy = (y / SUBJECT_GRID).toInt()
        val i = gy * l.gridWidth + gx
        if (gx < 0 || gx >= l.gridWidth || i < 0 || i >= grid.size) return false
        return (grid[i].toInt() and 0xff) > SUBJECT_HIT_ALPHA
    }

    /**
     * The last layers and what they were made from. [layers] stops when the lock screen leaves
     * composition and starts again on the next lock; without this every lock would decode the
     * wallpaper and build the layers again.
     */
    private class Cached(
        val key: String,
        val layers: DepthLayers?,
        val hadMask: Boolean,
        val hadSky: Boolean = true,
    )

    @Volatile private var cached: Cached? = null

    /** The last [KEY_DEPTH] value read (0 off, 1 X-ray, 2 overlap, 3 shape). */
    @Volatile private var mode = 0

    /** Settings.Secure [KEY_DEPTH] != 0, for the current user (re-read on a user switch). */
    val enabled: StateFlow<Boolean> =
        merge(
                secureSettings.observerFlow(
                    names = arrayOf(KEY_DEPTH),
                    userId = UserHandle.USER_ALL,
                ),
                userChanges(),
            )
            .onStart { emit(Unit) }
            .map { readMode().also { mode = it } != 0 }
            .onEach { enabledNow = it }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    /** IchthysOS Living Sky's switches, for the current user (re-read on a user switch). */
    val skyOptions: StateFlow<LivingSky.Options> =
        merge(
                secureSettings.observerFlow(
                    names =
                        arrayOf(
                            LivingSky.KEY_ENABLED,
                            LivingSky.KEY_WEATHER,
                            LivingSky.KEY_INTENSITY,
                            LivingSky.KEY_DEBUG,
                        ),
                    userId = UserHandle.USER_ALL,
                ),
                userChanges(),
            )
            .onStart { emit(Unit) }
            .map {
                val debug = LivingSky.readDebug(
                    secureSettings.getStringForUser(LivingSky.KEY_DEBUG, UserHandle.USER_CURRENT))
                LivingSky.Options(
                    debugMinute = debug.first,
                    debugCode = debug.second,
                    enabled = secureSettings.getIntForUser(
                        LivingSky.KEY_ENABLED, 0, UserHandle.USER_CURRENT) != 0,
                    weather = secureSettings.getIntForUser(
                        LivingSky.KEY_WEATHER, 1, UserHandle.USER_CURRENT) != 0,
                    intensity = secureSettings.getIntForUser(
                        LivingSky.KEY_INTENSITY, 100, UserHandle.USER_CURRENT).coerceIn(0, 100),
                )
            }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), LivingSky.Options())

    /** The pictures, while depth or Living Sky is on; null while off or loading, or live. */
    val layers: StateFlow<DepthLayers?> =
        combine(enabled, skyOptions) { depth, sky -> depth to sky.enabled }
            .distinctUntilChanged()
            .flatMapLatest { (depth, sky) ->
                if (!depth && !sky) {
                    cached = null
                    flowOf(null)
                } else {
                    // true: something changed, load again; false: the lock screen is back (or
                    // the user changed, which the cache key covers), the cached layers may do.
                    merge(
                            wallpaperChanges().map { true },
                            maskChanges().map { true },
                            userChanges().map { false },
                        )
                        .onStart { emit(false) }
                        .map { force -> load(force, depth, sky) }
                }
            }
            .flowOn(bgDispatcher)
            // IchthysOS editor v2: the Depth face's Shape mode reads the outline from SettingsLib.
            .onEach { com.android.settingslib.ichthys.clock.SubjectOutline.set(it?.outline) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    /**
     * IchthysOS Living Sky: the light and the weather of the moment, while it is on. New every
     * minute (the time tick, a time or time zone change), on new weather, and when battery saver
     * turns on or off. Only while the lock screen shows (WhileSubscribed).
     */
    val livingSky: StateFlow<SkyNow?> =
        skyOptions
            .flatMapLatest { o ->
                if (!o.enabled) {
                    flowOf(null)
                } else {
                    combine(
                        clockTicks().onStart { emit(Unit) },
                        weatherNow(),
                        powerSave(),
                    ) { _, now, saver ->
                        val times = LivingSky.sunTimes(context)
                        // KEY_DEBUG (demos, tests) pins the time of day and the weather.
                        val sun =
                            if (o.debugMinute >= 0) {
                                LivingSky.sun(times, o.debugMinute.toFloat(),
                                    java.util.Calendar.getInstance()
                                        .get(java.util.Calendar.DAY_OF_YEAR))
                            } else LivingSky.sunNow(times)
                        val code = if (o.debugCode >= 0) o.debugCode else now?.code
                        val weather =
                            if (o.weather && code != null) LivingSky.weatherFor(code) else null
                        SkyNow(o, sun, LivingSky.moonPhase(), weather, saver)
                    }
                }
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private fun clockTicks(): Flow<Unit> =
        broadcastDispatcher.broadcastFlow(
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        )

    /** The lock screen weather (LockWeather, which wants its listeners on the main thread). */
    private fun weatherNow(): Flow<LockWeather.Now?> =
        callbackFlow {
                val main = Handler(Looper.getMainLooper())
                val listener: () -> Unit = { trySend(LockWeather.current) }
                main.post {
                    LockWeather.addListener(context, listener)
                    trySend(LockWeather.current)
                }
                awaitClose { main.post { LockWeather.removeListener(listener) } }
            }
            .onStart { emit(null) }
            .distinctUntilChanged()

    private fun powerSave(): Flow<Boolean> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            .onStart { emit(Unit) }
            .map { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
            .distinctUntilChanged()

    private fun readMode(): Int =
        secureSettings.getIntForUser(KEY_DEPTH, 0, UserHandle.USER_CURRENT)

    /**
     * The Depth face with the touch heat fill is the lock screen clock: only then does holding a
     * finger on the clock paint into it (and take the touch from the swipes).
     */
    val clockPaintable: StateFlow<Boolean> =
        merge(
                secureSettings.observerFlow(
                    names = arrayOf(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, KEY_FILL),
                    userId = UserHandle.USER_ALL,
                ),
                userChanges(),
            )
            .onStart { emit(Unit) }
            .map {
                secureSettings.getIntForUser(
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                    0,
                    UserHandle.USER_CURRENT,
                ) == DEPTH_CLOCK_STYLE &&
                    secureSettings.getIntForUser(
                        KEY_FILL,
                        com.android.settingslib.ichthys.clock.DepthClockView.FILL_SOLID,
                        UserHandle.USER_CURRENT,
                    ) == com.android.settingslib.ichthys.clock.DepthClockView.FILL_HEAT
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

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
                // IchthysOS Living Sky: and when a sky mask is ready.
                context.contentResolver.registerContentObserver(
                    Uri.parse(LivingSky.SKY_URI),
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

    private fun load(force: Boolean, depth: Boolean, sky: Boolean): DepthLayers? =
        try {
            loadInternal(force, depth, sky)
        } catch (e: Exception) {
            // A wallpaper or mask that can't be read leaves the plain lock screen.
            Log.w(TAG, "depth layers not loaded", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "depth layers too large", e)
            null
        }

    private fun loadInternal(force: Boolean, depth: Boolean, sky: Boolean): DepthLayers? {
        val user = userTracker.userId
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return null
        val lockId = wm.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, user)
        val key = "L" + lockId + "S" + wm.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, user)
        val screen = screenSize()
        // IchthysOS Living Sky: which layers are wanted is part of what was loaded.
        val cacheKey = "$user/$key/${screen.x}x${screen.y}/" + (if (depth) "d" else "") +
            (if (sky) "s" else "")
        cached?.let {
            // Without a mask last time, only a mask that has turned up since needs a new load.
            if (!force && it.key == cacheKey && (it.hadMask || !maskExists(user, key)) &&
                (it.hadSky || !maskExists(user, key, SKY))) {
                return it.layers
            }
        }
        // IchthysOS: a live wallpaper has no picture to copy. getBitmapAsUser would hand back the
        // stock default image instead, drawn over the live one; no depth then.
        val shown = if (lockId > 0) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
        if (wm.getWallpaperInfo(shown, user) != null) {
            cached = Cached(cacheKey, null, hadMask = true, hadSky = true)
            return null
        }
        // The same picture IchthysDepth's LockWallpaper.bitmap() cuts the mask out of. Not
        // recycled: it can be WallpaperManager's process-wide cached bitmap, which ImageWallpaper
        // (same process) draws too.
        val picture =
            wm.getBitmapAsUser(user, false, WallpaperManager.FLAG_LOCK, false)
                ?: wm.getBitmapAsUser(user, false, WallpaperManager.FLAG_SYSTEM, true)
                ?: return null
        val region = region(wm, picture, screen, if (lockId > 0) WallpaperManager.FLAG_LOCK
            else WallpaperManager.FLAG_SYSTEM)
        val dst = Rect(0, 0, screen.x, screen.y)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val backdrop = Bitmap.createBitmap(screen.x, screen.y, Bitmap.Config.ARGB_8888)
        Canvas(backdrop).drawBitmap(picture, region, dst, paint)
        // IchthysOS: how bright it is behind the clock (the Depth face's contrast); Living Sky
        // publishes its graded value over this while it shows.
        val behindClock = com.android.settingslib.ichthys.clock.ClockBackdrop.measure(backdrop)
        com.android.settingslib.ichthys.clock.ClockBackdrop.publishPhoto(behindClock)
        // The glass clock's frost: this picture, very soft.
        com.android.settingslib.ichthys.clock.ClockBackdrop.publishFrost(backdrop, screen.x, screen.y)

        // With depth off, nothing of the subject is drawn (Living Sky alone needs only the sky).
        val mask = if (depth) openMask(user, key) else null
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
        // IchthysOS: depth is only drawn when it is right. A real subject (a person, a car, a
        // tower) comes out of the subject finder solid and with a clear edge against what is
        // behind it; a speck, or a grey, mushy coverage (haze, a landscape's "most salient"
        // patch) is no subject: the clock then sat half inside a mountain.
        var keep = cutout
        if (keep != null && subject != null) {
            val q = com.android.settingslib.ichthys.clock.DepthSource.isRealSubject(keep, backdrop)
            if (!q.first) {
                Log.i(TAG, "depth: no clear subject (${q.second}), ignored")
                keep?.recycle()
                keep = null
                subject = null
            }
        }
        // IchthysOS Living Sky: the sky, cut to the screen like the picture, and what it tells.
        // Depth asks for it too when there is no clear subject: a crisp skyline keeps the clock
        // in the sky instead (DepthSource).
        val wantSky = sky || (depth && keep == null)
        val skyMask = if (wantSky) openMask(user, key, SKY) else null
        var skyBitmap: Bitmap? = null
        var land: Bitmap? = null
        var skyInfo: LivingSky.SkyInfo? = null
        var skylineOutline: com.android.settingslib.ichthys.clock.SubjectOutline.Profile? = null
        if (skyMask != null) {
            val sx = skyMask.width.toFloat() / picture.width
            val sy = skyMask.height.toFloat() / picture.height
            val skyRegion =
                Rect(
                    (region.left * sx).roundToInt(),
                    (region.top * sy).roundToInt(),
                    (region.right * sx).roundToInt(),
                    (region.bottom * sy).roundToInt(),
                )
            // Its own paint: the cut-out's left SRC_IN on the shared one.
            val skyPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val small =
                Bitmap.createBitmap(screen.x / 2, screen.y / 2, Bitmap.Config.ALPHA_8).also {
                    Canvas(it).drawBitmap(skyMask, skyRegion, Rect(0, 0, it.width, it.height),
                        skyPaint)
                }
            val info = LivingSky.analyse(backdrop, small)
            skyBitmap = small
            skyInfo = info
            if (info.hasSky) {
                land =
                    Bitmap.createBitmap(screen.x, screen.y, Bitmap.Config.ALPHA_8).also {
                        val c = Canvas(it)
                        c.drawColor(android.graphics.Color.BLACK)
                        c.drawBitmap(skyMask, skyRegion, dst,
                            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                            })
                    }
            }
            skyMask.recycle()
            Log.i(TAG, "living sky for $key: sky ${(info.share * 100).roundToInt()}% of the " +
                "upper half, horizon ${(info.horizon * 100).roundToInt()}%, reason ${info.reason}")
            // IchthysOS: no clear subject, but a crisp skyline (a ridge, a city, a hill line):
            // the clock stays above it (DepthClockView fits it), nothing is drawn over it.
            if ((depth || sky) && keep == null && info.hasSky) {
                skylineOutline = com.android.settingslib.ichthys.clock.DepthSource.skyline(
                    small, backdrop, screen.x, screen.y)
                Log.i(TAG, "depth: skyline ${skylineOutline != null}")
            }
            if (!sky) {
                // Asked for by depth only: Living Sky is off.
                small.recycle()
                skyBitmap = null
                skyInfo = null
                land = null
            }
        }
        val night = keep?.let { night(it) }
        // IchthysOS editor v2: the subject's top outline, once per mask (Shape mode).
        val outline =
            subject?.let {
                com.android.settingslib.ichthys.clock.SubjectOutline.fromCoverage(
                    it, gridW, screen.y / SUBJECT_GRID, screen.x, screen.y, SUBJECT_HIT_ALPHA)
            } ?: skylineOutline
        Log.i(TAG, "depth layers for $key: ${screen.x}x${screen.y}, cut-out ${keep != null}")
        val layers =
            DepthLayers(backdrop.toHardware(), keep?.toHardware(), subject, gridW, night, outline,
                skyBitmap, land, skyInfo)
        cached = Cached(cacheKey, layers, hadMask = !depth || mask != null,
            hadSky = !wantSky || skyMask != null)
        return layers
    }

    /**
     * IchthysOS: whether the cut-out may be drawn over the clock: never when it would hide too
     * much of it ([MAX_CLOCK_COVER] of the clock's box), so the time stays readable whatever the
     * picture. Shape mode fits its digits around the subject instead, so it always may.
     */
    fun cutoutKeepsClockReadable(layers: DepthLayers): Boolean {
        if (mode == com.android.settingslib.ichthys.clock.DepthClockView.DEPTH_SHAPE) return true
        val grid = layers.subject ?: return true
        val box = clockBounds
        if (box.isEmpty || layers.gridWidth <= 0) return true
        val gw = layers.gridWidth
        val gh = grid.size / gw
        val x0 = (box.left / SUBJECT_GRID).toInt().coerceIn(0, gw)
        val x1 = (box.right / SUBJECT_GRID).toInt().coerceIn(0, gw)
        val y0 = (box.top / SUBJECT_GRID).toInt().coerceIn(0, gh)
        val y1 = (box.bottom / SUBJECT_GRID).toInt().coerceIn(0, gh)
        if (x1 <= x0 || y1 <= y0) return true
        var hit = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if ((grid[y * gw + x].toInt() and 0xff) > SUBJECT_HIT_ALPHA) hit++
        }
        val cover = hit.toFloat() / ((x1 - x0) * (y1 - y0))
        return cover <= MAX_CLOCK_COVER
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

    /**
     * The mask from IchthysDepth, or null while it is being made ([maskChanges] tells when).
     * [kind]: the subject's ("mask") or, IchthysOS Living Sky, the sky's ([SKY]).
     */
    private fun openMask(user: Int, key: String, kind: String = MASK): Bitmap? =
        try {
            context.contentResolver
                .openFileDescriptor(Uri.parse("content://$AUTHORITY/$kind/$user/$key"), "r")
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

    /** Whether IchthysDepth has the mask ready, without decoding it. */
    private fun maskExists(user: Int, key: String, kind: String = MASK): Boolean =
        try {
            context.contentResolver
                .openFileDescriptor(Uri.parse("content://$AUTHORITY/$kind/$user/$key"), "r")
                ?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
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
        /**
         * A finger is on the under-display sensor (UdfpsController): the depth pictures step
         * aside, so nothing of ours is on screen while the sensor takes its picture.
         */
        @JvmField val fingerOnSensor = MutableStateFlow(false)

        /**
         * The last [enabled] value, for code without the repository (the unlock transition's
         * easings). Current while the lock screen shows, which collects [enabled].
         */
        @Volatile var enabledNow = false

        private const val TAG = "IchthysDepth"
        private const val AUTHORITY = "org.ichthys.depth"
        private const val MASK = "mask"
        /** IchthysOS Living Sky: IchthysDepth's sky masks. */
        private const val SKY = "sky"

        /** How long a ripple spreads and fades. */
        const val RIPPLE_MS = 1100L
        private const val MAX_RIPPLES = 12

        /** Rings in the lens's trail. */
        private const val TRAIL = 5

        /** Lock screen pixels per cell of [DepthLayers.subject]. */
        private const val SUBJECT_GRID = 8
        /** Below this share of the screen covered, a mask has no real subject. */
        private const val MIN_SUBJECT_SHARE = 0.01f
        /** IchthysOS: the most of the clock's box a cut-out may hide (overlap / X-ray). */
        private const val MAX_CLOCK_COVER = 0.4f
        /** Coverage above which a touch counts as on the subject. */
        private const val SUBJECT_HIT_ALPHA = 128

        /**
         * 0 off; otherwise the subject of the lock wallpaper is drawn over the large clock:
         * 1 X-ray (with the touch lens; the old "on"), 2 overlap, 3 shape (editor v2: the Depth
         * face fits its digits around the subject, DepthClockView.DEPTH_*).
         */
        const val KEY_DEPTH = "ichthys_lockscreen_depth"
        private const val DEPTH_XRAY = com.android.settingslib.ichthys.clock.DepthClockView.DEPTH_XRAY

        private const val KEY_FILL = com.android.settingslib.ichthys.clock.DepthClockView.KEY_FILL
        /** The Depth face's index in KeyguardCustomClockView's LAYOUT_NAMES. */
        private const val DEPTH_CLOCK_STYLE = 80
    }
}

/**
 * IchthysOS depth lock screen, touch heat (kusti420/ichthysos-tracker#58): counts where the screen
 * is touched into [com.android.settingslib.ichthys.clock.TouchHeat], which the Depth face draws.
 * - Only touch-downs (where a finger lands), on the default display, while the device is unlocked:
 *   nothing on the lock screen or the bouncer is counted, so the map can't give a PIN or pattern
 *   away. Neither on the other credential screens: BiometricPrompt (and its PIN view) and the
 *   ConfirmLock / ChooseLock / ConfirmDeviceCredential activities.
 * - Watches through an input monitor and never takes the touches: apps get them as always.
 * - Saved in SystemUI's device-protected files every few minutes, so a SystemUI restart keeps the
 *   day; starts over at local midnight. Each user has their own day.
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
    private val authController: com.android.systemui.biometrics.AuthController,
    private val taskStackChangeListeners:
        com.android.systemui.shared.system.TaskStackChangeListeners,
    private val userTracker: UserTracker,
) : com.android.systemui.CoreStartable {

    private val handler by lazy { Handler(bgLooper) }
    private val dir by lazy { context.createDeviceProtectedStorageContext().filesDir }
    /** Whose day [TouchHeat] holds. Only touched on [bgLooper]. */
    private var user = 0
    /** User 0 keeps the file name from before days were per user. */
    private val file: java.io.File
        get() = java.io.File(dir, if (user == 0) "touch_heat.bin" else "touch_heat_$user.bin")
    private var dirty = false
    /** A credential screen is on top (see [isCredentialActivity]); set off the main thread. */
    @Volatile private var credentialOnTop = false
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
            user = userTracker.userId
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
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) {
                    if (newUser == user) return
                    if (dirty) save()
                    user = newUser
                    com.android.settingslib.ichthys.clock.TouchHeat.reset(today())
                    dirty = false
                    load()
                }
            }
        ) { handler.post(it) }
        taskStackChangeListeners.registerTaskStackListener(
            object : com.android.systemui.shared.system.TaskStackChangeListener {
                override fun onTaskStackChangedBackground() {
                    updateCredentialOnTop()
                }
            }
        )
        handler.post { updateCredentialOnTop() }
        // Saved the moment the phone locks, so a restart never loses the touches before it.
        keyguardStateController.addCallback(
            object : com.android.systemui.statusbar.policy.KeyguardStateController.Callback {
                override fun onKeyguardShowingChanged() {
                    if (keyguardStateController.isShowing) handler.post { if (dirty) save() }
                }
            }
        )
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
        // IchthysOS: nor any other place a PIN, password or pattern is typed.
        if (authController.isShowing || credentialOnTop) return
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
        val n = com.android.settingslib.ichthys.clock.TouchHeat.touches
        if (n == 1 || n % 100 == 0) Log.i(TAG, "$n touches today")
    }

    private fun updateCredentialOnTop() {
        credentialOnTop =
            try {
                com.android.systemui.shared.system.ActivityManagerWrapper.getInstance()
                    .runningTask
                    ?.topActivity
                    ?.let { isCredentialActivity(it.className) } ?: false
            } catch (e: RuntimeException) {
                false
            }
    }

    /**
     * Settings' screens that take a PIN, password or pattern: confirming the current one (opening
     * security settings, work profile and other challenges) and choosing a new one.
     */
    private fun isCredentialActivity(className: String): Boolean {
        val name = className.substringAfterLast('.')
        return name.startsWith("ConfirmLock") || name.startsWith("ChooseLock") ||
            name.startsWith("SetupChooseLock") || name.startsWith("ConfirmDeviceCredential")
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
