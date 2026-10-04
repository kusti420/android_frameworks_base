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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What applying a config means: from an evaluated file to the values to write. Pure logic over
 * the {@link OptionSchema}; IchthysConfigService supplies the device ({@link Store}) and the
 * preset files ({@link Presets}), the host tests an in-memory map.
 *
 * <h3>The three ways to apply</h3>
 * <ul>
 *   <li>{@link Mode#DECLARATIVE}: the file is the whole configuration. Every option it sets gets
 *       that value; every option it omits goes back to its default. Two kinds of option are
 *       left as they are when omitted, because they are state the device built up rather than
 *       configuration: the <em>preserved</em> options ({@link Option#isPreserved()}: the per-app
 *       lists of the privacy shields, background.unrestrictedApps, apps.install, apps.adopted,
 *       apps.confirmed) and the options without a default to go back to (default apps, lock
 *       screen shortcuts). To clear one, the file says so: {@code = [ ]} or {@code = null}.</li>
 *   <li>{@link Mode#MERGE}: only the options the file sets change.</li>
 *   <li>{@link Mode#SNAPSHOT}: the file is a generation snapshot ({@link State#STORED}) and the
 *       device goes back to exactly that: options it lists are written, every other one is
 *       unset, preserved or not. No preset fills in. Used by rollback.</li>
 * </ul>
 *
 * <h3>Presets</h3>
 * In the first two modes a {@link OptionSchema.PresetFamily} fills in its namespace wherever the
 * file sets nothing: theme.preset fills theme.*, privacy.level fills privacy.*. Declaratively, a
 * file that doesn't name the preset gets the default one; merging, a preset only fills in when
 * the file names it. Preserved options are never filled in.
 *
 * <h3>What gets written</h3>
 * A value the config sets (itself or through a preset) is always stored, also when it equals the
 * option's default and nothing was stored before: the reader of a key is free to have its own
 * idea of "unset", and an option that could not be set to its own default would be the result. A
 * reset stores the default for the same reason, and deletes the key only when the option has no
 * default. A snapshot restore is the exception: it deletes, because "nothing stored" is exactly
 * what the snapshot recorded.
 */
public final class ConfigPlanner {

    public enum Mode { DECLARATIVE, MERGE, SNAPSHOT }

    /** Which options {@link #state} reports. */
    public enum State {
        /** Every option, with its default where nothing is stored (export --all). */
        ALL,
        /**
         * What a file must say to reproduce the device when applied declaratively: the stored
         * values that differ from what the defaults and the active presets would give (export).
         */
        CHANGED,
        /** Every option that has a stored value, as stored (a generation snapshot). */
        STORED,
    }

    /** The device: the stored value of an option, or null when nothing is stored. */
    public interface Store {
        Object read(Option o);
    }

    /** The preset files: the text of {@code <directory>/<name>.nix}. */
    public interface Presets {
        /** @throws IllegalArgumentException when there is no such preset */
        String text(String directory, String name);
    }

    private final OptionSchema mSchema;
    private final Presets mPresets;

    public ConfigPlanner(OptionSchema schema, Presets presets) {
        mSchema = schema;
        mPresets = presets;
    }

    /**
     * What the device should hold after applying {@code declared} (an evaluated file): path →
     * value, null for "reset". Options that are not in the result are left alone.
     *
     * @throws IllegalArgumentException when the file names a preset that doesn't exist
     */
    public LinkedHashMap<String, Object> target(Map<String, Object> declared, Mode mode) {
        final LinkedHashMap<String, Object> wanted = new LinkedHashMap<>(declared);
        if (mode != Mode.SNAPSHOT) {
            for (OptionSchema.PresetFamily family : mSchema.presetFamilies()) {
                final boolean named = declared.get(family.selector) != null;
                final Object preset = named ? declared.get(family.selector)
                        : mode == Mode.MERGE ? null : mSchema.get(family.selector).defaultValue;
                if (!(preset instanceof String) || preset.equals(family.none)) {
                    continue;
                }
                // The default preset equals the option defaults, so a device that lacks its file
                // loses nothing; a preset the file asked for by name must exist.
                final Map<String, Object> values = load(family, (String) preset, named);
                for (Map.Entry<String, Object> e : values.entrySet()) {
                    if (!wanted.containsKey(e.getKey())) {
                        wanted.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
        if (mode == Mode.MERGE) {
            return wanted;
        }
        final LinkedHashMap<String, Object> full = new LinkedHashMap<>();
        for (Option o : mSchema.all()) {
            if (wanted.containsKey(o.path)) {
                full.put(o.path, wanted.get(o.path));
            } else if (hasDefaultState(o) && (mode == Mode.SNAPSHOT || !o.isPreserved())) {
                full.put(o.path, null); // not in the file: back to the default
            }
        }
        return full;
    }

    /**
     * Whether "not set" is a state the option can go back to. A role or a lock screen slot has
     * none (resetting would leave the phone without a dialer, or record an explicit empty
     * shortcut), so an unmentioned one is always left as it is.
     */
    private static boolean hasDefaultState(Option o) {
        return o.binding.isSettingsTable() || o.binding.store == Binding.Store.THEME_JSON;
    }

    /** The values a preset contributes: its family's namespace, minus the preserved options. */
    private Map<String, Object> load(OptionSchema.PresetFamily family, String name,
            boolean required) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final Map<String, Object> all;
        try {
            all = mSchema.evaluate(NixParser.parse(mPresets.text(family.directory, name)));
        } catch (NixParseException e) {
            throw new IllegalArgumentException(family.directory + "/" + name + ".nix: "
                    + e.getMessage());
        } catch (IllegalArgumentException e) {
            if (required) {
                throw e;
            }
            return out;
        }
        for (Map.Entry<String, Object> e : all.entrySet()) {
            final String path = e.getKey();
            if (path.startsWith(family.prefix) && !path.equals(family.selector)
                    && e.getValue() != null && !mSchema.get(path).isPreserved()) {
                out.put(path, e.getValue());
            }
        }
        return out;
    }

    /**
     * The writes that bring {@code live} to {@code target}. In a change, {@code before} is the
     * stored value (null: nothing stored) and {@code after} the target value (null: reset); see
     * {@link #valueToStore} for what a reset writes.
     */
    public ConfigDiff changes(Map<String, Object> target, Mode mode, Store live) {
        final LinkedHashMap<String, Object> before = new LinkedHashMap<>();
        final LinkedHashMap<String, Object> after = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : target.entrySet()) {
            final Option o = mSchema.get(e.getKey());
            final Object stored = live.read(o);
            final Object want = e.getValue();
            final boolean change;
            if (want != null) {
                // Also when nothing is stored and the value is the default: see the class
                // comment, "What gets written".
                change = !Objects.equals(stored, want);
            } else if (stored == null) {
                change = false;
            } else {
                change = mode == Mode.SNAPSHOT || !Objects.equals(stored, o.defaultValue);
            }
            if (change) {
                before.put(o.path, stored);
                after.put(o.path, want);
            }
        }
        return ConfigDiff.between(before, after);
    }

    /**
     * The value to store for a change: its {@code after} value, or for a reset the option's
     * default. Null means "delete the stored value": a reset of an option without a default, and
     * every reset of a snapshot restore.
     */
    public Object valueToStore(ConfigDiff.Change change, Mode mode) {
        if (change.after != null) {
            return change.after;
        }
        final Option o = mSchema.get(change.path);
        return mode != Mode.SNAPSHOT && hasDefaultState(o) ? o.defaultValue : null;
    }

    /** The device's configuration as path → value; see {@link State}. */
    public LinkedHashMap<String, Object> state(Store live, State kind) {
        final LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
        for (Option o : mSchema.all()) {
            final Object v = live.read(o);
            if (v != null) {
                stored.put(o.path, v);
            }
        }
        if (kind == State.STORED) {
            return stored;
        }
        // What a declarative apply of a file that only names the presets would give.
        final Map<String, Object> fromPresets = new HashMap<>();
        if (kind == State.CHANGED) {
            for (OptionSchema.PresetFamily family : mSchema.presetFamilies()) {
                final Object preset = stored.containsKey(family.selector)
                        ? stored.get(family.selector)
                        : mSchema.get(family.selector).defaultValue;
                if (preset instanceof String && !preset.equals(family.none)) {
                    fromPresets.putAll(load(family, (String) preset, false));
                }
            }
        }
        final LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Option o : mSchema.all()) {
            final Object v = stored.get(o.path);
            final Object effective = v != null ? v : o.defaultValue;
            if (kind == State.ALL) {
                if (effective != null) {
                    out.put(o.path, effective);
                }
            } else if (fromPresets.containsKey(o.path)) {
                // The preset would fill this one in: say the value whenever it is another one,
                // also when it is the option's default or was never stored.
                if (effective != null && !effective.equals(fromPresets.get(o.path))) {
                    out.put(o.path, effective);
                }
            } else if (v != null && !v.equals(o.defaultValue)) {
                out.put(o.path, v);
            }
        }
        return out;
    }
}
