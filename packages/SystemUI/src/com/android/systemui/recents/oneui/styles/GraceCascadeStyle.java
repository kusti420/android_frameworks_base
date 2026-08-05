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

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * The Android 7.1.1 recents stack — the cascade of task cards that Samsung's Grace UX shipped
 * essentially unchanged, and that AOSP dropped when Quickstep landed.
 *
 * <p>Almost nothing lives here: the arrangement is all in {@link CascadeLayoutManager}, which is an
 * adaptation of 7.1.1's {@code TaskStackLayoutAlgorithm} (Apache-2.0). That is the point — in 7.1.1
 * a card's position, depth and dim are a pure function of one scroll value and the card's index, so
 * there is no per-bind transform to apply and nothing for the style to remember. This class only
 * picks the card layout, hands back the layout manager, and turns off the one item animation that
 * looks wrong on overlapping cards.
 *
 * <p>Nothing here is derived from Samsung's framework; it is AOSP's own algorithm, restored.
 */
public class GraceCascadeStyle implements RecentsStyle {

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        return new CascadeLayoutManager(context);
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_grace;
    }

    /**
     * Sideways, either way, as in 7.1.1 — and using the host's own {@link ItemTouchHelper} rather
     * than a bespoke one.
     *
     * <p>This used to have to return 0. {@code ItemTouchHelper} picks the swiped card with
     * {@link RecyclerView#findChildViewUnder}, which walks children from the last index down and
     * tests raw bounds, ignoring Z entirely; the old {@code LinearLayoutManager} attached children
     * in ascending adapter-position order, so the walk ran from the <em>back</em> of the cascade
     * forwards, against draw order, and a swipe on the front card's header could dismiss a card
     * five layers behind it. {@link CascadeLayoutManager}
     * attaches children in descending position order instead, so child index order is Z order and
     * the stock hit test lands on exactly the card the user can see.
     */
    @Override
    public int getSwipeDirs() {
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        // The host re-binds the whole tail after a dismissal. With overlapping cards the default
        // change animation cross-fades every one of them, which reads as a flicker through the
        // entire cascade. Only the change animation is disabled: the remove and move animations are
        // wanted, and they are what slides the cards behind a dismissed one into its place.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Position, depth and dim are all scroll-driven and belong to the layout manager. The only
        // thing left to do at bind time is undo a swipe: a card dismissed by a fling is left
        // translated off-screen when it goes back to the pool, and would come back that way.
        card.setTranslationX(0f);
        card.setAlpha(1f);
    }
}
