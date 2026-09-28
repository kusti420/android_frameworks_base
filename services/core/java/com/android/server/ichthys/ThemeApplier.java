/*
 * Copyright (C) 2026 IchthysOS
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

package com.android.server.ichthys;

import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.util.Slog;
import android.util.TypedValue;

/**
 * Turns theme.shape.* into resource overrides with fabricated overlays (owned by "android", so
 * they survive reboots in OverlayManagerService's state and need no APK).
 *
 * <p>Overridden resources:
 * <ul>
 *   <li>android: {@code dimen/config_dialogCornerRadius}, {@code config_bottomDialogCornerRadius},
 *       {@code system_app_widget_background_radius} = radius; {@code config_buttonCornerRadius}
 *       = radius/2 (or 100dp for pill ends).</li>
 *   <li>com.android.systemui: {@code dimen/qs_corner_radius}, {@code notification_corner_radius}
 *       = radius.</li>
 * </ul>
 * Colors are not overlaid here: theme.accent/style go through Monet (THEME_CUSTOMIZATION JSON),
 * which already regenerates every palette resource.
 */
final class ThemeApplier {
    private static final String TAG = "IchthysConfig";
    private static final String OWNER = "android";
    private static final String ANDROID_OVERLAY = "ichthys_theme_shape";
    private static final String SYSUI_OVERLAY = "ichthys_theme_shape_sysui";
    private static final String SYSUI = "com.android.systemui";
    private static final int PILL_DP = 100;

    private final Context mContext;

    ThemeApplier(Context context) {
        mContext = context;
    }

    /**
     * @param radiusDp corner radius, or null to remove the overrides (stock look)
     * @param style "rounded", "pill" or "squircle"
     */
    void applyShape(Long radiusDp, String style, int userId) {
        final OverlayManager om = mContext.getSystemService(OverlayManager.class);
        if (om == null) {
            Slog.w(TAG, "OverlayManager unavailable, shape not applied");
            return;
        }
        final OverlayIdentifier androidId = new OverlayIdentifier(OWNER, ANDROID_OVERLAY);
        final OverlayIdentifier sysuiId = new OverlayIdentifier(OWNER, SYSUI_OVERLAY);
        final OverlayManagerTransaction.Builder t = new OverlayManagerTransaction.Builder();
        if (radiusDp == null) {
            t.unregisterFabricatedOverlay(androidId);
            t.unregisterFabricatedOverlay(sysuiId);
        } else {
            final int r = radiusDp.intValue();
            final int button = "pill".equals(style) ? PILL_DP : Math.max(4, r / 2);
            final FabricatedOverlay android;
            final FabricatedOverlay sysui;
            try {
                // The legacy Builder only takes int data types (16..31); dimensions need the
                // FRRO dimension overload on FabricatedOverlay itself.
                android = new FabricatedOverlay(ANDROID_OVERLAY, "android");
                android.setOwningPackage(OWNER);
                setDp(android, "android:dimen/config_dialogCornerRadius", r);
                setDp(android, "android:dimen/config_bottomDialogCornerRadius", r);
                setDp(android, "android:dimen/system_app_widget_background_radius", r);
                setDp(android, "android:dimen/config_buttonCornerRadius", button);
                sysui = new FabricatedOverlay(SYSUI_OVERLAY, SYSUI);
                sysui.setOwningPackage(OWNER);
                setDp(sysui, SYSUI + ":dimen/qs_corner_radius", r);
                setDp(sysui, SYSUI + ":dimen/notification_corner_radius", r);
            } catch (RuntimeException e) {
                // Shape is cosmetic: never let it fail the whole generation.
                Slog.w(TAG, "shape overlay not built, radius not applied", e);
                return;
            }
            t.registerFabricatedOverlay(android);
            t.registerFabricatedOverlay(sysui);
            t.setEnabled(android.getIdentifier(), true, userId);
            t.setEnabled(sysui.getIdentifier(), true, userId);
        }
        try {
            om.commit(t.build());
        } catch (RuntimeException e) {
            // Unregistering overlays that were never registered also lands here; harmless.
            Slog.w(TAG, "shape overlay commit failed", e);
        }
    }

    private static void setDp(FabricatedOverlay overlay, String resourceName, int dp) {
        overlay.setResourceValue(resourceName, (float) dp, TypedValue.COMPLEX_UNIT_DIP,
                null /* configuration */);
    }
}
