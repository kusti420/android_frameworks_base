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

package com.android.internal.ichthys;

/**
 * IchthysOS declarative configuration service ("ichthys_config", IchthysConfigService).
 * Callers need android.permission.MANAGE_ICHTHYS_CONFIG. Invalid configs throw
 * IllegalArgumentException whose message carries "line:col: reason".
 * {@hide}
 */
interface IIchthysConfig {
    /** The live state as ichthys.nix (all options, or only those changed from default). */
    String exportConfig(boolean all, int userId);

    /** The changes applying {@code config} would make, one "path: old -> new" per line. */
    String diff(String config, boolean merge, int userId);

    /**
     * Applies {@code config} (declaratively, or only the options it sets when {@code merge})
     * and records a generation. Returns the generation number now current.
     */
    int apply(String config, boolean merge, String source, int userId);

    /** Re-applies generation {@code generation} (-1: the previous one) as a new generation. */
    int rollback(int generation, int userId);

    /** "number\ttimeMillis\tsummary\tsource\tisCurrent(1|0)", newest first. */
    String[] listGenerations(int userId);

    /** The ichthys.nix text of one generation. */
    String getGeneration(int generation, int userId);

    /** Option reference for options under {@code prefix} ("" for all). */
    String describeOptions(String prefix);

    /** Names of the theme presets in /product/etc/ichthys/presets. */
    String[] listPresets();

    /** The text of one preset. */
    String getPreset(String name);
}
