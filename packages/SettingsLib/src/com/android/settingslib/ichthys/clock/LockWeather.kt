/*
 * Copyright (C) 2026 IchthysOS Project
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import com.android.internal.util.crdroid.OmniJawsClient
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * IchthysOS lock screen weather (scene-container lock screen and the lock screen editor v2).
 *
 * One weather source for the whole process, read from OmniJaws' provider off the main thread and
 * shared by every drawer ([DepthClockView]'s date line, [LockWeatherView]); the editor's previews
 * draw with exactly the same code as the lock screen.
 *
 * Keys (existing crDroid ones are Settings.System, the new ones Settings.Secure):
 * - [KEY_ENABLED], [KEY_CONDITION], [KEY_LOCATION], [KEY_WIND], [KEY_HUMIDITY]: System (crDroid).
 * - [KEY_POS], [KEY_TEMP], [KEY_HIGH_LOW], [KEY_ICONS], [KEY_SIZE]: Secure (IchthysOS).
 */
object LockWeather {
    private const val TAG = "IchthysLockWeather"

    const val KEY_ENABLED = "lockscreen_weather_enabled"
    const val KEY_CONDITION = "lockscreen_weather_text"
    const val KEY_LOCATION = "lockscreen_weather_location"
    const val KEY_WIND = "lockscreen_weather_wind_info"
    const val KEY_HUMIDITY = "lockscreen_weather_humidity_info"

    /** Secure: where the weather goes ([POS_DATE], [POS_UNDER], [POS_TOP_START], [POS_TOP_END]). */
    const val KEY_POS = "ichthys_lockscreen_weather_pos"
    /** Secure, 0/1 (default 1): the temperature. */
    const val KEY_TEMP = "ichthys_lockscreen_weather_temp"
    /** Secure, 0/1: today's high and low. */
    const val KEY_HIGH_LOW = "ichthys_lockscreen_weather_highlow"
    /** Secure string: an icon pack ("org.omnirom.omnijaws.<prefix>"), [ICONS_FOLLOW] or [ICONS_NONE]. */
    const val KEY_ICONS = "ichthys_lockscreen_weather_icons"
    /** Secure, percent 70..150 (default 100). */
    const val KEY_SIZE = "ichthys_lockscreen_weather_size"

    val SYSTEM_KEYS = arrayOf(KEY_ENABLED, KEY_CONDITION, KEY_LOCATION, KEY_WIND, KEY_HUMIDITY)
    val SECURE_KEYS = arrayOf(KEY_POS, KEY_TEMP, KEY_HIGH_LOW, KEY_ICONS, KEY_SIZE)

    /** In the Depth face's date line (other faces, or the date hidden: [POS_UNDER]). */
    const val POS_DATE = 0
    const val POS_UNDER = 1
    const val POS_TOP_START = 2
    const val POS_TOP_END = 3

    /** The icon pack OmniJaws itself uses (its settings). */
    const val ICONS_FOLLOW = ""
    const val ICONS_NONE = "none"

    private const val OMNIJAWS = "org.omnirom.omnijaws"
    private const val DEFAULT_PACK = "$OMNIJAWS.tabler"

    /** OmniJaws' built-in packs (drawables <prefix>_<code>), with their names. */
    val ICON_PACKS: List<Pair<String, String>> =
        listOf(
            "$OMNIJAWS.tabler" to "Tabler",
            "$OMNIJAWS.outline" to "Outline",
            "$OMNIJAWS.nothing" to "Nothing",
            "$OMNIJAWS.google_new" to "Google",
            "$OMNIJAWS.marshmallow" to "Marshmallow",
            "$OMNIJAWS.stickers" to "Stickers",
            "$OMNIJAWS.weather_color" to "Colour",
        )

    /** Line-art packs: tinted in the text colour. The others keep their own colours. */
    private val MONO_PREFIXES = setOf("tabler", "outline", "nothing")

