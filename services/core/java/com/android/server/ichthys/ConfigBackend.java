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

package com.android.server.ichthys;

import android.app.role.RoleManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.ichthys.config.Binding;
import com.android.internal.ichthys.config.Option;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads and writes option values in their backing stores for one user. Every call runs with the
 * system identity (callers clear the binder identity first).
 */
final class ConfigBackend {
    private static final String TAG = "IchthysConfig";

    private static final String LINEAGE_AUTHORITY = "lineagesettings";
    private static final String SETTINGS_AUTHORITY = "settings";
    private static final String CUSTOMIZATION_SELECTIONS =
            "content://com.android.systemui.customization/lockscreen_quickaffordance/selections";

    // THEME_CUSTOMIZATION_OVERLAY_PACKAGES JSON fields (SystemUI ThemeOverlayApplier).
    static final String JSON_ACCENT = "android.theme.customization.accent_color";
    static final String JSON_PALETTE = "android.theme.customization.system_palette";
    static final String JSON_SOURCE = "android.theme.customization.color_source";
    static final String JSON_SEEDS = "android.theme.customization.seed_color_list";
    static final String JSON_STYLE = "android.theme.customization.theme_style";
    static final String JSON_FONT = "android.theme.customization.font";
    static final String JSON_TIMESTAMP = "_applied_timestamp";

    private final Context mContext;

    ConfigBackend(Context context) {
        mContext = context;
    }

    // ---------------------------------------------------------------------------------------------
    // Read

    /** The option's live Nix value, or null when unset / not representable. */
    Object read(Option o, int userId) {
        final Binding b = o.binding;
        switch (b.store) {
            case SYSTEM:
            case SECURE:
            case GLOBAL:
            case LINEAGE_SYSTEM:
            case LINEAGE_SECURE:
            case LINEAGE_GLOBAL:
                return o.fromStored(readSetting(b, userId));
            case THEME_JSON:
                return readThemeJson(o, userId);
            case ROLE:
                return readRole(b.key, userId);
            case KEYGUARD_SLOT:
                return readKeyguardSlot(b.key, userId);
            default:
                return null;
        }
    }

    String readSetting(Binding b, int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        switch (b.store) {
            case SYSTEM:
                return Settings.System.getStringForUser(cr, b.key, userId);
            case SECURE:
                return Settings.Secure.getStringForUser(cr, b.key, userId);
            case GLOBAL:
                return Settings.Global.getString(cr, b.key);
            case LINEAGE_SYSTEM:
                return lineageGet("GET_system", b.key, userId);
            case LINEAGE_SECURE:
                return lineageGet("GET_secure", b.key, userId);
            case LINEAGE_GLOBAL:
                return lineageGet("GET_global", b.key, userId);
            default:
                throw new IllegalArgumentException(b.toString());
        }
    }

    private String lineageGet(String method, String key, int userId) {
        final Bundle args = new Bundle();
        args.putInt("_user", userId);
        try {
            final Bundle r = mContext.getContentResolver().call(LINEAGE_AUTHORITY, method, key, args);
            return r == null ? null : r.getString(Settings.NameValueTable.VALUE);
        } catch (RuntimeException e) {
            Slog.w(TAG, "lineagesettings " + method + " " + key + " failed", e);
            return null;
        }
    }

    private Object readThemeJson(Option o, int userId) {
        final JSONObject json = themeJson(userId);
        switch (o.binding.key) {
            case "accent": {
                final String source = json.optString(JSON_SOURCE, "");
                final String palette = json.optString(JSON_PALETTE, "");
                if ("preset".equals(source) && palette.matches("[0-9a-fA-F]{6}")) {
                    return "#" + palette.toLowerCase();
                }
                return "wallpaper";
            }
            case "style": {
                final String style = json.optString(JSON_STYLE, "");
                return style.isEmpty() ? null : o.fromStored(style);
            }
            case "font": {
                final String font = json.optString(JSON_FONT, "");
                return font.isEmpty() ? null : font;
            }
            default:
                return null;
        }
    }

