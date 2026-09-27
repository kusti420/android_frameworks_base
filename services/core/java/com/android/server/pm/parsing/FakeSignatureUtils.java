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

package com.android.server.pm.parsing;

import android.content.pm.Signature;
import android.os.Bundle;
import android.util.Slog;

/**
 * IchthysOS: signature spoofing, the platform half of running microG in place of Google Play
 * services.
 *
 * <p>microG's GmsCore takes the {@code com.google.android.gms} package name, but it is signed with
 * microG's own key. Apps that talk to Play services routinely verify the signature of whatever
 * answers to that package name, so without help they reject microG outright -- which is why Google
 * sign-in and most GMS-dependent apps do not work on a plain AOSP build with microG installed.
 *
 * <p>A package may therefore declare the signature it wants reported:
 *
 * <pre>
 *   &lt;uses-permission android:name="android.permission.FAKE_PACKAGE_SIGNATURE" /&gt;
 *   &lt;application&gt;
 *     &lt;meta-data android:name="fake-signature" android:value="308204..." /&gt;
 *   &lt;/application&gt;
 * </pre>
 *
 * <p><b>This is deliberately restricted.</b> {@code FAKE_PACKAGE_SIGNATURE} is declared
 * {@code signature|privileged}, so only packages in the privileged allow-list -- i.e. shipped in
 * {@code /system/priv-app} by this ROM -- can hold it. A sideloaded app cannot claim another app's
 * identity, which is the failure mode that makes the widely-circulated "signature spoofing" patches
 * dangerous.
 */
public final class FakeSignatureUtils {

    private static final String TAG = "FakeSignature";

    /** Permission a package must hold before its declared fake signature is honoured. */
    public static final String PERMISSION_FAKE_PACKAGE_SIGNATURE =
            "android.permission.FAKE_PACKAGE_SIGNATURE";

    /** Application meta-data key holding the hex-encoded certificate to report. */
    private static final String METADATA_FAKE_SIGNATURE = "fake-signature";

    private FakeSignatureUtils() {}

    /**
     * The signature this package asks to be reported as, or {@code null} when it asks for nothing
     * or is not allowed to.
     *
     * @param metaData the package's application meta-data
     * @param requestsPermission whether the package requested
     *     {@link #PERMISSION_FAKE_PACKAGE_SIGNATURE}
     * @param packageName only used for logging
     */
    public static Signature getFakeSignature(Bundle metaData, boolean requestsPermission,
            String packageName) {
        if (metaData == null || !requestsPermission) {
            return null;
        }
        final String hex = metaData.getString(METADATA_FAKE_SIGNATURE);
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        try {
            return new Signature(hex);
        } catch (IllegalArgumentException e) {
            // A malformed value must not take down whoever asked for this PackageInfo.
            Slog.w(TAG, "ignoring malformed fake-signature for " + packageName);
            return null;
        }
    }
}
