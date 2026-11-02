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

package com.android.systemui.shade.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;

import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.ichthys.ShoalContrast;

/**
 * IchthysOS "Shoal" shade styling: one place that decides whether the Shoal look is on and holds
 * its ink palette and type faces, shared by the notification rows, section headers, footer, shade
 * colours and the shade header.
 * <p>
 * Shoal is on when Settings.Secure {@code ichthys_theme_surface} is {@code hairline}. When that is
 * unset (the ichthys.nix default is hairline but the key is not always written), Shoal is taken to
 * be on when the rest of the Shoal theme is (Shoal quick settings, or two surface levels). An
 * explicit {@code tonal} always turns it off. The colours are the framework ichthys_* tokens and
 * follow the dark / light theme.
 */
public final class ShoalShade {

    private ShoalShade() {}

    public static final String KEY_SURFACE = "ichthys_theme_surface";
    public static final String KEY_SURFACES = "ichthys_theme_surfaces";
    public static final String KEY_QS_STYLE = "ichthys_qs_style";
    public static final String KEY_FONT_DISPLAY = "ichthys_theme_font_display";
    public static final String KEY_FONT_TEXT = "ichthys_theme_font_text";
    public static final String KEY_FONT_MONO = "ichthys_theme_font_mono";

    private static final String DEFAULT_DISPLAY = "syne";
    private static final String DEFAULT_TEXT = "instrument-sans";
    private static final String DEFAULT_MONO = "jetbrains-mono";

    // The palette is the framework's ichthys_* tokens, which the theme engine fills for the dark
    // and for the light theme. These used to be fixed dark hexes, which left the shade ink-black
    // with dark text on it in light theme.

