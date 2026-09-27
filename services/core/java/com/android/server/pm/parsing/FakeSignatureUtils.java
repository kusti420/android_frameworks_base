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

import libcore.util.HexEncoding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

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
 * <p><b>This is deliberately restricted.</b> Only {@link #ALLOWED_PACKAGES}, installed as
 * privileged system packages, may spoof, and only as Google's Play certificate. The permission
 * request is checked too, but on its own it proves nothing: this runs while building PackageInfo,
 * before any grant is consulted, and platform-key-signed packages would otherwise qualify. A
 * sideloaded or other system app cannot claim another app's identity, which is the failure mode
 * that makes the widely-circulated "signature spoofing" patches dangerous.
 */
public final class FakeSignatureUtils {

    private static final String TAG = "FakeSignature";

    /** Permission a package must hold before its declared fake signature is honoured. */
    public static final String PERMISSION_FAKE_PACKAGE_SIGNATURE =
            "android.permission.FAKE_PACKAGE_SIGNATURE";

    /** Application meta-data key holding the hex-encoded certificate to report. */
    private static final String METADATA_FAKE_SIGNATURE = "fake-signature";

    /**
     * SHA-256 of the one certificate that may be reported: Google's Play services / Play Store
     * release certificate (SHA-1 38:91:8A:45:3D:07:19:93:54:F8:B1:9A:F0:5E:C6:56:2C:ED:57:88),
     * which is what microG's GmsCore and FakeStore declare.
     */
    private static final String GOOGLE_PLAY_CERT_SHA256 =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";

    /**
     * The only packages allowed to spoof, each only as {@link #GOOGLE_PLAY_CERT_SHA256}.
     * Requesting the permission alone was never enough (it is not tied to a grant check here), and
     * without a package→certificate binding any privileged or platform-signed package could claim
     * any identity. (kusti420/ichthysos-tracker#12)
     */
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "com.google.android.gms",
            "com.android.vending");

    private FakeSignatureUtils() {}

    /**
     * The signature this package asks to be reported as, or {@code null} when it asks for nothing
     * or is not allowed to. All of these must hold: the package requested
     * {@link #PERMISSION_FAKE_PACKAGE_SIGNATURE}, it is a privileged system package, its name is
     * in {@link #ALLOWED_PACKAGES}, and the declared certificate is Google's Play certificate.
     *
     * @param metaData the package's application meta-data
     * @param requestsPermission whether the package requested
     *     {@link #PERMISSION_FAKE_PACKAGE_SIGNATURE}
     * @param isPrivileged whether the package is installed as a privileged system package
     * @param packageName the package asking to be reported with a different signature
     */
    public static Signature getFakeSignature(Bundle metaData, boolean requestsPermission,
            boolean isPrivileged, String packageName) {
        if (metaData == null || !requestsPermission) {
            return null;
        }
        final String hex = metaData.getString(METADATA_FAKE_SIGNATURE);
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        if (!isPrivileged || !ALLOWED_PACKAGES.contains(packageName)) {
            Slog.w(TAG, "refusing fake-signature for " + packageName
                    + " (privileged=" + isPrivileged + ")");
            return null;
        }
        final Signature signature;
        try {
            signature = new Signature(hex);
        } catch (IllegalArgumentException e) {
            // A malformed value must not take down whoever asked for this PackageInfo.
            Slog.w(TAG, "ignoring malformed fake-signature for " + packageName);
            return null;
        }
        if (!GOOGLE_PLAY_CERT_SHA256.equals(sha256Hex(signature.toByteArray()))) {
            Slog.w(TAG, "refusing fake-signature for " + packageName
                    + ": not the Google Play certificate");
            return null;
        }
        return signature;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexEncoding.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(bytes), false /* upperCase */);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
