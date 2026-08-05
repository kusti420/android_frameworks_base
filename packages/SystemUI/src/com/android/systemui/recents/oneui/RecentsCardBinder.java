/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.recents.oneui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.res.R;

/**
 * Fills in the parts of a recents card that every style shares: app icon, title, header tint and
 * the task snapshot. Styles then layer their own arrangement on top via
 * {@link RecentsStyle#onBindCard}.
 */
final class RecentsCardBinder {

    private RecentsCardBinder() {}

    static void bind(View card, RecentsTaskSource.TaskItem item) {
        final ImageView icon = card.findViewById(R.id.oneui_recents_card_icon);
        final TextView title = card.findViewById(R.id.oneui_recents_card_title);
        final View dismiss = card.findViewById(R.id.oneui_recents_card_dismiss);
        final ImageView thumbnail = card.findViewById(R.id.oneui_recents_card_thumbnail);
        final View header = card.findViewById(R.id.oneui_recents_card_header);

        if (icon != null) {
            icon.setImageDrawable(item.icon);
        }
        if (title != null) {
            title.setText(item.label);
        }

        // Tint the header with the app's primary colour. Styles without a coloured header simply
        // omit the header view.
        if (header != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(item.color);
            header.setBackground(bg);

            // Only re-colour the text that actually sits ON the tinted header. A style is free to
            // use the header ID for something that is not a text background — an accent strip down
            // the edge of a slim row, say — and in that case the title keeps its own contrast
            // against the card, so deriving its colour from item.color would wash it out (a light
            // app colour would paint near-black text onto a dark row).
            final int fg = RecentsTaskSource.isDarkColor(item.color) ? Color.WHITE : 0xDE000000;
            if (isDescendantOf(title, header)) {
                title.setTextColor(fg);
            }
            if (dismiss instanceof TextView && isDescendantOf(dismiss, header)) {
                ((TextView) dismiss).setTextColor(fg);
            }
        }

        if (thumbnail != null) {
            bindThumbnail(thumbnail, item);
        }
    }

    /**
     * True when {@code view} is {@code ancestor} itself or sits anywhere beneath it. Used to decide
     * whether a card's text is drawn on top of the app-tinted header.
     */
    private static boolean isDescendantOf(View view, View ancestor) {
        if (view == null || ancestor == null) {
            return false;
        }
        for (Object p = view; p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void bindThumbnail(ImageView thumbnail, RecentsTaskSource.TaskItem item) {
        final Bitmap bmp = item.thumbnail;
        final boolean usable = bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0;

        // Remember what the layout asked for. The no-snapshot branch below overwrites the scale
        // type, and RecyclerView reuses views — so without stashing the declared value, a single
        // no-snapshot bind would permanently downgrade that view: every later real snapshot would
        // be letterboxed, and for the styles that declare MATRIX (Grace cascade, Stack) the 7.1.1
        // scale-to-width treatment would silently never run on it again.
        ImageView.ScaleType declared =
                (ImageView.ScaleType) thumbnail.getTag(R.id.oneui_recents_declared_scale_type);
        if (declared == null) {
            declared = thumbnail.getScaleType();
            thumbnail.setTag(R.id.oneui_recents_declared_scale_type, declared);
        }

        // Always keep the thumbnail area visible at its declared size. Setting it GONE when a
        // snapshot is missing collapses the card to a header-only strip, which is what caused the
        // intermittent "last card is just a title bar" bug in the Classic style.
        thumbnail.setVisibility(View.VISIBLE);

        if (!usable) {
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            thumbnail.setImageMatrix(new Matrix());
            thumbnail.setImageDrawable(item.icon);
            return;
        }

        // Restore the layout's intent before drawing a real snapshot, undoing any CENTER_INSIDE
        // left behind by a previous no-snapshot bind on this recycled view.
        thumbnail.setScaleType(declared);

        // A layout that declares MATRIX wants the 7.1.1 treatment: scale the snapshot to the card
        // width and pin it to the top, so you see the app's actual last frame from the top down.
        // Any other declared scale type (centerCrop for grid/list cards) is honoured as-is.
        if (declared == ImageView.ScaleType.MATRIX) {
            thumbnail.setImageBitmap(bmp);
            thumbnail.post(() -> {
                int vw = thumbnail.getWidth();
                if (vw <= 0) {
                    return;
                }
                float scale = (float) vw / bmp.getWidth();
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                thumbnail.setImageMatrix(m);
            });
        } else {
            thumbnail.setImageBitmap(bmp);
        }
    }
}
