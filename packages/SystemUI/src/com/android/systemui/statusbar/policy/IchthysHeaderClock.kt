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
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet

/**
 * IchthysOS "Shoal": the shade header clock. With a Shoal surface style set (Settings.Secure
 * ichthys_theme_surface) it uses the theme's display face in bold (ichthys_theme_font_display,
 * falling back to the default face) and draws the seconds in the accent colour. Otherwise it is
 * the stock [Clock].
 */
class IchthysHeaderClock(context: Context, attrs: AttributeSet?) : Clock(context, attrs) {

    private var stockTypeface: Typeface? = null

    private fun shoal(): Boolean =
        Settings.Secure.getString(context.contentResolver, SURFACE_SETTING) != null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyShoalTypeface()
    }

    /** Re-evaluated whenever the header re-binds (theme changes recreate the header). */
    fun applyShoalTypeface() {
        if (stockTypeface == null) stockTypeface = typeface ?: Typeface.DEFAULT
        typeface =
            if (shoal()) {
                val family = Settings.Secure.getString(context.contentResolver, FONT_SETTING)
                val named =
                    if (family.isNullOrEmpty()) null else Typeface.create(family, Typeface.BOLD)
                if (named == null || named == Typeface.DEFAULT_BOLD) Typeface.DEFAULT_BOLD
                else named
            } else {
                stockTypeface
            }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (text == null || !shoal()) {
            super.setText(text, type)
            return
        }
        // Tint the seconds: the "SS" of an "H:MM:SS" time (nothing when seconds are off).
        val seconds = SECONDS.find(text)?.groups?.get(1)
        if (seconds == null) {
            super.setText(text, type)
            return
        }
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(context.getColor(android.R.color.system_accent1_400)),
            seconds.range.first,
            seconds.range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        super.setText(spannable, BufferType.SPANNABLE)
    }

    private companion object {
        const val SURFACE_SETTING = "ichthys_theme_surface"
        const val FONT_SETTING = "ichthys_theme_font_display"
        val SECONDS = Regex("[0-9]:[0-9]{2}:([0-9]{2})")
    }
}
