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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.StatusBarIconView;

import java.util.Set;

/**
 * IchthysOS "Shoal": shared geometry and drawing for the zig-zag status bar lattice
 * (Settings.Secure ichthys_statusbar_layout = "zigzag"), used by {@link StatusIconContainer} for
 * the system icons and by {@link NotificationIconContainer} for the status bar notification icons.
 *
 * <p>Every icon sits centred on an 11 dp disc; discs are 2 dp apart in two rows 1 dp apart, and
 * each icon moves half a pitch along from the previous one, alternating rows, so the lower row
 * nests into the gaps of the upper one. Icons are scaled about the centre of their glyph (the
 * drawable actually painted, not the whole view, which for Wi-Fi / mobile includes padding, the
 * network type and activity arrows) so the glyph fills ~70 % of the disc, and clipped to that
 * glyph. An icon without a drawable glyph is hidden instead of leaving an empty disc.
 */
public final class IchthysZigzag {

    public static final String LAYOUT_SETTING = "ichthys_statusbar_layout";

    static final float CELL_DP = 11f;
    static final float GAP_DP = 2f;
    static final float ROW_GAP_DP = 1f;
    /** Glyph box height / width as a fraction of the disc. */
    static final float GLYPH_H_FRACTION = 0.70f;
    static final float GLYPH_W_FRACTION = 0.80f;

    /** Neutral disc on a dark bar (design #161c20, lifted a touch so it reads on true black). */
    static final int DISC_ON_DARK = 0xFF1B2226;
    /** Hairline around neutral discs on a dark bar. */
    static final int RING_ON_DARK = 0x2EFFFFFF;
    /** Neutral disc on a light bar: translucent grey. */
    static final int DISC_ON_LIGHT = 0x40606864;
    /** Glyph on an accent disc. */
    static final int GLYPH_ON_ACCENT = 0xFF07090B;
    /** Muted notification glyph on a dark bar. */
    static final int NOTIF_GLYPH_ON_DARK = 0xFF8B938F;

    /** Slots whose disc is filled with the accent while shown: states worth noticing. */
    static final Set<String> ACTIVE_SLOTS = Set.of("vpn", "location", "hotspot", "cast",
            "screen_record", "data_saver", "zen", "sensors_off", "connected_display",
            "microphone", "camera");

    private IchthysZigzag() {}

    /** Unset means the option's default (statusBar.layout = "zigzag"); only "single" is off. */
    public static boolean isEnabled(Context context) {
        return !"single".equals(Settings.Secure.getStringForUser(context.getContentResolver(),
                LAYOUT_SETTING, UserHandle.USER_CURRENT));
    }

    /**
     * The theme accent: the shared Shoal token, so the status bar, notification dot, battery and
     * every "unset = accent" colour agree with the nav bar, brightness slider and Guard.
     */
    public static int accent(Context context) {
        return context.getColor(com.android.internal.R.color.ichthys_accent);
    }

    static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    /** Disc diameter for a container of the given inner height. */
    static float cellFor(View view, float innerHeight) {
        final float rowGap = rowGap(view);
        float cell = dp(view, CELL_DP);
        if (innerHeight > 0) {
            cell = Math.min(cell, (innerHeight - rowGap) / 2f);
        }
        return Math.max(1f, cell);
    }

    static float rowGap(View view) {
        return Math.max(1f, dp(view, ROW_GAP_DP));
    }

    static float halfPitch(float cell) {
        return (cell + cell * GAP_DP / CELL_DP) / 2f;
    }

    /** Scale that makes a glyph of the given size fill the disc. */
    static float glyphScale(float cell, RectF glyph) {
        return Math.min(cell * GLYPH_H_FRACTION / Math.max(1f, glyph.height()),
                cell * GLYPH_W_FRACTION / Math.max(1f, glyph.width()));
    }

    /** The view inside a status icon that draws its glyph, or null if it has none. */
    static View findGlyphView(View icon) {
        if (icon instanceof ImageView) {
            return icon;
        }
        if (!(icon instanceof ViewGroup)) {
            return null;
        }
        View v = icon.findViewById(R.id.wifi_signal);
        if (v == null) {
            v = icon.findViewById(R.id.mobile_signal);
        }
        if (v == null) {
            // Compose-backed icons (stacked mobile, bindable icons)
            v = icon.findViewById(R.id.compose_view);
        }
        if (v == null) {
            v = firstImage((ViewGroup) icon);
        }
        return v;
    }

