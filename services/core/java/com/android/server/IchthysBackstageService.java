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
 * <p>An app in Backstage stays in Backstage until it is stopped or taken out of the list: it
 * moves to the main screen whenever the user opens it (its icon, recents, its notification) and
 * goes back to the hidden display when the user leaves it (home, recents, another app) or turns
 * the screen off; the app that was on screen at screen-off comes back on unlock.
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
 * set of apps in Backstage. The crDroidSettings page and the Backstage tile write it; this service
 * reconciles. While an app is hidden, an ongoing notification offers "Open" and "Stop" (removes
 * the task and takes the app out of Backstage). The list is cleared at boot and on user switch.
 * Releasing the display (when nothing is left, or on any failure) moves remaining tasks back to
 * the main screen.
 */
public final class IchthysBackstageService {
    private static final String TAG = "IchthysBackstage";

    /** Settings.Secure, per user: comma-separated packages the user wants in Backstage. */
    public static final String KEY_PACKAGES = "ichthys_backstage_pkgs";
    /** Settings.Secure, per user: stop an app automatically after this many hours (0 = never). */
    public static final String KEY_MAX_HOURS = "ichthys_backstage_max_hours";

    private static final int MAX_APPS = 3;
    private static final float REFRESH_RATE = 5f;
    private static final long HOUR_MS = 60 * 60 * 1000L;
    /** Task events for this long after one of our own moves are the move, not the user. */
    private static final long MOVE_SETTLE_MS = 1_500;
    /**
     * How long an app may be off screen on the main display before it goes back to Backstage:
     * long enough to glance at recents or a share sheet, short enough not to matter to the app.
     */
    private static final long LEAVE_DELAY_MS = 2_500;

    private static final String CHANNEL_ID = "ichthys_backstage";
    private static final String ACTION_BRING_BACK =
            "com.android.server.ichthys.BACKSTAGE_BRING_BACK";
    private static final String ACTION_STOP = "com.android.server.ichthys.BACKSTAGE_STOP";
    private static final String EXTRA_PACKAGE = "package";
    private static final int NOTIFICATION_ID_BASE = 0x1c7b0000;

    private final Context mContext;
    private final Handler mHandler;
    private final IActivityTaskManager mAtm = ActivityTaskManager.getService();
    private final Runnable mCheckLeft = () -> hideLeftApps(false);

    // Everything below is only touched on mHandler.
    private int mUserId = UserHandle.USER_SYSTEM;
    /** package -> entry, in the order they were sent to Backstage. */
    private final Map<String, Entry> mEntries = new LinkedHashMap<>();
    private VirtualDisplay mDisplay;
    private ImageReader mReader;
    private PowerManager.WakeLock mDisplayWakeLock;
    /** Last value written/seen for KEY_PACKAGES, so our own writes don't re-trigger reconcile. */
    private String mLastPackagesValue = "";
    /** The Backstage app that was on screen when the screen went off; it returns on unlock. */
    private String mOnScreenAtLock;

    private static final class Entry {
        final String pkg;
        final long startedAt = SystemClock.elapsedRealtime();
        /** When we last moved its task (either way); events right after are our own. */
        long movedAt = SystemClock.elapsedRealtime();
        /** Whether the notification is up (the app is on the hidden display). */
        boolean notified;

        Entry(String pkg) {
            this.pkg = pkg;
        }