    /** Shade ground. */
    public static int inkColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_ink);
    }

    /** Notification card / raised surface. */
    public static int cardColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_card);
    }

    /** 1 dp hairline around cards and outlined buttons. */
    public static int hairlineColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_hairline);
    }

    /** Dividers inside a card, action button fill. */
    public static int dividerColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_raised);
    }

    /** Primary text (titles). */
    public static int textColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_text);
    }

    /** Body text. */
    public static int bodyColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_body);
    }

    /** Meta text, section headers, dates. */
    public static int mutedColor(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_muted);
    }

    /** Whether the Shoal surface style is on (see class doc). */
    public static boolean isActive(Context context) {
        try {
            final String surface = Settings.Secure.getString(context.getContentResolver(),
                    KEY_SURFACE);
            // IchthysOS: "stock" (theme.surface) means plain Monet, so no Shoal shade either.
            if ("tonal".equals(surface) || "stock".equals(surface)) return false;
            if ("hairline".equals(surface)) return true;
            // Unset means the option defaults: quickSettings.style = "shoal", theme.surfaces = 4.
            if (!"grid".equals(Settings.Secure.getString(context.getContentResolver(),
                    KEY_QS_STYLE))) {
                return true;
            }
            return Settings.Secure.getInt(context.getContentResolver(), KEY_SURFACES, 4) == 2;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Shoal on: paint the Shoal palette. The name is from when the palette was dark only; the
     * colours now follow the theme, so this is true in light theme as well.
     */
    public static boolean isInk(Context context) {
        return isActive(context);
    }

    /**
     * The theme accent: the seed the user picked (theme_customization_overlay_packages
     * system_palette), else the Monet accent.
     */
    public static int accent(Context context) {
        try {
            final String json = Settings.Secure.getString(context.getContentResolver(),
                    "theme_customization_overlay_packages");
            if (json != null) {
                final int i = json.indexOf("system_palette");
                if (i >= 0) {
                    final int q = json.indexOf('"', json.indexOf(':', i)) + 1;
                    final int e = json.indexOf('"', q);
                    int c = (int) Long.parseLong(json.substring(q, e), 16);
                    if ((c >>> 24) == 0) c |= 0xFF000000;
                    return c;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            return context.getColor(android.R.color.system_accent1_400);
        } catch (Exception ignored) {
        }
        return 0xFFFF6B3D;
    }

    /**
     * IchthysOS: the accent as TEXT on the shade ink (clock seconds, accent labels): the accent
     * itself when it reads there, else a lighter (darker in light theme) tone of it that reaches
     * {@link ShoalContrast#TEXT}. A dark accent such as indigo #3949ab sank into the ink.
     */
    public static int accentText(Context context) {
        return ShoalContrast.forText(accent(context), inkColor(context));
    }

    /**
     * IchthysOS: the accent as an icon, outline or thin indicator on the shade ink and its card
     * ({@link ShoalContrast#GRAPHIC}).
     */
    public static int accentGraphic(Context context) {
        return ShoalContrast.forGraphic(accent(context), inkColor(context), cardColor(context));
    }

    /** A dark, accent-tinted fill (icon discs): about 10% accent over the ink. */
    public static int accentTint(Context context) {
        return ColorUtils.blendARGB(inkColor(context), accent(context), 0.10f);
    }

    public static String displayFamily(Context context) {
        return family(context, KEY_FONT_DISPLAY, DEFAULT_DISPLAY);
    }

    public static String textFamily(Context context) {
        return family(context, KEY_FONT_TEXT, DEFAULT_TEXT);
    }

    public static String monoFamily(Context context) {
        return family(context, KEY_FONT_MONO, DEFAULT_MONO);
    }

    private static String family(Context context, String key, String def) {
        String name = null;
        try {
            name = Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Exception ignored) {
        }
        return TextUtils.isEmpty(name) ? def : name;
    }

    /** Named family at a weight (100-900). Unknown families fall back to the default face. */
    public static Typeface face(String family, int weight) {
        final Typeface base = Typeface.create(family, Typeface.NORMAL);
        return Typeface.create(base != null ? base : Typeface.DEFAULT, weight, false);
    }

    public static Typeface display(Context context, int weight) {
        return face(displayFamily(context), weight);
    }

    public static Typeface text(Context context, int weight) {
        return face(textFamily(context), weight);
    }

    public static Typeface mono(Context context, int weight) {
        return face(monoFamily(context), weight);
    }

    public static int dp(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics()));
    }

    /**
     * A pill: rounded rect of {@code heightPx} (at most the bounds' height), vertically centred in
     * its bounds, with an optional 1 dp outline. Lets a button keep its 48 dp touch target while
     * drawing a smaller pill.
     */
    public static final class PillDrawable extends Drawable {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int mHeight;
        private final boolean mHasStroke;

        public PillDrawable(int fill, int stroke, float strokeWidthPx, int heightPx) {
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(fill);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setColor(stroke);
            mStroke.setStrokeWidth(strokeWidthPx);
            mHasStroke = strokeWidthPx > 0 && stroke != 0;
            mHeight = heightPx;
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = getBounds();
            final int h = mHeight > 0 ? Math.min(mHeight, b.height()) : b.height();
            final float top = b.top + (b.height() - h) / 2f;
            final float half = mHasStroke ? mStroke.getStrokeWidth() / 2f : 0f;
            mRect.set(b.left + half, top + half, b.right - half, top + h - half);
            final float r = h / 2f;
            canvas.drawRoundRect(mRect, r, r, mFill);
            if (mHasStroke) canvas.drawRoundRect(mRect, r, r, mStroke);
        }

        @Override
        public void setAlpha(int alpha) {
            mFill.setAlpha(alpha);
            mStroke.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mFill.setColorFilter(colorFilter);
            mStroke.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A {@link PillDrawable} with a ripple on top, for buttons. */
    public static Drawable pill(Context context, int fill, int stroke, float heightDp) {
        final int h = heightDp > 0 ? dp(context, heightDp) : 0;
        final float sw = stroke != 0 ? context.getResources().getDisplayMetrics().density : 0f;
        final PillDrawable content = new PillDrawable(fill, stroke, sw, h);
        final PillDrawable mask = new PillDrawable(0xFFFFFFFF, 0, 0, h);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }
}