    data class Options(
        val enabled: Boolean = false,
        val temp: Boolean = true,
        val condition: Boolean = true,
        val location: Boolean = false,
        val highLow: Boolean = false,
        val wind: Boolean = false,
        val humidity: Boolean = false,
        val icons: String = ICONS_FOLLOW,
        val size: Int = 100,
        val pos: Int = POS_DATE,
    )

    /** The weather now. Temperatures are rounded numbers without the unit ("18"). */
    data class Now(
        val temp: String,
        val condition: String,
        val code: Int,
        val city: String,
        val high: String?,
        val low: String?,
        val wind: String?,
        val humidity: String?,
        val iconPack: String,
        /**
         * IchthysOS Living Sky: today's sunrise and sunset (epoch ms) when the provider knows them
         * (OpenMeteo), else 0.
         */
        val sunrise: Long = 0L,
        val sunset: Long = 0L,
    )

    fun readOptions(context: Context): Options {
        val cr = context.contentResolver
        fun sys(k: String, d: Int) =
            Settings.System.getIntForUser(cr, k, d, UserHandle.USER_CURRENT)
        fun sec(k: String, d: Int) =
            Settings.Secure.getIntForUser(cr, k, d, UserHandle.USER_CURRENT)
        return Options(
            enabled = sys(KEY_ENABLED, 0) != 0,
            temp = sec(KEY_TEMP, 1) != 0,
            condition = sys(KEY_CONDITION, 1) != 0,
            location = sys(KEY_LOCATION, 0) != 0,
            highLow = sec(KEY_HIGH_LOW, 0) != 0,
            wind = sys(KEY_WIND, 0) != 0,
            humidity = sys(KEY_HUMIDITY, 0) != 0,
            icons = Settings.Secure.getStringForUser(cr, KEY_ICONS, UserHandle.USER_CURRENT)
                ?: ICONS_FOLLOW,
            size = sec(KEY_SIZE, 100).coerceIn(70, 150),
            pos = sec(KEY_POS, POS_DATE).coerceIn(POS_DATE, POS_TOP_END),
        )
    }

    /**
     * Where the weather really goes: in the date line only on the Depth face with its date shown,
     * otherwise under the clock.
     */
    fun placeFor(o: Options, depthFace: Boolean, dateShown: Boolean): Int =
        if (o.pos == POS_DATE && !(depthFace && dateShown)) POS_UNDER else o.pos

    // ---- the shared source -----------------------------------------------------------------

    /** The last weather OmniJaws gave; null while there is none (or it is off). */
    @Volatile
    var current: Now? = null
        private set

