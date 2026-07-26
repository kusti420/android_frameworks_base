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

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * IchthysOS custom lockscreen clock.
 *
 * Renders a distinct clock face selected by [Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE] and
 * tuned live by the auxiliary secure settings written by the crDroid Settings "Custom clock style"
 * picker (color mode, custom color, size scale, opacity, top margin).
 *
 * Clock faces are built programmatically (no XML resources) so the whole render path lives under the
 * keyguard package. Each face here is authored to visually match its counterpart preview layout in
 * crDroidSettings so the picker previews faithfully.
 *
 * When the style is 0 ("Default Clock") this view stays [GONE] and the stock AOSP keyguard clock is
 * shown instead (the picker leaves the stock clock/smartspace overlays enabled for style 0).
 */
class KeyguardCustomClockView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                rebuild()
            }
        }

    private var currentClock: View? = null

    init {
        isClickable = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val cr = context.contentResolver
        WATCHED_KEYS.forEach { key ->
            cr.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
        rebuild()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.contentResolver.unregisterContentObserver(observer)
    }

    private fun secureInt(key: String, def: Int): Int =
        Settings.Secure.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    private fun secureString(key: String, def: String): String =
        Settings.Secure.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT)
            ?: def

    private fun rebuild() {
        currentClock?.let { removeView(it) }
        currentClock = null

        val style =
            secureInt(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, STYLE_DEFAULT)
        android.util.Log.i("IchthysClock", "rebuild: style=$style width=$width height=$height")
        if (style == STYLE_DEFAULT) {
            visibility = GONE
            return
        }
        visibility = VISIBLE

        val clock = buildClock(style)
        applyTheme(clock)
        applyContainerAttributes(clock)

        val lp =
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = clockGravity(style)
            }
        addView(clock, lp)
        currentClock = clock
    }

    private fun applyContainerAttributes(clock: View) {
        val opacity = secureInt(KEY_OPACITY, 100).coerceIn(0, 100)
        val scale = secureInt(KEY_SIZE_SCALE, 100).coerceIn(50, 150) / 100f
        val marginTopPx = secureInt(KEY_MARGIN_TOP, DEFAULT_MARGIN_TOP)

        clock.alpha = opacity / 100f
        clock.scaleX = scale
        clock.scaleY = scale
        clock.pivotY = 0f
        clock.post { clock.pivotX = clock.width / 2f }

        setPadding(dp(24), dp(BASE_TOP_DP) + marginTopPx, dp(24), 0)
    }

    /**
     * Walks the built clock and applies the selected color mode. Views are tagged with role tokens
     * (e.g. "text1", "accent1", "nolineheight") joined by '|'.
     */
    private fun applyTheme(root: View) {
        val mode = secureString(KEY_COLOR_MODE, MODE_DEFAULT)
        val customColor = secureInt(KEY_CUSTOM_COLOR, Color.WHITE)
        val accent = accentColor()
        themeRecursive(root, mode, customColor, accent)
    }

    private fun themeRecursive(view: View, mode: String, customColor: Int, accent: Int) {
        if (view is TextView) {
            val roles = (view.tag as? String)?.split("|") ?: emptyList()
            val isAccent = roles.any { it.startsWith("accent") }
            val isText = roles.any { it.startsWith("text") }
            when (mode) {
                MODE_ACCENT -> if (isAccent || isText) view.setTextColor(accent)
                MODE_CUSTOM -> if (isText) view.setTextColor(customColor)
                // MODE_DEFAULT keeps the face's designed colors.
            }
            if (roles.contains("nolineheight")) {
                view.includeFontPadding = false
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                themeRecursive(view.getChildAt(i), mode, customColor, accent)
            }
        }
    }

    private fun accentColor(): Int =
        context.getColor(android.R.color.system_accent1_100)

    // ---------------------------------------------------------------------------------------------
    // Clock faces. Each maps to a picker index in crDroidSettings ClockUtils.CLOCK_LAYOUTS.
    // ---------------------------------------------------------------------------------------------

    private fun clockGravity(style: Int): Int =
        when (style) {
            STYLE_STACKED,
            STYLE_SAMMY,
            STYLE_OOS,
            STYLE_OOS2,
            STYLE_MIUI,
            STYLE_IDE,
            STYLE_STYLISH6,
            STYLE_LABEL -> Gravity.TOP or Gravity.START
            else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

    private fun buildClock(style: Int): View =
        when (style) {
            STYLE_OOS -> buildOos()
            STYLE_OOS2 -> buildOos2()
            STYLE_CENTER -> buildCenter()
            STYLE_SIMPLE -> buildMinimal()
            STYLE_MIUI -> buildMiui()
            STYLE_IDE -> buildIde()
            STYLE_MOTO -> buildMoto()
            STYLE_STYLISH6 -> buildStylish6()
            STYLE_A9 -> buildA9()
            STYLE_STACKED -> buildStacked()
            STYLE_ACCENT -> buildAccent()
            STYLE_LABEL -> buildLabel()
            STYLE_SCAPULA -> buildScapula()
            STYLE_BIG -> buildBig()
            STYLE_SAMMY -> buildSammy()
            STYLE_TALL -> buildTall()
            else -> buildBig() // graceful fallback for any not-yet-ported style
        }

    /** idx 3 "Center Clock" - clean centered HH:MM with accent date. */
    private fun buildCenter(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh:mm", "HH:mm", 60f, "sans-serif", white(), "text1|nolineheight")
            )
            addView(
                textClock("EEE, d MMM", "EEE, d MMM", 14f, "sans-serif-medium", accentColor(),
                    "accent1")
            )
        }

    /** idx 4 "Simple Clock" - thin minimal HH:MM with muted date. */
    private fun buildMinimal(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh:mm", "HH:mm", 56f, "sans-serif-thin", white(), "text1|nolineheight")
            )
            addView(
                textClock("MMM d", "MMM d", 13f, "sans-serif-light", white70(), "text2")
            )
        }

    /** idx 23 "Stacked Clock" - bold hour over thin accent minute, left aligned. */
    private fun buildStacked(): View =
        column(Gravity.START).apply {
            addView(
                textClock("hh", "HH", 78f, "sans-serif-black", white(), "text1|nolineheight")
            )
            addView(
                textClock("mm", "mm", 78f, "sans-serif-thin", accentColor(), "accent1|nolineheight")
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(-20) }
            )
            addView(
                textClock("EEE, MMM d", "EEE, MMM d", 14f, "sans-serif-medium", white70(), "text2")
            )
        }

    /** idx 24 "X Factor" - accent hour, white minute split by a divider. */
    private fun buildAccent(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                textClock("hh", "HH", 60f, "sans-serif-medium", accentColor(),
                    "accent1|nolineheight")
            )
            row.addView(
                staticText(":", 60f, "sans-serif-thin", white(), "text1|nolineheight")
                    .also {
                        (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(2)
                        (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(2)
                    }
            )
            row.addView(
                textClock("mm", "mm", 60f, "sans-serif-medium", white(), "text1|nolineheight")
            )
            addView(row)
            addView(
                textClock("EEEE, MMM d", "EEEE, MMM d", 13f, "sans-serif", white70(), "text2")
            )
        }

    /** idx 59 "Big 1" - huge bold single-line time. Also the fallback face. */
    private fun buildBig(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh:mm", "HH:mm", 64f, "sans-serif-black", white(), "text1|nolineheight")
            )
            addView(
                textClock("EEEE, MMMM d", "EEEE, MMMM d", 13f, "sans-serif", white(), "text2")
            )
        }

    /** idx 60 "Big 2" - Samsung-inspired left stack: accent day, thin time, date. */
    private fun buildSammy(): View =
        column(Gravity.START).apply {
            addView(
                textClock("EEEE", "EEEE", 18f, "sans-serif-medium", accentColor(),
                    "accent1|nolineheight")
            )
            addView(
                textClock("hh:mm", "HH:mm", 72f, "sans-serif-thin", white(), "text1|nolineheight")
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(-4) }
            )
            addView(
                textClock("d MMMM", "d MMMM", 14f, "sans-serif", white70(), "text2")
            )
        }

    /** idx 67 "Tall clock" - very tall thin hour over accent minute. */
    private fun buildTall(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                textClock("hh", "HH", 120f, "sans-serif-thin", white(), "text1|nolineheight")
            )
            addView(
                textClock("mm", "mm", 120f, "sans-serif-thin", accentColor(), "accent1|nolineheight")
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(-40) }
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Ported crDroid faces (branch 15.0, layouts from crDroidSettings res/layout).
    // ---------------------------------------------------------------------------------------------

    /** idx 1 "OnePlus Clock" - weekday, "NEVER SETTLE" tagline, small time w/ accent first digit. */
    private fun buildOos(): View =
        column(Gravity.START).apply {
            addView(tc("EEEE", "EEEE", 18f, white(), "text1"))
            addView(tv("𝗡𝗘𝗩𝗘𝗥 𝘚𝘌𝘛𝘛𝘓𝘌", 15f, white(), "text2"))
            val base = tc("hh:mm", "HH:mm", 20f, white(), "text1")
            val top = tc("hh:mm", "HH:mm", 20f, accent600(), "accent1").apply {
                filters = arrayOf(InputFilter.LengthFilter(1))
            }
            addView(overlay(base, top))
            addView(tc("MMMM dd", "MMMM dd", 10f, white(), "text2"))
        }

    /** idx 2 "OnePlus Clock 2" - weekday over big time with red hour, date below. */
    private fun buildOos2(): View =
        column(Gravity.START).apply {
            addView(tc("EEEE", "EEEE", 17f, white(), "text1"))
            val base = tc("h:mm", "H:mm", 54f, white(), "text1")
            val top = tc("h", "H", 54f, holoRed(), "accent1")
            addView(overlay(base, top))
            val row = rowH()
            row.addView(tc("MMM dd", "MMM dd", 15f, white(), "text1"))
            addView(row)
        }

    /** idx 5 "MIUI Clock" - big time, all-caps weekday, MM/dd date, tightly stacked. */
    private fun buildMiui(): View =
        column(Gravity.START).apply {
            addView(tc("hh:mm", "HH:mm", 50f, white(), "text1"))
            addView(
                tc("EEE", "EEE", 30f, white(), "text1", allCaps = true)
                    .also { lp(it).topMargin = dp(-8) }
            )
            addView(
                tc("MM/dd", "MM/dd", 30f, white(), "text2")
                    .also { lp(it).topMargin = dp(-5) }
            )
        }

    /** idx 6 "IDE Clock" - a C++ snippet whose string values are live TextClocks. */
    private fun buildIde(): View =
        column(Gravity.START).apply {
            val w = white()
            val green = Color.parseColor("#3DDB86")
            val blue = Color.parseColor("#0040FF")
            val red = Color.parseColor("#FF0000")
            val sz = 8f
            rowH().let { r ->
                r.addView(tv("#include  ", sz, w, "text1", bold = true))
                r.addView(tv("<string>", sz, green, "accent1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("using namespace  ", sz, w, "text1", bold = true))
                r.addView(tv("std;", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("int main  ", sz, w, "text1", bold = true))
                r.addView(tv("()", sz, w, "text1", bold = true))
                addView(r)
            }
            addView(tv("{", sz, w, "text1", bold = true))
            addView(tv("\tstring time, date, day, month;", sz, w, "text1", bold = true))
            rowH().let { r ->
                r.addView(tv("\ttime =  \"", sz, w, "text1", bold = true))
                r.addView(tc("hh:mm", "kk:mm", sz, green, "accent1", letterSpacing = 0.03f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("\tday =  \"", sz, w, "text1", bold = true))
                r.addView(tc("EEEE", "EEEE", sz, green, "accent1", allCaps = true,
                    letterSpacing = 0.3f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("\tdate =  \"", sz, w, "text1", bold = true))
                r.addView(tc("yyyy.MM.dd", "yyyy.MM.dd", sz, blue, "accent1",
                    letterSpacing = 0.03f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            rowH().let { r ->
                r.addView(tv("\tmonth =  \"", sz, w, "text1", bold = true))
                r.addView(tc("MMMM", "MMMM", sz, red, "accent1", allCaps = true,
                    letterSpacing = 0.3f))
                r.addView(tv("\";", sz, w, "text1", bold = true))
                addView(r)
            }
            addView(tv("\treturn 0;", sz, w, "text1", bold = true))
            addView(tv("}", sz, w, "text1", bold = true))
        }

    /** idx 7 "Moto Clock" - centered bold time with small centered date. */
    private fun buildMoto(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                tc("hh:mm", "HH:mm", 50f, white(), "text1", bold = true, alpha = 0.9f)
                    .apply { gravity = Gravity.CENTER }
                    .also { lp(it).bottomMargin = dp(4) }
            )
            addView(
                tc("EEE, MMM d", "EEE, d MMM", 10f, white(), "text2|nolineheight")
                    .apply { gravity = Gravity.CENTER }
            )
        }

    /** idx 13 "Stylish Clock 6" - greeting, date, boxed weekday chip, divider line + time. */
    private fun buildStylish6(): View =
        column(Gravity.START).apply {
            val inner = column(Gravity.START).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            inner.addView(tv("Hello !", 11f, white(), "text1|nolineheight", bold = true))
            inner.addView(
                tc("MMMM • dd • yyyy", "dd • MMMM • yyyy", 7f, white(), "text1", bold = true)
            )
            val boxOuter =
                FrameLayout(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(100), dp(29)).also {
                            it.topMargin = dp(4)
                            it.gravity = Gravity.CENTER_HORIZONTAL
                        }
                }
            val shadow =
                View(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.END or Gravity.BOTTOM,
                        ).also {
                            it.marginStart = dp(2)
                            it.topMargin = dp(2)
                        }
                    setBackgroundColor(accent600())
                    tag = "accent1"
                }
            val whiteBox =
                FrameLayout(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        ).also {
                            it.marginEnd = dp(2)
                            it.bottomMargin = dp(2)
                        }
                    setBackgroundColor(Color.WHITE)
                }
            whiteBox.addView(
                tc("EEEE", "EEEE", 12f, Color.BLACK, null, bold = true).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        )
                }
            )
            boxOuter.addView(shadow)
            boxOuter.addView(whiteBox)
            inner.addView(boxOuter)
            val lineRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            dp(100),
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).also {
                            it.topMargin = dp(4)
                            it.gravity = Gravity.CENTER_HORIZONTAL
                        }
                    setPadding(dp(2), 0, 0, 0)
                }
            lineRow.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    setBackgroundColor(Color.WHITE)
                    tag = "text1"
                }
            )
            lineRow.addView(
                tc("hh • mm", "HH • mm", 7f, white(), "text1|nolineheight", bold = true)
                    .also { lp(it).marginStart = dp(8) }
            )
            inner.addView(lineRow)
            addView(inner)
        }

    /** idx 20 "Android 9 Vibe" - centered medium time over centered long date. */
    private fun buildA9(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            addView(
                tc("hh:mm", "HH:mm", 35f, white(), "accent1|nolineheight")
                    .apply { gravity = Gravity.CENTER }
                    .also { lp(it).topMargin = dp(6) }
            )
            addView(
                tc("EEEE, MMMM dd", "EEEE, dd MMMM", 10f, white(), "text1")
                    .apply { gravity = Gravity.CENTER }
            )
        }

    /** idx 28 "Label Clock" - weekday, time w/ accent first digit, date. */
    private fun buildLabel(): View =
        column(Gravity.START).apply {
            addView(tc("EEEE", "EEEE", 25f, white(), "text1"))
            val base = tc("hh:mm", "HH:mm", 25f, white(), "text1")
            val top = tc("hh:mm", "HH:mm", 25f, accent300(), "accent1").apply {
                filters = arrayOf(InputFilter.LengthFilter(1))
            }
            addView(overlay(base, top))
            addView(
                tc("MMMM dd", "MMMM dd", 10f, white(), "text2")
                    .also { lp(it).marginStart = dp(4) }
            )
        }

    /** idx 41 "Anci Scapula" - staggered accent hour/minute, date row, weekday. */
    private fun buildScapula(): View =
        column(Gravity.CENTER_HORIZONTAL).apply {
            val a1 = accentColor()
            val clear = Color.TRANSPARENT
            val row1 = rowH(Gravity.CENTER).also { lp(it).bottomMargin = dp(-10) }
            row1.addView(tc("hh", "HH", 45f, a1, "accent1").apply { gravity = Gravity.CENTER })
            row1.addView(tc("    ", "    ", 25f, clear, null))
            addView(row1)
            val row2 = rowH(Gravity.CENTER).also { lp(it).bottomMargin = dp(-10) }
            row2.addView(tc("    ", "    ", 25f, clear, null))
            row2.addView(tc("mm", "mm", 45f, a1, "accent1").apply { gravity = Gravity.CENTER })
            addView(row2)
            val row3 = rowH(Gravity.CENTER)
            row3.addView(tc("MMMM dd", "MMMM dd", 10f, a1, "accent1"))
            row3.addView(tc(", yyy", ", yyy", 10f, a1, "accent1"))
            addView(row3)
            addView(tc("EEEE", "EEEE", 10f, a1, "accent1").apply { gravity = Gravity.CENTER })
        }

    // ---------------------------------------------------------------------------------------------
    // View builder helpers.
    // ---------------------------------------------------------------------------------------------

    private fun column(horizontalGravity: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or horizontalGravity
            clipChildren = false
            clipToPadding = false
        }

    private fun textClock(
        format12: String,
        format24: String,
        sizeSp: Float,
        family: String,
        color: Int,
        tag: String,
    ): TextClock =
        TextClock(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            format12Hour = format12
            format24Hour = format24
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(family, Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setSingleLine(true)
            setTag(tag)
        }

    private fun staticText(
        text: String,
        sizeSp: Float,
        family: String,
        color: Int,
        tag: String,
    ): TextView =
        TextView(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(family, Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setTag(tag)
        }

    /** Flexible TextClock builder used by the ported crDroid faces. */
    private fun tc(
        format12: String,
        format24: String,
        sizeSp: Float,
        color: Int,
        tag: String?,
        bold: Boolean = false,
        allCaps: Boolean = false,
        letterSpacing: Float = 0f,
        alpha: Float = 1f,
    ): TextClock =
        TextClock(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            format12Hour = format12
            format24Hour = format24
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(clockFamily(), if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setSingleLine(true)
            setAllCaps(allCaps)
            setLetterSpacing(letterSpacing)
            this.alpha = alpha
            tag?.let { setTag(it) }
        }

    /** Flexible static TextView builder used by the ported crDroid faces. */
    private fun tv(
        text: String,
        sizeSp: Float,
        color: Int,
        tag: String?,
        bold: Boolean = false,
        allCaps: Boolean = false,
    ): TextView =
        TextView(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(clockFamily(), if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
            setAllCaps(allCaps)
            tag?.let { setTag(it) }
        }

    private fun rowH(gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            clipChildren = false
            clipToPadding = false
        }

    /** Stacks [top] over [base], both start/top aligned (crDroid's first-digit accent trick). */
    private fun overlay(base: View, top: View): FrameLayout =
        FrameLayout(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            clipChildren = false
            addView(
                base,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.TOP,
                ),
            )
            addView(
                top,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.TOP,
                ),
            )
        }

    private fun lp(v: View): LinearLayout.LayoutParams =
        v.layoutParams as LinearLayout.LayoutParams

    /** Resolves the framework lockscreen clock font family, falling back to sans-serif. */
    private fun clockFamily(): String =
        try {
            val id = resources.getIdentifier("config_clockFontFamily", "string", "android")
            if (id != 0) resources.getString(id).ifEmpty { "sans-serif" } else "sans-serif"
        } catch (e: Exception) {
            "sans-serif"
        }

    private fun accent600(): Int = context.getColor(android.R.color.system_accent1_600)

    private fun accent300(): Int = context.getColor(android.R.color.system_accent1_300)

    private fun holoRed(): Int = context.getColor(android.R.color.holo_red_light)

    private fun white(): Int = Color.WHITE

    private fun white70(): Int = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VIEW_TAG = "ichthys_custom_clock"

        private const val STYLE_DEFAULT = 0
        private const val STYLE_OOS = 1
        private const val STYLE_OOS2 = 2
        private const val STYLE_CENTER = 3
        private const val STYLE_SIMPLE = 4
        private const val STYLE_MIUI = 5
        private const val STYLE_IDE = 6
        private const val STYLE_MOTO = 7
        private const val STYLE_STYLISH6 = 13
        private const val STYLE_A9 = 20
        private const val STYLE_STACKED = 23
        private const val STYLE_ACCENT = 24
        private const val STYLE_LABEL = 28
        private const val STYLE_SCAPULA = 41
        private const val STYLE_BIG = 59
        private const val STYLE_SAMMY = 60
        private const val STYLE_TALL = 67

        private const val BASE_TOP_DP = 72
        private const val DEFAULT_MARGIN_TOP = 15

        // Auxiliary secure setting keys (raw keys written by crDroidSettings, not Settings.java
        // constants).
        private const val KEY_COLOR_MODE = "lock_screen_custom_clock_color_mode"
        private const val KEY_SIZE_SCALE = "lock_screen_custom_clock_size_scale"
        private const val KEY_CUSTOM_COLOR = "lock_screen_custom_clock_custom_color"
        private const val KEY_OPACITY = "lock_screen_custom_clock_opacity"
        private const val KEY_MARGIN_TOP = "lock_screen_custom_clock_margin_top"

        private const val MODE_DEFAULT = "default"
        private const val MODE_ACCENT = "accent"
        private const val MODE_CUSTOM = "custom"

        private val WATCHED_KEYS =
            listOf(
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                KEY_COLOR_MODE,
                KEY_SIZE_SCALE,
                KEY_CUSTOM_COLOR,
                KEY_OPACITY,
                KEY_MARGIN_TOP,
            )
    }
}
