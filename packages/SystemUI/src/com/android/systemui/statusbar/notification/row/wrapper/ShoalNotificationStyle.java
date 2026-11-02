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

package com.android.systemui.statusbar.notification.row.wrapper;

import android.app.Notification;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.widget.EmphasizedNotificationButton;
import com.android.settingslib.ichthys.ShoalContrast;
import com.android.systemui.shade.ui.ShoalShade;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * IchthysOS "Shoal": restyles the standard notification templates after they are applied, when
 * the Shoal surface style is on and the dark theme is active. Title 15 sp semibold, meta line
 * 12 sp muted, body 14 sp, all in the theme's text face; the small-icon disc becomes a dark
 * accent-tinted disc with an accent glyph; plain action buttons become 32 dp pills.
 * <p>
 * The faces alone (text face, mono for timers and counts) apply whenever Shoal is on, light or
 * dark, colorized or not. In Shoal the icon is always the small icon (see
 * NotificationRowIconViewInflaterFactory), never the launcher icon.
 * <p>
 * Colorized and media notifications keep their own colours. Only framework template ids are
 * touched, so an app's custom content view is left alone.
 */
final class ShoalNotificationStyle {

    private ShoalNotificationStyle() {}

    private static final int[] META_IDS = {
            com.android.internal.R.id.app_name_text,
            com.android.internal.R.id.app_name_text_divider,
            com.android.internal.R.id.header_text,
            com.android.internal.R.id.header_text_divider,
            com.android.internal.R.id.header_text_secondary,
            com.android.internal.R.id.header_text_secondary_divider,
            com.android.internal.R.id.time,
            com.android.internal.R.id.time_divider,
            com.android.internal.R.id.verification_text,
    };

    /** Numeral readouts in the meta line: the mono face (timers count up / down in place). */
    private static final int[] MONO_META_IDS = {
            com.android.internal.R.id.chronometer,
    };

    private static final int[] TITLE_IDS = {
            com.android.internal.R.id.title,
            com.android.internal.R.id.alt_title,
    };

    private static final int[] BODY_IDS = {
            com.android.internal.R.id.text,
            com.android.internal.R.id.big_text,
            com.android.internal.R.id.inbox_text0,
            com.android.internal.R.id.inbox_text1,
            com.android.internal.R.id.inbox_text2,
            com.android.internal.R.id.inbox_text3,
            com.android.internal.R.id.inbox_text4,
            com.android.internal.R.id.inbox_text5,
            com.android.internal.R.id.inbox_text6,
    };

    static void apply(View root, ExpandableNotificationRow row) {
        if (root == null || row == null) {
            return;
        }
        final Context context = root.getContext();
        if (!ShoalShade.isActive(context)) {
            return;
        }
        final Notification n;
        try {
            n = row.getEntryAdapter().getSbn().getNotification();
        } catch (Exception e) {
            return;
        }
        if (n == null || n.isMediaNotification()) {
            return;
        }

        // The type system applies to every template, light or dark, colorized or not: the text
        // face everywhere, the mono face for numeral readouts. Only the faces change here.
        applyFaces(root, context);

        if (!ShoalShade.isInk(context) || n.isColorized()) {
            return;
        }

        for (int id : TITLE_IDS) {
            styleText(root.findViewById(id), ShoalShade.text(context, 600), 15f,
                    ShoalShade.textColor(context));
        }
        for (int id : META_IDS) {
            styleText(root.findViewById(id), ShoalShade.text(context, 400), 12f,
                    ShoalShade.mutedColor(context));
        }
        for (int id : MONO_META_IDS) {
            styleText(root.findViewById(id), ShoalShade.mono(context, 500), 12f,
                    ShoalShade.mutedColor(context));
        }
        for (int id : BODY_IDS) {
            styleText(root.findViewById(id), ShoalShade.text(context, 400), 14f,
                    ShoalShade.bodyColor(context));
        }

        styleIcon(root.findViewById(com.android.internal.R.id.icon), context);

        final View actions = root.findViewById(com.android.internal.R.id.actions);
        if (actions instanceof ViewGroup) {
            final ViewGroup list = (ViewGroup) actions;
            for (int i = 0; i < list.getChildCount(); i++) {
                styleAction(list.getChildAt(i), context);
            }
        }
    }

    /** Typefaces only (no size / colour), for every template id plus the messaging bubbles. */
    private static void applyFaces(View root, Context context) {
        final Typeface title = ShoalShade.text(context, 600);
        final Typeface text = ShoalShade.text(context, 400);
        final Typeface mono = ShoalShade.mono(context, 500);
        for (int id : TITLE_IDS) {
            setFace(root.findViewById(id), title);
        }
        for (int id : META_IDS) {
            setFace(root.findViewById(id), text);
        }
        for (int id : BODY_IDS) {
            setFace(root.findViewById(id), text);
        }
        for (int id : MONO_META_IDS) {
            setFace(root.findViewById(id), mono);
        }
        // The collapsed group's child count ("7") beside the expand chevron.
        setFace(root.findViewById(com.android.internal.R.id.expand_button_number), mono);
        // Messaging / conversation bubbles are inflated per message: walk the list.
        final View messaging = root.findViewById(com.android.internal.R.id.notification_messaging);
        if (messaging instanceof ViewGroup) {
            faceMessages((ViewGroup) messaging, title, text);
        }
    }

    private static void faceMessages(ViewGroup group, Typeface name, Typeface text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            final int id = child.getId();
            if (id == com.android.internal.R.id.message_name) {
                setFace(child, name);
            } else if (id == com.android.internal.R.id.message_text) {
                setFace(child, text);
            } else if (child instanceof ViewGroup) {
                faceMessages((ViewGroup) child, name, text);
            }
        }
    }

    private static void setFace(View v, Typeface face) {
        if (v instanceof TextView) {
            ((TextView) v).setTypeface(face);
        }
    }

    private static void styleText(View v, android.graphics.Typeface face, float sp, int color) {
        if (!(v instanceof TextView)) {
            return;
        }
        final TextView t = (TextView) v;
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
    }

    /** Small icon only: an app (launcher / adaptive) icon has no disc and keeps its colours. */
    private static void styleIcon(View v, Context context) {
        if (!(v instanceof ImageView)) {
            return;
        }
        final ImageView icon = (ImageView) v;
        final Drawable disc = icon.getBackground();
        final Drawable glyph = icon.getDrawable();
        if (disc == null || glyph == null || glyph instanceof AdaptiveIconDrawable) {
            return;
        }
        final int discColor = ShoalShade.accentTint(context);
        disc.mutate().setColorFilter(discColor, PorterDuff.Mode.SRC_ATOP);
        // IchthysOS: the accent glyph on its accent-tinted disc, contrast-guarded against the
        // disc (a dark accent's glyph sank into its own dark tint).
        glyph.mutate().setColorFilter(
                ShoalContrast.forGraphic(ShoalShade.accent(context), discColor),
                PorterDuff.Mode.SRC_ATOP);
    }

    private static void styleAction(View v, Context context) {
        if (!(v instanceof Button)) {
            return;
        }
        final Button b = (Button) v;
        b.setTypeface(ShoalShade.text(context, 500));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        if (b instanceof EmphasizedNotificationButton) {
            // Emphasized buttons carry the app's semantic colours (answer / decline ...).
            return;
        }
        // A 32 dp pill (radius 16) inside the stock touch target.
        b.setBackgroundTintList(null);
        b.setBackground(ShoalShade.pill(context, ShoalShade.dividerColor(context), 0, 32));
        b.setTextColor(ShoalShade.textColor(context));
    }
}
