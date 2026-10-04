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

package com.android.server.sensors;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import libcore.util.HexEncoding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * IchthysOS: the free-software apps the ROM itself brings get exact sensors by default.
 *
 * <p>The sensor shield (native SensorService: generic sensor list, capped rates, rounded values)
 * is aimed at apps of unknown origin. The apps IchthysOS ships are open source and chosen by the
 * ROM, and some of them need the real thing (the maps app's compass), so each of them is put on
 * the per-user exemption list {@code ichthys_sensor_exempt_pkgs} once, when it is first seen
 * installed for that user. "Once" is remembered per package in {@link #SEEDED_SETTING}: an app
 * the user later takes off the list in Ichthys Guard, or that a config leaves out of
 * {@code privacy.shields.sensors.allow}, stays off. The exemption list remains the one thing the
 * shield reads.
 *
 * <p>Which apps:
 * <ul>
 *   <li>every app with a payload in {@code /product/etc/ichthys-preinstall/<package>/}: these
 *       are the FOSS apps installed from the ROM image at first boot;</li>
 *   <li>an app of {@code /product/etc/ichthys/apps-catalog.json} whose entry says
 *       {@code "foss": true}, and only when the installed package is signed with one of the
 *       entry's certificate pins. Catalog entries without the mark are not exempt: the catalog
 *       is where the proprietary recommendations live (a browser, a payments app), exactly the
 *       apps the shield is for.</li>
 * </ul>
 */
final class IchthysSensorExemptions {
    private static final String TAG = "IchthysSensorShield";

    private static final File PAYLOAD_DIR = new File("/product/etc/ichthys-preinstall");
    private static final File APPS_CATALOG = new File("/product/etc/ichthys/apps-catalog.json");

    /**
     * Per-user Secure key: the bundled packages already put on the exemption list once
     * (comma-separated). Bookkeeping only; nothing but this class reads it.
     */
    static final String SEEDED_SETTING = "ichthys_sensor_exempt_seeded";

    /** package → certificate pins (SHA-256, hex); an empty list means "no pin to check". */
    private Map<String, List<String>> mBundled;

    /**
     * Adds the bundled apps that are installed for {@code userId} and were never added before to
     * {@code exemptSetting}. Returns whether the list changed.
     */
    boolean seed(Context context, String exemptSetting, int userId) {
        final Map<String, List<String>> bundled = bundled();
        if (bundled.isEmpty()) {
            return false;
        }
        final ContentResolver resolver = context.getContentResolver();
        final Set<String> seeded = split(
                Settings.Secure.getStringForUser(resolver, SEEDED_SETTING, userId));
        final PackageManager pm = context.getPackageManager();
        final List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, List<String>> app : bundled.entrySet()) {
            if (!seeded.contains(app.getKey())
                    && isInstalled(pm, app.getKey(), userId)
                    && isSignedByPin(pm, app.getKey(), app.getValue())) {
                fresh.add(app.getKey());
            }
        }
        if (fresh.isEmpty()) {
            return false;
        }
        final Set<String> exempt = split(
                Settings.Secure.getStringForUser(resolver, exemptSetting, userId));
        final boolean changed = exempt.addAll(fresh);
        seeded.addAll(fresh);
        // The marker first: if the second write fails, the app is merely not exempt, where the
        // other order could re-add an app the user removed.
        Settings.Secure.putStringForUser(resolver, SEEDED_SETTING, String.join(",", seeded),
                userId);
        if (changed) {
            Settings.Secure.putStringForUser(resolver, exemptSetting, String.join(",", exempt),
                    userId);
            Slog.i(TAG, "user " + userId + ": exact sensors by default for " + fresh);
        }
        return changed;
    }

    private Map<String, List<String>> bundled() {
        if (mBundled == null) {
            mBundled = load();
        }
        return mBundled;
    }

    private static Map<String, List<String>> load() {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        final File[] payloads = PAYLOAD_DIR.listFiles(File::isDirectory);
        if (payloads != null) {
            for (File dir : payloads) {
                out.put(dir.getName(), new ArrayList<>());
            }
        }
        if (APPS_CATALOG.isFile()) {
            try {
                final JSONArray apps = new JSONObject(new String(
                        Files.readAllBytes(APPS_CATALOG.toPath()), StandardCharsets.UTF_8))
                        .getJSONArray("apps");
                for (int i = 0; i < apps.length(); i++) {
                    final JSONObject entry = apps.optJSONObject(i);
                    if (entry == null || !entry.optBoolean("foss", false)) {
                        continue;
                    }
                    final String pkg = entry.optString("package", "");
                    final List<String> pins = new ArrayList<>();
                    final JSONArray certs = entry.optJSONArray("certs");
                    for (int j = 0; certs != null && j < certs.length(); j++) {
                        final String pin = certs.optString(j, "").replace(":", "")
                                .toLowerCase(Locale.ROOT);
                        if (pin.length() == 64) {
                            pins.add(pin);
                        }
                    }
                    // Without a pin there is no telling the real app from another one.
                    if (!pkg.isEmpty() && !pins.isEmpty() && !out.containsKey(pkg)) {
                        out.put(pkg, pins);
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "could not read " + APPS_CATALOG, e);
            }
        }
        return out;
    }

    private static boolean isInstalled(PackageManager pm, String pkg, int userId) {
        try {
            final ApplicationInfo info = pm.getApplicationInfoAsUser(pkg, 0, userId);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean isSignedByPin(PackageManager pm, String pkg, List<String> pins) {
        if (pins.isEmpty()) {
            return true;
        }
        for (String pin : pins) {
            try {
                if (pm.hasSigningCertificate(pkg, HexEncoding.decode(pin),
                        PackageManager.CERT_INPUT_SHA256)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Not hex after all: not a pin.
            }
        }
        return false;
    }

    private static Set<String> split(String value) {
        final Set<String> out = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(value)) {
            for (String part : value.split(",")) {
                final String pkg = part.trim();
                if (!pkg.isEmpty()) {
                    out.add(pkg);
                }
            }
        }
        return out;
    }
}
