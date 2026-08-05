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
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.systemui.recents.oneui.RecentsStyle;
import com.android.systemui.recents.oneui.RecentsTaskSource;
import com.android.systemui.res.R;

/**
 * List recents, modelled on the One UI "Vertical list" task changer.
 *
 * <p>The arrangement that makes this style what it is lives in
 * {@code res/layout/oneui_recents_card_list.xml}: the app icon and name are <em>outside</em> the
 * card, in their own column down the left of the screen, and the card is a bare rounded snapshot
 * occupying a little over half the width on the right. There is no header bar, no title over the
 * snapshot and no ✕. What this class contributes is the geometry — how big a card is in the window
 * it currently finds itself in — plus the gap between rows and the recycling hygiene.
 *
 * <p>Every number here was measured off a reference device (1080x2400 at 420dpi, so 2.625 px/dp):
 *
 * <ul>
 *   <li>card 585x447px = 223x170dp, i.e. 54.2% of the screen width at 1.31:1;
 *   <li>card 19dp off the right edge, icon column 19dp off the left, leaving a 150dp label column;
 *   <li>26dp gap between cards, so a 196dp row pitch;
 *   <li>26dp corner radius, and no shadow whatsoever — the card edge meets the wallpaper flat;
 *   <li>icon 28dp, top-aligned with the card; label 14sp medium, white, centred on the icon.
 * </ul>
 *
 * <p>The card is sized from the <em>live</em> {@link RecyclerView}, never from {@link Resources} at
 * construction time. {@code OneUiRecentsActivity} declares {@code configChanges}, so it is not
 * recreated on a rotation and anything measured once would stay stuck at the old orientation's
 * numbers — which is exactly how the previous revision of this style ended up with a single
 * screen-filling card in landscape.
 */
public class ListStyle implements RecentsStyle {

    /**
     * Card width as a fraction of the window width — 585/1080 on the reference device. A fraction
     * rather than a dp constant because the card's whole job is to leave a readable column of
     * wallpaper beside it, and that reads as a proportion of the screen, not as a fixed width.
     */
    private static final float CARD_WIDTH_FRACTION = 0.542f;

    /** Card width divided by card height, measured: 585/447. */
    private static final float CARD_ASPECT = 1.31f;

    /**
     * Height ceiling, as a fraction of the list's height. Only bites when the window is short —
     * landscape and multi-window — where the aspect-derived height would be most of the window and
     * you could not tell there was a list at all. Letting the card stay wide and go squatter is the
     * right trade there: in landscape the snapshots are landscape too, so a wide card still frames
     * them better than a narrow one would.
     */
    private static final float MAX_CARD_HEIGHT_FRACTION = 0.5f;

    /** The icon column stops being a column below this, so the card yields width instead. */
    private static final float MIN_LABEL_COLUMN_DP = 96f;
    /** Floors, so a freak window size cannot collapse the card to nothing. */
    private static final float MIN_CARD_WIDTH_DP = 120f;
    private static final float MIN_CARD_HEIGHT_DP = 96f;

    /**
     * Kept in sync with {@code android:paddingStart}/{@code paddingEnd} on the row in
     * {@code oneui_recents_card_list.xml}. Needed here only to know how much width the label column
     * is left with once the card has taken its share.
     */
    private static final float ROW_SIDE_PADDING_DP = 19f;

    /** Gap between rows. Measured at 69px = 26dp on the reference device. */
    private static final float CARD_GAP_DP = 26f;

    /** How much of an empty card the fallback app icon is allowed to fill. */
    private static final float NO_SNAPSHOT_ICON_FRACTION = 0.28f;

    private int mCardWidthPx;
    private int mCardHeightPx;
    private int mViewportWidthPx;
    private int mViewportHeightPx;

    @Override
    public RecyclerView.LayoutManager createLayoutManager(Context context) {
        // Plain vertical list: adapter index 0 is the most recent task and belongs at the top, so
        // neither reverseLayout nor stackFromEnd is wanted.
        return new LinearLayoutManager(context, RecyclerView.VERTICAL, false);
    }

    @Override
    public int getCardLayoutRes() {
        return R.layout.oneui_recents_card_list;
    }

