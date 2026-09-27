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

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IchthysOS Backstage: keeps user-chosen apps "in the foreground" on a private, never-displayed
 * virtual display, so apps that stop working once they leave the screen (uploads, syncs, timers
 * tied to the activity lifecycle) keep running while the phone is used normally or locked.
 * (kusti420/ichthysos-tracker#38)
 *
 * <p>Mechanism: one virtual display owned by system_server, rendering into an {@link ImageReader}
 * that is drained and never shown. Flags: TRUSTED + OWN_DISPLAY_GROUP (its own power group, so the
 * main screen's sleep doesn't stop it) + ALWAYS_UNLOCKED (keyguard doesn't stop its activities;
 * nothing is ever drawn anywhere, so nothing leaks onto the lockscreen) + OWN_FOCUS +
 * STEAL_TOP_FOCUS_DISABLED (never takes input focus from the real screen). It is not PUBLIC or
 * PRESENTATION, so other apps can't place content on it or see it in display lists, and it isn't
 * mirrored or captured anywhere. A display-bound screen wake lock keeps only that display group
 * awake. Keyguard follows the default display group, so locking works as usual. Moved tasks stay
 * RESUMED and visible: their process is TOP.
 *
 * <p>Control: Settings.Secure {@link #KEY_PACKAGES} (comma-separated packages, per user) is the
 * desired set. The crDroidSettings page and later the Guard app write it; this service reconciles.
 * An ongoing notification per app offers "Bring back" (needs the device unlocked) and "Stop"
 * (removes the task). The list is cleared at boot and on user switch. Releasing the display (when
 * nothing is left, or on any failure) moves remaining tasks back to the main screen.
 */
public final class IchthysBackstageService {
    private static final String TAG = "IchthysBackstage";

    /** Settings.Secure, per user: comma-separated packages the user wants in Backstage. */
    public static final String KEY_PACKAGES = "ichthys_backstage_pkgs";
    /** Settings.Secure, per user: stop an app automatically after this many hours (0 = never). */
    public static final String KEY_MAX_HOURS = "ichthys_backstage_max_hours";

    private static final int MAX_APPS = 3;
    private static final float REFRESH_RATE = 5f;
    /** A launched app gets this long to show up on the display before it's dropped. */
    private static final long LAUNCH_GRACE_MS = 15_000;
    private static final long HOUR_MS = 60 * 60 * 1000L;

    private static final String CHANNEL_ID = "ichthys_backstage";
    private static final String ACTION_BRING_BACK =
            "com.android.server.ichthys.BACKSTAGE_BRING_BACK";
    private static final String ACTION_STOP = "com.android.server.ichthys.BACKSTAGE_STOP";
    private static final String EXTRA_PACKAGE = "package";
    private static final int NOTIFICATION_ID_BASE = 0x1c7b0000;

    private final Context mContext;
    private final Handler mHandler;
    private final IActivityTaskManager mAtm = ActivityTaskManager.getService();

    // Everything below is only touched on mHandler.
    private int mUserId = UserHandle.USER_SYSTEM;
    /** package -> entry, in the order they were sent to Backstage. */
    private final Map<String, Entry> mEntries = new LinkedHashMap<>();
    private VirtualDisplay mDisplay;
    private ImageReader mReader;
    private PowerManager.WakeLock mDisplayWakeLock;
    /** Last value written/seen for KEY_PACKAGES, so our own writes don't re-trigger reconcile. */
    private String mLastPackagesValue = "";

    private static final class Entry {
        final String pkg;
        final long startedAt = SystemClock.elapsedRealtime();
        /** Set once a task of this package was seen on the Backstage display. */
        boolean seen;
        int taskId = ActivityTaskManager.INVALID_TASK_ID;

        Entry(String pkg) {
            this.pkg = pkg;
        }
    }

    public IchthysBackstageService(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Called from SystemServer once activities can start. */
    public void onBootPhaseReady() {
        mHandler.post(this::init);
    }

    private void init() {
        mUserId = ActivityManager.getCurrentUser();
        // Backstage never survives a reboot: start from an empty list for every user.
        for (UserInfo user : mContext.getSystemService(UserManager.class).getAliveUsers()) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_PACKAGES, null,
                    user.id);
        }

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_PACKAGES), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                            int userId) {
                        if (userId == mUserId) {
                            reconcileWithSetting();
                        }
                    }
                }, UserHandle.USER_ALL);

        final IntentFilter actions = new IntentFilter();
        actions.addAction(ACTION_BRING_BACK);
        actions.addAction(ACTION_STOP);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String pkg = intent.getStringExtra(EXTRA_PACKAGE);
                if (pkg == null) {
                    return;
                }
                if (ACTION_BRING_BACK.equals(intent.getAction())) {
                    bringBack(pkg);
                } else {
                    stop(pkg);
                }
            }
        }, actions, null, mHandler, Context.RECEIVER_NOT_EXPORTED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Another user's apps must never stay running (or be brought back) under the
                // new user: return everything to the main screen and forget it.
                final int oldUser = mUserId;
                mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        ActivityManager.getCurrentUser());
                clearAll(oldUser);
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        try {
            mAtm.registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskRemoved(int taskId) {
                    mHandler.post(() -> scanDisplay());
                }

                @Override
                public void onTaskDisplayChanged(int taskId, int newDisplayId) {
                    mHandler.post(() -> scanDisplay());
                }
            });
        } catch (RemoteException e) {
            Slog.w(TAG, "registerTaskStackListener failed", e);
        }

        final NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Backstage",
                NotificationManager.IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    // ---- Settings <-> state ----

    private List<String> readPackages() {
        final String value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                KEY_PACKAGES, mUserId);
        final List<String> pkgs = new ArrayList<>();
        if (!TextUtils.isEmpty(value)) {
            for (String p : value.split(",")) {
                p = p.trim();
                if (!p.isEmpty() && !pkgs.contains(p)) {
                    pkgs.add(p);
                }
            }
        }
        return pkgs;
    }

    private void writePackages() {
        final String value = TextUtils.join(",", mEntries.keySet());
        mLastPackagesValue = value;
        Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_PACKAGES,
                value.isEmpty() ? null : value, mUserId);
    }

    private void reconcileWithSetting() {
        final List<String> wanted = readPackages();
        if (TextUtils.join(",", wanted).equals(mLastPackagesValue)) {
            return; // our own write
        }
        // Removed from the list: bring back to the main screen.
        for (String pkg : new ArrayList<>(mEntries.keySet())) {
            if (!wanted.contains(pkg)) {
                bringBack(pkg);
            }
        }
        for (String pkg : wanted) {
            if (!mEntries.containsKey(pkg)) {
                if (mEntries.size() >= MAX_APPS) {
                    Slog.w(TAG, "Backstage is full (" + MAX_APPS + "), ignoring " + pkg);
                    continue;
                }
                sendToBackstage(pkg);
            }
        }
        writePackages();
    }

    // ---- Moving apps ----

    private void sendToBackstage(String pkg) {
        final PackageManager pm = mContext.createContextAsUser(UserHandle.of(mUserId), 0)
                .getPackageManager();
        final Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Slog.w(TAG, pkg + " has no launcher activity, not sending to Backstage");
            return;
        }
        if (!ensureDisplay()) {
            return;
        }
        final Entry entry = new Entry(pkg);
        mEntries.put(pkg, entry);

        // Running already: move its task. Otherwise start it directly on the Backstage display.
        final ActivityManager.RunningTaskInfo task = findTask(pkg, Display.DEFAULT_DISPLAY);
        boolean moved = false;
        if (task != null) {
            try {
                mAtm.moveRootTaskToDisplay(task.taskId, mDisplay.getDisplay().getDisplayId());
                entry.taskId = task.taskId;
                moved = true;
            } catch (RemoteException | RuntimeException e) {
                // Not a root task (e.g. inside split screen) or refused: launch instead.
                Slog.w(TAG, "moving task " + task.taskId + " of " + pkg + " failed: " + e);
            }
        }
        if (!moved) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(mDisplay.getDisplay().getDisplayId());
            try {
                mContext.startActivityAsUser(launch, options.toBundle(), UserHandle.of(mUserId));
            } catch (RuntimeException e) {
                Slog.w(TAG, "launching " + pkg + " in Backstage failed", e);
                mEntries.remove(pkg);
                releaseDisplayIfEmpty();
                return;
            }
        }
        Slog.i(TAG, pkg + " sent to Backstage (" + (moved ? "moved" : "launched") + ")");
        postNotification(entry);
        mHandler.postDelayed(this::scanDisplay, LAUNCH_GRACE_MS);
        scheduleAutoStop(entry);
    }

    private void bringBack(String pkg) {
        final Entry entry = mEntries.remove(pkg);
        if (entry == null) {
            return;
        }
        cancelNotification(pkg);
        if (mDisplay != null) {
            final ActivityManager.RunningTaskInfo task =
                    findTask(pkg, mDisplay.getDisplay().getDisplayId());
            if (task != null) {
                try {
                    mAtm.moveRootTaskToDisplay(task.taskId, Display.DEFAULT_DISPLAY);
                } catch (RemoteException | RuntimeException e) {
                    Slog.w(TAG, "bringing back " + pkg + " failed: " + e);
                }
            }
        }
        final StatusBarManager sbm = mContext.getSystemService(StatusBarManager.class);
        if (sbm != null) {
            sbm.collapsePanels();
        }
        Slog.i(TAG, pkg + " brought back from Backstage");
        writePackages();
        releaseDisplayIfEmpty();
    }

    private void stop(String pkg) {
        final Entry entry = mEntries.remove(pkg);
        if (entry == null) {
            return;
        }
        cancelNotification(pkg);
        if (mDisplay != null) {
            final ActivityManager.RunningTaskInfo task =
                    findTask(pkg, mDisplay.getDisplay().getDisplayId());
            if (task != null) {
                try {
                    mAtm.removeTask(task.taskId);
                } catch (RemoteException | RuntimeException e) {
                    Slog.w(TAG, "stopping " + pkg + " failed: " + e);
                }
            }
        }
        Slog.i(TAG, pkg + " stopped in Backstage");
        writePackages();
        releaseDisplayIfEmpty();
    }

    /** Returns everything to the main screen and forgets it (user switch). */
    private void clearAll(int userId) {
        for (String pkg : new ArrayList<>(mEntries.keySet())) {
            cancelNotification(pkg);
        }
        mEntries.clear();
        mLastPackagesValue = "";
        Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_PACKAGES, null, userId);
        releaseDisplayIfEmpty();
    }

    /**
     * Drops entries whose app is no longer on the Backstage display: the app finished, was
     * removed from recents, or was moved elsewhere by the user.
     */
    private void scanDisplay() {
        if (mDisplay == null || mEntries.isEmpty()) {
            return;
        }
        final int displayId = mDisplay.getDisplay().getDisplayId();
        boolean changed = false;
        for (Entry entry : new ArrayList<>(mEntries.values())) {
            final ActivityManager.RunningTaskInfo task = findTask(entry.pkg, displayId);
            if (task != null) {
                entry.seen = true;
                entry.taskId = task.taskId;
            } else if (entry.seen
                    || SystemClock.elapsedRealtime() - entry.startedAt > LAUNCH_GRACE_MS) {
                Slog.i(TAG, entry.pkg + " left Backstage");
                mEntries.remove(entry.pkg);
                cancelNotification(entry.pkg);
                changed = true;
            }
        }
        if (changed) {
            writePackages();
            releaseDisplayIfEmpty();
        }
    }

    private ActivityManager.RunningTaskInfo findTask(String pkg, int displayId) {
        try {
            final List<ActivityManager.RunningTaskInfo> tasks =
                    mAtm.getTasks(Integer.MAX_VALUE, false, false, displayId);
            for (ActivityManager.RunningTaskInfo info : tasks) {
                if (info.userId != mUserId || info.displayId != displayId) {
                    continue;
                }
                final ComponentName base = info.baseActivity != null ? info.baseActivity
                        : info.topActivity;
                if (base != null && pkg.equals(base.getPackageName())) {
                    return info;
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getTasks failed", e);
        }
        return null;
    }

    private void scheduleAutoStop(Entry entry) {
        final int hours = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                KEY_MAX_HOURS, 0, mUserId);
        if (hours <= 0) {
            return;
        }
        mHandler.postDelayed(() -> {
            if (mEntries.get(entry.pkg) == entry) {
                Slog.i(TAG, entry.pkg + " reached the " + hours + " h Backstage limit");
                stop(entry.pkg);
            }
        }, hours * HOUR_MS);
    }

    // ---- The display ----

    private boolean ensureDisplay() {
        if (mDisplay != null) {
            return true;
        }
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        final DisplayMetrics metrics = new DisplayMetrics();
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
        // Same size and density as the real screen, so moving an app only changes the display
        // id, not its layout configuration.
        final int width = Math.min(metrics.widthPixels, metrics.heightPixels);
        final int height = Math.max(metrics.widthPixels, metrics.heightPixels);

        // GPU-only buffers, drained as soon as they arrive: nothing ever reads the pixels, and a
        // stalled consumer would block the app's rendering.
        mReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        mReader.setOnImageAvailableListener(reader -> {
            final Image image = reader.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }, mHandler);

        final int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED;
        final VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(
                "IchthysBackstage", width, height, metrics.densityDpi)
                .setFlags(flags)
                .setSurface(mReader.getSurface())
                .setRequestedRefreshRate(REFRESH_RATE)
                .build();
        try {
            mDisplay = dm.createVirtualDisplay(config);
        } catch (RuntimeException e) {
            Slog.e(TAG, "creating the Backstage display failed", e);
            mDisplay = null;
        }
        if (mDisplay == null) {
            mReader.close();
            mReader = null;
            return false;
        }
        // Keep only the Backstage display group awake. Keyguard and screen-off follow the
        // default display group, so the phone still locks normally.
        final PowerManager power = mContext.getSystemService(PowerManager.class);
        mDisplayWakeLock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG,
                mDisplay.getDisplay().getDisplayId());
        mDisplayWakeLock.setReferenceCounted(false);
        mDisplayWakeLock.acquire();
        Slog.i(TAG, "Backstage display " + mDisplay.getDisplay().getDisplayId() + " created");
        return true;
    }

    private void releaseDisplayIfEmpty() {
        if (!mEntries.isEmpty() || mDisplay == null) {
            return;
        }
        if (mDisplayWakeLock != null) {
            mDisplayWakeLock.release();
            mDisplayWakeLock = null;
        }
        // Without DESTROY_CONTENT_ON_REMOVAL, anything still on the display moves to the main
        // screen instead of being killed.
        mDisplay.release();
        mDisplay = null;
        if (mReader != null) {
            mReader.close();
            mReader = null;
        }
        Slog.i(TAG, "Backstage display released");
    }

    // ---- Notification ----

    private void postNotification(Entry entry) {
        final PackageManager pm = mContext.createContextAsUser(UserHandle.of(mUserId), 0)
                .getPackageManager();
        CharSequence label = entry.pkg;
        try {
            final ApplicationInfo ai = pm.getApplicationInfo(entry.pkg, 0);
            label = pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        final Notification.Action bringBack = new Notification.Action.Builder(null, "Bring back",
                actionIntent(ACTION_BRING_BACK, entry.pkg))
                // Only after unlocking: Backstage apps must not be reachable from the lockscreen.
                .setAuthenticationRequired(true)
                .build();
        final Notification.Action stop = new Notification.Action.Builder(null, "Stop",
                actionIntent(ACTION_STOP, entry.pkg))
                .setAuthenticationRequired(true)
                .build();
        final Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(com.android.internal.R.drawable.ic_settings_24dp)
                .setContentTitle(label + " is running in Backstage")
                .setContentText("It keeps running as if it were open. Bring it back to use it.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(bringBack)
                .addAction(stop)
                .build();
        mContext.getSystemService(NotificationManager.class).notifyAsUser(TAG,
                NOTIFICATION_ID_BASE + (entry.pkg.hashCode() & 0xffff), notification,
                UserHandle.of(mUserId));
    }

    private void cancelNotification(String pkg) {
        mContext.getSystemService(NotificationManager.class).cancelAsUser(TAG,
                NOTIFICATION_ID_BASE + (pkg.hashCode() & 0xffff), UserHandle.of(mUserId));
    }

    private PendingIntent actionIntent(String action, String pkg) {
        final Intent intent = new Intent(action)
                .setPackage(mContext.getPackageName())
                .putExtra(EXTRA_PACKAGE, pkg);
        return PendingIntent.getBroadcast(mContext, (action + pkg).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