    private static View firstImage(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View c = group.getChildAt(i);
            if (c.getVisibility() == View.GONE || c.getId() == R.id.status_bar_dot) {
                continue;
            }
            if (c instanceof ImageView && ((ImageView) c).getDrawable() != null) {
                return c;
            }
            if (c instanceof ViewGroup) {
                final View r = firstImage((ViewGroup) c);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Cheap pre-layout check: does the icon have something to draw on its disc? */
    static boolean hasGlyph(View icon) {
        final View glyph = findGlyphView(icon);
        if (glyph == null || glyph.getVisibility() == View.GONE) {
            return false;
        }
        if (glyph instanceof ImageView && ((ImageView) glyph).getDrawable() == null) {
            return false;
        }
        return glyph.getMeasuredWidth() > 0 && glyph.getMeasuredHeight() > 0;
    }

    /**
     * The box of the glyph an icon paints, in the icon view's own (untransformed) coordinates.
     * Returns false when the icon has nothing to show, in which case it must be hidden.
     */
    static boolean glyphRect(View icon, RectF out) {
        if (icon.getVisibility() == View.GONE) {
            return false;
        }
        final View glyph = findGlyphView(icon);
        if (glyph == null || glyph.getVisibility() == View.GONE
                || glyph.getWidth() <= 0 || glyph.getHeight() <= 0) {
            return false;
        }
        if (glyph instanceof ImageView) {
            final ImageView iv = (ImageView) glyph;
            final Drawable d = iv.getDrawable();
            if (d == null) {
                return false;
            }
            final Rect b = d.getBounds();
            if (b.isEmpty()) {
                out.set(0, 0, iv.getWidth() - iv.getPaddingLeft() - iv.getPaddingRight(),
                        iv.getHeight() - iv.getPaddingTop() - iv.getPaddingBottom());
            } else {
                out.set(b);
                iv.getImageMatrix().mapRect(out);
            }
            out.offset(iv.getPaddingLeft(), iv.getPaddingTop());
            if (iv instanceof StatusBarIconView) {
                // StatusBarIconView#onDraw scales the whole drawing about its (integer) centre.
                final float s = ((StatusBarIconView) iv).getIconScale();
                final float px = iv.getWidth() / 2;
                final float py = iv.getHeight() / 2;
                out.set(px + (out.left - px) * s, py + (out.top - py) * s,
                        px + (out.right - px) * s, py + (out.bottom - py) * s);
            }
            if (!out.intersect(0, 0, iv.getWidth(), iv.getHeight())) {
                return false;
            }
        } else {
            out.set(0, 0, glyph.getWidth(), glyph.getHeight());
        }
        View v = glyph;
        while (v != icon) {
            out.offset(v.getLeft(), v.getTop());
            final ViewParent parent = v.getParent();
            if (!(parent instanceof View)) {
                return false;
            }
            v = (View) parent;
            // INVISIBLE ancestors are fine: the icon group is only made visible once the
            // container has set the icon's visible state.
            if (v.getVisibility() == View.GONE) {
                return false;
            }
        }
        return out.width() >= 1f && out.height() >= 1f;
    }

    /**
     * Positions an icon so its glyph is centred on ({@code cx}, {@code cy}) in the container and
     * scaled by {@code scale}: the pivot goes on the glyph centre, and the icon is clipped to the
     * glyph so side decorations (network type, activity arrows) stay off the lattice.
     *
     * @return the {x, y} translation to apply to the icon.
     */
    static float[] placeOnDisc(View icon, RectF glyph, float cx, float cy, float scale) {
        if (icon instanceof StatusBarIconView) {
            ((StatusBarIconView) icon).setPivotOverridden(true);
        } else {
            final Rect clip = new Rect((int) Math.floor(glyph.left), (int) Math.floor(glyph.top),
                    (int) Math.ceil(glyph.right), (int) Math.ceil(glyph.bottom));
            if (!clip.equals(icon.getClipBounds())) {
                icon.setClipBounds(clip);
            }
        }
        icon.setPivotX(glyph.centerX());
        icon.setPivotY(glyph.centerY());
        return new float[] {cx - icon.getLeft() - glyph.centerX(),
                cy - icon.getTop() - glyph.centerY()};
    }

    /** Undoes {@link #placeOnDisc} (the caller resets translation and scale). */
    static void restore(View icon) {
        if (icon instanceof StatusBarIconView) {
            ((StatusBarIconView) icon).setPivotOverridden(false);
        } else {
            if (icon.getClipBounds() != null) {
                icon.setClipBounds(null);
            }
            icon.resetPivot();
        }
    }

    /** Draws the disc behind an icon placed by {@link #placeOnDisc}, following its animation. */
    static void drawDisc(Canvas canvas, View icon, float cell, boolean active, boolean lightBar,
            Paint fill, Paint ring, int accent) {
        if (icon.getVisibility() != View.VISIBLE || icon.getAlpha() <= 0f) {
            return;
        }
        final float cx = icon.getLeft() + icon.getTranslationX() + icon.getPivotX();
        final float cy = icon.getTop() + icon.getTranslationY() + icon.getPivotY();
        final float radius = cell / 2f;
        final float alpha = icon.getAlpha();
        if (active) {
            fill.setColor(accent);
        } else {
            fill.setColor(lightBar ? DISC_ON_LIGHT : DISC_ON_DARK);
        }
        fill.setAlpha(Math.round(Color.alpha(fill.getColor()) * alpha));
        canvas.drawCircle(cx, cy, radius, fill);
        if (!active && !lightBar) {
            ring.setColor(RING_ON_DARK);
            ring.setAlpha(Math.round(Color.alpha(RING_ON_DARK) * alpha));
            canvas.drawCircle(cx, cy, radius - ring.getStrokeWidth() / 2f, ring);
        }
    }

    /** Paint that recolours whatever is drawn through it to {@code color} (glyph override). */
    static Paint tintPaint(Paint paint, int color) {
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        return paint;
    }

    /** Bounds of the drawn disc of an icon, for a small tint layer. */
    static RectF discBounds(View icon, float cell, RectF out) {
        final float cx = icon.getLeft() + icon.getTranslationX() + icon.getPivotX();
        final float cy = icon.getTop() + icon.getTranslationY() + icon.getPivotY();
        out.set(cx - cell / 2f, cy - cell / 2f, cx + cell / 2f, cy + cell / 2f);
        return out;
    }
}
