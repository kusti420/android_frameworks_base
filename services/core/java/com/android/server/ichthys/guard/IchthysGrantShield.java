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

package com.android.server.ichthys.guard;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.ichthys.config.IchthysShields;

import java.util.List;

/**
 * IchthysOS: whether an app that is shielded from some data may hand out URI grants on it.
 *
 * <p>The providers answer a shielded app with an empty book, but a grant-only caller (one
 * without the permission, holding a URI grant) reads the single item it was granted: that is
 * what the contacts picker and shared vCards need. A shielded app that holds READ_CONTACTS could
 * launder its way around the shield through that path: it holds the provider's permission, so
 * the system lets it grant {@code contacts/<id>/data} for id 1..N to a helper app without any
 * permission, and the helper reads every row. The same holds for {@code mms/part/<id>}.
 *
 * <p>So {@link UriGrantsManagerService} asks here before it counts the granting app's uid
 * permission: a shielded app does not "hold" the permission for granting purposes and may only
 * pass on a grant it holds itself (the picker's), exactly like an app without the permission.
 *
 * <p>The decision mirrors the providers' own {@code IchthysShield} classes (ContactsProvider,
 * CalendarProvider, TelephonyProvider), which cannot be shared with system_server: the core
 * system, platform-signed and preinstalled apps, the default dialer and SMS app, and apps on the
 * shield's allow list are not shielded; an unreadable setting means not shielded (fail open, as
 * there).
 */
public final class IchthysGrantShield {

    private static final String TAG = "IchthysGrantShield";

    private IchthysGrantShield() {}

    /**
     * The shield that guards this content authority, or null for an unshielded one. {@code
     * authority} may be a {@link android.content.pm.ProviderInfo#authority} value, which lists
     * every authority of the provider separated by ';' ("contacts;com.android.contacts" for
     * ContactsProvider): the first listed authority that is shielded decides.
     */
    private static IchthysShields.Shield shieldFor(String authority) {
        if (authority == null) {
            return null;
        }
        if (authority.indexOf(';') >= 0) {
            for (String one : authority.split(";")) {
                final IchthysShields.Shield shield = shieldFor(one.trim());
                if (shield != null) {
                    return shield;
                }
            }
            return null;
        }
        switch (authority) {
            case "com.android.contacts":
            case "contacts":
            case "com.android.contacts.files":
                return IchthysShields.CONTACTS;
            case "call_log":
            case "call_log_shadow":
                return IchthysShields.CALL_LOG;
            case "com.android.calendar":
            case "calendar":
                return IchthysShields.CALENDAR;
            case "sms":
            case "mms":
            case "mms-sms":
                return IchthysShields.SMS_READ;
            default:
                return null;
        }
    }

    /**
     * Whether {@code uid} is shielded from the data behind {@code authority}, so that its
     * provider permission must not count when it hands out URI grants. Call with any identity;
     * the settings are read with the identity cleared.
     */
    public static boolean isShieldedGranter(Context context, int uid, String authority) {
        final IchthysShields.Shield shield = shieldFor(authority);
        if (!isShielded(context, uid, shield)) {
            return false;
        }
        Slog.i(TAG, "uid " + uid + " is shielded from " + authority
                + ": its permission does not count for URI grants");
        return true;
    }

    /**
     * Whether {@code uid} is shielded from contacts (the contacts lockdown), with the providers'
     * exemptions. AccountManagerService asks it so that READ_CONTACTS (and GET_ACCOUNTS of a
     * pre-O app) stops making the user's accounts visible to a shielded app: the account list
     * names the user as surely as the address book does. Call with any identity.
     */
    public static boolean isContactsShielded(Context context, int uid) {
        return isShielded(context, uid, IchthysShields.CONTACTS);
    }

    private static boolean isShielded(Context context, int uid, IchthysShields.Shield shield) {
        if (shield == null || context == null
                || UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final int userId = UserHandle.getUserId(uid);
            if (!isSwitchOn(context, shield, userId)) {
                return false;
            }
            final PackageManager pm = context.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager();
            if (pm.checkSignatures(Process.SYSTEM_UID, uid) == PackageManager.SIGNATURE_MATCH) {
                return false;
            }
            final String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length == 0) {
                return false;
            }
            final String allowed = Settings.Secure.getStringForUser(
                    context.getContentResolver(), shield.listKey, userId);
            for (String pkg : packages) {
                try {
                    final ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    if ((info.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                        return false;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Raced with an uninstall; treat as not preinstalled.
                }
                if (IchthysShields.isListed(allowed, pkg) || isRoleExempt(context, pkg, userId)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            // Unresolvable means not shielded, like the providers.
            Slog.w(TAG, "Cannot tell whether uid " + uid + " is shielded from " + shield.name
                    + ", allowing", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Unset or non-zero is on, like the providers read it. */
    private static boolean isSwitchOn(Context context, IchthysShields.Shield shield, int userId) {
        if (shield.switchKey == null) {
            return true;
        }
        final String stored = shield.switchTable == IchthysShields.Table.GLOBAL
                ? Settings.Global.getString(context.getContentResolver(), shield.switchKey)
                : Settings.Secure.getStringForUser(context.getContentResolver(),
                        shield.switchKey, userId);
        return IchthysShields.isOn(stored);
    }

    /** The default dialer and SMS app read everything, as in the providers. */
    private static boolean isRoleExempt(Context context, String pkg, int userId) {
        final RoleManager roles = context.getSystemService(RoleManager.class);
        if (roles == null) {
            return false;
        }
        final UserHandle user = UserHandle.of(userId);
        return holdsRole(roles, RoleManager.ROLE_DIALER, user, pkg)
                || holdsRole(roles, RoleManager.ROLE_SMS, user, pkg);
    }

    private static boolean holdsRole(RoleManager roles, String role, UserHandle user, String pkg) {
        final List<String> holders = roles.getRoleHoldersAsUser(role, user);
        return holders != null && holders.contains(pkg);
    }
}
