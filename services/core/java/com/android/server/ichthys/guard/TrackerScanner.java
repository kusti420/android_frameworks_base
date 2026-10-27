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

package com.android.server.ichthys.guard;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Install-time tracker code scan: reads the class names defined in an APK's classes*.dex
 * (header + class_defs + type_ids + string_ids only, no class loading, no code execution) and
 * matches their packages against tracker code-signature prefixes (Exodus style).
 */
final class TrackerScanner {
    private static final String TAG = "IchthysGuard";

    static final String TRACKERS_FILE = "/product/etc/ichthys-guard/trackers.json";
    /** Dex files larger than this are skipped (keeps system_server's heap sane). */
    // IchthysOS: real classes*.dex stay well below this (64K-method limit); this runs inside
    // system_server, where the old 96 MB (buffered twice) let a crafted APK exhaust the heap.
    private static final long MAX_DEX_BYTES = 32L * 1024 * 1024;

    /** Small built-in fallback DB, used when {@link #TRACKERS_FILE} is missing or broken. */
    private static final String[][] DEFAULT_SIGNATURES = {
        {"Google AdMob", "com.google.android.gms.ads.", "com.google.ads."},
        {"Google Firebase Analytics", "com.google.firebase.analytics.",
                "com.google.android.gms.measurement."},
        {"Google CrashLytics", "com.crashlytics.", "com.google.firebase.crashlytics."},
        {"Google Analytics", "com.google.android.gms.analytics.",
                "com.google.analytics."},
        {"Facebook Analytics", "com.facebook.appevents."},
        {"Facebook Ads", "com.facebook.ads."},
        {"Facebook Login", "com.facebook.login."},
        {"AppsFlyer", "com.appsflyer."},
        {"Adjust", "com.adjust.sdk."},
        {"Branch", "io.branch."},
        {"Unity Ads", "com.unity3d.ads.", "com.unity3d.services."},
        {"AppLovin (MAX and SparkLabs)", "com.applovin."},
        {"IronSource", "com.ironsource."},
        {"Vungle", "com.vungle."},
        {"Chartboost", "com.chartboost."},
        {"InMobi", "com.inmobi."},
        {"MoPub", "com.mopub."},
        {"Amplitude", "com.amplitude."},
        {"Mixpanel", "com.mixpanel."},
        {"Segment", "com.segment.analytics."},
        {"OneSignal", "com.onesignal."},
        {"Braze (formerly Appboy)", "com.appboy.", "com.braze."},
        {"Sentry", "io.sentry."},
        {"Bugsnag", "com.bugsnag."},
        {"New Relic", "com.newrelic."},
        {"Yandex Metrica", "com.yandex.metrica."},
        {"Huawei Mobile Services (HMS) Core", "com.huawei.hms.analytics."},
        {"Microsoft Visual Studio App Center Analytics",
                "com.microsoft.appcenter.analytics."},
        {"Microsoft Visual Studio App Center Crashes", "com.microsoft.appcenter.crashes."},
        {"Tencent Bugly", "com.tencent.bugly."},
        {"Pangle", "com.bytedance.sdk.openadsdk."},
        {"Startapp", "com.startapp."},
        {"Criteo", "com.criteo."},
        {"Smaato", "com.smaato."},
    };

    static final class Signature {
        final String name;
        final String[] prefixes;

        Signature(String name, String[] prefixes) {
            this.name = name;
            this.prefixes = prefixes;
        }
    }

    private final List<Signature> mSignatures;
    /** Where the DB came from, for dumpsys. */
    final String source;

    private TrackerScanner(List<Signature> signatures, String source) {
        mSignatures = signatures;
        this.source = source;
    }

    int size() {
        return mSignatures.size();
    }

