/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.notification.stack.AnimationFilter;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A container for Status bar system icons. Limits the number of system icons and handles overflow
 * similar to {@link NotificationIconContainer}.
 *
 * Children are expected to implement {@link StatusIconDisplayable}
 */
public class StatusIconContainer extends AlphaOptimizedLinearLayout {

    private static final String TAG = "StatusIconContainer";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_OVERFLOW = false;
    // Max 8 status icons including battery
    private static final int MAX_ICONS = 7;
    private static final int MAX_DOTS = 1;

    private int mDotPadding;
    private int mIconSpacing;
    private int mStaticDotDiameter;
    private int mUnderflowWidth;
    private int mUnderflowStart = 0;
    // Whether or not we can draw into the underflow space
    private boolean mNeedsUnderflow;
    // Individual StatusBarIconViews draw their etc dots centered in this width
    private int mIconDotFrameWidth;
    private boolean mQsExpansionTransitioning;
    private boolean mShouldRestrictIcons = true;
    // Used to count which states want to be visible during layout
    private ArrayList<StatusIconState> mLayoutStates = new ArrayList<>();
    // So we can count and measure properly
    private ArrayList<View> mMeasureViews = new ArrayList<>();
    // Any ignored icon will never be added as a child
    private Set<String> mIgnoredSlots = new HashSet<>();

    private Configuration mConfiguration;

