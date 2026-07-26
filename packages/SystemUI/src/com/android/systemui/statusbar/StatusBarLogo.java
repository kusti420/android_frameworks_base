/**
 * Copyright (C) 2017-2024 crDroid Android Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;

/**
 * Small custom logo shown in the status bar (crDroid "status bar logo" feature),
 * adapted to A17. It is a self-contained {@link ImageView} that observes its
 * settings via the {@link TunerService} ({@code system:} prefix) and resolves the
 * selected logo drawable by name.
 *
 * <p>Position (left/right of the clock) is handled by re-parenting the view within
 * the start-side status bar container it is anchored to. Tinting is driven by
 * {@link DarkReceiver}; the host registers the view with the correct dispatcher.
 *
 * @hide
 */
public class StatusBarLogo extends ImageView implements TunerService.Tunable, DarkReceiver {

    private static final int POSITION_LEFT = 0;
    private static final int POSITION_RIGHT = 1;

    private static final String STATUS_BAR_LOGO =
            "system:" + Settings.System.STATUS_BAR_LOGO;
    private static final String STATUS_BAR_LOGO_POSITION =
            "system:" + Settings.System.STATUS_BAR_LOGO_POSITION;
    private static final String STATUS_BAR_LOGO_STYLE =
            "system:" + Settings.System.STATUS_BAR_LOGO_STYLE;

    // Order must match crDroidSettings @array/statusbar_logo_drawables. Resolved at compile time
    // via R.drawable references (rather than Resources#getIdentifier by name), so the lookup does
    // not depend on the runtime package name of the hosting context.
    private static final int[] LOGO_DRAWABLES = {
        R.drawable.ic_crdroid_logo, R.drawable.ic_android_logo, R.drawable.ic_adidas,
        R.drawable.ic_alien, R.drawable.ic_apple_logo, R.drawable.ic_avengers,
        R.drawable.ic_batman, R.drawable.ic_batman_tdk, R.drawable.ic_beats,
        R.drawable.ic_biohazard, R.drawable.ic_blackberry, R.drawable.ic_cannabis,
        R.drawable.ic_emoticon_cool, R.drawable.ic_emoticon_devil, R.drawable.ic_fire,
        R.drawable.ic_heart, R.drawable.ic_nike, R.drawable.ic_pac_man,
        R.drawable.ic_puma, R.drawable.ic_rog, R.drawable.ic_spiderman,
        R.drawable.ic_superman, R.drawable.ic_windows, R.drawable.ic_xbox,
        R.drawable.ic_ghost, R.drawable.ic_ninja, R.drawable.ic_robot,
        R.drawable.ic_ironman, R.drawable.ic_captain_america, R.drawable.ic_flash,
        R.drawable.ic_tux_logo, R.drawable.ic_ubuntu_logo, R.drawable.ic_mint_logo
    };

    private boolean mAttached;
    // Guards against re-entrancy: re-parenting the view fires detach/attach, which must not
    // re-register tunables or recurse back into addToContainer().
    private boolean mInLayoutChange;
    private boolean mEnabled;
    private int mPosition = POSITION_LEFT;
    private int mStyle;
    private int mTint = Color.WHITE;

    // The start-side container (LEFT position, next to the clock) and the end-side container
    // (RIGHT position, by the system icons) the logo can live in, plus the clock it anchors to.
    private LinearLayout mStartContainer;
    private LinearLayout mEndContainer;
    private View mClockView;

    public StatusBarLogo(Context context) {
        this(context, null);
    }

