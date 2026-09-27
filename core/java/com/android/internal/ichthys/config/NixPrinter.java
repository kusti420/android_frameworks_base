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
import java.util.TreeMap;

/**
 * Canonical ichthys.nix writer: one nested attribute set per namespace, keys sorted, two-space
 * indent, each option preceded by its description as a {@code #} comment. Parsing the output gives
 * back the same values ({@code parse(print(x)) == x}).
 */
public final class NixPrinter {

    /** Supplies the comment printed above a leaf (its option description), or null for none. */
    public interface Comments {
        String commentFor(String path);
    }

    private NixPrinter() {}

    public static String print(Map<String, Object> root, Comments comments, String header) {
        final StringBuilder sb = new StringBuilder();
        if (header != null) {
            for (String line : header.split("\n")) {
                sb.append("# ").append(line).append('\n');
            }
        }
        sb.append("{\n");
        printSetBody(sb, root, "", 1, comments);
        sb.append("}\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void printSetBody(StringBuilder sb, Map<String, Object> set, String prefix,
            int indent, Comments comments) {
        final TreeMap<String, Object> sorted = new TreeMap<>(set);
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            final String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            final Object v = e.getValue();
            if (v instanceof Map) {
                if (!first) {
                    sb.append('\n');
                }
                pad(sb, indent).append(name(e.getKey())).append(" = {\n");
                printSetBody(sb, (Map<String, Object>) v, path, indent + 1, comments);
                pad(sb, indent).append("};\n");
            } else {
                final String c = comments == null ? null : comments.commentFor(path);
                if (c != null && !c.isEmpty()) {
                    for (String line : c.split("\n")) {
                        pad(sb, indent).append("# ").append(line).append('\n');
                    }
                }
                pad(sb, indent).append(name(e.getKey())).append(" = ").append(value(v))
                        .append(";\n");
            }
            first = false;
        }
    }

    /** Renders one value in Nix syntax. */
    @SuppressWarnings("unchecked")
    public static String value(Object v) {
        if (v == null || v == NixParser.NULL) {
            return "null";
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        if (v instanceof Long || v instanceof Integer) {
            return String.valueOf(((Number) v).longValue());
        }
        if (v instanceof Double || v instanceof Float) {
            String s = String.valueOf(((Number) v).doubleValue());
            if (!s.contains(".") && !s.contains("E")) {
                s = s + ".0";
            }
            return s;
        }
        if (v instanceof List) {
            final List<String> parts = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                parts.add(value(o));
            }
            return parts.isEmpty() ? "[ ]" : "[ " + String.join(" ", parts) + " ]";
        }
        if (v instanceof Map) {
            final StringBuilder sb = new StringBuilder("{ ");
            for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) v).entrySet()) {
                sb.append(name(e.getKey())).append(" = ").append(value(e.getValue())).append("; ");
            }
            return sb.append('}').toString();
        }
        return quote(String.valueOf(v));
    }

    static String quote(String s) {
        final StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '$':
                    sb.append("\\$");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String name(String key) {
        if (key.isEmpty()) {
            return quote(key);
        }
        final char c0 = key.charAt(0);
        boolean ok = (c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z') || c0 == '_';
        for (int i = 1; ok && i < key.length(); i++) {
            final char c = key.charAt(i);
            ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '\'' || c == '-';
        }
        if (ok && (key.equals("true") || key.equals("false") || key.equals("null"))) {
            ok = false;
        }
        return ok ? key : quote(key);
    }

    private static StringBuilder pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        return sb;
    }
}
