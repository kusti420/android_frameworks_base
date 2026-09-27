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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Installs the ROM's bundled third-party apps as ordinary user apps, once per user.
 *
 * <p>Shipping them as system prebuilts doesn't work for every APK: the package manager never
 * extracts native libraries for system apps, so upstream APKs with compressed JNI libs crash with
 * {@code dlopen failed}, and Play app bundles need their config splits installed alongside the
 * base APK. Installing them through a normal session fixes both, and makes them updatable and
 * uninstallable like any other app.
 *
 * <p>Payloads live in {@code /product/etc/ichthys-preinstall/<package>/*.apk} (base plus any
 * splits). Obtainium is set as installer of record and update owner, so it can later update these
 * apps without a prompt. An app the user uninstalls is remembered and never reinstalled.
 * (kusti420/ichthysos-tracker#44, #6)
 */
public final class IchthysPreinstallController {
    private static final String TAG = "IchthysPreinstall";

    private static final File PAYLOAD_DIR = new File("/product/etc/ichthys-preinstall");
    private static final File OBTAINIUM_APPS_JSON = new File(PAYLOAD_DIR, "obtainium-apps.json");
    private static final File STATE_FILE = new File("/data/system/ichthys_preinstall.xml");

    private static final String OBTAINIUM = "dev.imranr.obtainium";
    // Made the default camera when first installed (the GCam port the owner picked, #46).
    private static final String DEFAULT_CAMERA = "com.camera.lmc88r2";
    private static final String[] CAMERA_ACTIONS = {
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE,
        MediaStore.INTENT_ACTION_VIDEO_CAMERA,
        MediaStore.ACTION_IMAGE_CAPTURE,
        MediaStore.ACTION_VIDEO_CAPTURE,
    };
    private static final String CHANNEL_ID = "ichthys_preinstall";
    private static final int NOTIFICATION_ID = 0x1c7;
    private static final long COMMIT_TIMEOUT_SECONDS = 180;

    private static final String STATE_INSTALLED = "installed";
    private static final String STATE_FAILED = "failed";
    /** Pseudo package name recording that the Obtainium prompt was already shown to a user. */
    private static final String OBTAINIUM_PROMPTED = ":obtainium-prompted";

    private final Context mContext;
    private final Handler mHandler;
    private final AtomicFile mStateFile = new AtomicFile(STATE_FILE);
    /** userId -> (packageName -> state). Only touched on {@link #mHandler}. */
    private final Map<Integer, Map<String, String>> mState = new HashMap<>();

    public IchthysPreinstallController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Called from SystemServer once third-party apps can start. */
    public void onBootPhaseReady() {
        if (!PAYLOAD_DIR.isDirectory()) {
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
        final UserManager um = mContext.getSystemService(UserManager.class);
        if (um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS, UserHandle.of(userId))) {
            Slog.i(TAG, "installs disallowed for user " + userId + ", skipping");
            return;
        }
        final File[] packageDirs = PAYLOAD_DIR.listFiles(File::isDirectory);
        if (packageDirs == null) {
            return;
        }
        Arrays.sort(packageDirs);
        final Map<String, String> userState = mState.computeIfAbsent(userId, k -> new HashMap<>());
        final Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        final PackageManager pm = userContext.getPackageManager();
        boolean changed = false;

        for (File dir : packageDirs) {
            final String pkg = dir.getName();
            final boolean installed = isInstalled(pm, pkg);
            final String state = userState.get(pkg);
            if (installed) {
                if (!STATE_INSTALLED.equals(state)) {
                    userState.put(pkg, STATE_INSTALLED);
                    changed = true;
                }
                continue;
            }
            if (STATE_INSTALLED.equals(state)) {
                // We installed it before and it's gone: the user uninstalled it. Respect that.
                continue;
            }
            final File[] apks = dir.listFiles((d, name) -> name.endsWith(".apk"));
            if (apks == null || apks.length == 0) {
                continue;
            }
            final boolean ok = install(userContext, pm, pkg, apks);
            userState.put(pkg, ok ? STATE_INSTALLED : STATE_FAILED);
            if (ok && DEFAULT_CAMERA.equals(pkg)) {
                makeDefaultCamera(pm);
            }
            changed = true;
        }

        if (!userState.containsKey(OBTAINIUM_PROMPTED) && OBTAINIUM_APPS_JSON.isFile()
                && isInstalled(pm, OBTAINIUM)) {
            if (postObtainiumPrompt(userId)) {
                userState.put(OBTAINIUM_PROMPTED, STATE_INSTALLED);
                changed = true;
            }
        }
        if (changed) {
            writeState();
        }
    }

    private boolean install(Context userContext, PackageManager pm, String pkg, File[] apks) {
        final PackageInstaller installer = pm.getPackageInstaller();
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setInstallReason(PackageManager.INSTALL_REASON_DEVICE_SETUP);
        long total = 0;
        for (File apk : apks) {
            total += apk.length();
        }
        params.setSize(total);
        // The package installer resets the installer to null if it isn't installed for this user,
        // so only ask for it when it is.
        if (isInstalled(pm, OBTAINIUM)) {
            params.setInstallerPackageName(OBTAINIUM);
            params.setRequestUpdateOwnership(true);
        }

        int sessionId = -1;
        try {
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                for (File apk : apks) {
                    try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(apk,
                            ParcelFileDescriptor.MODE_READ_ONLY)) {
                        session.write(apk.getName(), 0, apk.length(), pfd);
                    }
                }
                final LinkedBlockingQueue<Intent> result = new LinkedBlockingQueue<>();
                session.commit(resultSender(result));
                final Intent intent = result.poll(COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                final int status = intent == null ? PackageInstaller.STATUS_FAILURE
                        : intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    Slog.i(TAG, "installed " + pkg + " for user " + userContext.getUserId());
                    return true;
                }
                Slog.w(TAG, "installing " + pkg + " failed: status=" + status + " "
                        + (intent == null ? "timeout"
                                : intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
                return false;
            }
        } catch (Exception e) {
            Slog.w(TAG, "installing " + pkg + " failed", e);
            if (sessionId != -1) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                    // Already committed or gone.
                }
            }
            return false;
        }
    }

    /**
     * One notification per user: tapping it opens Obtainium's {@code obtainium://apps/} deep link
     * with every bundled app's source preconfigured, which Obtainium imports after one confirmation.
     */
    private boolean postObtainiumPrompt(int userId) {
        final String json;
        try {
            json = new String(Files.readAllBytes(OBTAINIUM_APPS_JSON.toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            Slog.w(TAG, "could not read " + OBTAINIUM_APPS_JSON, e);
            return false;
        }
        final Intent view = new Intent(Intent.ACTION_VIEW,
                Uri.parse("obtainium://apps/" + Uri.encode(json)))
                .setPackage(OBTAINIUM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        final PendingIntent pi = PendingIntent.getActivity(userContext, 0, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final NotificationManager nm = userContext.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Bundled apps",
                NotificationManager.IMPORTANCE_DEFAULT));
        final Notification n = new Notification.Builder(userContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Keep bundled apps up to date")
                .setContentText("Tap to add them to Obtainium")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, n);
        return true;
    }

    /**
     * Prefers {@link #DEFAULT_CAMERA} for every camera intent it handles, so the camera shortcut
     * and "take photo" requests skip the chooser. Only done at install time; the user can change
     * it later like any default.
     */
    @SuppressWarnings("deprecation") // addPreferredActivity: what ResolverActivity's "Always" uses
    private static void makeDefaultCamera(PackageManager pm) {
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
                if (DEFAULT_CAMERA.equals(ai.packageName) && preferred == null) {
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

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static IntentSender resultSender(LinkedBlockingQueue<Intent> result) {
        final IIntentSender.Stub sender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder allowlistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                result.offer(intent);
            }
        };
        return new IntentSender((IIntentSender) sender);
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