    JSONObject themeJson(int userId) {
        final String s = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, userId);
        if (s == null || s.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private String readRole(String role, int userId) {
        final RoleManager rm = mContext.getSystemService(RoleManager.class);
        if (rm == null) {
            return null;
        }
        final List<String> holders = rm.getRoleHoldersAsUser(role, UserHandle.of(userId));
        return holders.isEmpty() ? null : holders.get(0);
    }

    private String readKeyguardSlot(String slot, int userId) {
        final Uri uri = maybeAddUser(Uri.parse(CUSTOMIZATION_SELECTIONS), userId);
        try (Cursor c = mContext.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) {
                return null;
            }
            final int slotCol = c.getColumnIndex("slot_id");
            final int affCol = c.getColumnIndex("affordance_id");
            while (c.moveToNext()) {
                if (slot.equals(c.getString(slotCol))) {
                    return c.getString(affCol);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "keyguard shortcut query failed", e);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Write

    /** Writes a normalized value; null resets the option to the consumer's default. */
    void write(Option o, Object value, int userId) {
        final Binding b = o.binding;
        switch (b.store) {
            case SYSTEM:
            case SECURE:
            case GLOBAL:
            case LINEAGE_SYSTEM:
            case LINEAGE_SECURE:
            case LINEAGE_GLOBAL:
                writeSetting(b, o.toStored(value), userId);
                return;
            case THEME_JSON:
                writeThemeJson(o, value, userId);
                return;
            case ROLE:
                writeRole(b.key, (String) value, userId);
                return;
            case KEYGUARD_SLOT:
                writeKeyguardSlot(b.key, (String) value, userId);
                return;
            default:
                throw new IllegalArgumentException(b.toString());
        }
    }

    void writeSetting(Binding b, String stored, int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        if (stored == null) {
            deleteSetting(b, userId);
            return;
        }
        final boolean ok;
        switch (b.store) {
            case SYSTEM:
                ok = Settings.System.putStringForUser(cr, b.key, stored, userId);
                break;
            case SECURE:
                ok = Settings.Secure.putStringForUser(cr, b.key, stored, userId);
                break;
            case GLOBAL:
                ok = Settings.Global.putString(cr, b.key, stored);
                break;
            case LINEAGE_SYSTEM:
                ok = lineagePut("PUT_system", b.key, stored, userId);
                break;
            case LINEAGE_SECURE:
                ok = lineagePut("PUT_secure", b.key, stored, userId);
                break;
            case LINEAGE_GLOBAL:
                ok = lineagePut("PUT_global", b.key, stored, userId);
                break;
            default:
                throw new IllegalArgumentException(b.toString());
        }
        if (!ok) {
            throw new IllegalStateException("could not write " + b);
        }
    }

    private void deleteSetting(Binding b, int userId) {
        final Bundle args = new Bundle();
        args.putInt("_user", userId);
        final String authority;
        final String method;
        switch (b.store) {
            case SYSTEM:
                authority = SETTINGS_AUTHORITY;
                method = Settings.CALL_METHOD_DELETE_SYSTEM;
                break;
            case SECURE:
                authority = SETTINGS_AUTHORITY;
                method = Settings.CALL_METHOD_DELETE_SECURE;
                break;
            case GLOBAL:
                authority = SETTINGS_AUTHORITY;
                method = Settings.CALL_METHOD_DELETE_GLOBAL;
                break;
            case LINEAGE_SYSTEM:
                authority = LINEAGE_AUTHORITY;
                method = "DELETE_system";
                break;
            case LINEAGE_SECURE:
                authority = LINEAGE_AUTHORITY;
                method = "DELETE_secure";
                break;
            case LINEAGE_GLOBAL:
                authority = LINEAGE_AUTHORITY;
                method = "DELETE_global";
                break;
            default:
                throw new IllegalArgumentException(b.toString());
        }
        mContext.getContentResolver().call(authority, method, b.key, args);
    }

    private boolean lineagePut(String method, String key, String value, int userId) {
        final Bundle args = new Bundle();
        args.putInt("_user", userId);
        args.putString(Settings.NameValueTable.VALUE, value);
        mContext.getContentResolver().call(LINEAGE_AUTHORITY, method, key, args);
        return true;
    }

    private void writeThemeJson(Option o, Object value, int userId) {
        final JSONObject json = themeJson(userId);
        try {
            switch (o.binding.key) {
                case "accent": {
                    if (value == null || "wallpaper".equals(value)) {
                        json.remove(JSON_ACCENT);
                        json.remove(JSON_PALETTE);
                        json.remove(JSON_SEEDS);
                        json.put(JSON_SOURCE, "home_wallpaper");
                    } else {
                        final String hex = ((String) value).substring(1);
                        final String rgb = hex.length() == 8 ? hex.substring(2) : hex;
                        json.put(JSON_ACCENT, rgb);
                        json.put(JSON_PALETTE, rgb);
                        json.put(JSON_SOURCE, "preset");
                        final JSONArray seeds = new JSONArray();
                        seeds.put("FF" + rgb.toUpperCase());
                        json.put(JSON_SEEDS, seeds);
                    }
                    break;
                }
                case "style":
                    if (value == null) {
                        json.remove(JSON_STYLE);
                    } else {
                        json.put(JSON_STYLE, o.toStored(value));
                    }
                    break;
                case "font":
                    if (value == null) {
                        json.remove(JSON_FONT);
                    } else {
                        json.put(JSON_FONT, value);
                    }
                    break;
                default:
                    throw new IllegalArgumentException(o.binding.toString());
            }
            json.put(JSON_TIMESTAMP, System.currentTimeMillis());
        } catch (JSONException e) {
            throw new IllegalStateException("theme JSON", e);
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, json.toString(), userId);
    }

    private void writeRole(String role, String pkg, int userId) {
        final RoleManager rm = mContext.getSystemService(RoleManager.class);
        if (rm == null) {
            throw new IllegalStateException("RoleManager unavailable");
        }
        final UserHandle user = UserHandle.of(userId);
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (pkg == null) {
            rm.clearRoleHoldersAsUser(role, 0, user, mContext.getMainExecutor(), done::complete);
        } else {
            rm.addRoleHolderAsUser(role, pkg, 0, user, mContext.getMainExecutor(),
                    done::complete);
        }
        boolean ok;
        try {
            ok = done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            throw new IllegalArgumentException("could not make " + pkg + " the holder of " + role
                    + " (is it installed and eligible?)");
        }
    }

    private void writeKeyguardSlot(String slot, String affordance, int userId) {
        final Uri uri = maybeAddUser(Uri.parse(CUSTOMIZATION_SELECTIONS), userId);
        final ContentResolver cr = mContext.getContentResolver();
        cr.delete(uri, null, new String[] {slot});
        if (affordance != null) {
            final ContentValues v = new ContentValues();
            v.put("slot_id", slot);
            v.put("affordance_id", affordance);
            if (cr.insert(uri, v) == null) {
                throw new IllegalArgumentException("SystemUI refused lock screen shortcut '"
                        + affordance + "' for " + slot);
            }
        }
    }

    private static Uri maybeAddUser(Uri uri, int userId) {
        return android.content.ContentProvider.maybeAddUserId(uri, userId);
    }

    static boolean isSystemOrShell(int uid) {
        final int appId = UserHandle.getAppId(uid);
        return appId == Process.SYSTEM_UID || appId == Process.SHELL_UID
                || appId == Process.ROOT_UID;
    }
}
