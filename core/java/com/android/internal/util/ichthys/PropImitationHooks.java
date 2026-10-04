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

package com.android.internal.util.ichthys;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * IchthysOS: the missing consumer for the crDroid "Play Integrity Fix" screen
 * ({@code crDroidSettings/res/xml/play_integrity_fix.xml}).
 *
 * <p>The Settings screen already imports a pif.json into
 * {@code Settings.Secure.spoof_pif_config} and offers a {@code spoof_pif_photos} switch, but
 * nothing in the ROM read either, so the whole screen was inert. This class applies the config by
 * overriding {@link Build} fields inside the processes that Play Integrity actually inspects.
 *
 * <p>Only a small allow-list of processes is touched, and the config is only read for those, so
 * ordinary app launches pay nothing.
 *
 * <ul>
 *   <li>{@code com.google.android.gms} -- Play services, or microG's GmsCore standing in for it,
 *       which is where the attestation the verdict is derived from is produced.</li>
 *   <li>{@code org.microg.gms.droidguard} -- microG's DroidGuard helper.</li>
 *   <li>{@code com.android.vending} -- the Play Store, which reports device state.</li>
 *   <li>{@code com.google.android.apps.photos} -- spoofed to a Pixel only when
 *       {@code spoof_pif_photos} is on, which is what unlocks the original-quality backup tier.</li>
 * </ul>
 */