        boolean settling() {
            return SystemClock.elapsedRealtime() - movedAt < MOVE_SETTLE_MS;
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
                final Entry entry = mEntries.get(intent.getStringExtra(EXTRA_PACKAGE));
                if (entry == null) {
                    return;
                }
                if (ACTION_BRING_BACK.equals(intent.getAction())) {
                    show(entry);
                } else {
                    stop(entry.pkg);
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

        // Locking hides the app on screen too (that is when apps stop most), and unlocking
        // brings that one back where it was.
        final IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    onScreenOff();
                } else {
                    onUnlocked();
                }
            }
        }, UserHandle.ALL, screen, null, mHandler);

        try {
            mAtm.registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskRemoved(int taskId) {
                    mHandler.post(() -> refresh());
                }

                @Override
                public void onTaskDisplayChanged(int taskId, int newDisplayId) {
                    mHandler.post(() -> refresh());
                }

                // Opening a Backstage app (its icon, recents, a notification) brings it to the
                // main screen; anything else coming to the front may mean the user left one.
                @Override
                public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                        boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                    mHandler.post(() -> onOpened(task));
                }

                @Override
                public void onTaskMovedToFront(ActivityManager.RunningTaskInfo task) {
                    mHandler.post(() -> {
                        onOpened(task);
                        scheduleLeftCheck();
                    });
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
        // Taken out of the list: back to the main screen for good.
        for (String pkg : new ArrayList<>(mEntries.keySet())) {
            if (!wanted.contains(pkg)) {
                remove(pkg, false);
            }
        }
        for (String pkg : wanted) {
            if (!mEntries.containsKey(pkg)) {
                if (mEntries.size() >= MAX_APPS) {
                    Slog.w(TAG, "Backstage is full (" + MAX_APPS + "), ignoring " + pkg);
                    continue;
                }
                add(pkg);
            }
        }
        writePackages();
        releaseDisplayIfEmpty();
    }

    // ---- Entries ----

    /** Puts [pkg] in Backstage: its running task goes to the hidden display, or it starts there. */
    private void add(String pkg) {
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
        if (findTask(pkg, Display.DEFAULT_DISPLAY) != null) {
            hide(entry);
        } else if (findTask(pkg, backstageId()) == null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(backstageId());
            try {
                mContext.startActivityAsUser(launch, options.toBundle(), UserHandle.of(mUserId));
                entry.movedAt = SystemClock.elapsedRealtime();
                postNotification(entry);
            } catch (RuntimeException e) {
                Slog.w(TAG, "launching " + pkg + " in Backstage failed", e);
                mEntries.remove(pkg);
                return;
            }
        }
        Slog.i(TAG, pkg + " added to Backstage");
        scheduleAutoStop(entry);
    }

    /**
     * Takes [pkg] out of Backstage. Its task comes back to the main screen, or with [kill] is
     * removed (the notification's Stop).
     */
    private void remove(String pkg, boolean kill) {
        final Entry entry = mEntries.remove(pkg);
        if (entry == null) {
            return;
        }
        cancelNotification(entry);
        final ActivityManager.RunningTaskInfo task = findTask(pkg, backstageId());
        if (task != null) {
            try {
                if (kill) {
                    mAtm.removeTask(task.taskId);
                } else {
                    mAtm.moveRootTaskToDisplay(task.taskId, Display.DEFAULT_DISPLAY);
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "taking " + pkg + " out of Backstage failed: " + e);
            }
        }
        Slog.i(TAG, pkg + (kill ? " stopped" : " taken out of Backstage"));
    }

    private void stop(String pkg) {
        remove(pkg, true);
        writePackages();
        releaseDisplayIfEmpty();
    }

    /** Returns everything to the main screen and forgets it (user switch). */
    private void clearAll(int userId) {
        for (Entry entry : mEntries.values()) {
            cancelNotification(entry);
        }
        mEntries.clear();
        mLastPackagesValue = "";
        mOnScreenAtLock = null;
        Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_PACKAGES, null, userId);
        releaseDisplayIfEmpty();
    }

    // ---- Moving between the screens ----

    /** Main screen -> hidden display. */
    private void hide(Entry entry) {
        final ActivityManager.RunningTaskInfo task = findTask(entry.pkg, Display.DEFAULT_DISPLAY);
        if (task == null || !ensureDisplay()) {
            return;
        }
        try {
            mAtm.moveRootTaskToDisplay(task.taskId, backstageId());
        } catch (RemoteException | RuntimeException e) {
            // Not a root task (e.g. inside split screen) or refused: leave it where it is.
            Slog.w(TAG, "hiding " + entry.pkg + " failed: " + e);
            return;
        }
        entry.movedAt = SystemClock.elapsedRealtime();
        postNotification(entry);
        Slog.i(TAG, entry.pkg + " went to Backstage");
    }

    /** Hidden display -> main screen, on top. */
    private void show(Entry entry) {
        cancelNotification(entry);
        final ActivityManager.RunningTaskInfo task = findTask(entry.pkg, backstageId());
        if (task != null) {
            try {
                mAtm.moveRootTaskToDisplay(task.taskId, Display.DEFAULT_DISPLAY);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "bringing back " + entry.pkg + " failed: " + e);
                return;
            }
            entry.movedAt = SystemClock.elapsedRealtime();
        }
        final StatusBarManager sbm = mContext.getSystemService(StatusBarManager.class);
        if (sbm != null) {
            sbm.collapsePanels();
        }
        Slog.i(TAG, entry.pkg + " is on screen (still in Backstage)");
    }

    /**
     * A task came to the front, or a launch was redirected into an existing task. If the user
     * opened a Backstage app, put it on the main screen.
     */
    private void onOpened(ActivityManager.RunningTaskInfo task) {
        if (mDisplay == null || task == null || task.userId != mUserId) {
            return;
        }
        final ComponentName base = task.baseActivity != null ? task.baseActivity
                : task.topActivity;
        final Entry entry = base != null ? mEntries.get(base.getPackageName()) : null;
        if (entry == null || entry.settling()) {
            return; // not ours, or the events of our own move
        }
        if (task.displayId == Display.DEFAULT_DISPLAY) {
            final ActivityManager.RunningTaskInfo hidden = findTask(entry.pkg, backstageId());
            if (hidden != null && hidden.taskId != task.taskId) {
                // The launcher started a fresh copy on the main screen: show the Backstage one
                // instead, and drop the copy if it is only the plain launcher start.
                show(entry);
                if (isPlainLaunch(task)) {
                    try {
                        mAtm.removeTask(task.taskId);
                    } catch (RemoteException | RuntimeException e) {
                        Slog.w(TAG, "removing the duplicate of " + entry.pkg + " failed: " + e);
                    }
                }
            }
        } else if (task.displayId == backstageId()) {
            // Brought to the front on the hidden display (launcher, recents, a notification of
            // the app): only the user does that, so it is theirs to see.
            show(entry);
        }
    }

    private void scheduleLeftCheck() {
        if (mEntries.isEmpty()) {
            return;
        }
        mHandler.removeCallbacks(mCheckLeft);
        mHandler.postDelayed(mCheckLeft, LEAVE_DELAY_MS);
    }

    /**
     * Sends Backstage apps that are on the main screen but no longer seen there back to the
     * hidden display; with [all], every one of them (the screen went off).
     */
    private void hideLeftApps(boolean all) {
        for (Entry entry : new ArrayList<>(mEntries.values())) {
            final ActivityManager.RunningTaskInfo task =
                    findTask(entry.pkg, Display.DEFAULT_DISPLAY);
            if (task != null && (all || !task.isVisible) && !entry.settling()) {
                hide(entry);
            }
        }
    }

    private void onScreenOff() {
        if (mEntries.isEmpty()) {
            return;
        }
        final ActivityManager.RunningTaskInfo top = topTask();
        final ComponentName base = top == null ? null
                : top.baseActivity != null ? top.baseActivity : top.topActivity;
        mOnScreenAtLock = base != null && mEntries.containsKey(base.getPackageName())
                ? base.getPackageName() : null;
        mHandler.removeCallbacks(mCheckLeft);
        hideLeftApps(true);
    }

    private void onUnlocked() {
        final Entry entry = mOnScreenAtLock != null ? mEntries.get(mOnScreenAtLock) : null;
        mOnScreenAtLock = null;
        if (entry != null) {
            show(entry);
        }
    }

    /** Keeps the notifications in step with where each app is, after any task change. */
    private void refresh() {
        if (mDisplay == null) {
            return;
        }
        for (Entry entry : mEntries.values()) {
            final boolean hidden = findTask(entry.pkg, backstageId()) != null;
            if (hidden && !entry.notified) {
                postNotification(entry);
            } else if (!hidden && entry.notified && !entry.settling()) {
                cancelNotification(entry);
            }
        }
    }

    private int backstageId() {
        return mDisplay != null ? mDisplay.getDisplay().getDisplayId() : Display.INVALID_DISPLAY;
    }

    private ActivityManager.RunningTaskInfo topTask() {
        try {
            final List<ActivityManager.RunningTaskInfo> top =
                    mAtm.getTasks(1, false, false, Display.DEFAULT_DISPLAY);
            return top.isEmpty() ? null : top.get(0);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** A task started by the launcher icon: MAIN + LAUNCHER and nothing else to lose. */
    private static boolean isPlainLaunch(ActivityManager.RunningTaskInfo task) {
        final Intent intent = task.baseIntent;
        return intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.getData() == null
                && task.numActivities <= 1;
    }

    private ActivityManager.RunningTaskInfo findTask(String pkg, int displayId) {
        if (displayId == Display.INVALID_DISPLAY) {
            return null;
        }
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

        final Notification.Action open = new Notification.Action.Builder(null, "Open",
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
                .setContentText("Open it to use it; when you leave it, it keeps running here.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(actionIntent(ACTION_BRING_BACK, entry.pkg))
                .addAction(open)
                .addAction(stop)
                .build();
        mContext.getSystemService(NotificationManager.class).notifyAsUser(TAG,
                NOTIFICATION_ID_BASE + (entry.pkg.hashCode() & 0xffff), notification,
                UserHandle.of(mUserId));
        entry.notified = true;
    }

    private void cancelNotification(Entry entry) {
        mContext.getSystemService(NotificationManager.class).cancelAsUser(TAG,
                NOTIFICATION_ID_BASE + (entry.pkg.hashCode() & 0xffff), UserHandle.of(mUserId));
        entry.notified = false;
    }

    private PendingIntent actionIntent(String action, String pkg) {
        final Intent intent = new Intent(action)
                .setPackage(mContext.getPackageName())
                .putExtra(EXTRA_PACKAGE, pkg);
        return PendingIntent.getBroadcast(mContext, (action + pkg).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
