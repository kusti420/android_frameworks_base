/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK;

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;

/**
 * Header displayed above a notification section in the shade. Currently used for Alerting and
 * Silent sections.
 */
public class SectionHeaderView extends StackScrollerDecorView {

    private ViewGroup mContents;
    private TextView mLabelView;
    private ImageView mClearAllButton;
    @StringRes @Nullable private Integer mLabelTextId;
    @Nullable private View.OnClickListener mLabelClickListener = null;
    @Nullable private View.OnClickListener mOnClearClickListener = null;

    public SectionHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        mContents = requireViewById(R.id.content);
        bindContents();
        super.onFinishInflate();
        setVisible(true /* visible */, false /* animate */);
    }

    private void bindContents() {
        mLabelView = requireViewById(R.id.header_label);
        mClearAllButton = requireViewById(R.id.btn_clear_all);
        if (mOnClearClickListener != null) {
            mClearAllButton.setOnClickListener(mOnClearClickListener);
        }
        if (mLabelClickListener != null) {
            mLabelView.setOnClickListener(mLabelClickListener);
        }
        if (mLabelTextId != null) {
            mLabelView.setText(mLabelTextId);
        }
        mLabelView.setAccessibilityHeading(true);
        applyShoalStyle();
        ViewCompat.replaceAccessibilityAction(
                mLabelView,
                ACTION_CLICK,
                getResources().getString(
                        R.string.accessibility_notification_section_header_open_settings),
                null
        );
    }

    @Override
    protected View findContentView() {
        return mContents;
    }

    @Override
    protected View findSecondaryView() {
        return null;
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    /**
     * Show the clear section [X] button
     * @param enabled
     */
    public void setClearSectionButtonEnabled(boolean enabled) {
        mClearAllButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return super.onInterceptTouchEvent(ev);
    }

    /**
     * Fired whenever the user clicks on the body of the header (e.g. no sub-buttons or anything).
     */
    public void setOnHeaderClickListener(View.OnClickListener listener) {
        mLabelClickListener = listener;
        mLabelView.setOnClickListener(listener);
    }

    @Override
    protected void applyContentTransformation(float contentAlpha, float translationY) {
        super.applyContentTransformation(contentAlpha, translationY);
        mLabelView.setAlpha(contentAlpha);
        mLabelView.setTranslationY(translationY);
        mClearAllButton.setAlpha(contentAlpha);
        mClearAllButton.setTranslationY(translationY);
    }

    /** Fired when the user clicks on the "X" button on the far right of the header. */
    public void setOnClearAllClickListener(View.OnClickListener listener) {
        mOnClearClickListener = listener;
        mClearAllButton.setOnClickListener(listener);
    }

    @Override
    public boolean needsClippingToShelf() {
        return true;
    }

    /** Sets text to be displayed in the header */
    public void setHeaderText(@StringRes int resId) {
        mLabelTextId = resId;
        mLabelView.setText(resId);
    }

    void setForegroundColors(@ColorInt int onSurface, @ColorInt int onSurfaceVariant) {
        mLabelView.setTextColor(onSurface);
        mClearAllButton.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));
        applyShoalStyle();
    }

    // Stock label look, captured before the first Shoal restyle so it can be put back.
    private boolean mStockCaptured;
    private float mStockTextSizePx;
    private float mStockLetterSpacing;
    private android.graphics.Typeface mStockTypeface;
    private int mStockPaddingStart;
    private boolean mShoalApplied;

    /**
     * IchthysOS "Shoal": section headers are small uppercase labels in the text face (12 sp
     * semibold, 0.09 em tracking, muted colour), 20 dp in from the screen edge. Stock otherwise.
     */
    private void applyShoalStyle() {
        if (mLabelView == null) {
            return;
        }
        final Context context = getContext();
        if (!mStockCaptured) {
            mStockCaptured = true;
            mStockTextSizePx = mLabelView.getTextSize();
            mStockLetterSpacing = mLabelView.getLetterSpacing();
            mStockTypeface = mLabelView.getTypeface();
            mStockPaddingStart = getPaddingStart();
        }
        if (com.android.systemui.shade.ui.ShoalShade.isActive(context)) {
            mShoalApplied = true;
            mLabelView.setAllCaps(true);
            mLabelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            mLabelView.setLetterSpacing(0.09f);
            mLabelView.setTypeface(com.android.systemui.shade.ui.ShoalShade.text(context, 600));
            if (com.android.systemui.shade.ui.ShoalShade.isInk(context)) {
                mLabelView.setTextColor(com.android.systemui.shade.ui.ShoalShade.mutedColor(context));
            }
            // The stack already insets this view by its 12 dp side padding.
            setPaddingRelative(com.android.systemui.shade.ui.ShoalShade.dp(context, 8),
                    getPaddingTop(), getPaddingEnd(), getPaddingBottom());
        } else if (mShoalApplied) {
            mShoalApplied = false;
            mLabelView.setAllCaps(false);
            mLabelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, mStockTextSizePx);
            mLabelView.setLetterSpacing(mStockLetterSpacing);
            mLabelView.setTypeface(mStockTypeface);
            setPaddingRelative(mStockPaddingStart, getPaddingTop(), getPaddingEnd(),
                    getPaddingBottom());
        }
    }
}
