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

/**
 * A syntax or validation error in an ichthys.nix file, with the 1-based position it refers to
 * ({@code line} 0 when the error isn't tied to a place in the text).
 */
public final class NixParseException extends Exception {
    public final int line;
    public final int column;

    public NixParseException(String message, int line, int column) {
        super(line > 0 ? line + ":" + column + ": " + message : message);
        this.line = line;
        this.column = column;
    }

    public NixParseException(String message) {
        this(message, 0, 0);
    }
}
