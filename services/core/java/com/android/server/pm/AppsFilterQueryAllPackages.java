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

package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;

/**
 * IchthysOS: the bridge between AppsFilter and the user-controlled QUERY_ALL_PACKAGES grant
 * (decision 2).
 *
 * <p>Stock AppsFilter only looks at whether an app <em>requests</em> QUERY_ALL_PACKAGES in its
 * manifest, because the permission is normal and always granted. Here the permission service
 * withholds it from apps the user installed until the user grants it (see
 * {@code AppIdPermissionPolicy.evaluateQueryAllPackagesFlags}), and AppsFilter has to honour that:
 * an app whose grant is withheld is filtered exactly like a stock Android 11+ app that never asked
 * for the permission.
 *
 * <p>The two halves live in different modules ({@code services.permission} cannot be called from
 * {@code services.core} directly), so they meet here:
 *
 * <ul>
 *   <li>the permission service registers a {@link RevokedChecker} once its state is loaded, and
 *       AppsFilter asks it on every uncached decision;
 *   <li>the permission service reports grant changes through {@link #onQueryAllPackagesChanged},
 *       and AppsFilter rebuilds its cached decisions for that app.
 * </ul>
 *
 * <p>Until a checker is registered nothing is withheld, which is the stock behaviour.
 *
 * <p>The permission service reports a withheld grant as granted to ordinary app callers, so the
 * app cannot tell. That also holds for a permission check a system service makes while it still
 * runs with the app's calling identity. A service that checks QUERY_ALL_PACKAGES itself before
 * handing out package information must therefore use {@link #checkPermission} or
 * {@link #enforcePermission} (or their calling-identity variants) instead of the Context methods.
 */
public final class AppsFilterQueryAllPackages {

    /** Answers whether a UID's QUERY_ALL_PACKAGES grant is currently withheld. */
    public interface RevokedChecker {
        /** Must be cheap and lock-free: it is called from AppsFilter's hot path. */
        boolean isQueryAllPackagesRevoked(int uid);
    }

    /** Implemented by AppsFilter to refresh its cache after a grant change. */
    interface ChangeListener {
        void onQueryAllPackagesChanged(int uid);
    }

    @Nullable
    private static volatile RevokedChecker sRevokedChecker;
    @Nullable
    private static volatile ChangeListener sChangeListener;

    private AppsFilterQueryAllPackages() {}

    /** Called by the permission service once its state is loaded. */
    public static void setRevokedChecker(@Nullable RevokedChecker checker) {
        sRevokedChecker = checker;
    }

    static void setChangeListener(@Nullable ChangeListener listener) {
        sChangeListener = listener;
    }

    /**
     * Whether {@code uid} requests QUERY_ALL_PACKAGES but must be filtered as if it did not.
     * Fails open (stock behaviour) when the permission service is not up or cannot answer.
     */
    public static boolean isRevoked(int uid) {
        final RevokedChecker checker = sRevokedChecker;
        if (checker == null) {
            return false;
        }
        try {
            return checker.isQueryAllPackagesRevoked(uid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * {@link Context#checkPermission} for QUERY_ALL_PACKAGES that also treats a withheld grant
     * as denied, whatever the calling identity is.
     */
    public static int checkPermission(@NonNull Context context, int pid, int uid) {
        if (isRevoked(uid)) {
            return PackageManager.PERMISSION_DENIED;
        }
        return context.checkPermission(Manifest.permission.QUERY_ALL_PACKAGES, pid, uid);
    }

    /** {@link #checkPermission} for the caller of the current binder call, or the system. */
    public static int checkCallingOrSelfPermission(@NonNull Context context) {
        return checkPermission(context, Binder.getCallingPid(), Binder.getCallingUid());
    }

    /**
     * {@link Context#enforcePermission} for QUERY_ALL_PACKAGES that also treats a withheld grant
     * as denied, whatever the calling identity is.
     */
    public static void enforcePermission(@NonNull Context context, int pid, int uid,
            @Nullable String message) {
        if (isRevoked(uid)) {
            throw new SecurityException((message != null ? message + ": " : "") + "uid " + uid
                    + " does not have " + Manifest.permission.QUERY_ALL_PACKAGES + ".");
        }
        context.enforcePermission(Manifest.permission.QUERY_ALL_PACKAGES, pid, uid, message);
    }

    /** {@link #enforcePermission} for the caller of the current binder call, or the system. */
    public static void enforceCallingOrSelfPermission(@NonNull Context context,
            @Nullable String message) {
        enforcePermission(context, Binder.getCallingPid(), Binder.getCallingUid(), message);
    }

    /**
     * Called by the permission service, after the state change has been committed, whenever an
     * app's QUERY_ALL_PACKAGES grant flips. Must not block: the caller holds the permission state
     * lock, so the listener only schedules the work.
     */
    public static void onQueryAllPackagesChanged(int uid) {
        final ChangeListener listener = sChangeListener;
        if (listener != null) {
            listener.onQueryAllPackagesChanged(uid);
        }
    }
}
