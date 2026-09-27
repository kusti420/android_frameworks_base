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

package com.android.internal.ichthys.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every option ichthys.nix understands, keyed by dotted path (like NixOS' {@code options}).
 *
 * <p>Evaluating a config = parse ({@link NixParser}) → {@link #evaluate} (flatten nested sets to
 * option paths, reject unknown options, type-check every value) → a flat map the service applies.
 */
public final class OptionSchema {

    private final TreeMap<String, Option> mOptions = new TreeMap<>();

    /** The full IchthysOS schema: IchthysOS options plus every live crDroid setting. */
    public static OptionSchema create() {
        final OptionSchema s = new OptionSchema();
        IchthysOptions.register(s);
        CrDroidOptions.register(s);
        return s;
    }

    public void add(Option o) {
        if (mOptions.containsKey(o.path)) {
            throw new IllegalStateException("duplicate option " + o.path);
        }
        // A path can't be both an option and a namespace of other options.
        for (String p : mOptions.keySet()) {
            if (p.startsWith(o.path + ".") || o.path.startsWith(p + ".")) {
                throw new IllegalStateException("option " + o.path + " clashes with " + p);
            }
        }
        mOptions.put(o.path, o);
    }

    public Option get(String path) {
        return mOptions.get(path);
    }

    public Collection<Option> all() {
        return mOptions.values();
    }

    /** Options under a prefix ("" for all), in path order. */
    public List<Option> under(String prefix) {
        final List<Option> out = new ArrayList<>();
        for (Option o : mOptions.values()) {
            if (prefix == null || prefix.isEmpty() || o.path.equals(prefix)
                    || o.path.startsWith(prefix + ".")) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * Flattens a parsed config to {option path → normalized value} (value null = "reset to the
     * default"), checking every path and value.
     *
     * @throws NixParseException for an unknown option or a value of the wrong type, pointing at
     *         the line that set it
     */
    public LinkedHashMap<String, Object> evaluate(NixParser.Result parsed)
            throws NixParseException {
        final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        flatten(parsed.root, "", parsed, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void flatten(Map<String, Object> set, String prefix, NixParser.Result parsed,
            Map<String, Object> out) throws NixParseException {
        for (Map.Entry<String, Object> e : set.entrySet()) {
            final String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            final Object v = e.getValue();
            final Option o = mOptions.get(path);
            if (o == null) {
                if (v instanceof Map && hasOptionsUnder(path)) {
                    flatten((Map<String, Object>) v, path, parsed, out);
                    continue;
                }
                throw error(parsed, path, "the option '" + path + "' does not exist"
                        + suggestion(path));
            }
            try {
                out.put(path, o.normalize(v));
            } catch (IllegalArgumentException ex) {
                throw error(parsed, path, ex.getMessage());
            }
        }
    }

    private boolean hasOptionsUnder(String prefix) {
        final String k = mOptions.ceilingKey(prefix + ".");
        return k != null && k.startsWith(prefix + ".");
    }

    private String suggestion(String path) {
        // Suggest the closest existing option in the same namespace (case-insensitive leaf match
        // first, then shared prefix), like Nix's "did you mean".
        final int dot = path.lastIndexOf('.');
        final String ns = dot < 0 ? "" : path.substring(0, dot);
        final String leaf = dot < 0 ? path : path.substring(dot + 1);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Option o : under(ns)) {
            final String l = o.path.substring(o.path.lastIndexOf('.') + 1);
            final int d = distance(leaf.toLowerCase(), l.toLowerCase());
            if (d < bestScore) {
                bestScore = d;
                best = o.path;
            }
        }
        return best != null && bestScore <= Math.max(2, leaf.length() / 3)
                ? "; did you mean '" + best + "'?" : "";
    }

    private static int distance(String a, String b) {
        final int[] prev = new int[b.length() + 1];
        final int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }

    private static NixParseException error(NixParser.Result parsed, String path, String msg) {
        final int[] p = parsed.positions.get(path);
        return p == null ? new NixParseException(msg) : new NixParseException(msg, p[0], p[1]);
    }

    /** Rebuilds nested attribute sets from {path → value} for printing. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unflatten(Map<String, Object> flat) {
        final Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            final String[] parts = e.getKey().split("\\.");
            Map<String, Object> cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cur.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    cur.put(parts[i], next);
                }
                cur = (Map<String, Object>) next;
            }
            cur.put(parts[parts.length - 1], e.getValue() == null ? NixParser.NULL : e.getValue());
        }
        return root;
    }

    /** Prints {path → value} as a canonical, commented ichthys.nix. */
    public String print(Map<String, Object> flat, String header) {
        return NixPrinter.print(unflatten(flat), path -> {
            final Option o = mOptions.get(path);
            return o == null ? null : o.description;
        }, header);
    }

    /** Human-readable option reference, like `man configuration.nix`. */
    public String docs(String prefix) {
        final StringBuilder sb = new StringBuilder();
        for (Option o : under(prefix)) {
            sb.append(o.path).append('\n');
            sb.append("    ").append(o.description).append('\n');
            sb.append("    type: ").append(o.typeName()).append('\n');
            sb.append("    default: ")
                    .append(o.defaultValue == null ? "(system default)"
                            : NixPrinter.value(o.defaultValue)).append('\n');
            sb.append("    backend: ").append(o.binding).append("\n\n");
        }
        return sb.toString();
    }
}
