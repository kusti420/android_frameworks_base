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

package com.android.server.pm.permission;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.role.RoleManagerLocal;
import com.android.server.LocalManagerRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IchthysOS: system-wide lockdown of the CONTACTS permission group.
 *
 * <p>The user-visible half of this feature lives in PermissionController, which drops a contacts
 * request before any dialog is built and sweeps the permission away from apps that already hold it.
 * This class is the half that cannot be worked around: it sits on the grant path inside
 * system_server, so <em>every</em> way of handing out contacts — {@code pm grant} from a shell, a
 * device policy controller, an install session that pre-grants permissions, PermissionController's
 * own "Allow" button and the per-app permission screen in Settings — is refused while the lockdown
 * is on. Nothing outside system_server can reach around it.
 *
 * <p><b>On unless switched off.</b> An unset {@link Settings.Global#CONTACTS_LOCKDOWN} means locked
 * down: the owner's rule is that no app besides the phone, contacts and messaging apps ever sees
 * contacts, so a fresh install starts locked rather than waiting for a toggle.
 *
 * <p>The allowlist is resolved from <em>roles</em> for the phone and messaging apps, so it follows
 * whatever the user picked, plus the contacts app (which holds no role) and the packages that own
 * the contacts database itself. Packages listed in the per-user {@link Settings.Secure} key
 * {@link #ALLOWED_PACKAGES_SETTING} are allowed too: that is the hook Ichthys Guard uses for
 * explicit per-app exceptions, and it is empty by default. Keep it in sync with
 * {@code com.android.permissioncontroller.permission.utils.ContactsLockdown}, which cannot share
 * code with this class because it ships in the permission APEX.
 *
 * <p><b>Unresolvable means allowed.</b> When the role holders cannot be read — RoleService has not
 * published its local interface yet, or no roles are resolved this early in boot — the grant is let
 * through rather than refused. Refusing would break the default grants that the phone and messaging
 * apps get at first boot and after an OTA, which is exactly the access this feature is supposed to
 * preserve. Anything that slips through that window is taken away again by the
 * {@code BOOT_COMPLETED} sweep in PermissionController.
 */
public final class ContactsLockdownPolicy {

    private static final String LOG_TAG = "ContactsLockdownPolicy";

    /**
     * Packages that own or directly serve the contacts database. These are not "apps that want your
     * contacts" — they are where the contacts live, so denying them breaks the phone and messaging
     * apps that are supposed to keep working.
     */
    private static final List<String> ALWAYS_ALLOWED_PACKAGES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "com.android.providers.contacts",
                            "com.android.contacts",
                            // The system contact picker: how an app with no contacts permission at
                            // all still gets the single contact the user deliberately chose.
                            "com.android.contactspicker",
                            "com.android.server.telecom",
                            // The contacts app. It holds no role, so it has to be named.
                            "org.fossify.contacts"));

    /**
     * Per-user {@link Settings.Secure} key: comma-separated packages the user explicitly allowed
     * contacts access despite the lockdown. Written by Ichthys Guard; empty by default. Keep in sync
     * with {@code ContactsLockdown.ALLOWED_PACKAGES_SETTING} in PermissionController.
     */
    public static final String ALLOWED_PACKAGES_SETTING = "ichthys_contacts_allowed_pkgs";

    private ContactsLockdownPolicy() {}

    /**
     * Whether the lockdown is switched on. Global, so one switch covers every user. Unset counts as
     * on.
     */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return Settings.Global.getInt(
                            context.getContentResolver(), Settings.Global.CONTACTS_LOCKDOWN, 1)
                    == 1;
        } catch (RuntimeException e) {
            Slog.w(LOG_TAG, "Failed to read " + Settings.Global.CONTACTS_LOCKDOWN, e);
            return false;
        }
    }

    /**
     * Whether {@code permissionName} belongs to the locked-down group. {@code GET_ACCOUNTS} is
     * included because it is in the same group and leaks the account identities behind the contact
     * list.
     */
    public static boolean isContactsPermission(String permissionName) {
        return Manifest.permission.READ_CONTACTS.equals(permissionName)
                || Manifest.permission.WRITE_CONTACTS.equals(permissionName)
                || Manifest.permission.GET_ACCOUNTS.equals(permissionName);
    }

    /**
     * Whether contacts access must be withheld from {@code packageName} in {@code userId}.
     *
     * @param appId the package's app ID, used to exempt the platform and the rest of the core
     *     system — those UIDs host telecom, telephony and Settings, and are not something the user
     *     is choosing to hand their contacts to
     * @param isRoleControllerCaller true when the question is being asked on behalf of a grant made
     *     by the role controller, which needs a wider answer during a role handover — see
     *     {@link #isRoleHandoverInProgress}
     */
    public static boolean isPackageBlocked(
            Context context,
            String packageName,
            int appId,
            int userId,
            boolean isRoleControllerCaller) {
        if (TextUtils.isEmpty(packageName) || !isEnabled(context)) {
            return false;
        }
        if (appId < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        if (ALWAYS_ALLOWED_PACKAGES.contains(packageName)
                || isUserAllowed(context, packageName, userId)) {
            return false;
        }

        final RoleManagerLocal roleManagerLocal =
                LocalManagerRegistry.getManager(RoleManagerLocal.class);
        if (roleManagerLocal == null) {
            Slog.w(LOG_TAG, "RoleService not up yet, allowing contacts for " + packageName);
            return false;
        }
        final Map<String, Set<String>> rolesAndHolders;
        try {
            rolesAndHolders = roleManagerLocal.getRolesAndHolders(userId);
        } catch (RuntimeException e) {
            Slog.w(LOG_TAG, "Cannot read role holders, allowing contacts for " + packageName, e);
            return false;
        }
        final Set<String> dialerHolders = rolesAndHolders.get(RoleManager.ROLE_DIALER);
        final Set<String> smsHolders = rolesAndHolders.get(RoleManager.ROLE_SMS);
        final boolean hasDialer = dialerHolders != null && !dialerHolders.isEmpty();
        final boolean hasSms = smsHolders != null && !smsHolders.isEmpty();

        if (!hasDialer && !hasSms) {
            // Neither role is resolved: too early to tell the phone app apart from anything else.
            Slog.w(LOG_TAG, "No dialer or SMS role yet, allowing contacts for " + packageName);
            return false;
        }
        if ((hasDialer && dialerHolders.contains(packageName))
                || (hasSms && smsHolders.contains(packageName))) {
            return false;
        }
        if (isRoleControllerCaller && isRoleHandoverInProgress(hasDialer, hasSms)) {
            Slog.i(LOG_TAG, "Role handover in progress, allowing contacts for " + packageName);
            return false;
        }
        return true;
    }

    /**
     * Whether the user explicitly exempted {@code packageName} for {@code userId}, see
     * {@link #ALLOWED_PACKAGES_SETTING}.
     */
    private static boolean isUserAllowed(Context context, String packageName, int userId) {
        final String allowed;
        try {
            allowed = Settings.Secure.getStringForUser(
                    context.getContentResolver(), ALLOWED_PACKAGES_SETTING, userId);
        } catch (RuntimeException e) {
            Slog.w(LOG_TAG, "Failed to read " + ALLOWED_PACKAGES_SETTING, e);
            return false;
        }
        if (TextUtils.isEmpty(allowed)) {
            return false;
        }
        for (String pkg : allowed.split(",")) {
            if (packageName.equals(pkg.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one of the two roles is currently unheld, which for a grant coming from the role
     * controller means a handover is under way.
     *
     * <p>{@code RoleControllerServiceImpl.addRoleHolderInternal()} grants the new holder its
     * permissions <em>before</em> recording it as the holder, and for an exclusive role it has
     * already removed the previous holder by then. So the app that is in the middle of becoming the
     * user's phone or messaging app looks like any other app at exactly the moment it is being
     * given contacts access. Refusing it there would leave a freshly chosen dialer unable to see
     * the contact list until something granted it again — so the role controller, and only the role
     * controller, is let through while a role is unheld.
     */
    private static boolean isRoleHandoverInProgress(boolean hasDialer, boolean hasSms) {
        return !hasDialer || !hasSms;
    }

    /**
     * Whether a grant of {@code permissionName} to {@code packageName} must be refused. The single
     * question the permission service asks on the grant path.
     */
    public static boolean isGrantBlocked(
            Context context,
            String packageName,
            int appId,
            String permissionName,
            int userId,
            boolean isRoleControllerCaller) {
        return isContactsPermission(permissionName)
                && isPackageBlocked(context, packageName, appId, userId, isRoleControllerCaller);
    }
}