public final class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.pif.debug", false);

    /** Where the Settings screen stores the imported pif.json. */
    private static final String KEY_PIF_CONFIG = "spoof_pif_config";

    /** Whether Google Photos should be spoofed as a Pixel. Defaults on, matching the XML. */
    private static final String KEY_SPOOF_PHOTOS = "spoof_pif_photos";

    private static final String PACKAGE_GMS = "com.google.android.gms";
    /** microG ships DroidGuard as a separate helper app rather than a process split. */
    private static final String PACKAGE_MICROG_DROIDGUARD = "org.microg.gms.droidguard";
    private static final String PACKAGE_VENDING = "com.android.vending";
    private static final String PACKAGE_PHOTOS = "com.google.android.apps.photos";

    /**
     * Debug-only extra target. Set to a package name to have the stored config applied to that
     * package too, e.g. {@code setprop persist.sys.pif.test_package com.android.settings} to see
     * the spoofed identity in Settings' About phone screen.
     *
     * <p>This exists because the spoof's real targets are Google Play processes, which a GMS-less
     * build does not have -- without it there is no way to confirm the mechanism works at all.
     */
    private static final String PROP_TEST_PACKAGE = "persist.sys.pif.test_package";

    /**
     * The device Photos wants to see for original-quality backup. Pixel XL is the long-standing
     * choice because it is the last model with an unlimited original-quality tier.
     */
    private static final Map<String, String> PHOTOS_PROPS = new HashMap<>();

    static {
        PHOTOS_PROPS.put("BRAND", "google");
        PHOTOS_PROPS.put("MANUFACTURER", "Google");
        PHOTOS_PROPS.put("DEVICE", "marlin");
        PHOTOS_PROPS.put("PRODUCT", "marlin");
        PHOTOS_PROPS.put("MODEL", "Pixel XL");
        PHOTOS_PROPS.put("FINGERPRINT",
                "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    private PropImitationHooks() {}

    /**
     * Per-process system property overrides. Populated by {@link #setProps} for PIF target
     * processes only. {@code null} for all other processes (zero overhead -- a null check).
     *
     * <p>This is read by {@code SystemProperties.get()} to intercept native property reads
     * so DroidGuard sees spoofed values even when it calls {@code SystemProperties.get()}
     * or {@code __system_property_get()} through its Java VM rather than reading
     * {@link android.os.Build} fields.
     */
    private static volatile Map<String, String> sPropertyOverrides;

    /**
     * Maps a PIF config field name to the set of system property keys that carry that value.
     * Android's property scheme scatters the same logical value across multiple partition-prefixed
     * property names; we must override all of them because we can't predict which one DroidGuard
     * will read.
     */
    private static final Map<String, String[]> FIELD_TO_SYSPROPS = new HashMap<>();

    static {
        FIELD_TO_SYSPROPS.put("FINGERPRINT", new String[]{
                "ro.build.fingerprint",
                "ro.system.build.fingerprint",
                "ro.product.build.fingerprint",
                "ro.vendor.build.fingerprint",
                "ro.odm.build.fingerprint",
        });
        FIELD_TO_SYSPROPS.put("BRAND", new String[]{
                "ro.product.brand",
                "ro.product.system.brand",
                "ro.product.vendor.brand",
                "ro.product.odm.brand",
                "ro.product.product.brand",
        });
        FIELD_TO_SYSPROPS.put("DEVICE", new String[]{
                "ro.product.device",
                "ro.product.system.device",
                "ro.product.vendor.device",
                "ro.product.odm.device",
                "ro.product.product.device",
                "ro.build.product",
        });
        FIELD_TO_SYSPROPS.put("MANUFACTURER", new String[]{
                "ro.product.manufacturer",
                "ro.product.system.manufacturer",
                "ro.product.vendor.manufacturer",
                "ro.product.odm.manufacturer",
                "ro.product.product.manufacturer",
        });
        FIELD_TO_SYSPROPS.put("MODEL", new String[]{
                "ro.product.model",
                "ro.product.system.model",
                "ro.product.vendor.model",
                "ro.product.odm.model",
                "ro.product.product.model",
        });
        FIELD_TO_SYSPROPS.put("PRODUCT", new String[]{
                "ro.product.name",
                "ro.product.system.name",
                "ro.product.vendor.name",
                "ro.product.odm.name",
                "ro.product.product.name",
        });
        FIELD_TO_SYSPROPS.put("ID", new String[]{"ro.build.id", "ro.build.display.id"});
        FIELD_TO_SYSPROPS.put("TYPE", new String[]{"ro.build.type"});
        FIELD_TO_SYSPROPS.put("TAGS", new String[]{"ro.build.tags"});
        FIELD_TO_SYSPROPS.put("SECURITY_PATCH", new String[]{"ro.build.version.security_patch"});
        FIELD_TO_SYSPROPS.put("RELEASE", new String[]{"ro.build.version.release",
                "ro.build.version.release_or_codename",
                "ro.build.version.release_or_preview_display"});
        FIELD_TO_SYSPROPS.put("INCREMENTAL", new String[]{"ro.build.version.incremental"});
        FIELD_TO_SYSPROPS.put("DEVICE_INITIAL_SDK_INT", new String[]{
                "ro.product.first_api_level",
                "ro.board.first_api_level",
        });
        FIELD_TO_SYSPROPS.put("SDK_INT", new String[]{"ro.build.version.sdk"});
        FIELD_TO_SYSPROPS.put("BOOTLOADER", new String[]{"ro.bootloader"});
        FIELD_TO_SYSPROPS.put("HARDWARE", new String[]{"ro.hardware"});
    }

    /**
     * Called by {@code SystemProperties.get()} on every property read. Returns the spoofed
     * value if this is a PIF target process and the property is overridden, or {@code null}
     * to fall through to the native property store. The null-check on the volatile field
     * is the only overhead for non-spoofed processes.
     */
    public static String getPropertyOverride(String key) {
        Map<String, String> overrides = sPropertyOverrides;
        if (overrides == null) return null;
        return overrides.get(key);
    }

    /**
     * Builds the system property override map from a PIF config and installs it.
     */
    private static void installPropertyOverrides(Map<String, String> config) {
        Map<String, String> overrides = new HashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String normalized = PropImitationTargets.normalizeKey(entry.getKey());
            String[] sysprops = FIELD_TO_SYSPROPS.get(normalized);
            if (sysprops != null) {
                for (String prop : sysprops) {
                    overrides.put(prop, entry.getValue());
                }
            }
        }
        if (!overrides.isEmpty()) {
            sPropertyOverrides = overrides;
            if (DEBUG) {
                Log.d(TAG, "installed " + overrides.size() + " system property overrides");
            }
        }
    }

    /**
     * Applies the configured spoof for this process, if it is one we spoof at all.
     *
     * <p>Called from {@code ActivityThread.handleBindApplication} once the app context exists and
     * before the application class is instantiated, so the overridden fields are already in place
     * the first time app code reads {@link Build}.
     *
     * <p>Never throws: a broken config must not stop an app from starting.
     */
    public static void setProps(Context context, String packageName, String processName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        try {
            if (PACKAGE_PHOTOS.equals(packageName)) {
                if (isPhotosSpoofEnabled(context)) {
                    applyAll(PropImitationTargets.resolveAll(PHOTOS_PROPS), "photos");
                }
                return;
            }
            // microG's GmsCore takes the com.google.android.gms package name but does not use
            // Google's ":unstable" process split, and it runs DroidGuard from its own package. So
            // match the whole GMS package rather than one process, plus microG's DroidGuard helper.
            final boolean isGms = PACKAGE_GMS.equals(packageName)
                    || PACKAGE_MICROG_DROIDGUARD.equals(packageName);
            final String testPackage = SystemProperties.get(PROP_TEST_PACKAGE, "");
            final boolean isTestTarget =
                    !TextUtils.isEmpty(testPackage) && testPackage.equals(packageName);
            if (!isGms && !PACKAGE_VENDING.equals(packageName) && !isTestTarget) {
                return;
            }
            final Map<String, String> config = withoutRuntimeSdk(readConfig(context));
            if (config.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "no pif config stored; leaving " + processName + " untouched");
                }
                return;
            }
            applyAll(PropImitationTargets.resolveAll(config), processName);
            // Also install system property overrides so that SystemProperties.get()
            // returns spoofed values for DroidGuard's native property reads.
            installPropertyOverrides(config);
        } catch (Throwable t) {
            // Deliberately broad: this runs on every GMS start and must never break app launch.
            Log.e(TAG, "failed to apply the Play Integrity spoof for " + packageName, t);
        }
    }

    /**
     * Drops the running SDK level (SDK_INT / PREVIEW_SDK_INT and their aliases) from a config.
     * Community and fetched pif.json files often carry {@code SDK_INT=32}, but microG's GmsCore
     * runs entirely under the com.google.android.gms package we spoof, so applying it would make
     * location, push and accounts take pre-Android 13 code paths. The verdict only cares about
     * DEVICE_INITIAL_SDK_INT, which is kept.
     */
    private static Map<String, String> withoutRuntimeSdk(Map<String, String> config) {
        final Map<String, String> out = new HashMap<>(config);
        for (Iterator<Map.Entry<String, String>> it = out.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, String> e = it.next();
            final PropImitationTargets.Assignment a =
                    PropImitationTargets.resolve(e.getKey(), e.getValue());
            if (a != null && ("SDK_INT".equals(a.field) || "PREVIEW_SDK_INT".equals(a.field))) {
                it.remove();
            }
        }
        return out;
    }

    private static boolean isPhotosSpoofEnabled(Context context) {
        final ContentResolver cr = context.getContentResolver();
        return Settings.Secure.getIntForUser(cr, KEY_SPOOF_PHOTOS, 0, UserHandle.myUserId()) != 0;
    }

    /** Reads the stored pif.json into a flat map. Returns empty for missing or malformed config. */
    private static Map<String, String> readConfig(Context context) {
        final Map<String, String> out = new HashMap<>();
        final String raw = Settings.Secure.getStringForUser(
                context.getContentResolver(), KEY_PIF_CONFIG, UserHandle.myUserId());
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        final String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                final JSONObject json = new JSONObject(trimmed);
                for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                    final String key = it.next();
                    final String value = json.optString(key, "");
                    if (!TextUtils.isEmpty(value)) {
                        out.put(key, value);
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "stored pif config is not valid JSON", e);
            }
            return out;
        }
        // The Settings screen normalises to JSON, but tolerate the prop format it also accepts in
        // case an older value is still stored.
        for (String line : trimmed.split("\n")) {
            final String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) {
                continue;
            }
            final int eq = l.indexOf('=');
            if (eq > 0) {
                out.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static void applyAll(List<PropImitationTargets.Assignment> assignments, String who) {
        for (PropImitationTargets.Assignment a : assignments) {
            apply(a);
        }
        if (DEBUG) {
            Log.d(TAG, "applied " + assignments.size() + " overrides to " + who);
        }
    }

    private static void apply(PropImitationTargets.Assignment assignment) {
        final Class<?> clazz = assignment.target == PropImitationTargets.Target.VERSION
                ? Build.VERSION.class : Build.class;
        try {
            final Field field = clazz.getDeclaredField(assignment.field);
            field.setAccessible(true);
            if (assignment.isInt) {
                field.set(null, Integer.valueOf(assignment.value));
            } else {
                field.set(null, assignment.value);
            }
            if (DEBUG) {
                Log.d(TAG, "set " + assignment);
            }
        } catch (NoSuchFieldException e) {
            // The field list is compiled in; a miss means this platform dropped the field.
            if (DEBUG) {
                Log.d(TAG, "no such field: " + assignment.field);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            Log.e(TAG, "could not set " + assignment.field, e);
        }
    }
}