    @Override
    public void onRecyclerViewCreated(RecyclerView recyclerView) {
        final Resources res = recyclerView.getResources();

        // Seed from the display so the very first bind — which happens before the list has ever
        // been measured — already uses the right card size and there is no visible resize on the
        // first frame. onViewportChanged replaces this with the measured value immediately after,
        // and that is the one that matters in multi-window, where the display is not the window.
        final DisplayMetrics dm = res.getDisplayMetrics();
        resolveCardSize(res, dm.widthPixels, dm.heightPixels);

        // Spacing lives here rather than in a card margin so that removing a row leaves the run of
        // gaps even, and so the host's own top/bottom padding is not doubled up at the ends.
        recyclerView.addItemDecoration(new GapDecoration(dp(res, CARD_GAP_DP)));

        // The host follows every dismiss with notifyItemRangeChanged over the tail. No row in this
        // style looks different depending on its position, so the only thing DefaultItemAnimator's
        // change animation contributes is a cross-fade of every surviving row. Drop it; the remove
        // and move animations stay, and they are what closes the hole the dismissed row left.
        final RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        recyclerView.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> onViewportChanged((RecyclerView) v));
    }

    /**
     * Re-derives the card size from the list's current size. Called on every layout pass, but only
     * does work when the size actually changed — the first layout, and each rotation or
     * multi-window resize the host absorbs without being recreated.
     */
    private void onViewportChanged(RecyclerView rv) {
        final int width = rv.getWidth();
        final int height = rv.getHeight();
        if (width <= 0 || height <= 0
                || (width == mViewportWidthPx && height == mViewportHeightPx)) {
            return;
        }
        mViewportWidthPx = width;
        mViewportHeightPx = height;

        if (!resolveCardSize(rv.getResources(), width, height)) {
            return;
        }

        // We are inside a layout pass; requesting another one from here is illegal, so defer. Rows
        // that are recycled in the meantime pick the new size up from onBindCard on their own.
        rv.post(() -> {
            for (int i = 0; i < rv.getChildCount(); i++) {
                applyCardSize(rv.getChildAt(i));
            }
        });
    }

    /**
     * Computes the card size for a window of the given size.
     *
     * @return true if the result differs from what was already in effect
     */
    private boolean resolveCardSize(Resources res, int viewportWidthPx, int viewportHeightPx) {
        // Width first, from the fraction, then held back if the label column it would leave is too
        // narrow to read an app name in.
        final int labelRoom = viewportWidthPx - 2 * dp(res, ROW_SIDE_PADDING_DP)
                - dp(res, MIN_LABEL_COLUMN_DP);
        int width = Math.round(viewportWidthPx * CARD_WIDTH_FRACTION);
        width = Math.max(dp(res, MIN_CARD_WIDTH_DP), Math.min(width, labelRoom));

        // Height follows the aspect ratio, then the short-window ceiling. Deliberately does not feed
        // back into the width: a squatter card in landscape is wanted, a narrower one is not.
        int height = Math.round(width / CARD_ASPECT);
        height = Math.max(dp(res, MIN_CARD_HEIGHT_DP),
                Math.min(height, Math.round(viewportHeightPx * MAX_CARD_HEIGHT_FRACTION)));

        if (width == mCardWidthPx && height == mCardHeightPx) {
            return false;
        }
        mCardWidthPx = width;
        mCardHeightPx = height;
        return true;
    }

    @Override
    public void onBindCard(View card, RecentsTaskSource.TaskItem item, int position, int count) {
        // Reset everything a recycled row can arrive carrying, unconditionally. ItemTouchHelper
        // translates the view it is swiping and raises it above every sibling (stashing the old
        // elevation in a tag it only reads back in clearView), so a row recycled mid-swipe — which
        // a dismiss makes likely — otherwise comes back offset and floating: the "ghost row".
        // Nothing here is position-dependent, so a flat reset is right.
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setAlpha(1f);
        // Zero, not a token value: there is no shadow under a One UI task card.
        card.setElevation(0f);

        applyCardSize(card);

        final ImageView thumbnail = card.findViewById(R.id.oneui_recents_card_thumbnail);
        if (thumbnail != null) {
            applySnapshotFallback(thumbnail, hasSnapshot(item));
        }
    }

    @Override
    public int getSwipeDirs() {
        // Opting in explicitly rather than inheriting the interface's "no swipe" default.
        // Swipe-to-dismiss is only safe in a style whose cards do not overlap: ItemTouchHelper picks
        // its target with RecyclerView#findChildViewUnder, which walks the children back-to-front on
        // bounds alone — no getZ(), no scale — so in an overlapping layout it hands back a card the
        // user is not touching and dismisses the wrong task. Nothing overlaps here by design: the
        // gap decoration only ever returns a positive offset and no row is translated at rest, so
        // the hit test is exact.
        //
        // Horizontal only: the list scrolls vertically, so UP/DOWN would have ItemTouchHelper claim
        // vertical drags off a row and the list would barely scroll.
        return ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
    }

    /**
     * Pushes the current card size onto a row. The row itself is {@code wrap_content} and the label
     * column is weighted, so sizing the card sizes the whole row: its height becomes the row height,
     * and whatever width it does not take is the icon-and-name column.
     */
    private void applyCardSize(View card) {
        if (mCardWidthPx <= 0 || mCardHeightPx <= 0) {
            return;
        }
        final ImageView thumbnail = card.findViewById(R.id.oneui_recents_card_thumbnail);
        if (thumbnail == null) {
            return;
        }
        final ViewGroup.LayoutParams lp = thumbnail.getLayoutParams();
        if (lp != null && (lp.width != mCardWidthPx || lp.height != mCardHeightPx)) {
            lp.width = mCardWidthPx;
            lp.height = mCardHeightPx;
            thumbnail.setLayoutParams(lp);
        }
        // Both ways a card can be presented are size-dependent — the snapshot's scale matrix and
        // the fallback icon's inset — so a resize has to redo whichever one this card is currently
        // in. Once bound, the scale type is a faithful record of that: the binder leaves MATRIX for
        // a usable snapshot and CENTER_INSIDE for the icon fallback, decided by the same test
        // hasSnapshot makes.
        applySnapshotFallback(thumbnail, thumbnail.getScaleType() == ImageView.ScaleType.MATRIX);
    }

    /**
     * Scales the snapshot to the card width and pins it to the top-left, which is the One UI crop:
     * you see the app's own header and the top of its content rather than a band out of its middle.
     *
     * <p>{@code RecentsCardBinder} already does this for a layout that declares {@code MATRIX} —
     * but it does it in a {@code post()} that reads the view's <em>current</em> width, so the matrix
     * it computes goes stale the moment the card is resized without being rebound. That is precisely
     * what a rotation does here. Recomputing from the size we are about to apply, rather than from
     * {@code getWidth()}, also makes the bind-time result correct on the first frame instead of one
     * layout pass later.
     */
    private void applyTopCropMatrix(ImageView thumbnail) {
        if (thumbnail.getScaleType() != ImageView.ScaleType.MATRIX) {
            // The no-snapshot fallback is showing; the binder has put CENTER_INSIDE on it and a
            // matrix would be ignored anyway.
            return;
        }
        final Drawable drawable = thumbnail.getDrawable();
        if (drawable == null || drawable.getIntrinsicWidth() <= 0) {
            return;
        }
        final int width = mCardWidthPx > 0 ? mCardWidthPx : thumbnail.getWidth();
        if (width <= 0) {
            return;
        }
        final float scale = (float) width / drawable.getIntrinsicWidth();
        final Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        thumbnail.setImageMatrix(matrix);
    }

    /**
     * Dresses the card as an empty slot when the task has no snapshot yet, and undoes that when it
     * does.
     *
     * <p>{@code RecentsCardBinder} (package-private, hence not linked) paints the app icon into the
     * thumbnail at {@code CENTER_INSIDE} when there is no snapshot. Left alone that fills the whole
     * card with one enormous icon, so it is inset here until it reads as a "no preview" marker on
     * the dark card surface. Views are recycled, so the inset has to be cleared just as
     * unconditionally as it is set — otherwise the next real snapshot on this view is drawn into a
     * padded box and comes out shrunken and off-centre.
     *
     * <p>The inset is padding rather than {@link ImageView#setImageAlpha} or any other
     * drawable-level effect on purpose: {@code item.icon} is the same {@code Drawable} instance the
     * row's own icon is showing (and, for a PackageManager-loaded icon, possibly one shared well
     * beyond this row), so mutating it here would dim that icon along with it.
     */
    private void applySnapshotFallback(ImageView thumbnail, boolean hasSnapshot) {
        if (hasSnapshot) {
            thumbnail.setPadding(0, 0, 0, 0);
            // The binder has just restored the layout's declared MATRIX scale type and computed its
            // own matrix from the view's current width; redo it against the size being applied now.
            applyTopCropMatrix(thumbnail);
            return;
        }
        final int height = mCardHeightPx > 0 ? mCardHeightPx : thumbnail.getHeight();
        final int inset = Math.max(0, Math.round(height * (1f - NO_SNAPSHOT_ICON_FRACTION) / 2f));
        thumbnail.setPadding(inset, inset, inset, inset);
    }

    private static boolean hasSnapshot(RecentsTaskSource.TaskItem item) {
        final Bitmap bmp = item != null ? item.thumbnail : null;
        return bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0;
    }

    private static int dp(Resources res, float value) {
        return Math.round(value * res.getDisplayMetrics().density);
    }

    /**
     * Uniform vertical gap between rows, hung off the bottom of every row rather than the top of
     * every row but the first. The offsets a decoration returns are cached per child, and a child
     * that is mid-removal reports {@link RecyclerView#NO_POSITION}; a rule that keys off the
     * position would make the disappearing row jump by a gap part way through its animation, while
     * "every row gets a trailing gap" gives the same answer whatever the position is. The list is
     * {@code clipToPadding="false"}, so the trailing gap after the last row costs nothing.
     */
    private static final class GapDecoration extends RecyclerView.ItemDecoration {

        private final int mGapPx;

        GapDecoration(int gapPx) {
            mGapPx = gapPx;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                RecyclerView.State state) {
            outRect.bottom = mGapPx;
        }
    }
}
