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

package com.android.settingslib.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.android.settingslib.widget.theme.R

/**
 * IchthysOS "Shoal" hairline list surfaces (theme.surface = hairline, Settings.Secure
 * `ichthys_theme_surface` unset or "hairline").
 *
 * Each preference group is drawn as ONE card: radius 24dp, #0c1013 fill and a 1dp #20282d
 * hairline in dark mode (tonal surface + outline-variant in light mode). Rows inside a group
 * touch (no gap, see [SettingsBasePreferenceFragment]'s item decoration) and each row only draws
 * its own slice of the card: the first row the rounded top, middle rows the two side lines, the
 * last row the rounded bottom, so together they read as a single outlined card.
 */
object ShoalSurfaces {

    private const val KEY_SURFACE = "ichthys_theme_surface"
    private const val KEY_FONT_TEXT = "ichthys_theme_font_text"
    private const val DEFAULT_TEXT = "instrument-sans"
    private const val KEY_FONT_DISPLAY = "ichthys_theme_font_display"
    private const val DEFAULT_DISPLAY = "syne"

    private const val INK_CARD = 0xFF0C1013.toInt()
    private const val INK_HAIRLINE = 0xFF20282D.toInt()
    private const val INK_MUTED = 0xFF8B938F.toInt()

    /** Hairline (Koi, the default) unless theme.surface is "tonal". */
    @JvmStatic
    fun isHairline(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, KEY_SURFACE) != "tonal"

