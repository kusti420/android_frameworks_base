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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** The option changes between two states ({path → value}, null = unset/default). */
public final class ConfigDiff {

    public static final class Change {
        public final String path;
        public final Object before;
        public final Object after;

        Change(String path, Object before, Object after) {
            this.path = path;
            this.before = before;
            this.after = after;
        }

        @Override
        public String toString() {
            return path + ": " + show(before) + " -> " + show(after);
        }

        private static String show(Object v) {
            return v == null ? "(default)" : NixPrinter.value(v);
        }
    }

    public final List<Change> changes = new ArrayList<>();

    private ConfigDiff() {}

    /**
     * @param before live state for every path that {@code after} mentions or that is set
     * @param after target state
     */
    public static ConfigDiff between(Map<String, Object> before, Map<String, Object> after) {
        final ConfigDiff d = new ConfigDiff();
        final TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        for (String p : paths) {
            final Object b = before.get(p);
            final Object a = after.get(p);
            if (!Objects.equals(b, a)) {
                d.changes.add(new Change(p, b, a));
            }
        }
        return d;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** One change per line, "path: old -> new". */
    public String format() {
        if (changes.isEmpty()) {
            return "no changes\n";
        }
        final StringBuilder sb = new StringBuilder();
        for (Change c : changes) {
            sb.append(c).append('\n');
        }
        return sb.toString();
    }

    /** Short summary for generation metadata, e.g. "theme.accent, privacy.sensorShield +3". */
    public String summary() {
        if (changes.isEmpty()) return "no changes";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, changes.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(changes.get(i).path);
        }
        if (changes.size() > 2) sb.append(" +").append(changes.size() - 2);
        return sb.toString();
    }
}
