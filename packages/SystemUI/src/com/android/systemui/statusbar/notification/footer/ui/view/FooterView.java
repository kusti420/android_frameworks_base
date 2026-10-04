/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.footer.ui.view;

import static android.graphics.PorterDuff.Mode.SRC_ATOP;

import static com.android.systemui.Flags.notificationFooterBackgroundTintOptimization;
import static com.android.systemui.Flags.notificationShadeBlur;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.ui.ShoalShade;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.row.FooterViewButton;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.util.DrawableDumpKt;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;
import java.util.function.Consumer;

public class FooterView extends StackScrollerDecorView {
    private FooterViewButton mClearAllButton;
    private FooterViewButton mSettingsButton;
    private FooterViewButton mHistoryButton;
    private boolean mShouldBeHidden;
    private boolean mIsBlurSupported;

    // Footer label
    private TextView mSeenNotifsFooterTextView;

    private @StringRes int mClearAllButtonTextId;
    private @StringRes int mClearAllButtonDescriptionId;
    private @StringRes int mMessageStringId;
    private @DrawableRes int mMessageIconId;

    private OnClickListener mClearAllButtonClickListener;

    public FooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.content);
    }

    protected View findSecondaryView() {
        return findViewById(R.id.dismiss_text);
    }

    /** Whether the "Clear all" button is currently visible. */
    public boolean isClearAllButtonVisible() {
        return isSecondaryVisible();
    }

    /** See {@link this#setClearAllButtonVisible(boolean, boolean, Consumer)}. */
    public void setClearAllButtonVisible(boolean visible, boolean animate) {
        setClearAllButtonVisible(visible, animate, /* onAnimationEnded = */ null);
    }

    /** Set the visibility of the Settings button to {@code visible}. */
    public void setSettingsButtonVisible(boolean visible) {
        mSettingsButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Set the visibility of the History button to {@code visible}. */
    public void setHistoryButtonVisible(boolean visible) {
        mHistoryButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Set the visibility of the "Clear all" button to {@code visible}. Animate the change if
     * {@code animate} is true.
     */
    public void setClearAllButtonVisible(boolean visible, boolean animate,
            Consumer<Boolean> onAnimationEnded) {
        setSecondaryVisible(visible, animate, onAnimationEnded);
    }

    /** See {@link this#setShouldBeHidden} below. */
    public boolean shouldBeHidden() {
        return mShouldBeHidden;
    }

    /**
     * Whether this view's visibility should be set to INVISIBLE. Note that this is different from
     * the {@link StackScrollerDecorView#setVisible} method, which in turn handles visibility
     * transitions between VISIBLE and GONE.
     */
    public void setShouldBeHidden(boolean hide) {
        mShouldBeHidden = hide;
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        super.dump(pw, args);
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            pw.println("visibility: " + DumpUtilsKt.visibilityString(getVisibility()));
            if (mHistoryButton != null) {
                pw.println("mHistoryButton visibility: "
                        + DumpUtilsKt.visibilityString(mHistoryButton.getVisibility()));
            }
            if (mClearAllButton != null) {
                pw.println("mClearAllButton visibility: "
                        + DumpUtilsKt.visibilityString(mClearAllButton.getVisibility()));
            }
            if (mSettingsButton != null) {
                pw.println("mSettingsButton visibility: "
                        + DumpUtilsKt.visibilityString(mSettingsButton.getVisibility()));
            }
            if (mSeenNotifsFooterTextView != null) {
                pw.println("mSeenNotifsFooterTextView visibility: "
                        + DumpUtilsKt.visibilityString(mSeenNotifsFooterTextView.getVisibility()));
            }
        });
    }

    /** Set the text label for the "Clear all" button. */
    public void setClearAllButtonText(@StringRes int textId) {
        if (mClearAllButtonTextId == textId) {
            return; // nothing changed
        }
        mClearAllButtonTextId = textId;
        updateClearAllButtonText();
    }

    private void updateClearAllButtonText() {
        if (mClearAllButtonTextId == 0) {
            return; // not initialized yet
        }
        mClearAllButton.setText(getContext().getString(mClearAllButtonTextId));
    }

    /** Set the accessibility content description for the "Clear all" button. */
    public void setClearAllButtonDescription(@StringRes int contentDescriptionId) {
        if (mClearAllButtonDescriptionId == contentDescriptionId) {
            return; // nothing changed
        }
        mClearAllButtonDescriptionId = contentDescriptionId;
        updateClearAllButtonDescription();
    }

    private void updateClearAllButtonDescription() {
        if (mClearAllButtonDescriptionId == 0) {
            return; // not initialized yet
        }
        mClearAllButton.setContentDescription(getContext().getString(mClearAllButtonDescriptionId));
    }

    /** Set the string for a message to be shown instead of the buttons. */
    public void setMessageString(@StringRes int messageId) {
        if (mMessageStringId == messageId) {
            return; // nothing changed
        }
        mMessageStringId = messageId;
        updateMessageString();
    }

    private void updateMessageString() {
        if (mMessageStringId == 0) {
            return; // not initialized yet
        }
        String messageString = getContext().getString(mMessageStringId);
        mSeenNotifsFooterTextView.setText(messageString);
    }

    /** Set the icon to be shown before the message (see {@link #setMessageString(int)}). */
    public void setMessageIcon(@DrawableRes int iconId) {
        if (mMessageIconId == iconId) {
            return; // nothing changed
        }
        mMessageIconId = iconId;
        updateMessageIcon();
    }

    private void updateMessageIcon() {
        if (mMessageIconId == 0) {
            return; // not initialized yet
        }
        int unlockIconSize = getResources()
                .getDimensionPixelSize(R.dimen.notifications_unseen_footer_icon_size);
        @SuppressLint("UseCompatLoadingForDrawables")
        Drawable messageIcon = getContext().getDrawable(mMessageIconId);
        if (messageIcon != null) {
            messageIcon.setBounds(0, 0, unlockIconSize, unlockIconSize);
            mSeenNotifsFooterTextView
                    .setCompoundDrawablesRelative(messageIcon, null, null, null);
        }
    }

    @Override
    protected void onFinishInflate() {
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logTriggerEvent("Footer.onFinishInflate()");
        }
        super.onFinishInflate();
        mClearAllButton = (FooterViewButton) findSecondaryView();
        mSettingsButton = findViewById(R.id.settings_button);
        mHistoryButton = findViewById(R.id.history_button);
        mSeenNotifsFooterTextView = findViewById(R.id.unlock_prompt_footer);
        updateContent();
        updateColors();
    }

    /** Show a message instead of the footer buttons. */
    public void setFooterLabelVisible(boolean isVisible) {
        // Note: hiding the buttons is handled in the FooterViewModel
        if (isVisible) {
            mSeenNotifsFooterTextView.setVisibility(View.VISIBLE);
        } else {
            mSeenNotifsFooterTextView.setVisibility(View.GONE);
        }
    }

    /** Set onClickListener for the notification settings button. */
    public void setSettingsButtonClickListener(OnClickListener listener) {
        mSettingsButton.setOnClickListener(listener);
    }

    /** Set onClickListener for the notification history button. */
    public void setHistoryButtonClickListener(OnClickListener listener) {
        mHistoryButton.setOnClickListener(listener);
    }

    /** Set onClickListener for the clear all (end) button. */
    public void setClearAllButtonClickListener(OnClickListener listener) {
        if (mClearAllButtonClickListener == listener) return;
        mClearAllButtonClickListener = listener;
        mClearAllButton.setOnClickListener(listener);
    }

    /**
     * Whether the touch is outside the Clear all button.
     */
    public boolean isOnEmptySpace(float touchX, float touchY) {
        SceneContainerFlag.assertInLegacyMode();
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    private void updateContent() {
        updateClearAllButtonText();
        updateClearAllButtonDescription();
        updateMessageString();
        updateMessageIcon();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logTriggerEvent("Footer.onConfigurationChanged()");
        }
        super.onConfigurationChanged(newConfig);
        updateColors();
        updateContent();
    }

    /**
     * Update the text and background colors for the current color palette and night mode setting.
     */
    public void updateColors() {
        Resources.Theme theme = mContext.getTheme();
        final @ColorInt int onSurface = mContext.getColor(
                com.android.internal.R.color.materialColorOnSurface);
        // Same resource, separate drawables to prevent touch effects from showing on the wrong
        // button.
        final Drawable clearAllBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final Drawable settingsBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final Drawable historyBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final @ColorInt int scHigh;

        if (!notificationFooterBackgroundTintOptimization()) {
            if (notificationShadeBlur()) {
                if (mIsBlurSupported) {
                    Color backgroundColor = Color.valueOf(
                            SurfaceEffectColors.surfaceEffect1(getContext()));
                    scHigh = ColorUtils.setAlphaComponent(backgroundColor.toArgb(), 0xFF);
                    // Apply alpha on background drawables.
                    int backgroundAlpha = (int) (backgroundColor.alpha() * 0xFF);
                    clearAllBg.setAlpha(backgroundAlpha);
                    settingsBg.setAlpha(backgroundAlpha);
                    if (historyBg != null) {
                        historyBg.setAlpha(backgroundAlpha);
                    }
                } else {
                    scHigh = mContext.getColor(
                            com.android.internal.R.color.materialColorSurfaceContainer);
                }
            } else {
                scHigh = mContext.getColor(
                        com.android.internal.R.color.materialColorSurfaceContainerHigh);
            }
            if (scHigh != 0) {
                final ColorFilter bgColorFilter = new PorterDuffColorFilter(scHigh, SRC_ATOP);
                clearAllBg.setColorFilter(bgColorFilter);
                settingsBg.setColorFilter(bgColorFilter);
                historyBg.setColorFilter(bgColorFilter);
            }
        } else {
            scHigh = 0;
        }

        mClearAllButton.setBackground(clearAllBg);
        mClearAllButton.setTextColor(onSurface);

        mSettingsButton.setBackground(settingsBg);
        mSettingsButton.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));

        mHistoryButton.setBackground(historyBg);
        mHistoryButton.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));

        applyShoalFooter(onSurface);

        mSeenNotifsFooterTextView.setTextColor(onSurface);
        mSeenNotifsFooterTextView.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));

        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logEvent("Footer.updateColors()",
                    "textColor(onSurface)=" + hexColorString(onSurface)
                            + " backgroundTint(surfaceContainerHigh)=" + hexColorString(scHigh)
                            + " background=" + DrawableDumpKt.dumpToString(settingsBg));
        }
    }

    // IchthysOS "Shoal" footer: stock state captured before the first restyle, to put it back.
    private boolean mShoalApplied;
    private ConstraintLayout.LayoutParams mStockSettingsLp;
    private ConstraintLayout.LayoutParams mStockHistoryLp;
    private ConstraintLayout.LayoutParams mStockClearAllLp;
    private Drawable[] mStockSettingsDrawables;
    private CharSequence mStockSettingsText;
    private float mStockTextSizePx;
    private Typeface mStockTypeface;
    private int[] mStockSettingsPadding;
    private int[] mStockClearAllPadding;
    private int[] mStockHistoryPadding;
    private int mStockHistoryDrawablePadding;
    private Typeface mStockSeenTypeface;

    /**
     * IchthysOS "Shoal": the footer is a "Manage" pill on the left (ink card + 1 dp hairline, the
     * notification settings button) with the history button beside it, and an accent "Clear all"
     * pill with ink text on the right. Pills are 40 dp tall inside the stock 48 dp touch targets.
     * Stock layout otherwise.
     */
    private void applyShoalFooter(@ColorInt int onSurface) {
        final Context context = mContext;
        final boolean shoal = ShoalShade.isActive(context);
        if (!shoal) {
            if (mShoalApplied) {
                mShoalApplied = false;
                mSettingsButton.setLayoutParams(new ConstraintLayout.LayoutParams(
                        mStockSettingsLp));
                mHistoryButton.setLayoutParams(new ConstraintLayout.LayoutParams(mStockHistoryLp));
                mClearAllButton.setLayoutParams(new ConstraintLayout.LayoutParams(
                        mStockClearAllLp));
                mSettingsButton.setText(mStockSettingsText);
                mSettingsButton.setCompoundDrawablesRelative(mStockSettingsDrawables[0],
                        mStockSettingsDrawables[1], mStockSettingsDrawables[2],
                        mStockSettingsDrawables[3]);
                mSettingsButton.setPaddingRelative(mStockSettingsPadding[0],
                        mStockSettingsPadding[1], mStockSettingsPadding[2],
                        mStockSettingsPadding[3]);
                mClearAllButton.setPaddingRelative(mStockClearAllPadding[0],
                        mStockClearAllPadding[1], mStockClearAllPadding[2],
                        mStockClearAllPadding[3]);
                mHistoryButton.setPaddingRelative(mStockHistoryPadding[0],
                        mStockHistoryPadding[1], mStockHistoryPadding[2],
                        mStockHistoryPadding[3]);
                mHistoryButton.setCompoundDrawablePadding(mStockHistoryDrawablePadding);
                for (TextView b : new TextView[] {mSettingsButton, mClearAllButton}) {
                    b.setTextSize(TypedValue.COMPLEX_UNIT_PX, mStockTextSizePx);
                    b.setTypeface(mStockTypeface);
                }
                if (mSeenNotifsFooterTextView != null) {
                    mSeenNotifsFooterTextView.setTypeface(mStockSeenTypeface);
                }
            }
            return;
        }
        if (!mShoalApplied) {
            mShoalApplied = true;
            mStockSettingsLp = new ConstraintLayout.LayoutParams(
                    (ConstraintLayout.LayoutParams) mSettingsButton.getLayoutParams());
            mStockHistoryLp = new ConstraintLayout.LayoutParams(
                    (ConstraintLayout.LayoutParams) mHistoryButton.getLayoutParams());
            mStockClearAllLp = new ConstraintLayout.LayoutParams(
                    (ConstraintLayout.LayoutParams) mClearAllButton.getLayoutParams());
            mStockSettingsDrawables = mSettingsButton.getCompoundDrawablesRelative();
            mStockSettingsText = mSettingsButton.getText();
            mStockTextSizePx = mClearAllButton.getTextSize();
            mStockTypeface = mClearAllButton.getTypeface();
            mStockSettingsPadding = new int[] {mSettingsButton.getPaddingStart(),
                    mSettingsButton.getPaddingTop(), mSettingsButton.getPaddingEnd(),
                    mSettingsButton.getPaddingBottom()};
            mStockClearAllPadding = new int[] {mClearAllButton.getPaddingStart(),
                    mClearAllButton.getPaddingTop(), mClearAllButton.getPaddingEnd(),
                    mClearAllButton.getPaddingBottom()};
            mStockHistoryPadding = new int[] {mHistoryButton.getPaddingStart(),
                    mHistoryButton.getPaddingTop(), mHistoryButton.getPaddingEnd(),
                    mHistoryButton.getPaddingBottom()};
            mStockHistoryDrawablePadding = mHistoryButton.getCompoundDrawablePadding();
            mStockSeenTypeface = mSeenNotifsFooterTextView != null
                    ? mSeenNotifsFooterTextView.getTypeface() : null;

            final int gap = ShoalShade.dp(context, 8);
            final int touch = ShoalShade.dp(context, 48);
            final int circle = ShoalShade.dp(context, 40);
            // Fresh params (not copies of the stock ones: "Clear all" is 0dp / match-constraint
            // in the stock layout, and a copied, already validated params keeps treating it as
            // such, which collapsed it to nothing).
            // Manage (settings) at the start.
            final ConstraintLayout.LayoutParams settings = new ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT, touch);
            settings.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            settings.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            settings.validate();
            mSettingsButton.setLayoutParams(settings);
            // History right after it: a 40 dp hairline circle, icon only.
            final ConstraintLayout.LayoutParams history = new ConstraintLayout.LayoutParams(
                    circle, touch);
            history.startToEnd = R.id.settings_button;
            history.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            history.setMarginStart(gap);
            history.validate();
            mHistoryButton.setLayoutParams(history);
            // Clear all, wrapped, at the end.
            final ConstraintLayout.LayoutParams clear = new ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT, touch);
            clear.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            clear.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            clear.validate();
            mClearAllButton.setLayoutParams(clear);

            mSettingsButton.setText(R.string.manage_notifications_text);
            mSettingsButton.setCompoundDrawablesRelative(null, null, null, null);
            final int padH = ShoalShade.dp(context, 18);
            mSettingsButton.setPaddingRelative(padH, 0, padH, 0);
            mClearAllButton.setPaddingRelative(padH, 0, padH, 0);
            // Centre the history icon (a start drawable, no text) in the circle.
            final Drawable icon = mHistoryButton.getCompoundDrawablesRelative()[0];
            int iconW = icon != null ? icon.getBounds().width() : 0;
            if (iconW <= 0 && icon != null) iconW = icon.getIntrinsicWidth();
            if (iconW <= 0) iconW = ShoalShade.dp(context, 20);
            mHistoryButton.setCompoundDrawablePadding(0);
            mHistoryButton.setPaddingRelative(Math.max(0, (circle - iconW) / 2), 0, 0, 0);
            mHistoryButton.setMinWidth(0);
            mHistoryButton.setMinimumWidth(0);
        }

        final boolean ink = ShoalShade.isInk(context);
        final int accent = ShoalShade.accent(context);
        mClearAllButton.setBackground(ShoalShade.pill(context, accent, 0, 40));
        // The theme's own colour for content on the accent, right in light and dark.
        mClearAllButton.setTextColor(
                context.getColor(com.android.internal.R.color.ichthys_on_accent));
        mClearAllButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mClearAllButton.setTypeface(ShoalShade.text(context, 600));

        final int cardFill = ink ? ShoalShade.CARD : mContext.getColor(
                com.android.internal.R.color.materialColorSurfaceContainerHigh);
        final int hairline = ink ? ShoalShade.HAIRLINE : 0x1F000000;
        final int text = ink ? ShoalShade.TEXT : onSurface;
        mSettingsButton.setBackground(ShoalShade.pill(context, cardFill, hairline, 40));
        mSettingsButton.setTextColor(text);
        mSettingsButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mSettingsButton.setTypeface(ShoalShade.text(context, 500));
        mHistoryButton.setBackground(ShoalShade.pill(context, cardFill, hairline, 40));
        mHistoryButton.setCompoundDrawableTintList(ColorStateList.valueOf(text));
        // "Unlock to see older notifications" and the like: the text face too.
        if (mSeenNotifsFooterTextView != null) {
            mSeenNotifsFooterTextView.setTypeface(ShoalShade.text(context, 400));
        }
    }

    public void setIsBlurSupported(boolean isBlurSupported) {
        if (notificationShadeBlur()) {
            if (mIsBlurSupported == isBlurSupported) {
                return;
            }
            mIsBlurSupported = isBlurSupported;
            updateColors();
        }
    }

    @Override
    @NonNull
    public ExpandableViewState createExpandableViewState() {
        return new FooterViewState();
    }

    public class FooterViewState extends ExpandableViewState {
        /**
         * used to hide the content of the footer to animate.
         * #hide is applied without animation, but #hideContent has animation.
         */
        public boolean hideContent;

        /**
         * When true, skip animating Y on the next #animateTo.
         * Once true, remains true until reset in #animateTo.
         */
        public boolean resetY = false;

        @Override
        public void copyFrom(ViewState viewState) {
            super.copyFrom(viewState);
            if (viewState instanceof FooterViewState) {
                hideContent = ((FooterViewState) viewState).hideContent;
            }
        }

        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof FooterView) {
                FooterView footerView = (FooterView) view;
                footerView.setContentVisibleAnimated(!hideContent);
            }
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            if (child instanceof FooterView) {
                // Must set animateY=false before super.animateTo, which checks for animateY
                if (resetY) {
                    properties.getAnimationFilter().animateY = false;
                    resetY = false;
                }
            }
            super.animateTo(child, properties);
        }
    }
}
