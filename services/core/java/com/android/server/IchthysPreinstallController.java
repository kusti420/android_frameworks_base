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

package com.android.server;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.pm.pkg.PackageStateInternal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The system's side of the Ichthys Apps catalog ({@code /product/etc/ichthys/apps-catalog.json},
 * generated from packages/apps/IchthysPrebuilts/catalog.list).
 *
 * <p>The ROM ships no third-party APK payloads any more: Ichthys Apps ({@link #ICHTHYS_APPS})
 * downloads, installs and updates every catalog app on the device. What stays here is what only
 * the system can do:
 * <ul>
 * <li>the one-time setup some apps need to work as intended (all-files access, being the default
 *     file picker or camera, storage access for Termux, ...), once per user, whoever installs the
 *     app. A catalog entry names its setup actions; they run when the package is installed and
 *     its signing certificate matches one of the entry's pins;
 * <li>telling a genuine catalog app from any other package ({@link #isBundledPreinstall}), for
 *     Ichthys Guard's new-app quarantine;
 * <li>migrations from earlier builds, which installed today's catalog apps from payloads in
 *     /product with Obtainium as their update owner.
 * </ul>
 * (kusti420/ichthysos-tracker#44, #6)
 */
public final class IchthysPreinstallController {
    private static final String TAG = "IchthysPreinstall";

    private static final File STATE_FILE = new File("/data/system/ichthys_preinstall.xml");
    /** The catalog: package, signing cert pins and setup of every app Ichthys Apps handles. */
    private static final File APPS_CATALOG = new File("/product/etc/ichthys/apps-catalog.json");

    /** Retired: earlier builds bundled it as the updater. Only named for the migration. */
    private static final String OBTAINIUM = "dev.imranr.obtainium";
    /** Ichthys Apps: installs and updates the catalog apps. */
    private static final String ICHTHYS_APPS = "org.ichthys.apps";

    /** package name -> catalog entry. The file is part of the image, so it is read once. */
    private static volatile Map<String, CatalogApp> sCatalog;

    /**
     * Whether {@code pkg} is a genuine catalog app: a package the ROM's catalog lists (to be
     * installed by Ichthys Apps, or bundled as a system app) whose installed copy is signed
     * with one of the entry's pinned certificates. A package that merely has a catalog app's
     * name is not one.
     *
     * <p>The name is from when these apps were payloads in the image, installed on first unlock;
     * the callers (Ichthys Guard, the notification manager) mean "one of the ROM's own apps, not
     * something the user picked up elsewhere". May be called from any thread in system_server.
     */
    public static boolean isBundledPreinstall(String pkg) {
        if (pkg == null) {
            return false;
        }
        final CatalogApp app = catalog().get(pkg);
        if (app == null) {
            return false;
        }
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final PackageStateInternal state = pmi == null ? null : pmi.getPackageStateInternal(pkg);
        final SigningDetails signing = state == null ? null : state.getSigningDetails();
        // All signers pinned, or a pinned certificate in the signing lineage.
        return signing != null && signing.hasAncestorOrSelfWithDigest(app.certs);
    }

    // Setup actions a catalog entry can name in its "setup" list.
    /** "All files access" (MANAGE_EXTERNAL_STORAGE app op), if the app asks for it. */
    private static final String SETUP_ALL_FILES_ACCESS = "allFilesAccess";
    /** Default for OPEN_DOCUMENT / GET_CONTENT. */
    private static final String SETUP_FILE_PICKER = "filePicker";
    /** Default viewer for archive types. */
    private static final String SETUP_ARCHIVE_OPENER = "archiveOpener";
    /** Default for the camera intents (#46). */
    private static final String SETUP_DEFAULT_CAMERA = "defaultCamera";
    /** Legacy storage permissions plus all-files access, as termux-setup-storage asks for. */
    private static final String SETUP_STORAGE_ACCESS = "storageAccess";

    private static final String[] CAMERA_ACTIONS = {
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE,
        MediaStore.INTENT_ACTION_VIDEO_CAMERA,
        MediaStore.ACTION_IMAGE_CAPTURE,
        MediaStore.ACTION_VIDEO_CAPTURE,
    };
    // Gets storage access up front, as if termux-setup-storage had already been run.
    private static final String TERMUX = "com.termux";
    private static final String[] STORAGE_PERMISSIONS = {
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };
    // The file manager and default document picker (replaces the IchthysFiles fork, #26).
    // Stock DocumentsUI stays the system document manager for CREATE_DOCUMENT /
    // OPEN_DOCUMENT_TREE; its OPEN_DOCUMENT / GET_CONTENT filters have no priority so that a
    // preferred activity can win (the resolver skips preferred activities when the top two
    // candidates differ in priority). ZArchiver's activities are named so that the right one is
    // preferred when several of its activities match; any other app gets its first match.
    private static final String ZARCHIVER = "ru.zdevs.zarchiver";
    private static final ComponentName ZARCHIVER_MAIN =
            new ComponentName(ZARCHIVER, "ru.zdevs.zarchiver.ZArchiver");
    private static final ComponentName ZARCHIVER_PICKER =
            new ComponentName(ZARCHIVER, "ru.zdevs.zarchiver.ZSelectFile");
    /**
     * The payload apps earlier builds set up right after installing them (ZArchiver as file
     * manager, the old default camera), see {@link #setUpCatalogApp}.
     */
    private static final Set<String> SET_UP_BY_EARLIER_BUILDS =
            Set.of(ZARCHIVER, "com.camera.lmc88r2");
    private static final String[] PICKER_ACTIONS = {
        Intent.ACTION_OPEN_DOCUMENT,
        Intent.ACTION_GET_CONTENT,
    };
    // Archive types opened by the archive opener (ZArchiver's main activity's archive filter).
    private static final String[] ARCHIVE_TYPES = {
        "application/zip", "application/x-zip-compressed", "application/zipx",
        "application/x-7z-compressed", "application/7z",
        "application/vnd.rar", "application/rar", "application/x-rar-compressed",
        "application/x-tar", "application/x-gtar", "application/x-ustar", "application/gnutar",
        "application/x-gtar-compressed", "application/gzip", "application/x-gzip",
        "application/gz", "application/x-bzip", "application/x-bzip2", "application/bz",
        "application/bz2", "application/x-xz", "application/xz", "application/x-lzma",
        "application/lzma", "application/zstd", "application/x-lz4", "application/x-compress",
        "application/x-compressed", "application/vnd.ms-cab-compressed",
        "application/x-iso9660-image", "application/x-apple-diskimage", "application/x-cpio",
        "application/x-rpm", "application/x-debian-package", "application/x-lha",
        "application/x-lzh", "application/x-lzx", "application/lzx", "application/lza",
        "application/x-ace-compressed",
    };
    private static final String STATE_INSTALLED = "installed";
    /**
     * Pseudo package name recording that a catalog app's setup ran for a user:
     * {@code ":" + id + SET_UP_SUFFIX}. ZArchiver's (":zarchiver-set-up") is the key earlier
     * builds used for its file manager setup, so a device that had it keeps it.
     */
    private static final String SET_UP_SUFFIX = "-set-up";
    /**
     * Keys earlier builds recorded a setup under, by package: Termux's storage access was granted
     * right after its payload was installed. A user who has one counts as set up.
     */
    private static final Map<String, String> LEGACY_SET_UP_KEYS =
            Map.of(TERMUX, ":termux-storage-granted");
    /**
     * Pseudo package name, system user only, recording that the catalog apps' update ownership
     * was taken back from Obtainium (see {@link #releaseObtainiumUpdateOwnership}). "-2": the
     * catalog has grown by the apps that used to be payloads (Termux, CoMaps, Mullvad VPN,
     * mpvEx, KernelSU-Next) and by the bundled system apps, so a device that ran the first
     * migration (":update-ownership-released", proprietary apps only) runs it once more.
     */
    private static final String UPDATE_OWNERSHIP_RELEASED = ":update-ownership-released-2";

    private final Context mContext;
    private final Handler mHandler;
    private final AtomicFile mStateFile = new AtomicFile(STATE_FILE);
    /**
     * userId -> (key -> state). The keys are the pseudo package names above, and the package
     * names of the payloads earlier builds installed (see {@link #SET_UP_BY_EARLIER_BUILDS}).
     * Only touched on {@link #mHandler}.
     */
    private final Map<Integer, Map<String, String>> mState = new HashMap<>();

    /** An {@link #APPS_CATALOG} entry, as far as setup is concerned. */
    private static final class CatalogApp {
        final String id;
        final String packageName;
        /**
         * SHA-256 of the accepted signing certificates, upper-case hex (what
         * {@link PackageUtils#computeSha256Digest} and {@link SigningDetails} compare with).
         */
        final Set<String> certs;
        /** Setup action names, in order. */
        final List<String> setup;

        CatalogApp(String id, String packageName, Set<String> certs, List<String> setup) {
            this.id = id;
            this.packageName = packageName;
            this.certs = certs;
            this.setup = setup;
        }

        String setUpKey() {
            return ":" + id + SET_UP_SUFFIX;
        }
    }

    public IchthysPreinstallController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Called from SystemServer once third-party apps can start. */
    public void onBootPhaseReady() {
        if (!APPS_CATALOG.isFile()) {
            return;
        }
        mHandler.post(() -> {
            readState();
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            filter.addAction(Intent.ACTION_USER_REMOVED);
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (userId < 0) {
                        return;
                    }
                    if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                        if (mState.remove(userId) != null) {
                            writeState();
                        }
                    } else {
                        processUser(userId);
                    }
                }
            }, UserHandle.ALL, filter, null, mHandler);

            // Fresh installs by anyone (Ichthys Apps, a store, adb): set up catalog apps. A full
            // removal (the app and its data) forgets the setup, so a later install gets it again.
            final IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            packages.addDataScheme("package");
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getData() == null) {
                        return;
                    }
                    final String pkg = intent.getData().getSchemeSpecificPart();
                    if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                        onPackageFullyRemoved(pkg, getSendingUserId());
                    } else if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        onPackageAdded(pkg, getSendingUserId());
                    }
                }
            }, UserHandle.ALL, packages, null, mHandler);

            // Users unlocked before the receiver was registered.
            final UserManager um = mContext.getSystemService(UserManager.class);
            for (UserHandle user : um.getUserHandles(/* excludeDying= */ true)) {
                if (um.isUserUnlocked(user)) {
                    processUser(user.getIdentifier());
                }
            }
        });
    }

    private void processUser(int userId) {
        final Map<String, String> userState = mState.computeIfAbsent(userId, k -> new HashMap<>());
        final Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        final PackageManager pm = userContext.getPackageManager();
        boolean changed = false;

        // Once per device (the update owner is not per user), from the system user, whose
        // packages relinquishUpdateOwnership() looks at for a system caller.
        if (userId == UserHandle.USER_SYSTEM
                && !userState.containsKey(UPDATE_OWNERSHIP_RELEASED)) {
            releaseObtainiumUpdateOwnership(pm);
            userState.put(UPDATE_OWNERSHIP_RELEASED, STATE_INSTALLED);
            changed = true;
        }

        // Catalog apps installed while this user was locked or before this ran (setup pending).
        for (CatalogApp app : catalog().values()) {
            changed |= setUpCatalogApp(pm, userId, userState, app);
        }
        if (changed) {
            writeState();
        }
    }

    /** A package was freshly installed for {@code userId}, by any installer. */
    private void onPackageAdded(String pkg, int userId) {
        if (pkg == null || userId < 0) {
            return;
        }
        final CatalogApp app = catalog().get(pkg);
        if (app == null || app.setup.isEmpty()) {
            return;
        }
        try {
            final PackageManager pm = mContext.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager();
            final Map<String, String> userState =
                    mState.computeIfAbsent(userId, k -> new HashMap<>());
            if (setUpCatalogApp(pm, userId, userState, app)) {
                writeState();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "could not set up " + pkg + " for user " + userId, e);
        }
    }

    /**
     * A catalog app and its data are gone for {@code userId}: its permissions and defaults went
     * with it, so the record that it was set up goes too and the next install is set up afresh.
     */
    private void onPackageFullyRemoved(String pkg, int userId) {
        final CatalogApp app = pkg == null ? null : catalog().get(pkg);
        final Map<String, String> userState = mState.get(userId);
        if (app == null || userState == null) {
            return;
        }
        boolean changed = userState.remove(app.setUpKey()) != null;
        // What earlier builds recorded for it: neither applies to a later install.
        changed |= userState.remove(pkg) != null;
        final String legacyKey = LEGACY_SET_UP_KEYS.get(pkg);
        if (legacyKey != null) {
            changed |= userState.remove(legacyKey) != null;
        }
        if (changed) {
            writeState();
        }
    }

    /**
     * Runs a catalog app's setup actions for a user, once per install: a default the user later
     * changes, or a permission they revoke, stays that way. Only for the genuine app, i.e. when
     * its signing certificate is one of the entry's pins.
     *
     * <p>Earlier builds installed some of today's catalog apps from payloads and set them up
     * right then. Such an app, still recorded as installed by that pass
     * ({@link #SET_UP_BY_EARLIER_BUILDS}) or under its old key ({@link #LEGACY_SET_UP_KEYS}),
     * counts as set up.
     *
     * @return whether {@code userState} changed
     */
    private boolean setUpCatalogApp(PackageManager pm, int userId, Map<String, String> userState,
            CatalogApp app) {
        final String key = app.setUpKey();
        if (app.setup.isEmpty() || userState.containsKey(key)
                || !isInstalled(pm, app.packageName)) {
            return false;
        }
        final String legacyKey = LEGACY_SET_UP_KEYS.get(app.packageName);
        if ((legacyKey != null && userState.containsKey(legacyKey))
                || (SET_UP_BY_EARLIER_BUILDS.contains(app.packageName)
                        && STATE_INSTALLED.equals(userState.get(app.packageName)))) {
            userState.put(key, STATE_INSTALLED);
            return true;
        }
        if (!isSignedByPin(pm, app)) {
            // Not recorded: the genuine app, installed later, still gets set up.
            Slog.w(TAG, app.packageName + " for user " + userId
                    + " isn't signed with a pinned certificate, not setting it up");
            return false;
        }
        for (String action : app.setup) {
            runSetupAction(pm, userId, app.packageName, action);
        }
        userState.put(key, STATE_INSTALLED);
        Slog.i(TAG, "set up " + app.packageName + " for user " + userId + ": " + app.setup);
        return true;
    }

    /**
     * Whether {@code app} is signed with one of its pinned certificates: one of the APK's signers
     * or, for a rotated key, a certificate in its signing lineage (the pinned key vouched for
     * the rotation).
     */
    private static boolean isSignedByPin(PackageManager pm, CatalogApp app) {
        try {
            final SigningInfo signing = pm.getPackageInfo(app.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
            if (signing == null) {
                return false;
            }
            final Signature[] certs = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
            if (certs == null) {
                return false;
            }
            for (Signature cert : certs) {
                final String digest = PackageUtils.computeSha256Digest(cert.toByteArray());
                if (digest != null && app.certs.contains(digest)) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "could not read the signing certificates of " + app.packageName, e);
        }
        return false;
    }

    /**
     * One-time migration: earlier builds installed today's catalog apps from payloads with
     * Obtainium as their update owner, and an update by anyone but the update owner needs the
     * user's confirmation. Clears that ownership (as the system may), so {@link #ICHTHYS_APPS},
     * which holds INSTALL_PACKAGES, can update them silently. Apps Obtainium doesn't own are left
     * alone. Where the OTA removed Obtainium (it was a system app and never updated itself),
     * there is nothing to clear: an update owner that isn't installed doesn't count.
     */
    private void releaseObtainiumUpdateOwnership(PackageManager pm) {
        for (CatalogApp app : catalog().values()) {
            try {
                if (!OBTAINIUM.equals(pm.getInstallSourceInfo(app.packageName)
                        .getUpdateOwnerPackageName())) {
                    continue;
                }
                pm.relinquishUpdateOwnership(app.packageName);
                Slog.i(TAG, "released " + OBTAINIUM + "'s update ownership of "
                        + app.packageName + " for " + ICHTHYS_APPS);
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed.
            } catch (RuntimeException e) {
                Slog.w(TAG, "could not release the update ownership of " + app.packageName, e);
            }
        }
    }

    /** Runs one named setup action for {@code pkg}; unknown names are logged and skipped. */
    private void runSetupAction(PackageManager pm, int userId, String pkg, String action) {
        try {
            switch (action) {
                case SETUP_ALL_FILES_ACCESS:
                    allowAllFilesAccess(pm, pkg);
                    break;
                case SETUP_FILE_PICKER:
                    makeDefaultFilePicker(pm, pkg);
                    break;
                case SETUP_ARCHIVE_OPENER:
                    makeDefaultArchiveOpener(pm, pkg);
                    break;
                case SETUP_DEFAULT_CAMERA:
                    makeDefaultCamera(pm, pkg);
                    break;
                case SETUP_STORAGE_ACCESS:
                    grantStorageAccess(pm, userId, pkg);
                    break;
                default:
                    Slog.w(TAG, "unknown setup action \"" + action + "\" for " + pkg);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "setup action " + action + " failed for " + pkg, e);
        }
    }

    /** The catalog by package name; empty if it is missing or unreadable. Any thread. */
    private static Map<String, CatalogApp> catalog() {
        Map<String, CatalogApp> catalog = sCatalog;
        if (catalog == null) {
            // Racing threads read the same file; whichever result is kept is the same.
            catalog = Collections.unmodifiableMap(loadCatalog());
            sCatalog = catalog;
        }
        return catalog;
    }

    private static Map<String, CatalogApp> loadCatalog() {
        if (!APPS_CATALOG.isFile()) {
            return Collections.emptyMap();
        }
        final Map<String, CatalogApp> out = new LinkedHashMap<>();
        try {
            final JSONArray apps = new JSONObject(new String(
                    Files.readAllBytes(APPS_CATALOG.toPath()), StandardCharsets.UTF_8))
                    .getJSONArray("apps");
            for (int i = 0; i < apps.length(); i++) {
                final JSONObject entry = apps.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                final String id = entry.optString("id", "");
                final String pkg = entry.optString("package", "");
                final Set<String> certs = new HashSet<>();
                final JSONArray certArray = entry.optJSONArray("certs");
                for (int j = 0; certArray != null && j < certArray.length(); j++) {
                    // Accept "AB:CD:..." too; compared as plain upper-case hex.
                    final String cert = certArray.optString(j, "").replace(":", "")
                            .toUpperCase(Locale.ROOT);
                    if (cert.length() == 64) {
                        certs.add(cert);
                    }
                }
                final List<String> setup = new ArrayList<>();
                final JSONArray setupArray = entry.optJSONArray("setup");
                for (int j = 0; setupArray != null && j < setupArray.length(); j++) {
                    final String action = setupArray.optString(j, "");
                    if (!action.isEmpty()) {
                        setup.add(action);
                    }
                }
                if (id.isEmpty() || pkg.isEmpty() || certs.isEmpty()) {
                    // No pins, no setup: there would be no telling the real app from another.
                    Slog.w(TAG, "catalog entry " + i + " (" + id + ") lacks id, package or certs");
                    continue;
                }
                out.put(pkg, new CatalogApp(id, pkg, Collections.unmodifiableSet(certs),
                        Collections.unmodifiableList(setup)));
            }
        } catch (Exception e) {
            Slog.w(TAG, "could not read " + APPS_CATALOG + ", no catalog app setup", e);
            return Collections.emptyMap();
        }
        return out;
    }

    /**
     * Prefers {@code pkg} for every camera intent it handles, so the camera shortcut and "take
     * photo" requests skip the chooser. Only done once, at setup; the user can change it later
     * like any default.
     */
    @SuppressWarnings("deprecation") // addPreferredActivity: what ResolverActivity's "Always" uses
    private static void makeDefaultCamera(PackageManager pm, String pkg) {
        for (String action : CAMERA_ACTIONS) {
            final Intent intent = new Intent(action);
            final List<ResolveInfo> handlers =
                    pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            ComponentName preferred = null;
            final ComponentName[] set = new ComponentName[handlers.size()];
            int bestMatch = 0;
            for (int i = 0; i < handlers.size(); i++) {
                final ActivityInfo ai = handlers.get(i).activityInfo;
                set[i] = new ComponentName(ai.packageName, ai.name);
                bestMatch = Math.max(bestMatch, handlers.get(i).match);
                if (pkg.equals(ai.packageName) && preferred == null) {
                    preferred = set[i];
                }
            }
            if (preferred == null || set.length < 2) {
                continue; // not handled by the camera, or nothing to choose between
            }
            final IntentFilter filter = new IntentFilter(action);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            try {
                pm.addPreferredActivity(filter, bestMatch, set, preferred);
                Slog.i(TAG, "default for " + action + ": " + preferred.flattenToShortString());
            } catch (RuntimeException e) {
                Slog.w(TAG, "could not set default camera for " + action, e);
            }
        }
    }

    /**
     * What {@code termux-setup-storage} asks for: the legacy storage permissions (Termux targets
     * API 28, so these are what it checks) plus "All files access". Restricted-permission
     * allowlisting is normally done by the install session already; it is repeated here because
     * without it the soft-restricted storage grant is silently ineffective for a pre-Q app.
     * The ~/storage symlinks themselves are created by termux-setup.sh (init).
     */
    private void grantStorageAccess(PackageManager pm, int userId, String pkg) {
        final UserHandle user = UserHandle.of(userId);
        final PackageInfo info;
        try {
            info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        final List<String> requested = info.requestedPermissions == null
                ? List.of() : Arrays.asList(info.requestedPermissions);
        for (String perm : STORAGE_PERMISSIONS) {
            if (!requested.contains(perm)) {
                continue;
            }
            try {
                pm.addWhitelistedRestrictedPermission(pkg, perm,
                        PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER);
            } catch (RuntimeException e) {
                Slog.w(TAG, "could not allowlist " + perm + " for " + pkg, e);
            }
            try {
                pm.grantRuntimePermission(pkg, perm, user);
            } catch (RuntimeException e) {
                Slog.w(TAG, "could not grant " + perm + " to " + pkg, e);
            }
        }
        allowAllFilesAccess(pm, pkg);
        Slog.i(TAG, "granted storage access to " + pkg + " for user " + userId);
    }

    /**
     * "All files access", if {@code pkg} asks for it. For ZArchiver it is the only storage
     * permission it asks for on R+; without it its picker shows "Access is denied".
     */
    private void allowAllFilesAccess(PackageManager pm, String pkg) {
        try {
            final PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            final List<String> requested = info.requestedPermissions == null
                    ? List.of() : Arrays.asList(info.requestedPermissions);
            if (requested.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    && info.applicationInfo != null) {
                mContext.getSystemService(AppOpsManager.class).setUidMode(
                        AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE, info.applicationInfo.uid,
                        AppOpsManager.MODE_ALLOWED);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "could not allow all-files access for " + pkg, e);
        }
    }

    /** Makes {@code pkg} the default for OPEN_DOCUMENT / GET_CONTENT. */
    private static void makeDefaultFilePicker(PackageManager pm, String pkg) {
        // Pickers: one "*/*" entry per action, which also covers requests for a specific type
        // (a preferred filter with "*/*" matches every type). The candidate set is the "*/*"
        // query, i.e. every handler of any type, so later narrower queries are subsets of it.
        for (String action : PICKER_ACTIONS) {
            final Intent intent = new Intent(action)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            final IntentFilter filter = new IntentFilter(action);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            filter.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                filter.addDataType("*/*");
            } catch (IntentFilter.MalformedMimeTypeException e) {
                continue; // can't happen
            }
            addPreferred(pm, intent, filter, pkg, ZARCHIVER_PICKER);
        }
        Slog.i(TAG, "made " + pkg + " the default file picker");
    }

    /** Makes {@code pkg} the default viewer for archives. */
    private static void makeDefaultArchiveOpener(PackageManager pm, String pkg) {
        // Archives: one entry per type, like the resolver's "Always" records it, each with the
        // exact handler set for that type.
        for (String type : ARCHIVE_TYPES) {
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("content://ichthys.invalid/file"), type);
            final IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            filter.addDataScheme(ContentResolver.SCHEME_CONTENT);
            filter.addDataScheme(ContentResolver.SCHEME_FILE);
            try {
                filter.addDataType(type);
            } catch (IntentFilter.MalformedMimeTypeException e) {
                continue;
            }
            addPreferred(pm, intent, filter, pkg, ZARCHIVER_MAIN);
        }
        Slog.i(TAG, "made " + pkg + " the default archive opener");
    }

    /**
     * Records an activity of {@code pkg} as the "Always" choice for {@code intent}, the way
     * ResolverActivity does: {@code filter} describes the intent, and the set is every activity
     * that currently resolves it (the resolver drops the preference once a handler outside the
     * set appears). The activity is {@code hint} if it is {@code pkg}'s and in the set, else the
     * first of {@code pkg}'s. Skipped when {@code pkg} doesn't handle it or there is no choice.
     */
    @SuppressWarnings("deprecation") // addPreferredActivity: what ResolverActivity's "Always" uses
    private static void addPreferred(PackageManager pm, Intent intent, IntentFilter filter,
            String pkg, ComponentName hint) {
        final Set<ComponentName> set = new LinkedHashSet<>();
        ComponentName preferred = null;
        int bestMatch = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            final ComponentName cn =
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            set.add(cn);
            bestMatch = Math.max(bestMatch, ri.match);
            if (preferred == null && pkg.equals(cn.getPackageName())) {
                preferred = cn;
            }
        }
        if (hint != null && pkg.equals(hint.getPackageName()) && set.contains(hint)) {
            preferred = hint;
        }
        if (preferred == null || set.size() < 2) {
            return;
        }
        try {
            pm.addPreferredActivity(filter, bestMatch, set.toArray(new ComponentName[0]),
                    preferred);
        } catch (RuntimeException e) {
            Slog.w(TAG, "could not make " + preferred.flattenToShortString() + " the default for "
                    + intent, e);
        }
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void readState() {
        mState.clear();
        if (!STATE_FILE.exists()) {
            return;
        }
        try (FileInputStream in = mStateFile.openRead()) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(in);
            Map<String, String> user = null;
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                if ("user".equals(parser.getName())) {
                    user = mState.computeIfAbsent(parser.getAttributeInt(null, "id"),
                            k -> new HashMap<>());
                } else if ("pkg".equals(parser.getName()) && user != null) {
                    user.put(parser.getAttributeValue(null, "name"),
                            parser.getAttributeValue(null, "state"));
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "could not read " + STATE_FILE + ", starting fresh", e);
            mState.clear();
        }
    }

    private void writeState() {
        FileOutputStream out = null;
        try {
            out = mStateFile.startWrite();
            final TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, "preinstall");
            for (Map.Entry<Integer, Map<String, String>> user : mState.entrySet()) {
                xml.startTag(null, "user");
                xml.attributeInt(null, "id", user.getKey());
                for (Map.Entry<String, String> pkg : user.getValue().entrySet()) {
                    xml.startTag(null, "pkg");
                    xml.attribute(null, "name", pkg.getKey());
                    xml.attribute(null, "state", pkg.getValue());
                    xml.endTag(null, "pkg");
                }
                xml.endTag(null, "user");
            }
            xml.endTag(null, "preinstall");
            xml.endDocument();
            mStateFile.finishWrite(out);
        } catch (Exception e) {
            Slog.w(TAG, "could not write " + STATE_FILE, e);
            if (out != null) {
                mStateFile.failWrite(out);
            }
        }
    }
}
