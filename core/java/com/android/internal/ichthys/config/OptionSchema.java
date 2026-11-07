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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Every option ichthys.nix understands, keyed by dotted path (like NixOS' {@code options}).
 *
 * <p>Evaluating a config = parse ({@link NixParser}) → {@link #evaluate} (flatten nested sets to
 * option paths, reject unknown options, type-check every value) → a flat map the service applies.
 *
 * <p>Besides options the schema knows:
 * <ul>
 *   <li><b>aliases</b> ({@link #alias}): a former path of an option. A config may use either
 *       path; everything the schema returns uses the current one.</li>
 *   <li><b>removed options</b> ({@link #removed}): paths that were options once. A config that
 *       still sets one is not an error: the line is ignored and reported as a note, so configs
 *       and generations written by an older build keep applying.</li>
 *   <li><b>preset families</b> ({@link #presetFamily}): an option that names a preset file whose
 *       values fill in a namespace wherever the config sets none (theme.preset fills theme.*,
 *       privacy.level fills privacy.*).</li>
 * </ul>
 */
public final class OptionSchema {

    /** An option whose value names a file of defaults for a namespace. */
    public static final class PresetFamily {
        /** The option that picks the preset, e.g. "theme.preset". */
        public final String selector;
        /** The paths the preset may fill, e.g. "theme." (the selector itself excepted). */
        public final String prefix;
        /** Directory of the preset files under /product/etc/ichthys, e.g. "presets". */
        public final String directory;
        /** The selector value that means "no preset", or null if there is none. */
        public final String none;

        PresetFamily(String selector, String prefix, String directory, String none) {
            this.selector = selector;
            this.prefix = prefix;
            this.directory = directory;
            this.none = none;
        }
    }

    private final TreeMap<String, Option> mOptions = new TreeMap<>();
    /** Former path → current path. */
    private final TreeMap<String, String> mAliases = new TreeMap<>();
    /** Former path → why it is gone. */
    private final TreeMap<String, String> mRemoved = new TreeMap<>();
    /** Where a value lives → the one option bound to it. */
    private final HashMap<Binding, String> mBound = new HashMap<>();
    private final List<PresetFamily> mFamilies = new ArrayList<>();

    /** The full IchthysOS schema: IchthysOS options plus every live crDroid setting. */
    public static OptionSchema create() {
        final OptionSchema s = new OptionSchema();
        IchthysOptions.register(s);
        CrDroidOptions.register(s);
        return s;
    }

    /**
     * @throws IllegalStateException for a path that exists already, a path that is also a
     *         namespace, or a second option bound to the same value. Two options on one key
     *         can't both be satisfied: a declarative apply resets the one the file omits and so
     *         undoes the one it sets, differently on every other run.
     */
    public void add(Option o) {
        checkFree(o.path);
        final String other = mBound.get(o.binding);
        if (other != null) {
            throw new IllegalStateException("options " + other + " and " + o.path
                    + " are both bound to " + o.binding);
        }
        mOptions.put(o.path, o);
        mBound.put(o.binding, o.path);
    }

    /** Keeps {@code old} working as another name of the option {@code path}. */
    public void alias(String old, String path) {
        if (!mOptions.containsKey(path)) {
            throw new IllegalStateException("alias " + old + " of unknown option " + path);
        }
        checkFree(old);
        mAliases.put(old, path);
    }

    /**
     * Records that {@code path} was an option once. Configs may still set it; the value is
     * ignored and reported as a note ({@code why}, e.g. "runtime state, not configuration").
     */
    public void removed(String path, String why) {
        checkFree(path);
        mRemoved.put(path, why);
    }

    /** Registers a preset family; see {@link PresetFamily}. */
    public void presetFamily(String selector, String prefix, String directory, String none) {
        if (!mOptions.containsKey(selector)) {
            throw new IllegalStateException("preset selector " + selector + " is not an option");
        }
        mFamilies.add(new PresetFamily(selector, prefix, directory, none));
    }

    public List<PresetFamily> presetFamilies() {
        return mFamilies;
    }

    /** A path can be one thing only, and never both a leaf and a namespace of other paths. */
    private void checkFree(String path) {
        if (mOptions.containsKey(path) || mAliases.containsKey(path)
                || mRemoved.containsKey(path)) {
            throw new IllegalStateException("duplicate option " + path);
        }
        for (TreeMap<String, ?> known : known()) {
            for (String p : known.keySet()) {
                if (p.startsWith(path + ".") || path.startsWith(p + ".")) {
                    throw new IllegalStateException("option " + path + " clashes with " + p);
                }
            }
        }
    }

    private List<TreeMap<String, ?>> known() {
        final List<TreeMap<String, ?>> all = new ArrayList<>();
        all.add(mOptions);
        all.add(mAliases);
        all.add(mRemoved);
        return all;
    }

    /** The option at {@code path}, which may be an alias. */
    public Option get(String path) {
        final String target = mAliases.get(path);
        return mOptions.get(target != null ? target : path);
    }

    /** The former paths of an option, for docs. */
    public List<String> aliasesOf(String path) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : mAliases.entrySet()) {
            if (e.getValue().equals(path)) {
                out.add(e.getKey());
            }
        }
        return out;
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
        return evaluate(parsed, false, null);
    }

    /**
     * Like {@link #evaluate(NixParser.Result)}, and says what it skipped.
     *
     * @param lenient an unknown option or a value that doesn't fit is skipped and noted instead
     *                of thrown. For generation snapshots only: they were valid when written, and
     *                a schema that moved on since must not make a rollback impossible.
     * @param notes   receives one line per skipped setting (a removed option, or anything
     *                {@code lenient} let through); may be null
     */
    public LinkedHashMap<String, Object> evaluate(NixParser.Result parsed, boolean lenient,
            List<String> notes) throws NixParseException {
        final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        flatten(parsed.root, "", parsed, out, lenient, notes != null ? notes : new ArrayList<>());
        return out;
    }

    @SuppressWarnings("unchecked")
    private void flatten(Map<String, Object> set, String prefix, NixParser.Result parsed,
            Map<String, Object> out, boolean lenient, List<String> notes)
            throws NixParseException {
        for (Map.Entry<String, Object> e : set.entrySet()) {
            final String written = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            final Object v = e.getValue();
            final Option o = get(written);
            if (o == null) {
                if (mRemoved.containsKey(written)) {
                    notes.add(written + " is no longer an option (" + mRemoved.get(written)
                            + "); ignored");
                    continue;
                }
                if (v instanceof Map && hasPathsUnder(written)) {
                    flatten((Map<String, Object>) v, written, parsed, out, lenient, notes);
                    continue;
                }
                final String msg = "the option '" + written + "' does not exist"
                        + suggestion(written);
                if (!lenient) {
                    throw error(parsed, written, msg);
                }
                notes.add(msg + "; ignored");
                continue;
            }
            final Object value;
            try {
                value = o.normalize(v);
            } catch (IllegalArgumentException ex) {
                // Name the path as the file wrote it, so the message matches the line.
                final String msg = ex.getMessage().replace(o.path, written);
                if (!lenient) {
                    throw error(parsed, written, msg);
                }
                notes.add(msg + "; ignored");
                continue;
            }
            if (out.containsKey(o.path) && !Objects.equals(out.get(o.path), value)) {
                // The option under its current and a former name, with two values.
                throw error(parsed, written, "'" + written + "' is another name of '" + o.path
                        + "', which this config also sets, to a different value");
            }
            out.put(o.path, value);
        }
    }

    private boolean hasPathsUnder(String prefix) {
        for (TreeMap<String, ?> known : known()) {
            final String k = known.ceilingKey(prefix + ".");
            if (k != null && k.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private String suggestion(String path) {
        // Suggest the closest existing option in the same namespace (case-insensitive leaf match
        // first, then shared prefix), like Nix's "did you mean". Former names count as well, and
        // lead to the current one.
        final int dot = path.lastIndexOf('.');
        final String ns = dot < 0 ? "" : path.substring(0, dot);
        final String leaf = dot < 0 ? path : path.substring(dot + 1);
        final TreeMap<String, String> candidates = new TreeMap<>();
        for (Option o : under(ns)) {
            candidates.put(o.path, o.path);
        }
        for (Map.Entry<String, String> e : mAliases.entrySet()) {
            if (ns.isEmpty() || e.getKey().startsWith(ns + ".")) {
                candidates.put(e.getKey(), e.getValue());
            }
        }
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            final String l = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
            final int d = distance(leaf.toLowerCase(Locale.ROOT), l.toLowerCase(Locale.ROOT));
            if (d < bestScore) {
                bestScore = d;
                best = e.getValue();
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
            if (o.isPreserved()) {
                sb.append("    kept: a config that doesn't mention it leaves it as it is\n");
            }
            final List<String> aliases = aliasesOf(o.path);
            if (!aliases.isEmpty()) {
                sb.append("    formerly: ").append(String.join(", ", aliases)).append('\n');
            }
            sb.append("    backend: ").append(o.binding).append("\n\n");
        }
        return sb.toString();
    }
}
