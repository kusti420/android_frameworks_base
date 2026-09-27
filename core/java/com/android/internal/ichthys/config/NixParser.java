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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the Nix subset ichthys.nix is written in.
 *
 * <p>Supported: one top-level attribute set; {@code a.b.c = value;} bindings with dotted paths
 * (merged into nested sets, like Nix); nested {@code { ... }} sets; double-quoted strings with
 * {@code \" \\ \n \t \r \$} escapes; integers; floats; {@code true}, {@code false}, {@code null};
 * whitespace-separated lists {@code [ a b c ]}; {@code # line} and block comments. Attribute names
 * are identifiers ({@code [A-Za-z_][A-Za-z0-9_'-]*}) or quoted strings. There are no functions,
 * {@code let}, {@code with} or imports: a config is data, not a program.
 *
 * <p>Values come back as {@code Map<String, Object>} (attribute sets, in source order),
 * {@code List<Object>}, {@code String}, {@code Long}, {@code Double}, {@code Boolean} and
 * {@link #NULL}. Every leaf's source position is recorded by its dotted path so validation errors
 * can point at the offending line.
 *
 * <p>Pure Java (no android.* imports) so it can be unit-tested on the host.
 */
public final class NixParser {

    /** The Nix {@code null} value (a plain Java null can't be stored in every collection). */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** A parsed file: the top-level set and "line:col" for every leaf path. */
    public static final class Result {
        public final Map<String, Object> root;
        public final Map<String, int[]> positions;

        Result(Map<String, Object> root, Map<String, int[]> positions) {
            this.root = root;
            this.positions = positions;
        }

        /** "line:col" of a leaf path, or "" if unknown. */
        public String where(String path) {
            final int[] p = positions.get(path);
            return p == null ? "" : p[0] + ":" + p[1];
        }
    }

    private static final int MAX_DEPTH = 64;

    private final String mSrc;
    private int mPos;
    private int mLine = 1;
    private int mCol = 1;
    private final Map<String, int[]> mPositions = new HashMap<>();

    private NixParser(String src) {
        mSrc = src;
    }

    /** Parses a whole file; the top level must be an attribute set. */
    public static Result parse(String src) throws NixParseException {
        final NixParser p = new NixParser(src == null ? "" : src);
        p.skipSpace();
        if (p.atEnd()) {
            return new Result(new LinkedHashMap<>(), p.mPositions);
        }
        if (p.peek() != '{') {
            throw p.error("a config file must be one attribute set: expected '{'");
        }
        final Map<String, Object> root = p.parseSet("", 0);
        p.skipSpace();
        if (!p.atEnd()) {
            throw p.error("unexpected text after the closing '}'");
        }
        return new Result(root, p.mPositions);
    }

    // ---------------------------------------------------------------------------------------------
    // Grammar

    private Map<String, Object> parseSet(String prefix, int depth) throws NixParseException {
        if (depth > MAX_DEPTH) {
            throw error("attribute sets nested too deeply");
        }
        expect('{');
        final Map<String, Object> set = new LinkedHashMap<>();
        while (true) {
            skipSpace();
            if (atEnd()) {
                throw error("unterminated attribute set: missing '}'");
            }
            if (peek() == '}') {
                advance();
                return set;
            }
            final int line = mLine;
            final int col = mCol;
            final List<String> path = parseAttrPath();
            skipSpace();
            expect('=');
            skipSpace();
            final String fullPrefix = join(prefix, path);
            final Object value = parseValue(fullPrefix, depth + 1);
            skipSpace();
            expect(';');
            bind(set, path, value, prefix, line, col);
        }
    }

    private List<String> parseAttrPath() throws NixParseException {
        final List<String> path = new ArrayList<>();
        while (true) {
            skipSpace();
            path.add(parseAttrName());
            skipSpace();
            if (!atEnd() && peek() == '.') {
                advance();
                continue;
            }
            return path;
        }
    }

    private String parseAttrName() throws NixParseException {
        if (atEnd()) {
            throw error("expected an attribute name");
        }
        final char c = peek();
        if (c == '"') {
            return parseString();
        }
        if (!isIdentStart(c)) {
            throw error("expected an attribute name, found '" + printable(c) + "'");
        }
        final int start = mPos;
        while (!atEnd() && isIdentPart(peek())) {
            advance();
        }
        return mSrc.substring(start, mPos);
    }

    private Object parseValue(String path, int depth) throws NixParseException {
        if (atEnd()) {
            throw error("expected a value");
        }
        final char c = peek();
        if (c == '{') {
            return parseSet(path, depth);
        }
        if (c == '[') {
            return parseList(path, depth);
        }
        if (c == '"') {
            return parseString();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
        }
        if (isIdentStart(c)) {
            final int line = mLine;
            final int col = mCol;
            final String word = parseAttrName();
            switch (word) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "null":
                    return NULL;
                default:
                    throw new NixParseException("unknown identifier '" + word
                            + "' (strings must be quoted; functions and variables are not supported)",
                            line, col);
            }
        }
        throw error("expected a value, found '" + printable(c) + "'");
    }

    private List<Object> parseList(String path, int depth) throws NixParseException {
        if (depth > MAX_DEPTH) {
            throw error("lists nested too deeply");
        }
        expect('[');
        final List<Object> list = new ArrayList<>();
        while (true) {
            skipSpace();
            if (atEnd()) {
                throw error("unterminated list: missing ']'");
            }
            if (peek() == ']') {
                advance();
                return list;
            }
            list.add(parseValue(path + "[" + list.size() + "]", depth + 1));
        }
    }

    private String parseString() throws NixParseException {
        final int line = mLine;
        final int col = mCol;
        expect('"');
        final StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw new NixParseException("unterminated string", line, col);
            }
            final char c = advance();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '$' && !atEnd() && peek() == '{') {
                throw error("string interpolation (${...}) is not supported; write \\${ for a "
                        + "literal");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (atEnd()) {
                throw new NixParseException("unterminated string", line, col);
            }
            final char e = advance();
            switch (e) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '"':
                case '\\':
                case '$':
                    sb.append(e);
                    break;
                default:
                    throw error("unknown escape '\\" + printable(e) + "'");
            }
        }
    }

    private Object parseNumber() throws NixParseException {
        final int line = mLine;
        final int col = mCol;
        final int start = mPos;
        if (peek() == '-') {
            advance();
        }
        boolean digits = false;
        boolean dot = false;
        while (!atEnd()) {
            final char c = peek();
            if (c >= '0' && c <= '9') {
                digits = true;
                advance();
            } else if (c == '.' && !dot) {
                dot = true;
                advance();
            } else {
                break;
            }
        }
        final String text = mSrc.substring(start, mPos);
        if (!digits || text.endsWith(".")) {
            throw new NixParseException("malformed number '" + text + "'", line, col);
        }
        if (!atEnd() && isIdentPart(peek())) {
            throw error("malformed number '" + text + printable(peek()) + "...'");
        }
        try {
            return dot ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new NixParseException("number out of range '" + text + "'", line, col);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Binding dotted paths into nested sets

    @SuppressWarnings("unchecked")
    private void bind(Map<String, Object> set, List<String> path, Object value, String prefix,
            int line, int col) throws NixParseException {
        Map<String, Object> cur = set;
        String walked = prefix;
        for (int i = 0; i < path.size() - 1; i++) {
            final String name = path.get(i);
            walked = join(walked, name);
            final Object existing = cur.get(name);
            if (existing == null) {
                final Map<String, Object> next = new LinkedHashMap<>();
                cur.put(name, next);
                cur = next;
            } else if (existing instanceof Map) {
                cur = (Map<String, Object>) existing;
            } else {
                throw new NixParseException("'" + walked + "' is already a value (at "
                        + whereOf(walked) + "), it can't also be an attribute set", line, col);
            }
        }
        final String leaf = path.get(path.size() - 1);
        final String full = join(walked, leaf);
        final Object existing = cur.get(leaf);
        if (existing != null) {
            if (existing instanceof Map && value instanceof Map) {
                // `a = { x = 1; }; a = { y = 2; };` is an error in Nix; so is it here.
                throw new NixParseException("attribute '" + full + "' already defined at "
                        + whereOf(full), line, col);
            }
            throw new NixParseException("attribute '" + full + "' already defined at "
                    + whereOf(full), line, col);
        }
        cur.put(leaf, value);
        recordPositions(full, value, line, col);
    }

    @SuppressWarnings("unchecked")
    private void recordPositions(String path, Object value, int line, int col) {
        if (value instanceof Map) {
            // Leaves inside a nested set were recorded while that set was parsed, under paths
            // relative to the set's prefix, which already equals `path`.
            if (!mPositions.containsKey(path)) {
                mPositions.put(path, new int[] {line, col});
            }
            return;
        }
        mPositions.put(path, new int[] {line, col});
    }

    private String whereOf(String path) {
        final int[] p = mPositions.get(path);
        return p == null ? "?" : p[0] + ":" + p[1];
    }

    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "." + name;
    }

    private static String join(String prefix, List<String> names) {
        String out = prefix;
        for (String n : names) {
            out = join(out, n);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Lexing helpers

    private void skipSpace() throws NixParseException {
        while (!atEnd()) {
            final char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                advance();
            } else if (c == '#') {
                while (!atEnd() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && mPos + 1 < mSrc.length() && mSrc.charAt(mPos + 1) == '*') {
                final int line = mLine;
                final int col = mCol;
                advance();
                advance();
                while (true) {
                    if (atEnd()) {
                        throw new NixParseException("unterminated block comment", line, col);
                    }
                    if (peek() == '*' && mPos + 1 < mSrc.length()
                            && mSrc.charAt(mPos + 1) == '/') {
                        advance();
                        advance();
                        break;
                    }
                    advance();
                }
            } else {
                return;
            }
        }
    }

    private void expect(char c) throws NixParseException {
        if (atEnd()) {
            throw error("expected '" + c + "' but the file ended");
        }
        if (peek() != c) {
            throw error("expected '" + c + "', found '" + printable(peek()) + "'");
        }
        advance();
    }

    private boolean atEnd() {
        return mPos >= mSrc.length();
    }

    private char peek() {
        return mSrc.charAt(mPos);
    }

    private char advance() {
        final char c = mSrc.charAt(mPos++);
        if (c == '\n') {
            mLine++;
            mCol = 1;
        } else {
            mCol++;
        }
        return c;
    }

    private NixParseException error(String message) {
        return new NixParseException(message, mLine, mCol);
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '\'' || c == '-';
    }

    private static String printable(char c) {
        if (c == '\n') return "\\n";
        if (c == '\t') return "\\t";
        if (c < 0x20) return String.format("\\u%04x", (int) c);
        return String.valueOf(c);
    }
}
