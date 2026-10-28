/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.views;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import static com.android.systemui.util.Utils.isGesturalModeOnDefaultDisplay;

import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;

import com.android.systemui.navigationbar.NavigationBarComponent.NavigationBarScope;
import com.android.systemui.navigationbar.views.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.views.buttons.IchthysNavStyle;
import com.android.systemui.res.R;
import com.android.systemui.settings.DisplayTracker;
import android.provider.Settings;

import com.android.systemui.shared.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** */
@NavigationBarScope
public final class NavigationBarTransitions extends BarTransitions implements
        LightBarTransitionsController.DarkIntensityApplier {

    public static final int MIN_COLOR_ADAPT_TRANSITION_TIME = 400;
    public static final int DEFAULT_COLOR_ADAPT_TRANSITION_TIME = 1700;
    private List<Listener> mListeners = new ArrayList<>();

    /**
     * Notified when the color of nav bar elements changes.
     */
    public interface DarkIntensityListener {
        /**
         * Called when the color of nav bar elements changes.
         * @param darkIntensity 0 is the lightest color, 1 is the darkest.
         */
        void onDarkIntensity(float darkIntensity);
    }

    private final NavigationBarView mView;
    private final LightBarTransitionsController mLightTransitionsController;
    private final DisplayTracker mDisplayTracker;
    private final boolean mAllowAutoDimWallpaperNotVisible;
    private boolean mWallpaperVisible;

    private boolean mLightsOut;
    private boolean mAutoDim;
    private View mNavButtons;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;
    private List<DarkIntensityListener> mDarkIntensityListeners;
    private boolean mIchthysInk;
    private boolean mIchthysTinted;

    @Inject
    public NavigationBarTransitions(
            NavigationBarView view,
            LightBarTransitionsController.Factory lightBarTransitionsControllerFactory,
            DisplayTracker displayTracker) {
        super(view, R.drawable.nav_background);

        mView = view;
        mLightTransitionsController = lightBarTransitionsControllerFactory.create(this);
        mDisplayTracker = displayTracker;
        mAllowAutoDimWallpaperNotVisible = view.getContext().getResources()
                .getBoolean(R.bool.config_navigation_bar_enable_auto_dim_no_visible_wallpaper);
        mDarkIntensityListeners = new ArrayList();

        mView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    View currentView = mView.getCurrentView();
                    if (currentView != null) {
                        mNavButtons = currentView.findViewById(R.id.nav_buttons);
                        applyLightsOut(false, true);
                    }
                });
        View currentView = mView.getCurrentView();
        if (currentView != null) {
            mNavButtons = currentView.findViewById(R.id.nav_buttons);
        }
    }

    public void init() {
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyLightsOut(false /*animate*/, true /*force*/);
    }

    @Override
    protected void applyModeBackground(int oldMode, int newMode, boolean animate) {
        // Custom Options "pitch black": keep the nav bar background opaque black at all times.
        // IchthysOS: gate on the Abyss palette rather than custom_pitch_black.
        if (mIchthysInk || (Settings.Secure.getInt(
                mView.getContext().getContentResolver(), "theme_engine_style_mode", 0) == 1
                && (mView.getContext().getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
            oldMode = MODE_OPAQUE_DARK;
            newMode = MODE_OPAQUE_DARK;
            animate = false;
        }
        // IchthysOS navBar.style (shoal/lattice/mono) in dark mode: the opaque bar is ink, not
        // black. The tint filter can't be removed once set, so "untint" with DST (keeps the
        // bar's own colour).
        if (mIchthysInk) {
            mBarBackground.setTint(IchthysNavStyle.tokens(mView.getContext()).ink);
            mBarBackground.setTintMode(PorterDuff.Mode.SRC_IN);
            mIchthysTinted = true;
        } else if (mIchthysTinted) {
            mBarBackground.setTintMode(PorterDuff.Mode.DST);
            mIchthysTinted = false;
        }
        super.applyModeBackground(oldMode, newMode, animate);
    }

    /**
     * IchthysOS: force the opaque ink bar with light-on-dark keys (navBar.style key styles while
     * the dark theme is on).
     */
    void setIchthysInk(boolean ink) {
        if (mIchthysInk == ink) return;
        mIchthysInk = ink;
        applyModeBackground(-1, getMode(), false /* animate */);
        reapplyDarkIntensity();
    }

    @Override
    public void destroy() {
        mLightTransitionsController.destroy();
    }

    void setWallpaperVisibility(boolean visible) {
        mWallpaperVisible = visible;
        applyLightsOut(true, false);
    }

    @Override
    public void setAutoDim(boolean autoDim) {
        // Ensure we aren't in gestural nav if we are triggering auto dim
        if (autoDim && isGesturalModeOnDefaultDisplay(mView.getContext(), mDisplayTracker,
                mNavBarMode)) {
            return;
        }
        if (mAutoDim == autoDim) return;
        mAutoDim = autoDim;
        applyLightsOut(true, false);
    }

    void setBackgroundFrame(Rect frame) {
        mBarBackground.setFrame(frame);
    }

    void setBackgroundOverrideAlpha(float alpha) {
        mBarBackground.setOverrideAlpha(alpha);
    }

    @Override
    protected boolean isLightsOut(int mode) {
        return super.isLightsOut(mode) || (mAllowAutoDimWallpaperNotVisible && mAutoDim
                && !mWallpaperVisible && mode != MODE_WARNING);
    }

    public LightBarTransitionsController getLightTransitionsController() {
        return mLightTransitionsController;
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyLightsOut(animate, false /*force*/);
        for (Listener listener : mListeners) {
            listener.onTransition(newMode);
        }
    }

    private void applyLightsOut(boolean animate, boolean force) {
        // apply to lights out
        applyLightsOut(isLightsOut(getMode()), animate, force);
    }

    private void applyLightsOut(boolean lightsOut, boolean animate, boolean force) {
        if (!force && lightsOut == mLightsOut) return;

        mLightsOut = lightsOut;
        if (mNavButtons == null) return;

        // ok, everyone, stop it right there
        mNavButtons.animate().cancel();

        // Bump percentage by 10% if dark.
        float darkBump = mLightTransitionsController.getCurrentDarkIntensity() / 10;
        final float navButtonsAlpha = lightsOut ? 0.6f + darkBump : 1f;

        if (!animate) {
            mNavButtons.setAlpha(navButtonsAlpha);
        } else {
            final int duration = lightsOut ? LIGHTS_OUT_DURATION : LIGHTS_IN_DURATION;
            mNavButtons.animate()
                .alpha(navButtonsAlpha)
                .setDuration(duration)
                .start();
        }
    }

    public void reapplyDarkIntensity() {
        applyDarkIntensity(mLightTransitionsController.getCurrentDarkIntensity());
    }

    @Override
    public void applyDarkIntensity(float darkIntensity) {
        if (mIchthysInk) {
            // The bar is forced to opaque ink: keys stay light regardless of the app's request.
            darkIntensity = 0f;
        }
        SparseArray<ButtonDispatcher> buttonDispatchers = mView.getButtonDispatchers();
        for (int i = buttonDispatchers.size() - 1; i >= 0; i--) {
            buttonDispatchers.valueAt(i).setDarkIntensity(darkIntensity);
        }
        mView.getRotationButtonController().setDarkIntensity(darkIntensity);

        for (DarkIntensityListener listener : mDarkIntensityListeners) {
            listener.onDarkIntensity(darkIntensity);
        }
        if (mAutoDim) {
            applyLightsOut(false, true);
        }
    }

    @Override
    public int getTintAnimationDuration() {
        if (isGesturalModeOnDefaultDisplay(mView.getContext(), mDisplayTracker, mNavBarMode)) {
            return Math.max(DEFAULT_COLOR_ADAPT_TRANSITION_TIME, MIN_COLOR_ADAPT_TRANSITION_TIME);
        }
        return LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION;
    }

    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    /**
     * Register {@code listener} to be notified when the color of nav bar elements changes.
     *
     * Returns the current nav bar color.
     */
    public float addDarkIntensityListener(DarkIntensityListener listener) {
        mDarkIntensityListeners.add(listener);
        return mLightTransitionsController.getCurrentDarkIntensity();
    }

    /**
     * Remove {@code listener} from being notified when the color of nav bar elements changes.
     */
    public void removeDarkIntensityListener(DarkIntensityListener listener) {
        mDarkIntensityListeners.remove(listener);
    }

    public void dump(PrintWriter pw) {
        pw.println("NavigationBarTransitions:");
        pw.println("  mMode: " + getMode());
        pw.println("  mAlwaysOpaque: " + isAlwaysOpaque());
        pw.println("  mAllowAutoDimWallpaperNotVisible: " + mAllowAutoDimWallpaperNotVisible);
        pw.println("  mWallpaperVisible: " + mWallpaperVisible);
        pw.println("  mLightsOut: " + mLightsOut);
        pw.println("  mAutoDim: " + mAutoDim);
        pw.println("  mIchthysInk: " + mIchthysInk);
        pw.println("  bg overrideAlpha: " + mBarBackground.getOverrideAlpha());
        pw.println("  bg color: " + mBarBackground.getColor());
        pw.println("  bg frame: " + mBarBackground.getFrame());
    }

    void addListener(Listener listener) {
        mListeners.add(listener);
    }

    void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    interface Listener {
        void onTransition(int newMode);
    }
}