    /** Loads {@link #TRACKERS_FILE}, else the built-in list. Never throws. */
    static TrackerScanner load() {
        final File f = new File(TRACKERS_FILE);
        if (f.isFile()) {
            try {
                final List<Signature> sigs = parseJson(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (!sigs.isEmpty()) {
                    return new TrackerScanner(sigs, TRACKERS_FILE);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Bad tracker DB " + TRACKERS_FILE + ", using built-in list", e);
            }
        }
        final List<Signature> sigs = new ArrayList<>();
        for (String[] row : DEFAULT_SIGNATURES) {
            final String[] prefixes = new String[row.length - 1];
            System.arraycopy(row, 1, prefixes, 0, prefixes.length);
            sigs.add(new Signature(row[0], prefixes));
        }
        return new TrackerScanner(sigs, "built-in");
    }

    /**
     * Accepts the Guard app's format (a JSON array of {name, codeSignatures: [prefix...]}) and
     * the Exodus API format ({"trackers": {id: {name, code_signature: "a.b.|c.d."}}}).
     */
    static List<Signature> parseJson(String json) throws Exception {
        final List<Signature> out = new ArrayList<>();
        final String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            final JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                addEntry(out, arr.optJSONObject(i));
            }
        } else {
            JSONObject root = new JSONObject(trimmed);
            if (root.has("trackers")) {
                final Object t = root.get("trackers");
                if (t instanceof JSONArray) {
                    final JSONArray arr = (JSONArray) t;
                    for (int i = 0; i < arr.length(); i++) {
                        addEntry(out, arr.optJSONObject(i));
                    }
                    return out;
                }
                root = (JSONObject) t;
            }
            final java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                addEntry(out, root.optJSONObject(keys.next()));
            }
        }
        return out;
    }

    private static void addEntry(List<Signature> out, @Nullable JSONObject o) {
        if (o == null) return;
        final String name = o.optString("name", null);
        if (name == null || name.isEmpty()) return;
        final Set<String> prefixes = new TreeSet<>();
        final JSONArray arr = o.optJSONArray("codeSignatures");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                addPrefix(prefixes, arr.optString(i, ""));
            }
        }
        final String exodus = o.optString("code_signature", "");
        for (String p : exodus.split("\\|")) {
            addPrefix(prefixes, p);
        }
        if (!prefixes.isEmpty()) {
            out.add(new Signature(name, prefixes.toArray(new String[0])));
        }
    }

    private static void addPrefix(Set<String> prefixes, String raw) {
        // Exodus signatures are regexes in practice made of literal package prefixes with
        // escaped dots; anything fancier is ignored rather than mis-matched.
        String p = raw.replace("\\.", ".").replace("/", ".").trim();
        if (p.startsWith("^")) p = p.substring(1);
        if (p.length() < 4 || !p.matches("[A-Za-z0-9_$.]+")) return;
        prefixes.add(p);
    }

    /**
     * Returns the names of the signatures matched by the classes defined in {@code apkPaths}
     * (sorted, no duplicates).
     */
    @NonNull
    String[] scan(@NonNull List<String> apkPaths) {
        final Set<String> packages = new HashSet<>();
        for (String path : apkPaths) {
            try (ZipFile zip = new ZipFile(path)) {
                final Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry e = entries.nextElement();
                    final String n = e.getName();
                    if (!n.startsWith("classes") || !n.endsWith(".dex") || n.indexOf('/') >= 0) {
                        continue;
                    }
                    if (e.getSize() > MAX_DEX_BYTES) {
                        Slog.w(TAG, "Skipping oversized " + n + " in " + path);
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(e)) {
                        collectPackages(readAll(in, e.getSize()), packages);
                    } catch (IOException | RuntimeException ex) {
                        Slog.w(TAG, "Bad dex " + n + " in " + path + ": " + ex);
                    } catch (OutOfMemoryError oom) {
                        // IchthysOS: one APK must not take system_server (and every boot's
                        // backfill) down with it.
                        Slog.w(TAG, "Out of memory scanning " + n + " in " + path);
                    }
                }
            } catch (IOException e) {
                Slog.w(TAG, "Cannot open " + path + ": " + e);
            }
        }
        final Set<String> matched = new TreeSet<>();
        for (String pkg : packages) {
            for (Signature sig : mSignatures) {
                if (matched.contains(sig.name)) continue;
                for (String prefix : sig.prefixes) {
                    if (pkg.startsWith(prefix)) {
                        matched.add(sig.name);
                        break;
                    }
                }
            }
        }
        return matched.toArray(new String[0]);
    }

    private static byte[] readAll(InputStream in, long size) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(
                size > 0 ? (int) size : 1 << 20);
        final byte[] buf = new byte[64 * 1024];
        int r;
        long total = 0;
        while ((r = in.read(buf)) > 0) {
            total += r;
            if (total > MAX_DEX_BYTES) throw new IOException("dex too large");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    /**
     * Adds the package of every class defined in {@code dex}, as "a.b.c." (trailing dot, so
     * that prefix "a.b." matches it and "a.bc." does not).
     */
    static void collectPackages(byte[] dex, Set<String> out) {
        if (dex.length < 0x70 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
            throw new IllegalArgumentException("not a dex file");
        }
        final int stringIdsSize = u32(dex, 0x38);
        final int stringIdsOff = u32(dex, 0x3C);
        final int typeIdsSize = u32(dex, 0x40);
        final int typeIdsOff = u32(dex, 0x44);
        final int classDefsSize = u32(dex, 0x60);
        final int classDefsOff = u32(dex, 0x64);
        check(dex, stringIdsOff, stringIdsSize, 4);
        check(dex, typeIdsOff, typeIdsSize, 4);
        check(dex, classDefsOff, classDefsSize, 32);
        for (int i = 0; i < classDefsSize; i++) {
            final int typeIdx = u32(dex, classDefsOff + i * 32);
            if (typeIdx < 0 || typeIdx >= typeIdsSize) continue;
            final int strIdx = u32(dex, typeIdsOff + typeIdx * 4);
            if (strIdx < 0 || strIdx >= stringIdsSize) continue;
            final String desc = readString(dex, u32(dex, stringIdsOff + strIdx * 4));
            // "Lcom/foo/Bar;" -> "com.foo."
            if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L') continue;
            final int slash = desc.lastIndexOf('/');
            if (slash <= 1) continue;
            final String pkg = desc.substring(1, slash + 1).replace('/', '.');
            out.add(pkg);
        }
    }

    private static void check(byte[] dex, int off, int count, int stride) {
        if (count < 0 || off < 0 || (long) off + (long) count * stride > dex.length) {
            throw new IllegalArgumentException("dex table out of bounds");
        }
    }

    private static int u32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) throw new IllegalArgumentException("oob");
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16
                | (b[off + 3] & 0xff) << 24;
    }

    /** string_data_item: uleb128 utf16_size, then MUTF-8 bytes up to a NUL. */
    @Nullable
    private static String readString(byte[] b, int off) {
        if (off < 0 || off >= b.length) return null;
        int p = off;
        // Skip the uleb128 length.
        for (int i = 0; i < 5 && p < b.length; i++) {
            if ((b[p++] & 0x80) == 0) break;
        }
        final int start = p;
        while (p < b.length && b[p] != 0) p++;
        // Class names are ASCII in practice; MUTF-8 oddities only affect exotic names.
        return new String(b, start, p - start, StandardCharsets.UTF_8);
    }
}
