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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * Grid recents: a vertically scrolling grid of tall, portrait task cards, most recent first, reading
 * left-to-right then top-to-bottom. Three columns on a phone in portrait.
 *
 * <p>A cell is nothing but the app's snapshot in a rounded rectangle, with the app icon straddling
 * the card's top edge. There is no title row and no per-card ✕ — the card layout simply does not
 * contain those views, which the shared binder and the host both tolerate.
 *
 * <h2>Sizing</h2>
 *
 * <p>Everything is derived from the <em>live</em> width of the RecyclerView rather than read out of
 * {@link Resources} once: {@code OneUiRecentsActivity} declares
 * {@code configChanges="orientation|screenSize"}, so it is never recreated on a rotation and any
 * number measured in {@link #createLayoutManager} would stay stuck at the launch orientation's
 * value. {@link #onViewportChanged} re-derives the column count and the card height on every layout
 * pass that actually changes the list's size.
 *
 * <p>The column count comes from a target cell width instead of being hard-coded, so the cards keep
 * roughly the same physical size in landscape and in multi-window as they have in portrait — the
 * grid grows more columns rather than wider cards. The card height then follows from the cell width
 * and a fixed aspect ratio, because rows only line up if every cell is the same height.
 *
 * <h2>Reference metrics</h2>
 *
 * <p>Measured off One UI 8 on a 1080x2400 @ 420dpi (2.625x) display: cards 313x638px
 * (119 x 243dp, aspect 1:2.04), 43px (16dp) between columns, 27px (10dp) outside them, a 104px
 * (40dp) icon centred on the card's top edge, and 99px (38dp) between one row's cards and the next
 * row's icons.
 */
public class GridStyle implements RecentsStyle {

    /**
     * The cell width the column count aims for. 120dp is what One UI 8 lands on with three columns
     * on a 1080px-wide, 420dpi phone.
     */
    private static final float TARGET_CELL_WIDTH_DP = 120f;
    /** Two columns is the narrowest arrangement that still reads as a grid rather than a list. */
    private static final int MIN_SPANS = 2;
    /** Cap: past this the snapshots stop being legible, however wide the window is. */
    private static final int MAX_SPANS = 6;

    /** Space between two columns. */
    private static final float GUTTER_DP = 16f;
    /** Space between the outermost cells and the edge of the list. */
    private static final float OUTER_MARGIN_DP = 10f;
    /**
     * Space between one row's cards and the next row's icons. Much larger than the gutter on
     * purpose: half of each icon hangs above its card, and the reference leaves clear air above that
     * so the icon reads as belonging to the card below it rather than to the card above.
     */
    private static final float ROW_GAP_DP = 38f;

    /** Card height as a multiple of card width. */
    private static final float CARD_ASPECT = 2.04f;
    /**
     * A card may not be taller than this share of the list. Short windows (landscape, multi-window)
     * would otherwise get a single row of cards taller than the screen. The column count is raised
     * first — that keeps the aspect ratio — and only a window too short even at {@link #MAX_SPANS}
     * falls back to squashing the cards.
     */
    private static final float MAX_CARD_VIEWPORT_FRACTION = 0.62f;

    /**
     * Extra room under the final row. The host floats the "Close all" pill over the list rather
     * than placing it after the cards, so scrolled fully to the end the last row would otherwise
     * stop just about level with it. The list's own bottom padding (24dp plus the navigation-bar
     * inset) already covers most of the pill; this is the margin on top of that.
     */
    private static final float CLEAR_ALL_CLEARANCE_DP = 32f;

    /** Size of the fallback app icon in an empty snapshot well, as a share of the card width. */
    private static final float NO_SNAPSHOT_ICON_FRACTION = 0.42f;

    private int mCardWidthPx;
    private int mCardHeightPx;
    private int mViewportWidthPx;
    private int mViewportHeightPx;

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        // Seeded from the display so the first layout pass is already close; the real value comes
        // from the measured list in onViewportChanged, including after every rotation.
        final Resources res = context.getResources();
        final DisplayMetrics dm = res.getDisplayMetrics();
        final int seedWidth = dm.widthPixels - 2 * dp(res, OUTER_MARGIN_DP);
        return new GridLayoutManager(context, resolveSpans(res, seedWidth, dm.heightPixels));
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_grid;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        final Resources res = recyclerView.getResources();

        // The margin outside the first and last columns is the list's own horizontal padding, so the
        // decoration only has to deal with the seams between columns. The host's inset listener
        // rewrites the vertical padding but preserves whatever horizontal padding it finds here.
        final int outerMargin = dp(res, OUTER_MARGIN_DP);
        recyclerView.setPadding(outerMargin, recyclerView.getPaddingTop(),
                outerMargin, recyclerView.getPaddingBottom());
        // Already set in the host layout; asserted here because the cards are sized against the
        // padded content width and would be cut off at the status bar if it were ever turned on.
        recyclerView.setClipToPadding(false);

        recyclerView.addItemDecoration(new GridSpacing(dp(res, GUTTER_DP), dp(res, ROW_GAP_DP),
                dp(res, CLEAR_ALL_CLEARANCE_DP)));

        // The host follows every dismiss with notifyItemRangeChanged over the tail. No cell in this
        // style looks different depending on its position, so all DefaultItemAnimator's change
        // animation adds is a cross-fade of every surviving cell. Drop it; the remove and move
        // animations stay, and they are what closes the hole the dismissed cell left.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        recyclerView.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> onViewportChanged((RecyclerView) v));
    }

    /**
     * Re-derives the column count and the card height from the list's current size. Called on every
     * layout pass, but only does work when the size actually changed — in practice the first layout
     * and each rotation or multi-window resize.
     */
    private void onViewportChanged(RecyclerView rv) {
        final int width = rv.getWidth() - rv.getPaddingLeft() - rv.getPaddingRight();
        final int height = rv.getHeight();
        if (width <= 0 || height <= 0
                || (width == mViewportWidthPx && height == mViewportHeightPx)) {
            return;
        }
        mViewportWidthPx = width;
        mViewportHeightPx = height;

        final Resources res = rv.getResources();
        final int spans = resolveSpans(res, width, height);
        final int cardWidth = cellWidth(res, width, spans);
        final int cardHeight = cardHeight(res, width, height, spans);

        final GridLayoutManager lm = rv.getLayoutManager() instanceof GridLayoutManager
                ? (GridLayoutManager) rv.getLayoutManager() : null;
        final boolean spansChanged = lm != null && lm.getSpanCount() != spans;
        final boolean sizeChanged = cardHeight != mCardHeightPx || cardWidth != mCardWidthPx;
        mCardWidthPx = cardWidth;
        mCardHeightPx = cardHeight;
        if (!spansChanged && !sizeChanged) {
            return;
        }

        // We are inside a layout pass, so requesting another one from here is illegal; defer. Cells
        // recycled in the meantime pick the new height up from onBindCard on their own.
        rv.post(() -> {
            if (spansChanged) {
                lm.setSpanCount(spans);
                rv.invalidateItemDecorations();
            }
            for (int i = 0; i < rv.getChildCount(); i++) {
                applyCardHeight(rv.getChildAt(i));
            }
        });
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Reset, unconditionally, everything a recycled cell can arrive carrying. ItemTouchHelper
        // translates and raises the view it is swiping and only restores that in clearView, so a
        // cell recycled mid-swipe — which a dismiss makes likely — otherwise comes back offset,
        // half-faded and floating. Nothing here depends on the position, so a flat reset is right.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setAlpha(1f);
        card.setElevation(0f);

        // The card has no title view, so without this there is nothing for TalkBack to announce.
        card.setContentDescription(item != null ? item.label : null);

        applyCardHeight(card);

        final ImageView thumbnail = card.findViewById(R.id.oneui_recents_card_thumbnail);
        if (thumbnail != null) {
            applySnapshotFallback(thumbnail, hasSnapshot(item));
        }
    }

    @Override
    public int getSwipeDirs() {
        // Opting in explicitly. Swipe is only safe in a style whose cards do not overlap:
        // ItemTouchHelper picks its target with RecyclerView#findChildViewUnder, which walks the
        // children back-to-front on raw bounds alone — no Z, no scale — so in an overlapping layout
        // it hands back a card the user is not touching. Here every cell has its own rectangle (the
        // spacing decoration only ever returns non-negative offsets, and nothing translates a card),
        // so the hit test cannot be ambiguous. The icon overhang does not change that: it is drawn
        // inside its own item's bounds, in the top padding reserved for it.
        //
        // Horizontal only: the grid scrolls vertically, so UP/DOWN would have ItemTouchHelper claim
        // vertical drags that started on a cell and the grid would barely scroll.
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    private void applyCardHeight(View card) {
        if (mCardHeightPx <= 0) {
            return;
        }
        final View thumbnail = card.findViewById(R.id.oneui_recents_card_thumbnail);
        if (thumbnail == null) {
            return;
        }
        final ViewGroup.LayoutParams lp = thumbnail.getLayoutParams();
        if (lp != null && lp.height != mCardHeightPx) {
            lp.height = mCardHeightPx;
            thumbnail.setLayoutParams(lp);
        }
    }

    /**
     * Insets the thumbnail when the task has no snapshot, so the fallback stands in as a "no
     * preview" marker instead of filling the card.
     *
     * <p>{@code RecentsCardBinder} (package-private, hence not linked) draws {@code item.icon} into
     * the thumbnail under {@code CENTER_INSIDE} when a task has no snapshot. On a cell this tall
     * that would be an icon the size of a phone screen. The binder restores the layout's declared
     * {@code centerCrop} by itself on the next real snapshot, so the scale type is deliberately not
     * touched here — only the padding, which the binder does not manage, has to be undone.
     *
     * <p>The inset is padding rather than an alpha or any other drawable-level effect on purpose:
     * {@code item.icon} is the same {@code Drawable} instance the overhanging icon is showing, and
     * for a PackageManager-loaded icon possibly one shared well beyond this card, so mutating it
     * would dim that icon too.
     */
    private void applySnapshotFallback(ImageView thumbnail, boolean hasSnapshot) {
        if (hasSnapshot || mCardWidthPx <= 0 || mCardHeightPx <= 0) {
            thumbnail.setPadding(0, 0, 0, 0);
            return;
        }
        // Inset from both axes towards the same square, so the icon's size is set by the card's
        // width; deriving it from the height alone would ask for a box wider than the card.
        final int box = Math.round(mCardWidthPx * NO_SNAPSHOT_ICON_FRACTION);
        final int insetX = Math.max(0, (mCardWidthPx - box) / 2);
        final int insetY = Math.max(0, (mCardHeightPx - box) / 2);
        thumbnail.setPadding(insetX, insetY, insetX, insetY);
    }

    private static boolean hasSnapshot(RecentsTaskSource.TaskItem item) {
        final Bitmap bmp = item != null ? item.thumbnail : null;
        return bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0;
    }

    /**
     * The column count for a list this size: as many columns of roughly
     * {@link #TARGET_CELL_WIDTH_DP} as fit, then raised further while the resulting card would be
     * taller than {@link #MAX_CARD_VIEWPORT_FRACTION} of the list. Narrowing the cells is the only
     * way to shorten the cards without abandoning their aspect ratio.
     */
    private static int resolveSpans(Resources res, int contentWidthPx, int viewportHeightPx) {
        final int target = Math.max(1, dp(res, TARGET_CELL_WIDTH_DP));
        int spans = Math.round(contentWidthPx / (float) target);
        spans = Math.max(MIN_SPANS, Math.min(MAX_SPANS, spans));

        final int maxCardHeight = Math.round(viewportHeightPx * MAX_CARD_VIEWPORT_FRACTION);
        while (spans < MAX_SPANS
                && naturalCardHeight(res, contentWidthPx, spans) > maxCardHeight) {
            spans++;
        }
        return spans;
    }

    /** Width of one cell once the gutters between the columns have been taken out. */
    private static int cellWidth(Resources res, int contentWidthPx, int spans) {
        final int gutter = dp(res, GUTTER_DP);
        return Math.max(1, (contentWidthPx - gutter * (spans - 1)) / spans);
    }

    private static int naturalCardHeight(Resources res, int contentWidthPx, int spans) {
        return Math.round(cellWidth(res, contentWidthPx, spans) * CARD_ASPECT);
    }

    /** The card height every cell in this configuration gets, aspect ratio permitting. */
    private static int cardHeight(Resources res, int contentWidthPx, int viewportHeightPx,
            int spans) {
        final int max = Math.round(viewportHeightPx * MAX_CARD_VIEWPORT_FRACTION);
        return Math.max(1, Math.min(max, naturalCardHeight(res, contentWidthPx, spans)));
    }

    private static int dp(Resources res, float value) {
        return Math.round(value * res.getDisplayMetrics().density);
    }

    /**
     * Even gutters between columns, a fixed gap between rows, and extra room under the last row.
     *
     * <p>Splitting one gutter across the two cells that share it keeps every column exactly the same
     * width, which per-card margins cannot do. The span count is read back off the layout manager on
     * every call rather than cached, so the offsets stay right after a rotation has changed the
     * column count under us.
     */
    private static final class GridSpacing extends RecyclerView.ItemDecoration {

        private final int mGutter;
        private final int mRowGap;
        private final int mLastRowExtra;

        GridSpacing(int gutter, int rowGap, int lastRowExtra) {
            mGutter = gutter;
            mRowGap = rowGap;
            mLastRowExtra = lastRowExtra;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                RecyclerView.State state) {
            final int spanCount = spanCountOf(parent);
            final int column = columnOf(view, parent, spanCount);
            outRect.left = column * mGutter / spanCount;
            outRect.right = mGutter - (column + 1) * mGutter / spanCount;
            // No top offset: the item reserves its own space for the overhanging icon as padding,
            // so adding one here would double the gap above every row.
            outRect.top = 0;
            outRect.bottom = mRowGap + (isInLastRow(view, parent, state, spanCount)
                    ? mLastRowExtra : 0);
        }

        private static boolean isInLastRow(View view, RecyclerView parent, RecyclerView.State state,
                int spanCount) {
            final int position = parent.getChildAdapterPosition(view);
            final int count = state.getItemCount();
            if (position == RecyclerView.NO_POSITION || count <= 0) {
                return false;
            }
            // First position of the final row, for a grid where every card spans one column.
            return position >= count - 1 - ((count - 1) % spanCount);
        }

        private static int spanCountOf(RecyclerView parent) {
            final RecyclerView.LayoutManager lm = parent.getLayoutManager();
            return lm instanceof GridLayoutManager
                    ? Math.max(1, ((GridLayoutManager) lm).getSpanCount()) : 1;
        }

        private static int columnOf(View view, RecyclerView parent, int spanCount) {
            final ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof GridLayoutManager.LayoutParams) {
                final int index = ((GridLayoutManager.LayoutParams) lp).getSpanIndex();
                if (index != GridLayoutManager.LayoutParams.INVALID_SPAN_ID) {
                    return index;
                }
            }
            // Not laid out yet: every card spans one column, so the position gives the same answer.
            final int position = parent.getChildAdapterPosition(view);
            return position == RecyclerView.NO_POSITION ? 0 : position % spanCount;
        }
    }

    // --- One UI Home chrome and motion (OneUiChrome, SPEC_RECENTS.md Part 2).

    @Override
    public void styleClearAll(TextView clearAll) {
        OneUiChrome.styleClearAll(clearAll);
    }

    @Override
    public void animateEnter(RecyclerView recyclerView) {
        OneUiChrome.enterGrid(recyclerView);
    }

    /** One UI dismisses on a fling of 2000 px/s or more. */
    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return OneUiChrome.SWIPE_ESCAPE_PX_PER_S;
    }
}
