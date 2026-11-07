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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One typed configuration option, NixOS-options style: a dotted path, a type, a default, a
 * description and the backend it binds to.
 *
 * <p>Values in this class are "Nix values": Boolean, Long, Double, String, List&lt;String&gt; or null.
 * A null default means "unset": the consumer's own built-in default applies, and resetting the
 * option deletes the stored value.
 *
 * <p>A <em>preserved</em> option ({@link #preserved()}) holds state the user or an app built up
 * (per-app lists, "the first-boot screen was confirmed"): a declarative apply leaves it alone
 * unless the file mentions it, where any other option the file omits goes back to its default.
 */
public final class Option {

    public enum Type { BOOL, INT, FLOAT, STRING, ENUM, COLOR, STRING_LIST, PACKAGE, PACKAGE_LIST }

    private static final Pattern PACKAGE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern COLOR = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    public final String path;
    public final Type type;
    public final Object defaultValue;
    public final String description;
    public final Binding binding;

    // ENUM: Nix-facing names and the values stored for them, index-aligned.
    final String[] enumNames;
    final String[] enumStored;
    // INT / FLOAT bounds.
    final double min;
    final double max;
    // COLOR stored as a decimal ARGB int (crDroid color pickers) instead of "#AARRGGBB".
    boolean colorAsInt;
    // COLOR also accepts the word "wallpaper" (theme.accent).
    boolean allowWallpaper;
    // BOOL stored as "true"/"false" instead of 1/0.
    boolean boolAsText;
    // Lists are stored joined with this separator.
    String listSeparator = ",";
    // Declarative applies keep the stored value unless the file mentions the option.
    boolean preserved;
    // PACKAGE_LIST: tags an entry may carry after the package name ("org.example.app:picked").
    String[] entryTags = {};
    // ENUM: names this option used to have -> the current name.
    Map<String, String> enumAliases;

    private Option(String path, Type type, Object def, String description, Binding binding,
            String[] enumNames, String[] enumStored, double min, double max) {
        this.path = path;
        this.type = type;
        this.defaultValue = def;
        this.description = description;
        this.binding = binding;
        this.enumNames = enumNames;
        this.enumStored = enumStored;
        this.min = min;
        this.max = max;
    }

    // ---------------------------------------------------------------------------------------------
    // Factories

    public static Option bool(String path, Boolean def, String desc, Binding b) {
        return new Option(path, Type.BOOL, def, desc, b, null, null, 0, 0);
    }

    public static Option integer(String path, Long def, long min, long max, String desc,
            Binding b) {
        return new Option(path, Type.INT, def, desc, b, null, null, min, max);
    }

    public static Option floating(String path, Double def, double min, double max, String desc,
            Binding b) {
        return new Option(path, Type.FLOAT, def, desc, b, null, null, min, max);
    }

    public static Option string(String path, String def, String desc, Binding b) {
        return new Option(path, Type.STRING, def, desc, b, null, null, 0, 0);
    }

    public static Option packageName(String path, String def, String desc, Binding b) {
        return new Option(path, Type.PACKAGE, def, desc, b, null, null, 0, 0);
    }

    public static Option color(String path, String def, String desc, Binding b) {
        return new Option(path, Type.COLOR, def, desc, b, null, null, 0, 0);
    }

    public static Option stringList(String path, String desc, Binding b) {
        return new Option(path, Type.STRING_LIST, null, desc, b, null, null, 0, 0);
    }

    public static Option packageList(String path, String desc, Binding b) {
        return new Option(path, Type.PACKAGE_LIST, null, desc, b, null, null, 0, 0);
    }

    /**
     * @param names Nix-facing enum names
     * @param stored the value stored for each name
     * @param def default name, or null for "unset"
     */
    public static Option enumeration(String path, String[] names, String[] stored, String def,
            String desc, Binding b) {
        if (names.length != stored.length) {
            throw new IllegalArgumentException(path + ": enum names/values mismatch");
        }
        return new Option(path, Type.ENUM, def, desc, b, names, stored, 0, 0);
    }

    // Builder-style modifiers (fluent, used while registering).

    public Option storedAsInt() {
        colorAsInt = true;
        return this;
    }

    public Option orWallpaper() {
        allowWallpaper = true;
        return this;
    }

    public Option boolAsText() {
        boolAsText = true;
        return this;
    }

    public Option separator(String sep) {
        listSeparator = sep;
        return this;
    }

    /**
     * Marks state that belongs to the device rather than to a config file: a declarative apply
     * keeps it unless the file mentions the option (see the class comment).
     */
    public Option preserved() {
        preserved = true;
        return this;
    }

    public boolean isPreserved() {
        return preserved;
    }

    /**
     * PACKAGE_LIST: lets an entry carry one of {@code tags} after a colon, the form some lists
     * store a per-app mode in ("org.example.app:picked").
     */
    public Option entryTags(String... tags) {
        entryTags = tags;
        return this;
    }

    /** ENUM: keeps a name the option used to have working ({@code old} means {@code name}). */
    public Option enumAlias(String old, String name) {
        if (!Arrays.asList(enumNames).contains(name)) {
            throw new IllegalArgumentException(path + ": no enum name " + name);
        }
        if (enumAliases == null) {
            enumAliases = new HashMap<>();
        }
        enumAliases.put(old, name);
        return this;
    }

    public List<String> enumNames() {
        return enumNames == null ? new ArrayList<>() : Arrays.asList(enumNames);
    }

    // ---------------------------------------------------------------------------------------------
    // Validation and conversion

    /**
     * Checks a parsed Nix value against this option's type and returns it normalized
     * (Long for INT, Double for FLOAT, List&lt;String&gt; for lists, null for Nix null).
     *
     * @throws IllegalArgumentException with a human message when the value doesn't fit
     */
    @SuppressWarnings("unchecked")
    public Object normalize(Object v) {
        if (v == null || v == NixParser.NULL) {
            return null;
        }
        switch (type) {
            case BOOL:
                if (v instanceof Boolean) return v;
                throw bad("a boolean (true or false)", v);
            case INT: {
                if (!(v instanceof Long)) throw bad("an integer", v);
                final long l = (Long) v;
                if (min != max && (l < min || l > max)) {
                    throw new IllegalArgumentException(path + " must be between " + (long) min
                            + " and " + (long) max + ", got " + l);
                }
                return v;
            }
            case FLOAT: {
                final double d;
                if (v instanceof Long) d = (Long) v;
                else if (v instanceof Double) d = (Double) v;
                else throw bad("a number", v);
                if (min != max && (d < min || d > max)) {
                    throw new IllegalArgumentException(path + " must be between " + min + " and "
                            + max + ", got " + d);
                }
                return d;
            }
            case STRING:
                if (v instanceof String) return v;
                throw bad("a string", v);
            case PACKAGE:
                if (v instanceof String && PACKAGE.matcher((String) v).matches()) return v;
                throw bad("a package name like \"org.example.app\"", v);
            case COLOR:
                if (v instanceof String) {
                    final String s = (String) v;
                    if (allowWallpaper && "wallpaper".equals(s)) return s;
                    if (COLOR.matcher(s).matches()) return canonicalColor(s);
                }
                throw bad(allowWallpaper ? "\"wallpaper\" or a color \"#rrggbb\""
                        : "a color \"#rrggbb\" or \"#aarrggbb\"", v);
            case ENUM: {
                final String name = v instanceof String ? enumName((String) v) : null;
                if (name != null) return name;
                throw bad("one of " + String.join(", ", enumNames), v);
            }
            case STRING_LIST:
            case PACKAGE_LIST: {
                if (!(v instanceof List)) throw bad("a list of strings", v);
                final List<String> out = new ArrayList<>();
                for (Object o : (List<Object>) v) {
                    if (!(o instanceof String)) throw bad("a list of strings", v);
                    final String s = (String) o;
                    if (type == Type.PACKAGE_LIST && !PACKAGE.matcher(packageOf(s)).matches()) {
                        throw bad(entryTags.length == 0 ? "a list of package names"
                                : "a list of package names (an entry may end in :"
                                        + String.join(" or :", entryTags) + ")", v);
                    }
                    if (s.contains(listSeparator)) {
                        throw new IllegalArgumentException(path + ": list entries can't contain '"
                                + listSeparator + "'");
                    }
                    out.add(s);
                }
                return out;
            }
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * The canonical spelling of a colour, the one {@link #fromStored} prints: lower case, and
     * "#rrggbb" for an opaque colour ("#ffrrggbb" names the same colour).
     */
    private static String canonicalColor(String s) {
        final String c = s.toLowerCase(Locale.ROOT);
        return c.length() == 9 && c.startsWith("#ff") ? "#" + c.substring(3) : c;
    }

    /**
     * The enum name {@code s} stands for: a current name, a former one ({@link #enumAlias}), or
     * the stored value itself, bare or as the "v&lt;value&gt;" placeholder the options generator
     * used to emit for an entry it could not name. Null when it is none of them.
     */
    private String enumName(String s) {
        final List<String> names = Arrays.asList(enumNames);
        if (names.contains(s)) return s;
        if (enumAliases != null && enumAliases.containsKey(s)) return enumAliases.get(s);
        final List<String> stored = Arrays.asList(enumStored);
        int i = stored.indexOf(s);
        if (i < 0 && s.length() > 1 && s.charAt(0) == 'v') {
            i = stored.indexOf(s.substring(1));
        }
        return i < 0 ? null : enumNames[i];
    }

    /** The package name of a list entry, without the tag it may carry. */
    private String packageOf(String entry) {
        for (String tag : entryTags) {
            if (entry.endsWith(":" + tag)) {
                return entry.substring(0, entry.length() - tag.length() - 1);
            }
        }
        return entry;
    }

    /** The string written to a settings table for a normalized value (null deletes). */
    @SuppressWarnings("unchecked")
    public String toStored(Object v) {
        if (v == null) {
            return null;
        }
        switch (type) {
            case BOOL:
                return boolAsText ? String.valueOf(v) : (((Boolean) v) ? "1" : "0");
            case INT:
                return String.valueOf(v);
            case FLOAT:
                return String.valueOf(v);
            case COLOR: {
                final String s = (String) v;
                if (!colorAsInt || "wallpaper".equals(s)) return s;
                long argb = Long.parseLong(s.substring(1), 16);
                if (s.length() == 7) argb |= 0xFF000000L;
                return String.valueOf((int) argb);
            }
            case ENUM:
                return enumStored[Arrays.asList(enumNames).indexOf(v)];
            case STRING_LIST:
            case PACKAGE_LIST:
                return String.join(listSeparator, (List<String>) v);
            default:
                return (String) v;
        }
    }

    /**
     * The Nix value for a stored string, or null when nothing is stored or the stored value
     * can't be represented (e.g. an enum value no longer offered).
     */
    public Object fromStored(String s) {
        if (s == null) {
            return null;
        }
        try {
            switch (type) {
                case BOOL:
                    if ("1".equals(s) || "true".equalsIgnoreCase(s)) return Boolean.TRUE;
                    if ("0".equals(s) || "false".equalsIgnoreCase(s)) return Boolean.FALSE;
                    return null;
                case INT:
                    return Long.valueOf(s.trim());
                case FLOAT:
                    return Double.valueOf(s.trim());
                case COLOR: {
                    if ("wallpaper".equals(s)) return allowWallpaper ? s : null;
                    if (colorAsInt || !s.startsWith("#")) {
                        final int argb = (int) Long.parseLong(s.trim());
                        return (argb >>> 24) == 0xFF
                                ? String.format("#%06x", argb & 0xFFFFFF)
                                : String.format("#%08x", argb);
                    }
                    return COLOR.matcher(s).matches() ? canonicalColor(s) : null;
                }
                case ENUM: {
                    final int i = Arrays.asList(enumStored).indexOf(s);
                    return i < 0 ? null : enumNames[i];
                }
                case STRING_LIST:
                case PACKAGE_LIST: {
                    final List<String> out = new ArrayList<>();
                    if (s.isEmpty()) return out;
                    for (String part : s.split(Pattern.quote(listSeparator))) {
                        final String t = part.trim();
                        if (!t.isEmpty()) out.add(t);
                    }
                    return out;
                }
                default:
                    return s;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One-line type description for docs and errors. */
    public String typeName() {
        switch (type) {
            case ENUM:
                return "one of " + String.join(" | ", enumNames);
            case INT:
                return min != max ? "integer " + (long) min + ".." + (long) max : "integer";
            case FLOAT:
                return min != max ? "number " + min + ".." + max : "number";
            case COLOR:
                return allowWallpaper ? "color or \"wallpaper\"" : "color";
            case STRING_LIST:
                return "list of strings";
            case PACKAGE_LIST:
                return entryTags.length == 0 ? "list of package names"
                        : "list of package names (an entry may end in :"
                                + String.join(" or :", entryTags) + ")";
            case PACKAGE:
                return "package name";
            default:
                return type.name().toLowerCase(Locale.ROOT);
        }
    }

    private IllegalArgumentException bad(String expected, Object v) {
        return new IllegalArgumentException(path + " must be " + expected + ", got "
                + NixPrinter.value(v));
    }
}