    @JvmStatic
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Card fill: ink card (#0c1013) in the dark, the lowest tonal surface in the light. */
    @JvmStatic
    fun cardColor(context: Context): Int =
        if (isNight(context)) INK_CARD
        else context.getColor(R.color.settingslib_materialColorSurfaceContainerLowest)

    /** Card hairline: #20282d in the dark, outline-variant in the light. */
    @JvmStatic
    fun hairlineColor(context: Context): Int =
        if (isNight(context)) INK_HAIRLINE
        else context.getColor(R.color.settingslib_materialColorOutlineVariant)

    /** Muted secondary text (#8b938f in the dark). */
    @JvmStatic
    fun mutedTextColor(context: Context): Int =
        if (isNight(context)) INK_MUTED
        else context.getColor(R.color.settingslib_materialColorOnSurfaceVariant)

    /** The theme's body font (theme.font.text, Instrument Sans by default). */
    @JvmStatic
    fun textTypeface(context: Context, weight: Int): Typeface {
        var name = Settings.Secure.getString(context.contentResolver, KEY_FONT_TEXT)
        if (TextUtils.isEmpty(name)) name = DEFAULT_TEXT
        val family = Typeface.create(name, Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(family, weight, false)
        }
        return family
    }

    private fun dp(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private fun resolveDimen(context: Context, attr: Int, fallbackDp: Float): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true) &&
                tv.type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimensionPixelSize(tv.data, context.resources.displayMetrics)
        }
        return Math.round(dp(context, fallbackDp))
    }

    private fun rippleColor(context: Context): ColorStateList {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)) {
            if (tv.resourceId != 0) {
                return context.getColorStateList(tv.resourceId)
            }
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                    tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return ColorStateList.valueOf(tv.data)
            }
        }
        return ColorStateList.valueOf(0x1FFFFFFF)
    }

    /**
     * Replaces [view]'s background with its slice of the hairline group card. [positionState] is
     * one of android.R.attr.state_single / state_first / state_middle / state_last; any other
     * value leaves the view alone. The view's padding is preserved.
     */
    @JvmStatic
    @JvmOverloads
    fun applyRow(view: View, positionState: Int, selected: Boolean = false) {
        val pos = when (positionState) {
            android.R.attr.state_single -> ShoalRowShape.SINGLE
            android.R.attr.state_first -> ShoalRowShape.FIRST
            android.R.attr.state_middle -> ShoalRowShape.MIDDLE
            android.R.attr.state_last -> ShoalRowShape.LAST
            else -> return
        }
        val c = view.context
        val accent = c.getColor(R.color.settingslib_materialColorPrimary)
        val fill =
            if (selected) {
                // Accent wash over the card for the selected (split-screen) homepage row.
                blend(cardColor(c), accent, 0.16f)
            } else {
                cardColor(c)
            }
        val shape =
            ShoalRowShape(
                position = pos,
                fillColor = fill,
                lineColor = if (selected) accent else hairlineColor(c),
                strokePx = dp(c, 1f).coerceAtLeast(1f),
                radiusPx = dp(c, 24f),
                insetStartPx = resolveDimen(c, android.R.attr.listPreferredItemPaddingStart, 16f),
                insetEndPx = resolveDimen(c, android.R.attr.listPreferredItemPaddingEnd, 16f),
            )
        val bg = ShoalRowDrawable(rippleColor(c), shape,
            joinsNext = pos == ShoalRowShape.FIRST || pos == ShoalRowShape.MIDDLE)
        val start = view.paddingStart
        val top = view.paddingTop
        val end = view.paddingEnd
        val bottom = view.paddingBottom
        view.background = bg
        view.setPaddingRelative(start, top, end, bottom)
        view.invalidateOutline()
    }

    /** Category header: uppercase 12sp, letter-spaced, muted (#8b938f), in the body font. */
    @JvmStatic
    fun styleCategoryTitle(title: TextView?) {
        if (title == null) return
        val c = title.context
        title.isAllCaps = true
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        title.letterSpacing = 0.08f
        title.typeface = textTypeface(c, 600)
        title.setTextColor(mutedTextColor(c))
    }

    /**
     * A list row's title (medium) and summary (regular) in the body face. With [sized] and the
     * hairline surface, the Shoal sizes apply too: title 15sp, summary 12.5sp muted (#8b938f).
     */
    @JvmStatic
    @JvmOverloads
    fun styleRowText(title: TextView?, summary: TextView?, sized: Boolean = true) {
        val c = (title ?: summary ?: return).context
        val shoalSized = sized && isHairline(c)
        title?.let {
            it.typeface = textTypeface(c, 500)
            if (shoalSized) it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        summary?.let {
            it.typeface = textTypeface(c, 400)
            if (shoalSized) {
                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                if (isNight(c)) {
                    val muted = mutedTextColor(c)
                    it.setTextColor(ColorStateList(
                        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf((muted and 0x00ffffff) or 0x61000000, muted)))
                }
            }
        }
    }

    /**
     * Large page title (collapsing toolbar): the display face (theme.font.display, Syne by
     * default) at weight 600.
     */
    @JvmStatic
    fun displayTypeface(context: Context, weight: Int): Typeface {
        var name = Settings.Secure.getString(context.contentResolver, KEY_FONT_DISPLAY)
        if (TextUtils.isEmpty(name)) name = DEFAULT_DISPLAY
        val family = Typeface.create(name, Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(family, weight, false)
        }
        return family
    }

    private fun blend(base: Int, over: Int, amount: Float): Int {
        fun ch(shift: Int): Int {
            val a = (base shr shift) and 0xff
            val b = (over shr shift) and 0xff
            return Math.round(a + (b - a) * amount).coerceIn(0, 255)
        }
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * A row's background: a ripple masked by its [ShoalRowShape]. [joinsNext] tells the list's item
 * decoration not to leave a gap below this row, so the group's hairline stays continuous.
 */
class ShoalRowDrawable internal constructor(
    color: ColorStateList,
    content: Drawable,
    val joinsNext: Boolean,
) : RippleDrawable(color, content, null)

/** One slice of a hairline group card (see [ShoalSurfaces]). */
internal class ShoalRowShape(
    private val position: Int,
    fillColor: Int,
    lineColor: Int,
    private val strokePx: Float,
    private val radiusPx: Float,
    private val insetStartPx: Int,
    private val insetEndPx: Int,
) : Drawable() {

    companion object {
        const val SINGLE = 0
        const val FIRST = 1
        const val MIDDLE = 2
        const val LAST = 3
    }

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = lineColor
        }
    private val fillAlpha = fillPaint.alpha
    private val lineAlpha = linePaint.alpha
    private val rect = RectF()

    private val roundTop: Boolean get() = position == SINGLE || position == FIRST
    private val roundBottom: Boolean get() = position == SINGLE || position == LAST

    /** Horizontal card edges for the current layout direction. */
    private fun cardLeft(): Int =
        bounds.left + if (layoutDirection == View.LAYOUT_DIRECTION_RTL) insetEndPx else insetStartPx

    private fun cardRight(): Int =
        bounds.right - if (layoutDirection == View.LAYOUT_DIRECTION_RTL) insetStartPx else insetEndPx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val half = strokePx / 2f
        // Open edges run past the bounds (and are clipped), so only the side lines show there.
        val over = radiusPx + strokePx * 2
        rect.set(
            cardLeft() + half,
            if (roundTop) b.top + half else b.top - over,
            cardRight() - half,
            if (roundBottom) b.bottom - half else b.bottom + over,
        )
        val save = canvas.save()
        canvas.clipRect(b)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, linePaint)
        canvas.restoreToCount(save)
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val over = Math.round(radiusPx + strokePx * 2)
        outline.setRoundRect(
            cardLeft(),
            if (roundTop) b.top else b.top - over,
            cardRight(),
            if (roundBottom) b.bottom else b.bottom + over,
            radiusPx,
        )
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = fillAlpha * alpha / 255
        linePaint.alpha = lineAlpha * alpha / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
