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

/** Where an option's value lives on the device. Interpreted by IchthysConfigService. */
public final class Binding {

    public enum Store {
        /** Settings.System / Secure / Global. */
        SYSTEM, SECURE, GLOBAL,
        /** LineageSettings.System / Secure / Global (lineagesettings provider). */
        LINEAGE_SYSTEM, LINEAGE_SECURE, LINEAGE_GLOBAL,
        /**
         * A field of the Secure THEME_CUSTOMIZATION_OVERLAY_PACKAGES JSON read by SystemUI's
         * ThemeOverlayController (Monet). {@link #key} names the logical field:
         * "accent", "style" or "font".
         */
        THEME_JSON,
        /** The holder of a RoleManager role ({@link #key} = role name, e.g. DIALER). */
        ROLE,
        /** A lock screen shortcut slot ({@link #key} = slot id, e.g. bottom_end). */
        KEYGUARD_SLOT,
    }

    public final Store store;
    public final String key;

    private Binding(Store store, String key) {
        this.store = store;
        this.key = key;
    }

    public static Binding setting(Store store, String key) {
        return new Binding(store, key);
    }

    public static Binding themeJson(String field) {
        return new Binding(Store.THEME_JSON, field);
    }

    public static Binding role(String roleName) {
        return new Binding(Store.ROLE, roleName);
    }

    public static Binding keyguardSlot(String slotId) {
        return new Binding(Store.KEYGUARD_SLOT, slotId);
    }

    /** True for plain key/value settings tables (they can be reset by writing null). */
    public boolean isSettingsTable() {
        switch (store) {
            case SYSTEM:
            case SECURE:
            case GLOBAL:
            case LINEAGE_SYSTEM:
            case LINEAGE_SECURE:
            case LINEAGE_GLOBAL:
                return true;
            default:
                return false;
        }
    }

    /** Two bindings are equal when they name the same value on the device. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Binding && ((Binding) other).store == store
                && ((Binding) other).key.equals(key);
    }

    @Override
    public int hashCode() {
        return store.hashCode() * 31 + key.hashCode();
    }

    @Override
    public String toString() {
        return store.name().toLowerCase() + ":" + key;
    }
}
