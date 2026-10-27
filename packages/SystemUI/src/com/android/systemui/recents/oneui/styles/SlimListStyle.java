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

package com.android.systemui.recents.oneui.styles;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * Slim list: the recent tasks as a column of full-width pills, most recent at the top.
 *
 * <p>"Slim" is about the row, not the list. Every other style spends the screen on previews; this
 * one spends it on nothing at all. A row is a 64dp pill carrying an app icon and an app name and
 * no third thing — no snapshot, no dismiss ✕, no app-coloured accent — separated from its
 * neighbours by a 16dp gap. The result is airier than a dense list (about ten tasks on a phone
 * screen) while still being a list rather than a deck: you read it, you do not flick through it.
 *
 * <p>That austerity is what carries the look. Because the rows hold no imagery, they can be a flat
 * uniform near-black with no border and no elevation, and the wallpaper the host leaves visible
 * behind them becomes the only colour on screen. Anything reintroduced into the row — a tinted
 * strip, a thumbnail chip — costs that, which is why {@code oneui_recents_card_slim.xml} omits the
 * {@code _thumbnail}, {@code _dismiss} and {@code _header} IDs outright rather than hiding them.
 *
 * <p>Nothing here is sized from the display, so a rotation the host absorbs without being recreated
 * needs no fixing up: the row is a fixed height and its two children are fixed and weighted.
 */
public class SlimListStyle implements RecentsStyle {

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        // Plain vertical, unreversed: adapter position 0 (most recent) lands at the top, which is
        // also where OneUiRecentsActivity scrolls to on every load.
        return new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_slim;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        // The host follows every dismiss with notifyItemRangeChanged over the tail. No row in this
        // style looks different depending on its position, so the only thing DefaultItemAnimator's
        // change animation contributes is a cross-fade of every surviving row — most of the screen
        // flickering to redraw pixels that did not change. Drop it; the remove and move animations
        // stay, and they are what closes the hole the dismissed row left.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Every row is identical apart from its icon and label, both of which RecentsCardBinder has
        // already set, so the only work left is undoing what a *previous* life of this recycled view
        // may have left on it. ItemTouchHelper translates the view it is dragging and raises it above
        // its neighbours, and restores that only in clearView; a row recycled mid-swipe — which
        // dismissing by swipe makes the common case, not the rare one — otherwise comes back offset,
        // half-faded, and casting a shadow that flat rows are not supposed to have. Unconditional
        // rather than guarded: reading the four properties to decide whether to write them costs the
        // same as writing them.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setAlpha(1f);
        card.setElevation(0f);
    }

    /** IchthysOS: the row has no thumbnail view, so the snapshots would only be fetched for nothing. */
    @Override
    public boolean showsSnapshots() {
        return false;
    }

    @Override
    public int getSwipeDirs() {
        // With no ✕ in the row, swipe is the only way to dismiss a single task, so this style has to
        // opt in — and it is one of the styles that safely can. ItemTouchHelper resolves its target
        // with RecyclerView#findChildViewUnder, which walks the children back-to-front on raw bounds
        // alone (no Z, no scale), so in an overlapping layout it hands back a row the user is not
        // touching and dismisses the wrong task. Nothing overlaps here: the rows are separated by
        // positive vertical margins, there is no item decoration, and no row is translated.
        //
        // Horizontal only. The list scrolls vertically, so UP|DOWN would have ItemTouchHelper claim
        // vertical drags that start on a row — which is all of them — and the list would barely
        // scroll.
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    // --- One UI Home chrome and motion (OneUiChrome, SPEC_RECENTS.md Part 2).

    @Override
    public void styleClearAll(TextView clearAll) {
        OneUiChrome.styleClearAll(clearAll);
    }

    @Override
    public void animateEnter(RecyclerView recyclerView) {
        OneUiChrome.enterVertical(recyclerView);
    }

    /** One UI dismisses on a fling of 2000 px/s or more. */
    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return OneUiChrome.SWIPE_ESCAPE_PX_PER_S;
    }
}
