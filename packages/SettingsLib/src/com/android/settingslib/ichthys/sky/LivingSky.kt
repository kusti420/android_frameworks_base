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

package com.android.settingslib.ichthys.sky

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.provider.Settings
import com.android.settingslib.ichthys.clock.LockWeather
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * IchthysOS Living Sky: the lock wallpaper's sky follows the real one. It warms at sunrise, is
 * bright at midday, turns gold and then blue at sunset and goes dark with stars at night, and it
 * shows the weather outside (clouds, rain, snow, fog, storms).
 *
 * Works on any photo: IchthysDepth finds its sky (SkyFinder, `content://org.ichthys.depth/sky`) and
 * this grades it on the GPU ([SHADER]): sky pixels move to a palette keyed on the sun's elevation
 * (keeping the photo's own light: its clouds and gradients), the land is relit with the same light,
 * stars and the moon come out in the sky only, and the sun's glow sits where the sun would be.
 * Shared by SystemUI's lock screen (IchthysDepthRepository / IchthysDepthLayers) and the lock
 * screen editor's preview, so both draw exactly the same.
 */
object LivingSky {
    /** Settings.Secure: 0 off, 1 on. */
    const val KEY_ENABLED = "ichthys_living_sky"
    /** Settings.Secure: show the weather (default on). */
    const val KEY_WEATHER = "ichthys_living_sky_weather"
    /** Settings.Secure: 0..100, how far the picture moves (default 100). */
    const val KEY_INTENSITY = "ichthys_living_sky_intensity"
    /**
     * Settings.Secure, for demos and tests only: "minute,code[,dark]" pins the time of day
     * (minutes after midnight, -1: real) and the weather (a condition code, -1: real). Unset:
     * everything real. IchthysOS: the third field pins whether the picture's sky counts as a night
     * one ([SkyInfo.darkSky]): 1 relights it by day whatever it is, 0 never does, -1 or absent:
     * as analysed. E.g. `settings put secure ichthys_living_sky_debug 780,-1,1` shows the day
     * relight of any picture at 13:00.
     */
    const val KEY_DEBUG = "ichthys_living_sky_debug"
    /** Settings.Global, from TwilightService: "HH:mm,HH:mm", today's sunset and sunrise. */
    const val KEY_TWILIGHT = "ichthys_twilight_times"

    /** IchthysDepth's sky masks; <user>/<key> as for the subject masks. */
    const val SKY_URI = "content://org.ichthys.depth/sky"

    data class Options(
        val enabled: Boolean = false,
        val weather: Boolean = true,
        val intensity: Int = 100,
        /** [KEY_DEBUG]'s pinned minute of the day and condition code (-1: real). */
        val debugMinute: Int = -1,
        val debugCode: Int = -1,
        /** IchthysOS: [KEY_DEBUG]'s third field, the night-sky override (-1 as analysed). */
        val debugDark: Int = -1,
    )

    /** [KEY_DEBUG] as (minute, code); (-1, -1) when unset or unreadable. */
    fun readDebug(value: String?): Pair<Int, Int> {
        val p = value?.split(",") ?: return -1 to -1
        val minute = p.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 0..1439 } ?: -1
        val code = p.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 0..47 } ?: -1
        return minute to code
    }

    /** IchthysOS: [KEY_DEBUG]'s third field: 1 force the night-sky day relight, 0 never, -1 auto. */
    fun readDebugDark(value: String?): Int =
        value?.split(",")?.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 0..1 } ?: -1

    fun readOptions(cr: ContentResolver, user: Int): Options =
        Options(
            enabled = Settings.Secure.getIntForUser(cr, KEY_ENABLED, 0, user) != 0,
            weather = Settings.Secure.getIntForUser(cr, KEY_WEATHER, 1, user) != 0,
            intensity = Settings.Secure.getIntForUser(cr, KEY_INTENSITY, 100, user).coerceIn(0, 100),
        )

    // ---- the sun -------------------------------------------------------------------------------

    /** Today's sunrise and sunset, minutes after local midnight. */
    class SunTimes(val sunrise: Float, val sunset: Float)

    /**
     * Where today's sun times come from: the weather provider's (OpenMeteo fills them), else
     * TwilightService's ([KEY_TWILIGHT], from the location), else 07:00 and 19:00.
     */
    fun sunTimes(context: Context): SunTimes {
        LockWeather.current?.let { w ->
            if (w.sunrise > 0L && w.sunset > 0L) {
                val rise = minuteOf(w.sunrise)
                val set = minuteOf(w.sunset)
                if (set > rise) return SunTimes(rise, set)
            }
        }
        try {
            val v = Settings.Global.getString(context.contentResolver, KEY_TWILIGHT)
            val parts = v?.split(",")
            if (parts != null && parts.size == 2) {
                val set = hhmm(parts[0])
                val rise = hhmm(parts[1])
                if (set != null && rise != null && set > rise) return SunTimes(rise, set)
            }
        } catch (e: RuntimeException) {
            // Unreadable: the fixed day below.
        }
        return SunTimes(7f * 60f, 19f * 60f)
    }

    private fun minuteOf(millis: Long): Float {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.HOUR_OF_DAY) * 60f + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
    }

    private fun hhmm(s: String): Float? {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60f + m
    }

    /**
     * The sun at [minute] (local, 0..1440) on [dayOfYear]: [elevation] in degrees, [noon] its
     * elevation at solar noon, [dayFraction] 0 at sunrise .. 1 at sunset (outside: night).
     */
    class Sun(val elevation: Float, val noon: Float, val dayFraction: Float)

    /**
     * The sun's height from the day's length: the latitude follows from the day length and the
     * sun's declination (cos H0 = -tan φ tan δ), then the hour angle gives the height. Near the
     * equinoxes the day length says little about the latitude, so it eases to 45°. The curve is
     * pinned to 0° at the real sunrise and sunset either way.
     */
    fun sun(times: SunTimes, minute: Float, dayOfYear: Int): Sun {
        val rise = times.sunrise
        var set = times.sunset
        if (set <= rise) set += 1440f
        val dayLen = (set - rise).coerceIn(30f, 1410f)
        val noonMin = rise + dayLen / 2f
        val decl = Math.toRadians(23.44 * sin(2.0 * PI * (284 + dayOfYear) / 365.0))
        val h0 = PI * dayLen / 1440.0
        val td = tan(decl)
        val latEst = if (abs(td) > 1e-4) atan(-cos(h0) / td) else Math.toRadians(DEFAULT_LAT)
        val w = smoothstep(2f, 6f, abs(Math.toDegrees(decl)).toFloat()).toDouble()
        val lat = (Math.toRadians(DEFAULT_LAT) * (1 - w) + latEst * w)
            .coerceIn(-Math.toRadians(66.0), Math.toRadians(66.0))
        val b = cos(lat) * cos(decl)
        val a = -b * cos(h0)
        var hour = 2.0 * PI * (minute - noonMin) / 1440.0
        while (hour > PI) hour -= 2.0 * PI
        while (hour < -PI) hour += 2.0 * PI
        val e = Math.toDegrees(asin((a + b * cos(hour)).coerceIn(-1.0, 1.0))).toFloat()
        val noon = Math.toDegrees(asin((a + b).coerceIn(-1.0, 1.0))).toFloat()
        val since = ((minute - rise) % 1440f + 1440f) % 1440f
        return Sun(e, max(noon, 1f), since / dayLen)
    }

    /** The sun now, for [times]. */
    fun sunNow(times: SunTimes, now: Long = System.currentTimeMillis()): Sun {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60f + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
        return sun(times, minute, c.get(Calendar.DAY_OF_YEAR))
    }

    /** The moon's phase now: 0 new, 0.5 full (a mean synodic month from a known new moon). */
    fun moonPhase(now: Long = System.currentTimeMillis()): Float {
        val days = (now - NEW_MOON_MS) / 86_400_000.0
        val p = (days / SYNODIC_DAYS) % 1.0
        return (if (p < 0) p + 1 else p).toFloat()
    }

    // ---- the weather ---------------------------------------------------------------------------

    /** The weather's kinds; the shader paints each into the scene. */
    const val TYPE_NONE = 0
    const val TYPE_CLOUDS = 1
    const val TYPE_RAIN = 2
    const val TYPE_SNOW = 3
    const val TYPE_FOG = 4

    /**
     * The weather as Living Sky shows it: the effect ([type], [intensity] 0..1), how much of the
     * sky it covers ([cover]: no stars, sun or moon through it), how grey ([grey]) and dark ([dark])
     * it makes the light, and lightning ([storm]).
     */
    data class Weather(
        val type: Int,
        val intensity: Float,
        val cover: Float,
        val grey: Float,
        val dark: Float,
        val storm: Boolean = false,
    )

    val CLEAR = Weather(TYPE_NONE, 0f, 0f, 0f, 0f)

    /** From a Yahoo-style condition code (LockWeather.Now.code; any provider maps to 0..47). */
    fun weatherFor(code: Int): Weather =
        when (code) {
            0, 1, 2, 3, 4 -> Weather(TYPE_RAIN, 1f, 1f, 0.85f, 0.42f, storm = true)
            37, 38, 39, 45, 47 -> Weather(TYPE_RAIN, 0.75f, 0.95f, 0.8f, 0.35f, storm = true)
            5, 6, 18, 35 -> Weather(TYPE_RAIN, 0.6f, 0.95f, 0.7f, 0.25f)
            17 -> Weather(TYPE_RAIN, 0.8f, 0.95f, 0.75f, 0.3f)
            7 -> Weather(TYPE_SNOW, 0.6f, 0.9f, 0.55f, 0.12f)
            8, 9 -> Weather(TYPE_RAIN, 0.35f, 0.9f, 0.6f, 0.2f)
            10, 11, 12 -> Weather(TYPE_RAIN, 0.75f, 0.95f, 0.7f, 0.28f)
            40 -> Weather(TYPE_RAIN, 0.5f, 0.85f, 0.6f, 0.22f)
            13, 14 -> Weather(TYPE_SNOW, 0.45f, 0.85f, 0.5f, 0.1f)
            15, 16, 46 -> Weather(TYPE_SNOW, 0.75f, 0.9f, 0.55f, 0.12f)
            41, 43 -> Weather(TYPE_SNOW, 1f, 0.95f, 0.6f, 0.15f)
            42 -> Weather(TYPE_SNOW, 0.5f, 0.85f, 0.5f, 0.1f)
            19, 21, 22 -> Weather(TYPE_FOG, 0.45f, 0.5f, 0.55f, 0.08f)
            20 -> Weather(TYPE_FOG, 0.85f, 0.6f, 0.8f, 0.1f)
            23, 24 -> Weather(TYPE_CLOUDS, 0.35f, 0.35f, 0.15f, 0f)
            26 -> Weather(TYPE_CLOUDS, 0.95f, 0.8f, 0.5f, 0.12f)
            27, 28 -> Weather(TYPE_CLOUDS, 0.75f, 0.65f, 0.38f, 0.08f)
            29, 30, 44 -> Weather(TYPE_CLOUDS, 0.45f, 0.4f, 0.2f, 0.03f)
            33, 34 -> Weather(TYPE_CLOUDS, 0.2f, 0.15f, 0.05f, 0f)
            else -> CLEAR
        }

    // ---- the photo -----------------------------------------------------------------------------

    const val REASON_OK = 0
    /** Not enough sky in the upper half of the screen. */
    const val REASON_NO_SKY = 1
    /**
     * A sky, but already a night one. IchthysOS: no longer a refusal: such a picture has a sky
     * ([SkyInfo.darkSky]) and is relit by day by the shader's day path (see [SHADER]); the
     * constant stays for old callers and saved looks.
     */
    const val REASON_DARK = 2
    /** No sky mask yet (IchthysDepth is making it), or a live wallpaper. */
    const val REASON_UNKNOWN = 3

    /**
     * What Living Sky needs to know about one picture as the lock screen shows it: whether it can
     * ([hasSky], else [reason]), the sky's [share] of the screen's upper half, where it ends
     * ([horizon], 0..1 of the height, averaged over the columns), its mean linear luma
     * ([skyLuma]), and a clear patch of sky for the moon ([moonX], [moonY], fractions; -1: none).
     * IchthysOS, the picture's own time and kind:
     * - [darkSky]: the sky is a night one (mean luma under [DARK_SKY_LUMA]); by day the shader
     *   cross-fades it to a clean sky of the grade instead of multiplying its noise up.
     * - [flatSky]: a flat gradient (a render's sky, a poster), no clouds: only a tint shift toward
     *   the grade, never a texture replacement, in either path.
     * - [landLuma]: the mean linear luma of what isn't sky, for the day path's lift of the land.
     */
    class SkyInfo(
        val hasSky: Boolean,
        val reason: Int,
        val share: Float,
        val horizon: Float,
        val skyLuma: Float,
        val moonX: Float,
        val moonY: Float,
        val darkSky: Boolean = false,
        val flatSky: Boolean = false,
        val landLuma: Float = 0.1f,
    ) {
        /** This, with [darkSky] as [KEY_DEBUG]'s third field says (-1: as it is). */
        fun withDarkOverride(override: Int): SkyInfo =
            when (override) {
                0, 1 -> SkyInfo(hasSky, reason, share, horizon, skyLuma, moonX, moonY,
                    override == 1, flatSky, landLuma)
                else -> this
            }

        companion object {
            val UNKNOWN = SkyInfo(false, REASON_UNKNOWN, 0f, 0.5f, 0.2f, -1f, -1f)
        }
    }

    /**
     * [SkyInfo] of [picture] and its [sky] mask (alpha = sky), both cut to the screen (any sizes,
     * any configs). Small and quick: they are looked at at [GRID_W] x [GRID_H].
     */
    fun analyse(picture: Bitmap, sky: Bitmap): SkyInfo {
        val px = pixels(picture)
        val sk = pixels(sky)
        val w = GRID_W
        val h = GRID_H
        var upper = 0f
        var lumaSum = 0f
        var lumaSq = 0f
        var lumaN = 0f
        var landSum = 0f
        var landN = 0f
        val skyL = FloatArray(w * h) { -1f }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = (sk[i] ushr 24) / 255f
            if (y < h / 2) upper += a
            val c = px[i]
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            val l = 0.2126f * r * r + 0.7152f * g * g + 0.0722f * b * b
            if (a > 0.5f) {
                lumaSum += a * l
                lumaSq += a * l * l
                lumaN += a
                skyL[i] = l
            } else if (a < 0.5f) {
                // IchthysOS: the land's mean, for the day relight of a night picture.
                landSum += (1f - a) * l
                landN += 1f - a
            }
        }
        val share = upper / (w * (h / 2))
        val skyLuma = if (lumaN > 0f) lumaSum / lumaN else 0f
        val landLuma = if (landN > 0f) landSum / landN else 0.1f
        // IchthysOS: a flat sky (a render's gradient, a poster): little spread about its mean and
        // nothing standing out bright (a cloud); the spread is measured over the sky's own mean,
        // so a dark gradient counts as flat as a bright one.
        var cloudN = 0
        for (l in skyL) if (l >= 0f && l > skyLuma * 1.4f) cloudN++
        val variance = if (lumaN > 0f) max(0f, lumaSq / lumaN - skyLuma * skyLuma) else 0f
        val spread = if (skyLuma > 1e-4f) kotlin.math.sqrt(variance) / skyLuma else 0f
        val flat = lumaN > 0f && spread < FLAT_SKY_SPREAD && cloudN < lumaN * FLAT_SKY_CLOUDS
        val dark = lumaN > 0f && skyLuma < DARK_SKY_LUMA
        // The sky's bottom, column by column: the lowest row of the sky that reaches down from
        // the top (sky below a tree is not the horizon).
        var hz = 0f
        var hzN = 0
        for (x in 0 until w) {
            var y = 0
            while (y < h && (sk[y * w + x] ushr 24) > 128) y++
            if (y > 0) {
                hz += y.toFloat() / h
                hzN++
            }
        }
        val horizon = if (hzN > 0) (hz / hzN).coerceIn(0.12f, 1f) else 0.5f
        // IchthysOS: a night sky is a sky too (darkSky): by day the shader relights it instead of
        // brightening its noise (REASON_DARK is history). Pictures with no sky stay refused.
        val reason = if (share < MIN_SHARE) REASON_NO_SKY else REASON_OK
        // The moon: the clear spot nearest its usual place (upper right), all sky around it.
        var moonX = -1f
        var moonY = -1f
        var best = Float.MAX_VALUE
        val rad = 4
        for (y in (h * 0.07f).toInt()..(h * 0.42f).toInt()) for (x in rad until w - rad) {
            var clear = true
            loop@ for (dy in -rad..rad) for (dx in -rad..rad) {
                val yy = y + dy
                if (yy < 0 || yy >= h || (sk[yy * w + x + dx] ushr 24) < 230) {
                    clear = false
                    break@loop
                }
            }
            if (!clear) continue
            val fx = (x + 0.5f) / w
            val fy = (y + 0.5f) / h
            // IchthysOS: the top-right corner, clear of a centred clock (it sat beside the digits).
            val d = (fx - 0.86f) * (fx - 0.86f) + (fy - 0.1f) * (fy - 0.1f) * 0.5f
            if (d < best) {
                best = d
                moonX = fx
                moonY = fy
            }
        }
        return SkyInfo(reason == REASON_OK, reason, share, horizon, max(skyLuma, 0.004f), moonX, moonY,
            darkSky = dark, flatSky = flat, landLuma = max(landLuma, 0.004f))
    }

    private fun pixels(b: Bitmap): IntArray {
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val small = Bitmap.createScaledBitmap(soft, GRID_W, GRID_H, true)
        val px = IntArray(GRID_W * GRID_H)
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        if (small !== soft) small.recycle()
        if (soft !== b) soft.recycle()
        return px
    }

    // ---- the grade -----------------------------------------------------------------------------

    /**
     * The light at one moment: the sky's palette ([top] to [hor]izon, [cloudLight] for clouds),
     * the land's [light] tint and [exposure], [nightness] (land desaturated to moonlight),
     * [stars], how far the sky moves to the palette ([skyAmount]), the sun's glow ([sunGlow],
     * [sunColor], at [sunX], [sunY] as fractions of the screen) and the moon ([moonVis],
     * [moonPhase]). Colours are linear (sRGB squared, like the shader). IchthysOS: [dayness]
     * (0 night .. 1 the sun well up) is how far a night picture's sky is relit by day.
     */
    class Grade(
        val top: FloatArray,
        val hor: FloatArray,
        val cloudLight: FloatArray,
        val light: FloatArray,
        val exposure: Float,
        val nightness: Float,
        val stars: Float,
        val skyAmount: Float,
        val sunGlow: Float,
        val sunColor: FloatArray,
        val sunX: Float,
        val sunY: Float,
        val moonVis: Float,
        val moonPhase: Float,
        val dayness: Float = 0f,
    )

    /**
     * IchthysOS: how far [info]'s sky is cross-faded to the grade's clean sky by day (the shader's
     * `darkDay`): only a night sky, from civil twilight on, [DARK_SKY_AMOUNT] of the way; a flat
     * one only a tint's worth. 0 for a day picture (its own path) and at night (unchanged).
     */
    fun darkDay(grade: Grade, info: SkyInfo): Float =
        if (!info.darkSky) 0f else grade.dayness * DARK_SKY_AMOUNT * flatAmount(info)

    /** IchthysOS: a flat gradient sky moves only this share of the way to any palette. */
    fun flatAmount(info: SkyInfo): Float = if (info.flatSky) FLAT_SKY_AMOUNT else 1f

    /**
     * IchthysOS: the gain the day path lifts a night picture's land by: to a daylight mean
     * ([DAY_LAND_LUMA]) from its own, at most [MAX_LAND_LIFT] (a black picture stays dim rather
     * than turn to noise). 1 when nothing is relit.
     */
    fun landLift(grade: Grade, info: SkyInfo): Float =
        if (darkDay(grade, info) <= 0f) 1f
        else (DAY_LAND_LUMA / max(info.landLuma, 0.004f)).coerceIn(1f, MAX_LAND_LIFT)

    /**
     * The grade for [sun] under [weather] (null: none shown), on a picture whose sky ends at
     * [horizon]. Smooth in the elevation: the palette is interpolated between [KEYS].
     */
    fun grade(sun: Sun, weather: Weather?, horizon: Float, moonPhase: Float): Grade {
        val e = sun.elevation
        val wx = weather ?: CLEAR
        val k = keyAt(e)
        var top = k[0]
        var hor = k[1]
        var light = k[2]
        var exposure = k[3][0]
        // Overcast: the sky greys toward a cloud tone of the same light (dark at night), the
        // light flattens, everything dims.
        val dayLight = mix(0.12f, 1f, smoothstep(-10f, 2f, e))
        val overcast = mixC(floatArrayOf(120f, 128f, 140f), hor, 0.35f).map { it * dayLight }.toFloatArray()
        top = mixC(top, mixC(overcast, floatArrayOf(20f, 24f, 32f), smoothstep(-2f, -8f, e) * 0.8f), wx.grey)
        hor = mixC(hor, overcast, wx.grey * 0.9f)
        val grey = (light[0] * 0.3f + light[1] * 0.59f + light[2] * 0.11f)
        light = mixC(light, floatArrayOf(grey, grey, grey), wx.grey * 0.6f)
        // Heavy cloud: grey-blue light whatever the hour (a storm at sunset isn't brown).
        val heavy = smoothstep(0.55f, 1f, wx.cover)
        top = slate(top, heavy * 0.8f)
        hor = slate(hor, heavy * 0.8f)
        light = slate(light, heavy * 0.7f)
        val dim = 1f - wx.dark
        top = top.map { it * dim }.toFloatArray()
        hor = hor.map { it * dim }.toFloatArray()
        exposure *= dim

        val topL = lin(top)
        val horL = lin(hor)
        val lightL = lin(light).let { c ->
            val m = max(c[0], max(c[1], c[2])).coerceAtLeast(1e-4f)
            floatArrayOf(c[0] / m, c[1] / m, c[2] / m)
        }
        val expL = exposure * exposure
        val cloudL = FloatArray(3) { (horL[it] + (lightL[it] * expL - horL[it]) * 0.55f) }
        val clear = 1f - wx.cover
        val glow =
            smoothstep(-4f, 0f, e) * (0.32f + 0.25f * (1f - smoothstep(4f, 16f, e))) *
                (1f - wx.cover * 0.85f)
        val dayF = sun.dayFraction.coerceIn(0f, 1f)
        // IchthysOS: the sun rides high (above the clock by midday), from the horizon at
        // sunrise and sunset to 8% of the screen at its noon height.
        val sunY = horizon - (e / max(sun.noon, 5f)).coerceIn(-0.2f, 1f) * (horizon - 0.08f)
        return Grade(
            top = topL,
            hor = horL,
            cloudLight = cloudL,
            light = lightL,
            exposure = expL,
            nightness = smoothstep(-2f, -10f, e),
            stars = ((-e - 6f) / 8f).coerceIn(0f, 1f) * clear,
            skyAmount = mix(1f, DAY_SKY_AMOUNT, smoothstep(4f, 14f, e)),
            sunGlow = glow,
            sunColor = lin(mixC(light, floatArrayOf(255f, 255f, 255f), 0.35f)),
            sunX = 0.15f + 0.7f * dayF,
            sunY = sunY,
            moonVis = smoothstep(-4f, -10f, e) * clear,
            moonPhase = moonPhase,
            // IchthysOS: from civil twilight (-6°) to a sun clear of the horizon (2°): the night
            // path below, the relit night picture above, a cross-fade between.
            dayness = smoothstep(-6f, 2f, e),
        )
    }

    /**
     * IchthysOS: about how bright (linear luma) the graded sky is behind the lock screen clock,
     * for the Depth face's contrast (ClockBackdrop); [intensity] as the grade's. With [info], a
     * night picture's sky follows its day relight: as dark as the photo at night, the grade's
     * brightness by day, [darkDay] of the way between; a flat sky moves only a tint's worth.
     */
    fun brightnessBehindClock(grade: Grade, intensity: Float, info: SkyInfo? = null): Float {
        fun l(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        val k = intensity.coerceIn(0f, 1f)
        val graded = (l(grade.top) * 0.6f + l(grade.hor) * 0.4f)
        if (info == null || !info.darkSky) {
            val sky = if (info != null) mix(info.skyLuma, graded, grade.skyAmount * flatAmount(info)) else graded
            return sky * k + (1f - k) * 0.3f
        }
        val sky = mix(info.skyLuma, graded, darkDay(grade, info))
        return sky * k + (1f - k) * info.skyLuma
    }

    /** top, horizon, light (sRGB 0..255) and exposure at elevation [e]. */
    private fun keyAt(e: Float): Array<FloatArray> {
        if (e <= KEYS[0][0]) return entry(KEYS[0])
        for (i in 1 until KEYS.size) {
            val b = KEYS[i]
            if (e <= b[0]) {
                val a = KEYS[i - 1]
                val t = (e - a[0]) / (b[0] - a[0])
                val ea = entry(a)
                val eb = entry(b)
                return Array(4) { mixC(ea[it], eb[it], t) }
            }
        }
        return entry(KEYS.last())
    }

    private fun entry(k: FloatArray): Array<FloatArray> =
        arrayOf(k.copyOfRange(1, 4), k.copyOfRange(4, 7), k.copyOfRange(7, 10), floatArrayOf(k[10]))

    /**
     * Sun elevation (degrees), sky top, horizon, light on the land (sRGB) and the land's exposure
     * (an sRGB factor): night, astronomical, nautical and civil twilight, golden hour, day.
     */
    private val KEYS = arrayOf(
        floatArrayOf(-40f, 3f, 5f, 14f, 10f, 14f, 32f, 40f, 50f, 80f, 0.24f),
        floatArrayOf(-12f, 8f, 12f, 34f, 32f, 34f, 74f, 60f, 66f, 110f, 0.27f),
        floatArrayOf(-6f, 24f, 32f, 78f, 190f, 92f, 84f, 150f, 110f, 130f, 0.4f),
        floatArrayOf(-1f, 44f, 66f, 130f, 255f, 140f, 70f, 255f, 160f, 110f, 0.64f),
        floatArrayOf(4f, 70f, 120f, 190f, 255f, 196f, 130f, 255f, 210f, 160f, 0.86f),
        floatArrayOf(12f, 74f, 142f, 220f, 190f, 220f, 245f, 255f, 244f, 225f, 1f),
        floatArrayOf(21f, 58f, 136f, 228f, 175f, 214f, 246f, 255f, 252f, 240f, 1f),
    )

    // ---- drawing -------------------------------------------------------------------------------

    /** No subject: a transparent shader for the subject mask input. */
    private val NO_SUBJECT: Shader by lazy {
        BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8),
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    /** [bitmap] stretched over a [w] x [h] surface, smoothly sampled: the shader's inputs. */
    fun fitted(bitmap: Bitmap, w: Int, h: Int): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
            })
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }

    /**
     * Draws the graded picture over a [w] x [h] surface (screen pixels; scale the canvas for a
     * smaller preview). One per surface; main thread.
     */
    class Painter {
        private val shader = RuntimeShader(SHADER)
        private val paint = Paint().also { it.shader = shader }
        private val maskPaint =
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
        private val rect = RectF()

        /**
         * The uniforms: [picture] (the photo, or the weather layer over it) and [sky] (its mask)
         * as shaders over the surface ([fitted]), [info] and [grade] for them, [intensity] 0..1,
         * [time] (seconds, the stars' twinkle) and [flash] (lightning, 0..1). IchthysOS:
         * [darkOverride] is [KEY_DEBUG]'s third field (see [SkyInfo.withDarkOverride]).
         */
        fun bind(
            picture: Shader,
            sky: Shader,
            info: SkyInfo,
            grade: Grade,
            intensity: Float,
            w: Int,
            h: Int,
            density: Float,
            time: Float,
            flash: Float,
            weather: Weather? = null,
            subject: Shader? = null,
            darkOverride: Int = -1,
        ) {
            val inf = info.withDarkOverride(darkOverride)
            // IchthysOS: a night picture by day (the shader's day path), and a flat gradient sky
            // that only takes a tint of the palette.
            shader.setFloatUniform("darkDay", darkDay(grade, inf))
            shader.setFloatUniform("lift", landLift(grade, inf))
            shader.setInputShader("picture", picture)
            shader.setInputShader("skyMask", sky)
            shader.setInputShader("subjectMask", subject ?: NO_SUBJECT)
            // IchthysOS: the weather is painted into the scene here (clouds in the photo's own
            // sky, rain / snow / fog at their depth), not laid over it.
            val wx = weather ?: CLEAR
            shader.setFloatUniform("cover", wx.cover)
            shader.setFloatUniform("rain", if (wx.type == TYPE_RAIN) wx.intensity else 0f)
            shader.setFloatUniform("snow", if (wx.type == TYPE_SNOW) wx.intensity else 0f)
            shader.setFloatUniform("fogAmt",
                when (wx.type) {
                    TYPE_FOG -> wx.intensity
                    TYPE_RAIN -> 0.3f * wx.intensity
                    TYPE_SNOW -> 0.25f * wx.intensity
                    else -> 0f
                })
            shader.setFloatUniform("storm", if (wx.storm) 1f else 0f)
            shader.setFloatUniform("size", w.toFloat(), h.toFloat())
            shader.setFloatUniform("density", density)
            shader.setFloatUniform("amount", intensity.coerceIn(0f, 1f))
            shader.setFloatUniform("skyAmount", grade.skyAmount * flatAmount(inf))
            shader.setFloatUniform("horizon", info.horizon)
            shader.setFloatUniform("skyLuma", info.skyLuma)
            shader.setFloatUniform("top", grade.top)
            shader.setFloatUniform("hor", grade.hor)
            shader.setFloatUniform("cloudLight", grade.cloudLight)
            shader.setFloatUniform("light", grade.light)
            shader.setFloatUniform("exposure", grade.exposure)
            shader.setFloatUniform("nightness", grade.nightness)
            shader.setFloatUniform("sunColor", grade.sunColor)
            shader.setFloatUniform("sunPos", grade.sunX * w, grade.sunY * h)
            shader.setFloatUniform("sunGlow", grade.sunGlow)
            val moon = info.moonX >= 0f
            shader.setFloatUniform("moonPos",
                if (moon) info.moonX * w else -1000f, if (moon) info.moonY * h else -1000f)
            shader.setFloatUniform("moonPhase", grade.moonPhase)
            shader.setFloatUniform("moonVis", if (moon) grade.moonVis else 0f)
            shader.setFloatUniform("stars", grade.stars)
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("flash", flash.coerceIn(0f, 1f))
        }

        /** The whole surface. */
        fun draw(canvas: Canvas, w: Int, h: Int) {
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }

        /** Only where [mask] (alpha, stretched over the surface) is: the subject over the clock. */
        fun drawMasked(canvas: Canvas, w: Int, h: Int, mask: Bitmap) {
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            val save = canvas.saveLayer(rect, null)
            canvas.drawRect(rect, paint)
            canvas.drawBitmap(mask, null, rect, maskPaint)
            canvas.restoreToCount(save)
        }
    }

    /**
     * The grade, in screen pixels. Colours are worked on squared (close to linear light) and
     * written back with a little dither, so night gradients don't band.
     * - Sky (skyMask's alpha): the palette's gradient from the top to the picture's horizon, times
     *   the photo's own light relative to its mean sky (its clouds and glow stay; clouds take the
     *   cloud light, not the sky's blue); stars (two grids of hashed cells), the moon (its phase
     *   from a terminator ellipse) and the sun's disc only here, all hidden by clouds.
     * - Land: the photo times the light's tint and exposure, desaturated toward blue moonlight at
     *   night, with a breath of the sky's colour in its shadows.
     * - The sun's glow over both (less on the land), lightning, then [amount] of all of it.
     *
     * IchthysOS, a night picture by day (`darkDay` > 0, see [darkDay]): the reverse of the night
     * grade. Multiplying a night sky up to a day one (the sky path above, `pow(rel, 0.6)`) shows
     * the sensor's noise and its stars, so the sky is instead cross-faded to a clean sky of the
     * grade, and the land is lifted through a filmic curve. The night path itself is untouched:
     * at `darkDay` 0 every line below is what it was.
     * - Stars go first: an erosion (the darkest of five taps in a 2 dp cross) removes specks a
     *   few px wide; clouds and the horizon's glow are far wider and survive it.
     * - The photo's own light relative to its mean sky (`relD`) is compressed hard, `pow(0.3)`:
     *   noise (rel 0.7..1.4) becomes +-10 %, a moonlit cloud (rel 3) stays +40 %. That is the
     *   luma-preserving part: the generated sky takes the photo's relative structure as its
     *   shading, and nothing of its absolute (dark) level.
     * - Bright structure takes the cloud light, as a day sky's clouds do; a flat gradient sky has
     *   none and is only tinted (`skyAmount`, `darkDay` scaled down by [FLAT_SKY_AMOUNT]).
     * - Silhouettes stay crisp because the edge is the sky MASK's, not the texture's: the land and
     *   the sky are mixed by `s` exactly as before, and the land's lift is monotonic (a ridge
     *   darker than the sky stays darker than the sky: no light rim, no halo).
     * - Land: `lift` ([landLift]) brings its mean to a daylight level, through a Reinhard curve
     *   with a white point (a toe that keeps black black, a shoulder that never clips), its
     *   darkest parts smoothed with the five taps' mean (their noise would be lifted the most),
     *   then a touch of desaturation in the light's own colour and some airlight from the
     *   horizon: the inverse of the night path's blue moonlight.
     */
    const val SHADER = """
uniform shader picture;
uniform shader skyMask;
uniform shader subjectMask;
uniform float2 size;
uniform float density;
uniform float amount;
uniform float skyAmount;
uniform float horizon;
uniform float skyLuma;
uniform float3 top;
uniform float3 hor;
uniform float3 cloudLight;
uniform float3 light;
uniform float exposure;
uniform float nightness;
uniform float3 sunColor;
uniform float2 sunPos;
uniform float sunGlow;
uniform float2 moonPos;
uniform float moonPhase;
uniform float moonVis;
uniform float stars;
uniform float time;
uniform float flash;
uniform float cover;
uniform float rain;
uniform float snow;
uniform float fogAmt;
uniform float storm;
// IchthysOS: a night picture by day: how far its sky is cross-faded to the grade's clean sky
// (0: the night path, untouched) and the gain its land is lifted by (1: none).
uniform float darkDay;
uniform float lift;

float luma(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

float hash(float2 p) {
    float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float vnoise(float2 x) {
    float2 i = floor(x);
    float2 f = fract(x);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 x) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(x);
        x = x * 2.07 + float2(17.1, 9.2);
        a *= 0.5;
    }
    return v / 0.9375;
}

float star(float2 p, float cell, float keep, float radius) {
    float2 g = floor(p / cell);
    float h = hash(g);
    if (h < keep) {
        return 0.0;
    }
    float2 o = float2(hash(g + 17.17), hash(g + 41.41));
    float2 c = (g + 0.2 + 0.6 * o) * cell;
    float d = length(p - c);
    float b = (h - keep) / (1.0 - keep);
    float twinkle = 0.72 + 0.28 * sin(time * (0.8 + 1.7 * o.x) + o.y * 37.0);
    return (1.0 - smoothstep(0.0, radius, d)) * (0.3 + 0.7 * b) * twinkle;
}

// How far away a pixel is, 0 (at the lens) .. 1 (the sky): land gets nearer toward the bottom of
// the frame, land standing up into the sky's band (ridges, tree lines) is far, the subject (the
// depth cut-out) is nearest.
float depthAt(float2 p, float s, float subj) {
    float hy = horizon * size.y;
    float t = clamp((p.y - hy) / max(size.y - hy, 1.0), 0.0, 1.0);
    float land = mix(0.82, 0.06, pow(t, 0.75));
    if (p.y < hy) {
        land = mix(0.95, 0.84, clamp(p.y / max(hy, 1.0), 0.0, 1.0));
    }
    return mix(mix(land, 1.0, s), 0.04, subj);
}

// One layer of rain at [scale] (cell size, dp): thin slanted streaks falling at [speed].
float rainLayer(float2 p, float scale, float speed, float seed) {
    float2 q = p / (density * scale);
    q.x += q.y * 0.12;
    float col = floor(q.x);
    float r = hash(float2(col, seed));
    if (r < 0.35) {
        return 0.0;
    }
    float y = q.y * 0.07 - time * speed * (0.75 + 0.5 * r) + r * 13.0;
    float f = fract(y);
    float streak = smoothstep(0.0, 0.03, f) * (1.0 - smoothstep(0.03, 0.22, f));
    float x = abs(fract(q.x) - 0.5 - (r - 0.5) * 0.5);
    return streak * (1.0 - smoothstep(0.015, 0.06, x)) * (0.5 + 0.5 * r);
}

// One layer of snow at [scale] (cell size, dp): soft flakes drifting down at [speed].
float snowLayer(float2 p, float scale, float speed, float seed, float soft) {
    float2 q = p / (density * scale);
    q.y -= time * speed;
    q.x += sin(q.y * 0.4 + seed + time * 0.3) * 0.35;
    float2 g = floor(q);
    float r = hash(g + seed);
    if (r < 0.68) {
        return 0.0;
    }
    float2 c = float2(hash(g + seed + 3.1), hash(g + seed + 7.7)) - 0.5;
    float d = length(fract(q) - 0.5 - c * 0.55);
    float rad = 0.025 + 0.035 * hash(g + seed + 11.3);
    return (1.0 - smoothstep(rad, rad + soft, d)) * (0.55 + 0.45 * r);
}

half4 main(float2 p) {
    float4 src = float4(picture.eval(p));
    float alpha = src.a;
    float3 c = alpha > 0.0001 ? src.rgb / alpha : float3(0.0);
    float s = float(skyMask.eval(p).a);
    float subj = float(subjectMask.eval(p).a);
    float depth = depthAt(p, s, subj);
    float3 lin = c * c;
    float L = luma(lin);
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float sat = mx > 0.001 ? (mx - mn) / mx : 0.0;

    float v = clamp(p.y / max(horizon * size.y, 1.0), 0.0, 1.0);
    float3 grad = mix(top, hor, smoothstep(0.0, 1.0, pow(v, 1.3)));
    float rel = clamp(L / max(skyLuma, 0.004), 0.1, 3.0);

    // IchthysOS: a night picture by day (see the comment above main): five taps in a 2 dp cross,
    // their darkest for the sky (an erosion: the stars go), their mean for the land's shadows.
    float3 tapMin = lin;
    float3 tapMean = lin;
    float relD = rel;
    if (darkDay > 0.0) {
        float r = 2.2 * density;
        float3 c1 = float3(picture.eval(p + float2(r, 0.0)).rgb);
        float3 c2 = float3(picture.eval(p - float2(r, 0.0)).rgb);
        float3 c3 = float3(picture.eval(p + float2(0.0, r)).rgb);
        float3 c4 = float3(picture.eval(p - float2(0.0, r)).rgb);
        float3 mn = min(min(c, c1), min(min(c2, c3), c4));
        tapMin = mn * mn;
        float3 mean = (c + c1 + c2 + c3 + c4) * 0.2;
        tapMean = mean * mean;
        relD = clamp(luma(tapMin) / max(skyLuma, 0.002), 0.1, 4.0);
        // The clouds and the overcast below read the denoised ratio by day.
        rel = mix(rel, relD, darkDay);
    }

    float cloud = smoothstep(1.08, 1.6, rel) * (1.0 - smoothstep(0.12, 0.35, sat));
    float3 skyNew = mix(grad, cloudLight, cloud) * pow(rel, 0.6);
    float3 skyOut = mix(lin, skyNew, skyAmount);
    if (darkDay > 0.0 && s > 0.001) {
        // The clean sky of the grade, shaded by the photo's own (compressed) structure; what
        // stands out bright in it is cloud and takes the cloud light. Cross-faded over the night
        // result, so a twilight picture moves smoothly between the two.
        float structure = pow(relD, 0.3);
        float cloudD = smoothstep(1.6, 3.0, relD);
        float3 skyDay = mix(grad, cloudLight, cloudD) * structure;
        skyOut = mix(skyOut, skyDay, darkDay);
    }

    // The weather's clouds, as a grade of the photo's own sky: no painted cloud shapes (noise
    // over a real photo never stopped looking stuck on). Cover pulls the sky toward the cloud
    // light and flattens it, keeping the photo's own structure (its clouds, glow and texture,
    // relative to its mean sky) as the overcast's light and shade; storms darken it.
    float deck = 0.0;
    if (cover > 0.01 && s > 0.01) {
        deck = smoothstep(0.0, 1.0, cover) * 0.88;
        float shade = clamp(pow(rel, 0.55), 0.7, 1.35);
        float3 overcast = cloudLight * shade * (1.0 - 0.45 * storm);
        // Heavy cloud is grey whatever the hour: only thin cover keeps the warm light.
        float heavy = smoothstep(0.55, 1.0, cover);
        overcast = mix(overcast, luma(overcast) * float3(0.94, 0.98, 1.06), heavy * 0.75);
        overcast += float3(0.85, 0.89, 1.0) * (flash * 1.2 * shade);
        skyOut = mix(skyOut, overcast, deck);
    }

    float3 land = lin * light * exposure;
    if (darkDay > 0.0 && s < 0.999) {
        // The land of a night picture by day: its darkest parts smoothed (their noise would be
        // lifted the most), lifted by the gain to a daylight mean through a Reinhard curve with
        // a white point w (y(0) = 0 with unit slope, y(w) = 1, never above): no clipping. Then a
        // touch of desaturation in the light's own colour and some airlight from the horizon,
        // the day's haze: the inverse of the night path's blue moonlight.
        float3 base = mix(lin, tapMean, 0.45 * (1.0 - smoothstep(0.01, 0.08, L)));
        float3 x = base * light * exposure * lift;
        float w = 1.6;
        float3 y = x * (1.0 + x / (w * w)) / (1.0 + x);
        y = mix(y, luma(y) * light, 0.18);
        y += hor * 0.03;
        land = mix(land, y, darkDay);
    }
    float ll = luma(land);
    land = mix(land, ll * float3(0.72, 0.84, 1.3), nightness * 0.55);
    land += hor * 0.025;
    // Clouds passing over the land on a broken-cloud day.
    float broken = cover * (1.0 - cover) * 4.0;
    if (broken > 0.05 && s < 0.99) {
        float2 su = float2(p.x / size.x * 1.6 + time * 0.006, p.y / size.y * 2.2);
        float sh = smoothstep(0.45, 0.7, fbm(su));
        land *= 1.0 - sh * broken * 0.3 * clamp(exposure * 1.5, 0.0, 1.0);
    }
    // Wet ground darkens in the rain; snow lifts pale, flat surfaces a little.
    land = mix(land, land * 0.72, rain * 0.6);
    land = mix(land, mix(land, float3(ll * 1.25 + 0.03), 0.35), snow * (1.0 - sat));

    float3 o = mix(land, skyOut, s);
    float clearSky = (1.0 - cloud) * (1.0 - deck);

    if (sunGlow > 0.0) {
        // A sun, not a flat disc: a wide soft bloom, a tight halo and a small hot core, all
        // veiled by cloud.
        float d = length(p - sunPos) / size.x;
        float veil = 1.0 - 0.8 * deck;
        o += sunColor * (exp(-3.2 * d) * sunGlow * mix(0.3, 1.0, s) * veil);
        o += sunColor * (exp(-28.0 * d) * sunGlow * 0.9 * s * clearSky);
        float core = 1.0 - smoothstep(0.004, 0.016, d);
        o += (sunColor + 0.35) * (core * s * clearSky * min(sunGlow * 2.5, 1.0) * 1.4);
    }

    if (stars > 0.0) {
        float st = star(p, 22.0 * density, 0.9, 1.1 * density) +
            star(p + 911.0, 57.0 * density, 0.82, 1.7 * density);
        o += float3(0.92, 0.95, 1.0) * (st * stars * s * clearSky * (1.0 - smoothstep(0.55, 1.0, v)));
    }

    if (moonVis > 0.0) {
        float r = 13.0 * density;
        float2 m = (p - moonPos) / r;
        float d = length(m);
        if (d < 5.0) {
            float disc = 1.0 - smoothstep(1.0 - 1.2 / r, 1.0 + 1.2 / r, d);
            float yy = sqrt(max(0.0, 1.0 - m.y * m.y));
            float edge = cos(6.2831853 * moonPhase) * yy;
            float lit = moonPhase < 0.5 ? smoothstep(edge - 0.08, edge + 0.08, m.x)
                : 1.0 - smoothstep(-edge - 0.08, -edge + 0.08, m.x);
            float full = 1.0 - abs(2.0 * moonPhase - 1.0);
            float halo = exp(-1.4 * max(d - 1.0, 0.0)) * 0.06 * (0.25 + 0.75 * full);
            float k = moonVis * s * clearSky;
            o = mix(o, float3(0.80, 0.81, 0.86) * mix(0.03, 1.0, lit), disc * k);
            o += float3(0.45, 0.5, 0.65) * (halo * (1.0 - disc) * k);
        }
    }

    // Fog (and the haze rain and snow bring): thicker with distance and low over the land.
    if (fogAmt > 0.0) {
        float3 fogCol = mix(hor, cloudLight, 0.5) * mix(0.6, 1.0, exposure);
        float low = 1.0 - 0.5 * smoothstep(0.0, 0.8, v) * s;
        float f = fogAmt * smoothstep(0.05, 1.0, depth) * low;
        o = mix(o, fogCol, clamp(f * 0.9, 0.0, 0.95));
    }

    // Rain and snow in three depths: a layer only shows over what is farther away than it, so a
    // near tree or the subject stands in front of the far rain.
    float3 dropCol = mix(cloudLight, float3(0.9), 0.4) * mix(0.35, 1.0, exposure);
    if (rain > 0.0) {
        float far = rainLayer(p, 3.0, 2.2, 1.0) * smoothstep(0.62, 0.7, depth) * 0.35;
        float mid = rainLayer(p + 77.0, 6.0, 2.9, 2.0) * smoothstep(0.3, 0.38, depth) * 0.5;
        float nea = rainLayer(p + 191.0, 12.0, 3.6, 3.0) * smoothstep(0.05, 0.1, depth) * 0.6;
        o += dropCol * ((far + mid + nea) * rain);
    }
    if (snow > 0.0) {
        float far = snowLayer(p, 8.0, 0.35, 1.0, 0.1) * smoothstep(0.62, 0.7, depth) * 0.45;
        float mid = snowLayer(p + 53.0, 14.0, 0.55, 2.0, 0.12) * smoothstep(0.3, 0.38, depth) * 0.6;
        float nea = snowLayer(p + 131.0, 24.0, 0.8, 3.0, 0.14) * smoothstep(0.05, 0.1, depth) * 0.65;
        o = mix(o, float3(0.92, 0.94, 0.97) * mix(0.45, 1.0, exposure), clamp((far + mid + nea) * snow, 0.0, 1.0));
    }

    o += float3(0.85, 0.89, 1.0) * (flash * mix(0.12, 0.35, s));
    o = mix(lin, o, amount);
    float3 outC = sqrt(max(o, float3(0.0)));
    outC = clamp(outC + (hash(p) - 0.5) / 255.0, 0.0, 1.0);
    return half4(half3(outC * alpha), half(alpha));
}
"""

    // ---- small maths ---------------------------------------------------------------------------

    private fun lin(c: FloatArray): FloatArray =
        FloatArray(3) { (c[it] / 255f).let { v -> v * v } }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** [c] (sRGB) moved [t] of the way to its own brightness in a cool grey. */
    private fun slate(c: FloatArray, t: Float): FloatArray {
        val l = c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f
        return mixC(c, floatArrayOf(l * 0.94f, l * 0.98f, l * 1.06f), t)
    }

    // Any length: the palette keys mix their one-value exposure entry through here too.
    private fun mixC(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    fun smoothstep(e0: Float, e1: Float, v: Float): Float {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val DEFAULT_LAT = 45.0
    /** At full day the sky moves this far to the palette (the photo is most likely a day one). */
    private const val DAY_SKY_AMOUNT = 0.6f
    /**
     * IchthysOS: by day a night picture's sky is cross-faded this far to the grade's clean sky:
     * nearly all of it (a night sky has nothing to keep but its clouds, which stay as shading).
     */
    const val DARK_SKY_AMOUNT = 0.92f
    /** IchthysOS: a flat gradient sky moves only this far to any palette (a tint, no texture). */
    const val FLAT_SKY_AMOUNT = 0.45f
    /** IchthysOS: the day path lifts a night picture's land to this mean linear luma ... */
    const val DAY_LAND_LUMA = 0.16f
    /** ... by at most this gain (sRGB: about 2.4x; more turns a black picture into noise). */
    const val MAX_LAND_LIFT = 6f
    private const val NEW_MOON_MS = 947_182_440_000L
    private const val SYNODIC_DAYS = 29.530588853
    private const val GRID_W = 72
    private const val GRID_H = 160
    /** Below this share of the screen's upper half as sky, no Living Sky. */
    const val MIN_SHARE = 0.12f
    /**
     * A sky darker than this (mean linear luma) is a night sky already. IchthysOS: history: since
     * the day relight ([DARK_SKY_LUMA]) such a sky is graded, not refused.
     */
    const val MIN_SKY_LUMA = 0.012f
    /**
     * IchthysOS: a sky whose mean linear luma is under this (sRGB about 48/255) is a night or
     * dusk one ([SkyInfo.darkSky]): dark enough that multiplying it up to a day sky would show
     * its noise, so the shader cross-fades it to a clean sky instead.
     */
    const val DARK_SKY_LUMA = 0.035f
    /** IchthysOS: a sky whose luma spread (standard deviation over its mean) is under this ... */
    const val FLAT_SKY_SPREAD = 0.14f
    /** ... with fewer bright (cloud) cells than this share is a flat gradient ([SkyInfo.flatSky]). */
    const val FLAT_SKY_CLOUDS = 0.02f
}
