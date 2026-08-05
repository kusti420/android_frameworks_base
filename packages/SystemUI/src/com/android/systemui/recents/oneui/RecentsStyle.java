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

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * One selectable recents layout.
 *
 * <p>{@link OneUiRecentsActivity} owns the window, the task list, and all launch/dismiss plumbing;
 * a style only decides how the cards are arranged and how each card looks. Implementations must be
 * stateless with respect to task data — they are handed the {@link RecentsTaskSource.TaskItem} at
 * bind time and must not cache it.
 *
 * <p>Card layouts must use the shared IDs declared in {@code res/values/oneui_recents_ids.xml}
 * ({@code oneui_recents_card_icon}, {@code _title}, {@code _thumbnail}, and optionally
 * {@code _dismiss} / {@code _header}); {@link RecentsCardBinder} populates those before
 * {@link #onBindCard} runs, so a style only has to add its own flourishes.
 */
public interface RecentsStyle {

    /** Dismisses the task at an adapter position, animating the list and refreshing empty state. */
    interface Dismisser {
        void dismissTask(int position);
    }

    /**
     * Handed the host's dismiss entry point before {@link #onRecyclerViewCreated}.
     *
     * <p>Exists because dismissal used to be reachable only by clicking a view with id
     * {@code oneui_recents_card_dismiss}. The One UI styles have no ✕ at all, which forced them to
     * carry a 0x0 {@code gone} view under that id purely as a callback handle — a hack two separate
     * styles independently arrived at. A style that drives its own dismiss gesture should keep this
     * reference and call it instead.
     */
    default void setDismisser(Dismisser dismisser) {}

    /** The arrangement for this style. Called once, in {@code onCreate}. */
    RecyclerView.LayoutManager createLayoutManager(Context context);

    /** The per-task card layout. Must contain the shared card IDs. */
    int getCardLayoutRes();

    /**
     * Hook for item decorations, scroll-driven transforms, snapping, etc. Called once after the
     * layout manager and adapter are attached.
     */
    default void onRecyclerViewCreated(RecyclerView recyclerView) {}

    /**
     * Per-card flourishes (overlap offsets, scale falloff, elevation ordering). The shared binder
     * has already set the icon, title, colours and snapshot.
     *
     * @param position index into the task list, 0 == most recent
     * @param count    total number of tasks currently shown
     */
    default void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {}

    /**
     * Swipe-to-dismiss directions, as {@link ItemTouchHelper} flags. Return 0 for no swipe.
     *
     * <p>Swipe is deliberately <em>opt-in</em>. {@link ItemTouchHelper} resolves its target with
     * {@code RecyclerView.findChildViewUnder}, which walks children from the last index down and
     * tests raw bounds only — it ignores Z and ignores scale. In any style whose cards overlap,
     * the cards behind the front one still have bounds under the finger and higher child indices
     * (child index ascends with adapter position regardless of {@code reverseLayout}), so the
     * helper scans the stack back-to-front, against draw order, and silently dismisses the wrong
     * task — possibly one faded to invisible. Defaulting this to LEFT|RIGHT made that the trap
     * every overlapping style fell into by doing nothing, so the default is now 0: a style that
     * wants swipe must say so, having satisfied itself that its cards do not overlap (or, like
     * the cascade, having supplied its own depth-aware hit test).
     */
    default int getSwipeDirs() {
        return 0;
    }

    /** Whether tapping the scrim behind the cards should close recents. */
    default boolean dismissOnScrimTap() {
        return true;
    }
}
