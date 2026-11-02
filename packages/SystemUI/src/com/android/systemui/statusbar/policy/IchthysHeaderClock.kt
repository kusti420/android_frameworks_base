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

package com.android.systemui.statusbar.policy

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.util.TypedValue
import com.android.settingslib.ichthys.ShoalContrast
import com.android.systemui.shade.ui.ShoalShade

/**
 * IchthysOS "Shoal": the shade header clock. With the Shoal surface style on ([ShoalShade]) it
 * uses the theme's mono face (ichthys_theme_font_mono, default JetBrains Mono) at weight 500 — the
 * same face as the status bar clock, so the whole system reads as one type system — and draws the
 * seconds of an H:MM:SS time in the accent colour. The [header] clock (the one Shoal header shared
 * by the Shade and Quick Settings scenes) is 30 sp, tracked in to -0.5 sp, and draws ":SS" at
 * 18 sp. Otherwise it is the stock [Clock].
 */
class IchthysHeaderClock(context: Context, attrs: AttributeSet?) : Clock(context, attrs) {

    private var stockTypeface: Typeface? = null
    private var stockTextSizePx = 0f
    private var stockLetterSpacing = 0f
    private var stockIncludeFontPadding = true
    private var shoalApplied = false

    /** The Shoal shade/QS header clock (30 sp); otherwise the stock size. */
    var header: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyShoalTypeface()
        }

    private fun shoal(): Boolean = ShoalShade.isActive(context)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyShoalTypeface()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Clock reloads the status bar clock size here; keep the Shoal header size.
        if (shoalApplied && header) setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP)
    }

    /** Re-evaluated whenever the header re-binds (theme changes recreate the header). */
    fun applyShoalTypeface() {
        if (stockTypeface == null) {
            stockTypeface = typeface ?: Typeface.DEFAULT
            stockTextSizePx = textSize
            stockLetterSpacing = letterSpacing
            stockIncludeFontPadding = includeFontPadding
        }
        if (shoal()) {
            shoalApplied = true
            typeface = ShoalShade.mono(context, 500)
            if (header) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP)
                letterSpacing = HEADER_TRACKING_SP / HEADER_SP
                includeFontPadding = false
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, stockTextSizePx)
                letterSpacing = stockLetterSpacing
                includeFontPadding = stockIncludeFontPadding
            }
        } else if (shoalApplied) {
            shoalApplied = false
            typeface = stockTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_PX, stockTextSizePx)
            letterSpacing = stockLetterSpacing
            includeFontPadding = stockIncludeFontPadding
        }
        // Re-run setText so the seconds spans follow the current mode.
        val current: CharSequence? = text
        if (!current.isNullOrEmpty()) setText(current.toString(), BufferType.NORMAL)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (text == null || !shoal()) {
            super.setText(text, type)
            return
        }
        // Style the seconds: the "SS" of an "H:MM:SS" time (nothing when seconds are off).
        val seconds = SECONDS.find(text)?.groups?.get(1)
        if (seconds == null) {
            super.setText(text, type)
            return
        }
        // The header clock's seconds (with their colon) sit small and accented beside "HH:MM".
        val start = if (header) seconds.range.first - 1 else seconds.range.first
        val end = seconds.range.last + 1
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(secondsColor()),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (header) {
            spannable.setSpan(
                RelativeSizeSpan(HEADER_SECONDS_SP / HEADER_SP),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            // No negative tracking on the small seconds.
            spannable.setSpan(
                LetterSpacingResetSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        super.setText(spannable, BufferType.SPANNABLE)
    }

    /**
     * IchthysOS: the accent for the seconds, contrast-guarded against what the clock sits on: the
     * shade ink for the header clock; for the status bar clock the ground its own (dark/light
     * tinted) text colour implies, light text meaning a dark ground. A dark accent is lifted to a
     * lighter tone of itself there; one that already reads is used as is.
     */
    private fun secondsColor(): Int {
        val accent = ShoalShade.accent(context)
        val ground =
            if (header) ShoalShade.inkColor(context) else ShoalContrast.onFill(currentTextColor)
        return ShoalContrast.forText(accent, ground)
    }

    /** Clears the view's letter spacing inside the span. */
    private class LetterSpacingResetSpan : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            tp.letterSpacing = 0f
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.letterSpacing = 0f
        }
    }

    companion object {
        private const val HEADER_SP = 30f
        private const val HEADER_SECONDS_SP = 18f
        private const val HEADER_TRACKING_SP = -0.5f
        private val SECONDS = Regex("[0-9]:[0-9]{2}:([0-9]{2})")
    }
}