    /** The editor's stand-in while there is no weather yet (previews only). */
    @Volatile var sample: Now? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, TAG) }
    private var appContext: Context? = null
    private var registered = false
    private var refreshQueued = false
    private var refreshAgain = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }

    private val providerObserver =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    /** Main thread. The first listener starts watching OmniJaws, the last one stops it. */
    fun addListener(context: Context, l: () -> Unit) {
        listeners.add(l)
        if (registered) return
        val app = context.applicationContext ?: context
        appContext = app
        registered = true
        try {
            app.registerReceiver(receiver,
                IntentFilter().apply {
                    addAction(OmniJawsClient.WEATHER_UPDATE)
                    addAction(OmniJawsClient.WEATHER_ERROR)
                    addAction(Intent.ACTION_USER_SWITCHED)
                },
                Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.w(TAG, "no weather broadcasts", e)
        }
        try {
            app.contentResolver.registerContentObserver(
                Uri.parse("content://org.omnirom.omnijaws.provider"), true, providerObserver)
        } catch (e: Exception) {
            // OmniJaws is not installed: nothing to watch.
        }
        refresh()
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
        if (!registered || listeners.isNotEmpty()) return
        registered = false
        val app = appContext ?: return
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Exception) {}
        app.contentResolver.unregisterContentObserver(providerObserver)
    }

    /** Reads the weather again, off the main thread; listeners hear of it on the main thread. */
    fun refresh() {
        val app = appContext ?: return
        if (refreshQueued) {
            // A change while reading: read once more afterwards, so the last one is never missed.
            refreshAgain = true
            return
        }
        refreshQueued = true
        worker.execute {
            val now = query(app)
            main.post {
                refreshQueued = false
                if (now != current) {
                    current = now
                    listeners.forEach { it() }
                }
                if (refreshAgain) {
                    refreshAgain = false
                    refresh()
                }
            }
        }
    }

    private fun query(context: Context): Now? {
        val cr = context.contentResolver
        var enabled = false
        var metric = true
        var pack = ""
        try {
            cr.query(OmniJawsClient.SETTINGS_URI, OmniJawsClient.SETTINGS_PROJECTION,
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    enabled = c.getInt(0) == 1
                    metric = c.getInt(1) == 0
                    pack = c.getString(4) ?: ""
                }
            }
            if (!enabled) return null
            cr.query(OmniJawsClient.WEATHER_URI, OmniJawsClient.WEATHER_PROJECTION,
                null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val city = c.getString(0) ?: ""
                val windSpeed = c.getFloat(1)
                val code = c.getInt(3)
                val temp = round(c.getFloat(4))
                val humidity = c.getString(5)
                val condition = c.getString(6) ?: ""
                // Only some providers fill these; a bad value must not cost the weather.
                fun millis(col: String): Long =
                    try {
                        c.getColumnIndex(col).let { if (it >= 0) c.getLong(it) else 0L }
                    } catch (e: RuntimeException) {
                        0L
                    }
                val sunrise = millis("sunrise")
                val sunset = millis("sunset")
                var high: String? = null
                var low: String? = null
                // The first forecast row is today.
                if (c.moveToNext()) {
                    low = round(c.getFloat(7))
                    high = round(c.getFloat(8))
                }
                return Now(
                    temp = temp,
                    condition = condition.split(" ").joinToString(" ") { w ->
                        w.lowercase(Locale.getDefault())
                            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    },
                    code = code,
                    city = city,
                    high = high,
                    low = low,
                    wind = if (windSpeed.isNaN()) null
                        else round(windSpeed) + if (metric) " km/h" else " mph",
                    humidity = humidity?.takeIf { it.isNotBlank() },
                    iconPack = pack.ifEmpty { DEFAULT_PACK },
                    sunrise = sunrise,
                    sunset = sunset,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "weather not read", e)
        }
        return null
    }

    private fun round(v: Float): String {
        if (v.isNaN()) return "-"
        val r = v.roundToInt()
        return if (r == 0) "0" else r.toString()
    }

    // ---- icons -----------------------------------------------------------------------------

    private val packRes = HashMap<String, Resources?>()

    /** The pack an [Options.icons] value and the weather's own choice come to (null: no icon). */
    fun packFor(icons: String, now: Now): String? =
        when (icons) {
            ICONS_NONE -> null
            ICONS_FOLLOW -> now.iconPack
            else -> icons
        }

    fun isMono(pack: String): Boolean = MONO_PREFIXES.contains(pack.substringAfterLast('.'))

    /** The pack's drawable for [code] (its "na" one if it has none). Main thread. */
    fun icon(context: Context, pack: String, code: Int): Drawable? {
        val idx = pack.lastIndexOf('.')
        if (idx <= 0) return null
        val pkg = pack.substring(0, idx)
        val prefix = pack.substring(idx + 1)
        val res =
            packRes.getOrPut(pkg) {
                try {
                    context.packageManager.getResourcesForApplication(pkg)
                } catch (e: Exception) {
                    null
                }
            } ?: return null
        return try {
            var id = res.getIdentifier("${prefix}_$code", "drawable", pkg)
            if (id == 0) id = res.getIdentifier("${prefix}_na", "drawable", pkg)
            if (id == 0) null else res.getDrawable(id, null)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Draws one weather line: the icon, the temperature (full strength), then the rest a little
 * dimmer, separated by middle dots, in the Depth face's date typeface. Parts that don't fit
 * [layout]'s width are dropped, least important first, so it never runs off the screen.
 */
class LockWeatherDrawer(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = dateTypeface()
            letterSpacing = 0.04f
            textAlign = Paint.Align.LEFT
        }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var iconBitmap: Bitmap? = null
    private var iconKey = ""

    /** Text colour (the date's cream by default). */
    var color = DepthClockView.POND_CREAM
    /** Overall strength, e.g. 0.92 awake and 0.7 dozing, like the date line. */
    var strength = 0.95f
    var dozing = false

    private var temp: String? = null
    private val rest = ArrayList<String>()
    private var restText = ""
    private var pack: String? = null
    private var code = 0
    private var iconSize = 0f
    private var gap = 0f

    var width = 0f
        private set
    /** Height of the line (icon or text, whichever is taller). */
    var height = 0f
        private set

    /**
     * Lays out [now] as [o] says at [textSize] px, in at most [maxWidth] px. False when nothing
     * is left to draw.
     */
    fun layout(o: LockWeather.Options, now: LockWeather.Now, textSize: Float, maxWidth: Float): Boolean {
        text.textSize = textSize
        iconSize = textSize * 1.3f
        gap = textSize * 0.36f
        pack = LockWeather.packFor(o.icons, now)
        code = now.code
        temp = if (o.temp) "${now.temp}°" else null
        // Least important last: they are dropped first.
        val parts = ArrayList<String>()
        if (o.condition && now.condition.isNotBlank()) parts.add(now.condition)
        if (o.highLow && now.high != null && now.low != null) parts.add("${now.high}° / ${now.low}°")
        if (o.location && now.city.isNotBlank()) parts.add(now.city)
        if (o.humidity && now.humidity != null) parts.add(now.humidity)
        if (o.wind && now.wind != null) parts.add(now.wind)
        rest.clear()
        rest.addAll(parts)
        while (true) {
            restText = rest.joinToString(SEPARATOR)
            width = measure()
            if (width <= maxWidth || rest.isEmpty()) break
            rest.removeAt(rest.size - 1)
        }
        if (width > maxWidth && pack != null && temp != null) {
            // Still too wide (huge size, narrow clock): the temperature alone.
            pack = null
            width = measure()
        }
        val fm = text.fontMetrics
        height = max(fm.descent - fm.ascent, if (pack != null) iconSize else 0f)
        return width > 0f
    }

    private fun measure(): Float {
        var w = 0f
        if (pack != null) w += iconSize
        temp?.let {
            if (w > 0f) w += gap
            w += text.measureText(it)
        }
        if (restText.isNotEmpty()) {
            if (w > 0f) w += gap * 1.5f
            w += text.measureText(restText)
        }
        return w
    }

    /** Draws the laid-out line from [left], its text on [baseline]. Returns its bounds in [out]. */
    fun draw(canvas: Canvas, left: Float, baseline: Float, out: RectF? = null) {
        val fm = text.fontMetrics
        // The icon is centred on the text's middle.
        val mid = baseline + (fm.ascent + fm.descent) / 2f
        if (dozing) {
            text.clearShadowLayer()
            iconPaint.clearShadowLayer()
        } else {
            // IchthysOS polish: tighter and darker than a glow, so it reads on busy pictures.
            text.setShadowLayer(4.5f * density, 0f, 1f * density, SHADOW)
            iconPaint.setShadowLayer(4.5f * density, 0f, 1f * density, SHADOW)
        }
        var x = left
        val p = pack
        if (p != null) {
            iconBitmap(p)?.let { b ->
                iconPaint.alpha = (255 * strength).toInt()
                iconPaint.colorFilter = if (dozing && !LockWeather.isMono(p)) GREY else null
                canvas.drawBitmap(b, x, mid - iconSize / 2f, iconPaint)
            }
            x += iconSize
        }
        temp?.let {
            if (x > left) x += gap
            text.color = withAlpha(color, strength)
            canvas.drawText(it, x, baseline, text)
            x += text.measureText(it)
        }
        if (restText.isNotEmpty()) {
            if (x > left) x += gap * 1.5f
            text.color = withAlpha(color, strength * 0.88f)
            canvas.drawText(restText, x, baseline, text)
        }
        out?.set(left, mid - height / 2f, left + width, mid + height / 2f)
    }

    /** From the line's middle to its text baseline (after [layout]). */
    val baselineFromMiddle: Float
        get() {
            val fm = text.fontMetrics
            return -(fm.ascent + fm.descent) / 2f
        }

    /** Plain text of the line (no icon), e.g. for the Depth face's status line. */
    fun plain(o: LockWeather.Options, now: LockWeather.Now): String {
        val parts = ArrayList<String>()
        if (o.temp) parts.add("${now.temp}°")
        if (o.condition && now.condition.isNotBlank()) parts.add(now.condition)
        if (o.highLow && now.high != null && now.low != null) parts.add("${now.high}°/${now.low}°")
        return parts.joinToString(" ")
    }

    private fun iconBitmap(pack: String): Bitmap? {
        val size = max(1, iconSize.roundToInt())
        val mono = LockWeather.isMono(pack)
        val key = "$pack|$code|$size|${if (mono) color else 0}"
        if (key == iconKey) return iconBitmap
        iconKey = key
        // Not recycled: a display list drawn before may still hold the old one.
        iconBitmap = null
        val d = LockWeather.icon(context, pack, code) ?: return null
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val copy = d.mutate()
        if (mono) copy.setTint(color or 0xFF000000.toInt())
        copy.setBounds(0, 0, size, size)
        copy.draw(Canvas(b))
        iconBitmap = b
        return b
    }

    companion object {
        private const val SEPARATOR = "  ·  "
        private val SHADOW = Color.argb(160, 0, 12, 16)
        private val GREY = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

        /** The Depth face's date typeface (Syne 600), else sans-serif-medium. Made once. */
        private val DATE_TYPEFACE: Typeface by lazy {
            try {
                val f = File("/system/fonts/Syne-VF.ttf")
                if (f.isFile) Typeface.Builder(f).setFontVariationSettings("'wght' 600").build()
                else null
            } catch (e: Exception) {
                null
            } ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        fun dateTypeface(): Typeface = DATE_TYPEFACE

        private fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((255 * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color),
                Color.blue(color))
    }
}

/**
 * IchthysOS lock screen weather as a view of its own: under the clock ([LockWeather.POS_UNDER]) or
 * in a top corner ([SLOT_TOP]). It shows itself only when the weather is on, there is weather,
 * and it is placed in its [slot] (the date-line place is the Depth face's own); otherwise it takes
 * no height.
 *
 * On the lock screen ([followClock]) it reads everything from the settings, live, for the current
 * user, and lines itself up with the clock face the way KeyguardCustomClockView places it. In the
 * editor's preview the host sets [previewOptions] and the spans instead.
 */
class LockWeatherView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** [LockWeather.POS_UNDER] or [SLOT_TOP] (both top corners). */
    var slot = LockWeather.POS_UNDER

    /**
     * SystemUI: follow KeyguardCustomClockView's size, offset and top margin settings (spans,
     * [onShift]). The preview sets the spans itself.
     */
    var followClock = false

    /** The span the line is aligned in, in this view's pixels (start/centre/end of it). */
    var spanLeft = -1f
    var spanRight = -1f

    /** SystemUI: the clock's box height, for [onShift] (how far the scaled face ends from it). */
    var clockHeight = 0
        set(v) {
            if (field == v) return
            field = v
            updateShift()
        }

    /** SystemUI: called with the vertical shift (px) under the clock that the face's scale needs. */
    var onShift: ((Int) -> Unit)? = null

    /** Preview: the content's bounds in this view after each draw (null: nothing shown). */
    var onContentBounds: ((RectF?) -> Unit)? = null

    /** Preview values (null: the settings). */
    var previewOptions: LockWeather.Options? = null
        set(v) {
            if (field == v) return
            field = v
            reload()
        }
    var previewDepthFace: Boolean? = null
        set(v) {
            if (field == v) return
            field = v
            reload()
        }
    var previewDateShown: Boolean? = null
        set(v) {
            if (field == v) return
            field = v
            reload()
        }
    /** DepthClockView.ALIGN_* for the under-clock line (the date line's alignment). */
    var previewAlign: Int? = null
        set(v) {
            if (field == v) return
            field = v
            reload()
        }

    /** Dozing: no shadow, colour icons in grey. The host dims the view itself, eased. */
    var dozing = false
        set(v) {
            if (field == v) return
            field = v
            drawer.dozing = v
            invalidate()
        }

    /**
     * IchthysOS polish: 0 awake .. 1 AOD (the host's eased doze amount). The scrim pill behind
     * the line fades out with it; AOD shows the bare line.
     */
    var dozeAmount = 0f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /** IchthysOS polish: a subtle dark pill behind the line, so it reads on any picture. */
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL }
    private val pill = RectF()
    private val anchorListener: () -> Unit = { post { updateShift() } }

    private val drawer = LockWeatherDrawer(context).apply { strength = 0.95f }
    private var options = LockWeather.Options()
    private var depthFace = true
    private var dateShown = true
    private var align = DepthClockView.ALIGN_CENTRE
    private var scale = 1f
    private var offsetX = 0f
    private var marginTop = DEFAULT_MARGIN_TOP
    /** The clock's turn (degrees), so the line goes under the turned clock's lowest point. */
    private var rotation = 0f
    private var shown = false
    private val bounds = RectF()
    private var lastBounds: RectF? = null
    private var lastShift = Int.MIN_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = reload()
        }
    private val userReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reload()
        }
    private val weatherListener: () -> Unit = { reload() }
    private var watching = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (previewOptions == null) {
            val cr = context.contentResolver
            for (k in LockWeather.SYSTEM_KEYS) {
                cr.registerContentObserver(Settings.System.getUriFor(k), false, observer,
                    UserHandle.USER_ALL)
            }
            for (k in LockWeather.SECURE_KEYS + CLOCK_KEYS) {
                cr.registerContentObserver(Settings.Secure.getUriFor(k), false, observer,
                    UserHandle.USER_ALL)
            }
            context.registerReceiver(userReceiver, IntentFilter(Intent.ACTION_USER_SWITCHED),
                Context.RECEIVER_NOT_EXPORTED)
            watching = true
        }
        // IchthysOS polish: under the Depth face, follow where it wants the line.
        if (followClock) DepthFaceAnchor.addListener(anchorListener)
        LockWeather.addListener(context, weatherListener)
        reload()
    }

    override fun onDetachedFromWindow() {
        DepthFaceAnchor.removeListener(anchorListener)
        LockWeather.removeListener(weatherListener)
        if (watching) {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(userReceiver)
            watching = false
        }
        super.onDetachedFromWindow()
    }

    private fun secure(key: String, def: Int): Int =
        Settings.Secure.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    private fun reload() {
        options = previewOptions ?: LockWeather.readOptions(context)
        depthFace = previewDepthFace ?: (secure(KEY_STYLE, 0) == DEPTH_STYLE)
        dateShown = previewDateShown ?: (secure(KEY_SHOW_DATE, 1) != 0)
        align = previewAlign ?: secure(DepthClockView.KEY_DATE_ALIGN, DepthClockView.ALIGN_CENTRE)
        if (followClock) {
            scale = secure(KEY_SCALE, 100).coerceIn(40, 180) / 100f
            offsetX = secure(KEY_OFFSET_X, 0).toFloat()
            marginTop = secure(KEY_MARGIN_TOP, DEFAULT_MARGIN_TOP)
            rotation = secure(KEY_ROTATION, 0).toFloat()
            updateShift()
        }
        val now = data()
        val place = LockWeather.placeFor(options, depthFace, dateShown)
        val inSlot =
            if (slot == SLOT_TOP) place == LockWeather.POS_TOP_START || place == LockWeather.POS_TOP_END
            else place == slot
        shown = options.enabled && now != null && inSlot
        // Shown, hidden or resized with the options.
        requestLayout()
        invalidate()
    }

    private fun data(): LockWeather.Now? =
        LockWeather.current ?: if (previewOptions != null) LockWeather.sample else null

    /**
     * The line's place against the clock's unscaled box: under the scaled face, (scale - 1) x the
     * face's height. IchthysOS polish: under the Depth face, where the face says it goes
     * ([DepthFaceAnchor]: under the digits you can see, not under its whole box).
     */
    private fun updateShift() {
        if (!followClock) return
        val padTop = dp(BASE_TOP_DP) + marginTop
        val faceH = max(0f, (clockHeight - padTop).toFloat())
        val anchor = if (depthFace) DepthFaceAnchor.underTop else Float.NaN
        val bottom = if (anchor.isNaN()) faceH else anchor
        // IchthysOS: a turned clock reaches lower on one side than its box does: the line goes
        // under that, not into the digits (it used to sit over or above them).
        val half = max(0f, resources.displayMetrics.widthPixels / 2f - dp(SIDE_DP)) * scale
        val turn = Math.toRadians(rotation.toDouble())
        val drop = (half * abs(sin(turn)) - bottom * scale / 2f * (1 - abs(cos(turn)))).toFloat()
        val shift = (padTop + bottom * scale + max(0f, drop) - clockHeight).roundToInt()
        if (shift != lastShift) {
            lastShift = shift
            onShift?.invoke(shift)
        }
    }

    private fun textSize(): Float = textSizeFor(resources.displayMetrics, options, slot)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val now = data()
        if (!shown || now == null || !drawer.layout(options, now, textSize(), spanWidth(w))) {
            setMeasuredDimension(w, 0)
            return
        }
        // Under the clock: the gap above and room under (the pill fits in them). Top corners:
        // room for the pill above and under the line.
        val h =
            if (slot == SLOT_TOP) drawer.height + 2 * dp(PILL_V_DP) + dp(2f)
            else drawer.height + dp(UNDER_GAP_DP) + dp(6f)
        setMeasuredDimension(w, h.roundToInt())
    }

    private fun spans(w: Int): Pair<Float, Float> {
        if (followClock) {
            if (slot == SLOT_TOP) return dp(TOP_INSET_DP) to w - dp(TOP_INSET_DP)
            val centre = w / 2f + offsetX
            val half = max(0f, w / 2f - dp(SIDE_DP)) * scale
            // IchthysOS: never past the screen's side margins (a moved or enlarged clock put the
            // line partly off the screen).
            return max(dp(SIDE_DP), centre - half) to min(w - dp(SIDE_DP), centre + half)
        }
        val l = if (spanLeft >= 0f) spanLeft else 0f
        val r = if (spanRight >= 0f) spanRight else w.toFloat()
        return l to r
    }

    private fun spanWidth(w: Int): Float {
        val (l, r) = spans(w)
        return max(0f, r - l - 2 * dp(PILL_H_DP))
    }

    override fun onDraw(canvas: Canvas) {
        val now = data()
        if (!shown || now == null || height == 0) {
            report(null)
            return
        }
        if (!drawer.layout(options, now, textSize(), spanWidth(width))) {
            report(null)
            return
        }
        val (l, r) = spans(width)
        // IchthysOS polish: the line keeps the pill's padding inside its span.
        val inset = dp(PILL_H_DP)
        val place = LockWeather.placeFor(options, depthFace, dateShown)
        val left =
            when {
                slot == SLOT_TOP ->
                    if (place == LockWeather.POS_TOP_END) r - inset - drawer.width else l + inset
                align == DepthClockView.ALIGN_START -> l + inset
                align == DepthClockView.ALIGN_END -> r - inset - drawer.width
                else -> (l + r) / 2f - drawer.width / 2f
            }
        val top = if (slot == SLOT_TOP) dp(PILL_V_DP) else dp(UNDER_GAP_DP)
        // The line's box is drawer.height tall; the text's baseline sits so the box is centred.
        val fmTop = top + drawer.height / 2f
        val baseline = fmTop + drawer.baselineFromMiddle
        pill.set(left - inset, top - dp(PILL_V_DP), left + drawer.width + inset,
            top + drawer.height + dp(PILL_V_DP))
        val pillAlpha = (Color.alpha(PILL) * (1f - dozeAmount)).roundToInt()
        if (pillAlpha > 0) {
            pillPaint.alpha = pillAlpha
            canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, pillPaint)
        }
        drawer.draw(canvas, left, baseline, bounds)
        // The editor selects (and frames) the pill.
        report(pill)
    }

    private fun report(r: RectF?) {
        val cb = onContentBounds ?: return
        val old = lastBounds
        if (r == null && old == null) return
        if (r != null && old != null && r == old) return
        lastBounds = r?.let { RectF(it) }
        cb(lastBounds)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Both top corners ([LockWeather.POS_TOP_START] / [LockWeather.POS_TOP_END]). */
        const val SLOT_TOP = 100

        /** Room between the clock and the line under it. */
        const val UNDER_GAP_DP = 6f
        /** The top corners' inset from the clock box's edges (16 dp more to the screen's). */
        const val TOP_INSET_DP = 8f
        /** IchthysOS polish: the scrim pill's padding around the line, and its colour. */
        private const val PILL_H_DP = 10f
        private const val PILL_V_DP = 5f
        private val PILL = Color.argb(72, 8, 12, 16)
        /** Text sizes at 100 %: under the clock a little larger than in the corners. */
        private const val UNDER_SP = 16f
        private const val TOP_SP = 15f

        private fun textSizeFor(dm: android.util.DisplayMetrics, o: LockWeather.Options, slot: Int) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                if (slot == SLOT_TOP) TOP_SP else UNDER_SP, dm) * o.size / 100f

        /**
         * IchthysOS polish: the height (px) the line takes under the clock, as [onMeasure] gives
         * it ([drawer] is laid out for it), so the Depth face can keep room for it; 0 when there
         * is nothing to draw.
         */
        fun underHeight(
            context: Context,
            drawer: LockWeatherDrawer,
            o: LockWeather.Options,
            now: LockWeather.Now,
        ): Float {
            val dm = context.resources.displayMetrics
            if (!drawer.layout(o, now, textSizeFor(dm, o, LockWeather.POS_UNDER), Float.MAX_VALUE)) {
                return 0f
            }
            return drawer.height + (UNDER_GAP_DP + 6f) * dm.density
        }

        // KeyguardCustomClockView's settings and layout.
        private const val KEY_STYLE = "lock_screen_custom_clock_style"
        private const val KEY_SHOW_DATE = "ichthys_clock_show_date"
        private const val KEY_SCALE = "lock_screen_custom_clock_size_scale"
        private const val KEY_OFFSET_X = "ichthys_clock_offset_x"
        private const val KEY_MARGIN_TOP = "lock_screen_custom_clock_margin_top"
        private const val KEY_ROTATION = "ichthys_clock_rotation"
        private const val DEPTH_STYLE = 80
        private const val BASE_TOP_DP = 72
        private const val DEFAULT_MARGIN_TOP = 15
        private const val SIDE_DP = 24f
        private val CLOCK_KEYS =
            arrayOf(KEY_STYLE, KEY_SHOW_DATE, KEY_SCALE, KEY_OFFSET_X, KEY_MARGIN_TOP,
                KEY_ROTATION, DepthClockView.KEY_DATE_ALIGN)
    }
}
