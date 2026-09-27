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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the keys of a Play Integrity ("pif.json") config onto the {@link android.os.Build}
 * fields they are meant to override.
 *
 * <p>Community pif.json files are not written to one schema. The same value shows up as
 * {@code MODEL}, {@code ro.product.model} and {@code *.build.model} depending on which tool
 * produced the file, and integer fields such as the initial SDK level appear as both
 * {@code DEVICE_INITIAL_SDK_INT} and {@code FIRST_API_LEVEL}. This class does the normalising and
 * classifying so the reflection layer stays trivial.
 *
 * <p>Deliberately free of {@code android.*} imports so the mapping can be unit-tested on the host.
 */
public final class PropImitationTargets {

    /** Which class holds the field being overridden. */
    public enum Target {
        /** A field on {@code android.os.Build}. */
        BUILD,
        /** A field on {@code android.os.Build.VERSION}. */
        VERSION,
    }

    /** One resolved override: which field to set, and to what. */
    public static final class Assignment {
        public final Target target;
        public final String field;
        public final String value;
        /** True when the field is an {@code int} and {@link #value} must be parsed. */
        public final boolean isInt;

        Assignment(Target target, String field, String value, boolean isInt) {
            this.target = target;
            this.field = field;
            this.value = value;
            this.isInt = isInt;
        }

        @Override
        public String toString() {
            return (target == Target.VERSION ? "Build.VERSION." : "Build.") + field + "=" + value;
        }
    }

    private static final class FieldSpec {
        final Target target;
        final boolean isInt;

        FieldSpec(Target target, boolean isInt) {
            this.target = target;
            this.isInt = isInt;
        }
    }

    /** Field name (already normalised) to where it lives. */
    private static final Map<String, FieldSpec> FIELDS = new HashMap<>();

    /** Spellings seen in the wild that mean one of the {@link #FIELDS} entries. */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // android.os.Build -- strings.
        for (String name : new String[] {
                "BOARD", "BOOTLOADER", "BRAND", "DEVICE", "DISPLAY", "FINGERPRINT", "HARDWARE",
                "HOST", "ID", "MANUFACTURER", "MODEL", "ODM_SKU", "PRODUCT", "SKU",
                "SOC_MANUFACTURER", "SOC_MODEL", "TAGS", "TYPE", "USER"}) {
            FIELDS.put(name, new FieldSpec(Target.BUILD, false));
        }
        // android.os.Build.VERSION -- strings.
        for (String name : new String[] {
                "BASE_OS", "CODENAME", "INCREMENTAL", "RELEASE", "SECURITY_PATCH"}) {
            FIELDS.put(name, new FieldSpec(Target.VERSION, false));
        }
        // android.os.Build.VERSION -- ints.
        for (String name : new String[] {
                "SDK_INT", "PREVIEW_SDK_INT", "DEVICE_INITIAL_SDK_INT", "MEDIA_PERFORMANCE_CLASS"}) {
            FIELDS.put(name, new FieldSpec(Target.VERSION, true));
        }

        ALIASES.put("FIRST_API_LEVEL", "DEVICE_INITIAL_SDK_INT");
        ALIASES.put("API_LEVEL", "DEVICE_INITIAL_SDK_INT");
        ALIASES.put("PRODUCT_FIRST_API_LEVEL", "DEVICE_INITIAL_SDK_INT");
        ALIASES.put("BUILD_ID", "ID");
        ALIASES.put("VERSION_RELEASE", "RELEASE");
        ALIASES.put("VERSION_INCREMENTAL", "INCREMENTAL");
        ALIASES.put("VERSION_SECURITY_PATCH", "SECURITY_PATCH");
        ALIASES.put("VERSION_SDK_INT", "SDK_INT");
        ALIASES.put("BUILD_VERSION_RELEASE", "RELEASE");
        ALIASES.put("SECURITY_PATCH_LEVEL", "SECURITY_PATCH");
    }

    private PropImitationTargets() {}

    /**
     * Reduces a raw config key to a bare field name.
     *
     * <p>Strips the {@code *.} wildcard prefix some generators emit, drops a leading {@code ro.},
     * drops {@code product.}/{@code build.}/{@code version.}/{@code system.}/{@code vendor.}
     * namespace segments, and upper-cases the rest with non-alphanumerics folded to {@code _}.
     */
    public static String normalizeKey(String rawKey) {
        if (rawKey == null) {
            return "";
        }
        String key = rawKey.trim();
        while (key.startsWith("*")) {
            key = key.substring(1);
        }
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        // Fold separators first so "ro.product.model" and "ro_product_model" behave the same.
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append('_');
            }
        }
        String normalized = sb.toString();
        // Drop leading namespace segments. Done as a loop because keys stack them, e.g.
        // "RO_BUILD_VERSION_RELEASE" -> "RELEASE".
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : new String[] {
                    "RO_", "PERSIST_", "SYSTEM_", "VENDOR_", "ODM_PRODUCT_", "BOOT_"}) {
                if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                    normalized = normalized.substring(prefix.length());
                    stripped = true;
                }
            }
        }
        // Collapse repeated underscores left behind by "*.".
        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Resolves one config entry.
     *
     * @return the assignment to make, or {@code null} when the key names no field we can override
     */
    public static Assignment resolve(String rawKey, String value) {
        if (value == null) {
            return null;
        }
        String name = normalizeKey(rawKey);
        if (name.isEmpty()) {
            return null;
        }
        FieldSpec spec = FIELDS.get(name);
        if (spec == null) {
            String alias = ALIASES.get(name);
            if (alias != null) {
                name = alias;
                spec = FIELDS.get(name);
            }
        }
        // Try progressively shorter tails: "PRODUCT_MODEL" -> "MODEL".
        if (spec == null) {
            int cut = name.indexOf('_');
            while (spec == null && cut >= 0 && cut + 1 < name.length()) {
                String tail = name.substring(cut + 1);
                String aliased = ALIASES.containsKey(tail) ? ALIASES.get(tail) : tail;
                spec = FIELDS.get(aliased);
                if (spec != null) {
                    name = aliased;
                    break;
                }
                cut = name.indexOf('_', cut + 1);
            }
        }
        if (spec == null) {
            return null;
        }
        if (spec.isInt) {
            try {
                Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                // An unparseable integer would blow up the reflection layer; drop it instead.
                return null;
            }
        }
        return new Assignment(spec.target, name, spec.isInt ? value.trim() : value, spec.isInt);
    }

    /**
     * Resolves a whole config, skipping entries that name nothing overridable.
     *
     * <p>Order is not significant: every assignment targets a distinct field, and later duplicates
     * of the same field overwrite earlier ones the same way the config would.
     */
    public static List<Assignment> resolveAll(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return Collections.emptyList();
        }
        final Map<String, Assignment> byField = new HashMap<>();
        final List<String> order = new ArrayList<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            Assignment assignment = resolve(entry.getKey(), entry.getValue());
            if (assignment == null) {
                continue;
            }
            String id = assignment.target + "." + assignment.field;
            if (!byField.containsKey(id)) {
                order.add(id);
            }
            byField.put(id, assignment);
        }
        final List<Assignment> out = new ArrayList<>(order.size());
        for (String id : order) {
            out.add(byField.get(id));
        }
        return out;
    }
}