    public StatusBarLogo(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarLogo(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setScaleType(ScaleType.CENTER_INSIDE);
        setVisibility(View.GONE);
    }

    /**
     * Anchors the logo. LEFT position places it in {@code startContainer} next to
     * {@code clockView}; RIGHT position places it at the end of {@code endContainer}
     * (the far-right system-icon group). Must be called before the view is attached.
     */
    public void anchorTo(LinearLayout startContainer, LinearLayout endContainer, View clockView) {
        mStartContainer = startContainer;
        mEndContainer = endContainer;
        mClockView = clockView;
        final int pad = (int) (4 * getResources().getDisplayMetrics().density);
        setPaddingRelative(pad, 0, pad, 0);
        addToContainer();
    }

    private LinearLayout targetContainer() {
        return mPosition == POSITION_LEFT ? mStartContainer : mEndContainer;
    }

    private int computeTargetIndex(LinearLayout container) {
        if (mPosition == POSITION_LEFT) {
            // Just before the clock on the start side.
            int clockIndex = container.indexOfChild(mClockView);
            return clockIndex < 0 ? 0 : clockIndex;
        }
        // RIGHT: append to the end-side container (far right, after the system icons).
        return container.getChildCount();
    }

    // Re-parenting the logo (removeView/addView) must NOT happen synchronously from
    // onTuningChanged: the first tuning callback fires from addTunable() inside
    // onAttachedToWindow, i.e. while the parent container is mid-iteration in
    // dispatchAttachedToWindow. Mutating its children there corrupts the loop (null child ->
    // NPE crash loop). Defer to the next frame when we're already attached; do it inline
    // (pre-attach, safe) otherwise.
    private void scheduleAddToContainer() {
        if (isAttachedToWindow()) {
            post(this::addToContainer);
        } else {
            addToContainer();
        }
    }

    private void addToContainer() {
        final LinearLayout target = targetContainer();
        if (target == null || mInLayoutChange) {
            return;
        }
        final ViewParent parent = getParent();
        final ViewGroup currentParent = (parent instanceof ViewGroup) ? (ViewGroup) parent : null;
        final int targetIndex = computeTargetIndex(target);
        // Already in the right container at the right spot — do NOT remove/re-add, which would
        // churn attach state and (during the initial tuning callback) recurse infinitely.
        if (currentParent == target && target.indexOfChild(this) == targetIndex) {
            return;
        }
        mInLayoutChange = true;
        try {
            if (currentParent != null) {
                currentParent.removeView(this);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            setLayoutParams(lp);
            target.addView(this, computeTargetIndex(target));
        } finally {
            mInLayoutChange = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Ignore attach events caused by our own re-parenting.
        if (mInLayoutChange || mAttached) {
            return;
        }
        mAttached = true;
        final TunerService tunerService = Dependency.get(TunerService.class);
        // Register all keys in one call so the first (synchronous) callback runs after the
        // view is fully set up.
        tunerService.addTunable(this, STATUS_BAR_LOGO, STATUS_BAR_LOGO_POSITION,
                STATUS_BAR_LOGO_STYLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Ignore detach events caused by our own re-parenting.
        if (mInLayoutChange || !mAttached) {
            return;
        }
        mAttached = false;
        Dependency.get(TunerService.class).removeTunable(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case STATUS_BAR_LOGO:
                mEnabled = TunerService.parseIntegerSwitch(newValue, false);
                updateLogo();
                break;
            case STATUS_BAR_LOGO_POSITION:
                mPosition = TunerService.parseInteger(newValue, POSITION_LEFT);
                scheduleAddToContainer();
                break;
            case STATUS_BAR_LOGO_STYLE:
                mStyle = TunerService.parseInteger(newValue, 0);
                updateLogo();
                break;
            default:
                break;
        }
    }

    private void updateLogo() {
        if (!mEnabled) {
            setVisibility(View.GONE);
            setImageDrawable(null);
            return;
        }
        int style = mStyle;
        if (style < 0 || style >= LOGO_DRAWABLES.length) {
            style = 0;
        }
        setImageResource(LOGO_DRAWABLES[style]);
        setImageTintList(ColorStateList.valueOf(mTint));
        setVisibility(View.VISIBLE);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTint = DarkIconDispatcher.getTint(areas, this, tint);
        if (mEnabled && getDrawable() != null) {
            setImageTintList(ColorStateList.valueOf(mTint));
        }
    }
}