    // IchthysOS "Shoal": zig-zag lattice layout (Settings.Secure ichthys_statusbar_layout =
    // "zigzag", written by the ichthys.nix option statusBar.layout). Icons become glyphs on small
    // discs in two rows, the lower row offset by half a pitch, which fits about twice as many
    // icons in the same width. Geometry and colours live in IchthysZigzag.
    static final String LAYOUT_SETTING = IchthysZigzag.LAYOUT_SETTING;
    private static final int ZZ_MAX_ICONS = 16;
    private boolean mZigzag;
    /** True while any child still carries lattice pivots / clips / scale. */
    private boolean mZzApplied;
    private boolean mZzLightBar;
    private float mZzCell;
    private float mZzHalfPitch;
    private float mZzRowGap;
    private int mZzAccent;
    /** Children placed on the lattice, and whether their disc is an accent (active) one. */
    private final ArrayMap<View, Boolean> mZzShown = new ArrayMap<>();
    private final RectF mZzGlyph = new RectF();
    private final RectF mZzLayerBounds = new RectF();
    private final Paint mZzDiscPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mZzRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mZzGlyphPaint = new Paint();
    /**
     * IchthysOS: re-measure, posted when a layout pass places an icon the last measure pass did
     * not count (see {@link #calculateZigzagTranslations}).
     */
    private final Runnable mZzRemeasure = this::requestLayout;
    private final ContentObserver mLayoutObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    updateZigzag();
                }
            };

    public StatusIconContainer(Context context) {
        this(context, null);
    }

    public StatusIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        reloadDimens();
        setWillNotDraw(!DEBUG_OVERFLOW);
        mZzRingPaint.setStyle(Style.STROKE);
        mZzRingPaint.setStrokeWidth(
                Math.max(1f, 0.75f * getResources().getDisplayMetrics().density));
        updateZigzagColors();
        mZigzag = readZigzag();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(LAYOUT_SETTING), false, mLayoutObserver,
                UserHandle.USER_ALL);
        if (mUsesQsBtPill) {
            com.android.systemui.statusbar.IchthysBtBatteryView.addQsListener(mQsPillListener);
        }
        updateZigzag();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mLayoutObserver);
        com.android.systemui.statusbar.IchthysBtBatteryView.removeQsListener(mQsPillListener);
        super.onDetachedFromWindow();
    }

    /** IchthysOS: this is the shade header's lattice; the QS header's BT pill stands in for it. */
    private boolean mUsesQsBtPill;
    // IchthysOS: also re-requested after the current frame: a requestLayout() that lands while
    // the AndroidView holder above still has its own request pending is swallowed (see
    // calculateZigzagTranslations), and the Bluetooth icon then stayed drawn next to the pill.
    private final Runnable mQsPillListener = () -> {
        requestLayout();
        removeCallbacks(mZzRemeasure);
        post(mZzRemeasure);
    };

    public void setUsesQsBtPill(boolean uses) {
        mUsesQsBtPill = uses;
    }

    private boolean readZigzag() {
        return IchthysZigzag.isEnabled(getContext());
    }

    /**
     * Whether the icons behind this container are on a light background (dark icons). Fed from
     * the status bar's dark icon dispatcher; picks the neutral disc colour.
     */
    public void setZigzagLightBar(boolean lightBar) {
        if (mZzLightBar != lightBar) {
            mZzLightBar = lightBar;
            invalidate();
        }
    }

    private void updateZigzag() {
        final boolean zigzag = readZigzag();
        if (zigzag != mZigzag) {
            mZigzag = zigzag;
            requestLayout();
            invalidate();
        }
    }

    /** The accent follows the system (Monet) accent, which the ichthys theme engine drives. */
    private void updateZigzagColors() {
        mZzAccent = IchthysZigzag.accent(getContext());
        // IchthysOS: the glyph on an active (accent) disc follows the disc's contrast.
        IchthysZigzag.tintPaint(mZzGlyphPaint, IchthysZigzag.glyphOnAccent(mZzAccent));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void setQsExpansionTransitioning(boolean expansionTransitioning) {
        mQsExpansionTransitioning = expansionTransitioning;
    }

    public void setShouldRestrictIcons(boolean should) {
        mShouldRestrictIcons = should;
    }

    public boolean isRestrictingIcons() {
        return mShouldRestrictIcons;
    }

    private void reloadDimens() {
        // This is the same value that StatusBarIconView uses
        mIconDotFrameWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size_sp);
        mDotPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_dot_padding);
        mIconSpacing = getResources().getDimensionPixelSize(R.dimen.status_bar_system_icon_spacing);
        int radius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
        mStaticDotDiameter = 2 * radius;
        mUnderflowWidth = mIconDotFrameWidth + (MAX_DOTS - 1) * (mStaticDotDiameter + mDotPadding);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mZigzag) {
            for (int i = 0; i < mZzShown.size(); i++) {
                final View child = mZzShown.keyAt(i);
                if (child.getParent() == this) {
                    IchthysZigzag.drawDisc(canvas, child, mZzCell, mZzShown.valueAt(i),
                            mZzLightBar, mZzDiscPaint, mZzRingPaint, mZzAccent);
                }
            }
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (mZigzag && Boolean.TRUE.equals(mZzShown.get(child))) {
            // Active icons sit on an accent disc: draw their glyph dark, whatever the bar tint.
            IchthysZigzag.discBounds(child, mZzCell, mZzLayerBounds);
            final int save = canvas.saveLayer(mZzLayerBounds, mZzGlyphPaint);
            final boolean more = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(save);
            return more;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float midY = getHeight() / 2.0f;

        // Layout all child views so that we can move them around later
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (midY - height / 2.0f);
            child.layout(0, top, width, top + height);
        }

        resetViewStates();
        if (mZigzag) {
            calculateZigzagTranslations();
        } else {
            mZzShown.clear();
            calculateIconTranslations();
        }
        applyIconStates();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (DEBUG_OVERFLOW) {
            Paint paint = new Paint();
            paint.setStyle(Style.STROKE);
            paint.setColor(Color.RED);

            // Show bounding box
            canvas.drawRect(getPaddingStart(), 0, getWidth() - getPaddingEnd(), getHeight(), paint);

            // Show etc box
            paint.setColor(Color.GREEN);
            canvas.drawRect(
                    mUnderflowStart, 0, mUnderflowStart + mUnderflowWidth, getHeight(), paint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mZigzag) {
            measureZigzag(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        mMeasureViews.clear();
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int specWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int count = getChildCount();
        // Collect all of the views which want to be laid out
        for (int i = 0; i < count; i++) {
            StatusIconDisplayable icon = (StatusIconDisplayable) getChildAt(i);
            if (icon.isIconVisible() && !icon.isIconBlocked()
                    && !mIgnoredSlots.contains(icon.getSlot())) {
                mMeasureViews.add((View) icon);
            }
        }

        int visibleCount = mMeasureViews.size();
        int maxVisible = visibleCount <= MAX_ICONS ? MAX_ICONS : MAX_ICONS - 1;
        int totalWidth = mPaddingLeft + mPaddingRight;
        boolean trackWidth = true;

        // Measure all children so that they report the correct width
        int childWidthSpec = MeasureSpec.makeMeasureSpec(specWidth, MeasureSpec.UNSPECIFIED);
        mNeedsUnderflow = mShouldRestrictIcons && visibleCount > MAX_ICONS;
        for (int i = 0; i < visibleCount; i++) {
            // Walking backwards
            View child = mMeasureViews.get(visibleCount - i - 1);
            measureChild(child, childWidthSpec, heightMeasureSpec);
            int spacing = i == visibleCount - 1 ? 0 : mIconSpacing;
            if (mShouldRestrictIcons) {
                if (i < maxVisible && trackWidth) {
                    totalWidth += getViewTotalMeasuredWidth(child) + spacing;
                } else if (trackWidth) {
                    // We've hit the icon limit; add space for dots
                    totalWidth += mUnderflowWidth;
                    trackWidth = false;
                }
            } else {
                totalWidth += getViewTotalMeasuredWidth(child) + spacing;
            }
        }
        setMeasuredDimension(
                getMeasuredWidth(widthMode, specWidth, totalWidth),
                getMeasuredHeight(heightMeasureSpec, mMeasureViews));
    }

    /** Zig-zag measure: icon i sits half a pitch left of icon i - 1, alternating rows. */
    private void measureZigzag(int widthMeasureSpec, int heightMeasureSpec) {
        mMeasureViews.clear();
        final int childWidthSpec = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            StatusIconDisplayable icon = (StatusIconDisplayable) child;
            // Measure every child: onLayout lays all of them out, hidden or not.
            measureChild(child, childWidthSpec, heightMeasureSpec);
            if (icon.isIconVisible() && !icon.isIconBlocked()
                    && !mIgnoredSlots.contains(icon.getSlot())
                    && !isBtReplacedByPill(icon)
                    && IchthysZigzag.hasGlyph(child)) {
                mMeasureViews.add(child);
            }
        }

        mZzRowGap = IchthysZigzag.rowGap(this);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final float avail = heightMode == MeasureSpec.UNSPECIFIED ? -1f
                : MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
        // IchthysOS: the cell shrinks to the height given only where icons are restricted (the
        // status bar row). The shade header's lattice (setShouldRestrictIcons(false)) keeps its
        // design cell whatever height a transition hands it, so it never draws smaller discs.
        final float cell = IchthysZigzag.cellFor(this, mShouldRestrictIcons ? avail : -1f);
        mZzCell = cell;
        mZzHalfPitch = IchthysZigzag.halfPitch(cell);

        final int shown = Math.min(mMeasureViews.size(), ZZ_MAX_ICONS);
        int totalWidth = getPaddingLeft() + getPaddingRight();
        if (shown > 0) {
            totalWidth += (int) Math.ceil(cell + (shown - 1) * mZzHalfPitch);
        }
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int specWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int width = widthMode == MeasureSpec.EXACTLY ? specWidth
                : widthMode == MeasureSpec.AT_MOST ? Math.min(totalWidth, specWidth) : totalWidth;
        final int wanted = (int) Math.ceil(2 * cell + mZzRowGap) + getPaddingTop()
                + getPaddingBottom();
        final int height = heightMode == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : heightMode == MeasureSpec.AT_MOST
                        ? Math.min(wanted, MeasureSpec.getSize(heightMeasureSpec)) : wanted;
        mNeedsUnderflow = false;
        setMeasuredDimension(width, height);
    }

    /**
     * Places each visible icon, right to left, on the lattice: its glyph centred on its disc and
     * scaled to fill it. Icons without a glyph, past {@link #ZZ_MAX_ICONS} or beyond the
     * available width are hidden (never an empty disc, never drawn over the clock).
     */
    /**
     * The Bluetooth BT-battery pill stands in for the stock "bluetooth" slot while it shows.
     * IchthysOS: only a pill next to this container (same parent) counts; the keyguard status bar
     * and the shade header have none and keep their Bluetooth icon.
     */
    private boolean isBtReplacedByPill(StatusIconDisplayable icon) {
        if (!"bluetooth".equals(icon.getSlot())) {
            return false;
        }
        // IchthysOS: the shade header's lattice and the QS header's pill don't share a parent.
        if (mUsesQsBtPill) {
            return com.android.systemui.statusbar.IchthysBtBatteryView.isQsPillShowing();
        }
        if (!(getParent() instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup parent = (ViewGroup) getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            final View child = parent.getChildAt(i);
            if (child instanceof com.android.systemui.statusbar.IchthysBtBatteryView
                    && ((com.android.systemui.statusbar.IchthysBtBatteryView) child)
                            .isShowingLevel()) {
                return true;
            }
        }
        return false;
    }

    private void calculateZigzagTranslations() {
        mLayoutStates.clear();
        mZzShown.clear();
        mZzApplied = true;
        final float width = getWidth();
        final float contentStart = getPaddingStart();
        final float midY = getPaddingTop()
                + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2.0f;
        final float cell = mZzCell;
        final float topCy = midY - (cell + mZzRowGap) / 2f;
        final float bottomCy = midY + (cell + mZzRowGap) / 2f;
        final boolean rtl = isLayoutRtl();
        // Count the icons that make it onto the lattice first: reading the lattice from its start
        // (left), the top row takes positions 0, 2, 4... and the bottom row 1, 3, 5..., so the
        // top row gets ceil(n / 2) icons and the bottom row sits half a pitch towards the end.
        // Icons are placed from the end, so the row of the i-th from the end depends on n.
        int total = 0;
        // IchthysOS: an icon whose glyph arrived after the last measure pass (a freshly bound
        // Wi-Fi / mobile view sets its drawable asynchronously) is placeable now, but this
        // container was measured, and so sized, without it. Its own requestLayout() can be
        // swallowed on the way up: when it lands while an ancestor still has its layout request
        // pending (inside an AndroidView, between Compose's measure and layout of the holder), the
        // propagation stops there and nothing re-measures this container afterwards. The lattice
        // then filled the stale width from the end and silently dropped the leftmost icon (the
        // shade header's mute icon) until some unrelated relayout. Re-measure in that case.
        boolean missedByMeasure = false;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            StatusIconDisplayable iconView = (StatusIconDisplayable) child;
            if (getViewStateFromChild(child) == null) {
                continue;
            }
            if (!iconView.isIconVisible() || iconView.isIconBlocked()
                    || mIgnoredSlots.contains(iconView.getSlot())
                    || isBtReplacedByPill(iconView)
                    || !IchthysZigzag.glyphRect(child, mZzGlyph)) {
                continue;
            }
            if (!mMeasureViews.contains(child)) {
                missedByMeasure = true;
            }
            final float cx = width - getPaddingEnd() - cell / 2f - total * mZzHalfPitch;
            // IchthysOS: the width limit (never drawn over the clock) only where icons are
            // restricted; the shade header shows all of them (setShouldRestrictIcons(false)).
            if (total >= ZZ_MAX_ICONS
                    || (mShouldRestrictIcons && cx - cell / 2f < contentStart)) {
                continue;
            }
            total++;
        }
        if (missedByMeasure) {
            removeCallbacks(mZzRemeasure);
            post(mZzRemeasure);
        }
        int shown = 0;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            StatusIconDisplayable iconView = (StatusIconDisplayable) child;
            StatusIconState state = getViewStateFromChild(child);
            if (state == null) {
                continue;
            }
            // Apply lattice positions without animating: the discs are recorded by this container
            // and would not follow a child's property animation.
            state.qsExpansionTransitioning = true;
            final float cx = width - getPaddingEnd() - cell / 2f - shown * mZzHalfPitch;
            if (!iconView.isIconVisible() || iconView.isIconBlocked()
                    || mIgnoredSlots.contains(iconView.getSlot())
                    || isBtReplacedByPill(iconView)
                    || shown >= ZZ_MAX_ICONS
                    || (mShouldRestrictIcons && cx - cell / 2f < contentStart)
                    || !IchthysZigzag.glyphRect(child, mZzGlyph)) {
                state.visibleState = STATE_HIDDEN;
                continue;
            }
            final float cy = ((total - 1 - shown) % 2 == 0) ? topCy : bottomCy;
            final float scale = IchthysZigzag.glyphScale(cell, mZzGlyph);
            final float drawCx = rtl ? width - cx : cx;
            final float[] t = IchthysZigzag.placeOnDisc(child, mZzGlyph, drawCx, cy, scale);
            state.visibleState = STATE_ICON;
            state.setXTranslation(t[0]);
            state.setYTranslation(t[1], "statusIconContainer zigzag");
            state.setScaleX(scale);
            state.setScaleY(scale);
            mLayoutStates.add(0, state);
            mZzShown.put(child, IchthysZigzag.ACTIVE_SLOTS.contains(iconView.getSlot()));
            shown++;
        }
    }

    private int getMeasuredHeight(int heightMeasureSpec, List<View> measuredChildren) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            return MeasureSpec.getSize(heightMeasureSpec);
        } else {
            int highest = 0;
            for (View child : measuredChildren) {
                highest = Math.max(child.getMeasuredHeight(), highest);
            }
            return highest + getPaddingTop() + getPaddingBottom();
        }
    }

    private int getMeasuredWidth(int widthMode, int specWidth, int totalWidth) {
        if (widthMode == MeasureSpec.EXACTLY) {
            if (!mNeedsUnderflow && totalWidth > specWidth) {
                mNeedsUnderflow = true;
            }
            return specWidth;
        } else {
            if (widthMode == MeasureSpec.AT_MOST && totalWidth > specWidth) {
                mNeedsUnderflow = true;
                totalWidth = specWidth;
            }
            return totalWidth;
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        StatusIconState vs = new StatusIconState();
        vs.justAdded = true;
        child.setTag(R.id.status_bar_view_state_tag, vs);
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        child.setTag(R.id.status_bar_view_state_tag, null);
        if (mZzShown.remove(child) != null || mZzApplied) {
            IchthysZigzag.restore(child);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setTranslationY(0f);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final int configDiff = newConfig.diff(mConfiguration);
        mConfiguration.setTo(newConfig);
        if ((configDiff & (ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_FONT_SCALE)) != 0) {
            reloadDimens();
        }
        // Theme (accent) changes arrive as a configuration change.
        updateZigzagColors();
        invalidate();
    }

    /**
     * Adds a name of an icon slot to be ignored. The icon will not be shown or measured.
     *
     * @param slotName The name of the icon as it exists in {@code
     *     frameworks/base/core/res/res/values/config.xml}.
     */
    public void addIgnoredSlot(String slotName) {
        boolean added = mIgnoredSlots.add(slotName);
        if (added) {
            requestLayout();
        }
    }

    /**
     * Adds certain slot names to the list of slots to be ignored.
     *
     * @param slotNames The names of the icons to ignore.
     */
    public void addIgnoredSlots(List<String> slotNames) {
        boolean willAddAny = false;
        for (String slotName : slotNames) {
            willAddAny |= mIgnoredSlots.add(slotName);
        }

        if (willAddAny) {
            requestLayout();
        }
    }

    /**
     * Removes a slot from the list of ignored icon slots. The icon will then be shown when set to
     * visible by the {@link StatusBarIconController}.
     *
     * @param slotName The name of the icon slot to remove from the ignored list.
     */
    public void removeIgnoredSlot(String slotName) {
        boolean removed = mIgnoredSlots.remove(slotName);
        if (removed) {
            requestLayout();
        }
    }

    /**
     * Removes a list of slots from the list of ignored icon slots. The icons will then be shown
     * when set to visible by the {@link StatusBarIconController}.
     *
     * @param slots The names of the icon slots to remove from the ignored list.
     */
    public void removeIgnoredSlots(List<String> slots) {
        boolean removedAny = false;
        for (String slot : slots) {
            removedAny |= mIgnoredSlots.remove(slot);
        }

        if (removedAny) {
            requestLayout();
        }
    }

    /**
     * Calculates the X translation for each icon view.
     *
     * <p>This is done by iterating through the children from right to left, giving each icon view a
     * position, and then deciding if it should be shown as an icon, a dot, or hidden.
     */
    private void calculateIconTranslations() {
        mLayoutStates.clear();
        float width = getWidth();
        float translationX = width - getPaddingEnd();
        float contentStart = getPaddingStart();
        int childCount = getChildCount();
        // Underflow === don't show content until that index
        if (DEBUG) Log.d(TAG, "calculateIconTranslations: start=" + translationX
                + " width=" + width + " underflow=" + mNeedsUnderflow);

        // Collect all of the states which want to be visible
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            StatusIconDisplayable iconView = (StatusIconDisplayable) child;
            StatusIconState childState = getViewStateFromChild(child);

            if (!iconView.isIconVisible() || iconView.isIconBlocked()
                    || mIgnoredSlots.contains(iconView.getSlot())) {
                childState.visibleState = STATE_HIDDEN;
                if (DEBUG) Log.d(TAG, "skipping child (" + iconView.getSlot() + ") not visible");
                continue;
            }

            // Move translationX to the spot within StatusIconContainer's layout to add the view
            // without cutting off the child view.
            translationX -= getViewTotalWidth(child);
            childState.visibleState = STATE_ICON;
            childState.setXTranslation(translationX);
            mLayoutStates.add(0, childState);

            // Shift translationX over by mIconSpacing for the next view.
            translationX -= mIconSpacing;
        }

        // Show either 1-MAX_ICONS icons, or (MAX_ICONS - 1) icons + overflow
        int totalVisible = mLayoutStates.size();
        int maxVisible = totalVisible <= MAX_ICONS ? MAX_ICONS : MAX_ICONS - 1;

        // Init mUnderflowStart value with the offset to let the dot be placed next to battery icon.
        // This is to prevent if the underflow happens at rightest(totalVisible - 1) child then
        // break the for loop with mUnderflowStart staying 0(initial value), causing the dot be
        // placed at the leftest side.
        mUnderflowStart = (int) Math.max(contentStart, width - getPaddingEnd() - mUnderflowWidth);
        int visible = 0;
        int firstUnderflowIndex = -1;
        for (int i = totalVisible - 1; i >= 0; i--) {
            StatusIconState state = mLayoutStates.get(i);
            // Allow room for underflow if we found we need it in onMeasure
            if ((mNeedsUnderflow && (state.getXTranslation() < (contentStart + mUnderflowWidth)))
                    || (mShouldRestrictIcons && (visible >= maxVisible))) {
                firstUnderflowIndex = i;
                break;
            }
            mUnderflowStart = (int) Math.max(
                    contentStart, state.getXTranslation() - mUnderflowWidth - mIconSpacing);
            visible++;
        }

        if (firstUnderflowIndex != -1) {
            int totalDots = 0;
            int dotWidth = mStaticDotDiameter + mDotPadding;
            int dotOffset = mUnderflowStart + mUnderflowWidth - mIconDotFrameWidth;
            for (int i = firstUnderflowIndex; i >= 0; i--) {
                StatusIconState state = mLayoutStates.get(i);
                if (totalDots < MAX_DOTS) {
                    state.setXTranslation(dotOffset);
                    state.visibleState = STATE_DOT;
                    dotOffset -= dotWidth;
                    totalDots++;
                } else {
                    state.visibleState = STATE_HIDDEN;
                }
            }
        }

        // Stole this from NotificationIconContainer. Not optimal but keeps the layout logic clean
        if (isLayoutRtl()) {
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                StatusIconState state = getViewStateFromChild(child);
                state.setXTranslation(width - state.getXTranslation() - child.getWidth());
            }
        }
    }

    private void applyIconStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            StatusIconState vs = getViewStateFromChild(child);
            if (vs != null) {
                vs.applyToView(child);
                vs.qsExpansionTransitioning = mQsExpansionTransitioning;
            }
        }
    }

    private void resetViewStates() {
        final boolean restoreLattice = mZzApplied && !mZigzag;
        if (restoreLattice) {
            mZzApplied = false;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            StatusIconState vs = getViewStateFromChild(child);
            if (vs == null) {
                continue;
            }

            if (restoreLattice) {
                IchthysZigzag.restore(child);
            }
            vs.initFrom(child);
            vs.setAlpha(1.0f, "statusIconContainer reset");
            vs.hidden = false;
            // IchthysOS: the zig-zag layout scales and offsets icons; the row layout never does.
            vs.setScaleX(1f);
            vs.setScaleY(1f);
            vs.setYTranslation(0f, "statusIconContainer reset");
        }
    }

    private static @Nullable StatusIconState getViewStateFromChild(View child) {
        return (StatusIconState) child.getTag(R.id.status_bar_view_state_tag);
    }

    private static int getViewTotalMeasuredWidth(View child) {
        return child.getMeasuredWidth() + child.getPaddingStart() + child.getPaddingEnd();
    }

    private static int getViewTotalWidth(View child) {
        return child.getWidth() + child.getPaddingStart() + child.getPaddingEnd();
    }

    /** A {@link ViewState} that also stores the icon's visibility state. */
    public static class StatusIconState extends ViewState {

        public StatusIconState() {
            super(false /* usePhysicsForMovement */);
        }

        /// StatusBarIconView.STATE_*
        public int visibleState = STATE_ICON;
        public boolean justAdded = true;
        public boolean qsExpansionTransitioning = false;

        // How far we are from the end of the view actually is the most relevant for animation
        float distanceToViewEnd = -1;

        @Override
        public void applyToView(View view) {
            float parentWidth = 0;
            if (view.getParent() instanceof View) {
                parentWidth = ((View) view.getParent()).getWidth();
            }

            float currentDistanceToEnd = parentWidth - getXTranslation();

            if (!(view instanceof StatusIconDisplayable)) {
                return;
            }
            StatusIconDisplayable icon = (StatusIconDisplayable) view;
            AnimationProperties animationProperties = null;
            boolean animateVisibility = true;

            // Figure out which properties of the state transition (if any) we need to animate
            if (justAdded
                    || icon.getVisibleState() == STATE_HIDDEN && visibleState == STATE_ICON) {
                // Icon is appearing, fade it in by putting it where it will be and animating alpha
                super.applyToView(view);
                view.setAlpha(0.f);
                icon.setVisibleState(STATE_HIDDEN);
                animationProperties = ADD_ICON_PROPERTIES;
            } else if (icon.getVisibleState() != visibleState) {
                if (icon.getVisibleState() == STATE_ICON && visibleState == STATE_HIDDEN) {
                    // Disappearing, don't do anything fancy
                    animateVisibility = false;
                } else {
                    // all other transitions (to/from dot, etc)
                    animationProperties = ANIMATE_ALL_PROPERTIES;
                }
            } else if (visibleState != STATE_HIDDEN && distanceToViewEnd != currentDistanceToEnd) {
                // Visibility isn't changing, just animate position
                animationProperties = X_ANIMATION_PROPERTIES;
            }

            icon.setVisibleState(visibleState, animateVisibility);
            if (animationProperties != null && !qsExpansionTransitioning) {
                animateTo(view, animationProperties);
            } else {
                super.applyToView(view);
            }

            qsExpansionTransitioning = false;
            justAdded = false;
            distanceToViewEnd = currentDistanceToEnd;

        }
    }

    private static final AnimationProperties ADD_ICON_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200).setDelay(50);

    private static final AnimationProperties X_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);

    private static final AnimationProperties ANIMATE_ALL_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX().animateY()
                .animateAlpha().animateScale();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);
}
