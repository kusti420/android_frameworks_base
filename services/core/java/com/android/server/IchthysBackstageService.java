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
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
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
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.ArraySet;
import android.util.SparseIntArray;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * IchthysOS Backstage: keeps user-chosen apps "in the foreground" on a private, never-displayed
 * virtual display, so apps that stop working once they leave the screen (uploads, syncs, timers
 * tied to the activity lifecycle) keep running while the phone is used normally or locked.
 * (kusti420/ichthysos-tracker#38)
 *
 * <p>An app in Backstage stays in Backstage until it is stopped or taken out of the list: it
 * moves to the main screen whenever the user opens it (its icon, recents, its notification) and
 * goes back to a hidden display when the user leaves it (home, recents, another app) or the main
 * screen turns off; the app that was on screen at screen-off comes back on unlock. Screen-off and
 * unlock come from the default display's own state and the keyguard, not from the global
 * SCREEN_OFF / USER_PRESENT broadcasts: a hidden display keeps the device "interactive", so those
 * never come while an app is hidden.
 *
 * <p>Mechanism: each hidden app gets its own virtual display (a "stage") owned by system_server,
 * rendering into an {@link ImageReader} that is drained and never shown, so every hidden app is
 * the top, RESUMED app of its display (one shared display left only the top one resumed). Flags:
 * TRUSTED + OWN_DISPLAY_GROUP (its own power group, so the main screen's sleep doesn't stop it) +
 * ALWAYS_UNLOCKED (keyguard doesn't stop its activities; nothing is ever drawn anywhere, so
 * nothing leaks onto the lockscreen) + OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED (never takes input
 * focus from the real screen). It is not PUBLIC or PRESENTATION, so other apps can't place content
 * on it or see it in display lists, and it isn't mirrored or captured anywhere. A display-bound
 * screen wake lock keeps only that display group awake. Keyguard follows the default display
 * group, so locking works as usual. A stage exists only while its app is actually hidden: it is
 * made when the app is hidden and released when the app comes back to the main screen, goes away
 * or leaves Backstage.
 *
 * <p>Battery: while at least one app is hidden, its awake display group keeps the device in the
 * global "awake" state: the CPU doesn't suspend, Doze and idle maintenance don't start, other apps
 * get no SCREEN_OFF, and battery stats count the time as screen-on. That is the price of keeping
 * an app running in the foreground with the screen off. With no app hidden (all on the main
 * screen, or none in Backstage) nothing is held. Hiding the first app at screen-off adds an
 * awake display group right after the main one went to sleep, so the device briefly reports
 * screen off and then on again (other apps see SCREEN_OFF followed by SCREEN_ON).
 *
 * <p>Control: Settings.Secure {@link #KEY_PACKAGES} (comma-separated packages, per user) is the
 * set of apps in Backstage. The crDroidSettings page and the Backstage tile write it; this service
 * reconciles. While an app is hidden, an ongoing notification offers "Open" and "Stop" (removes
 * the task and takes the app out of Backstage). The list is cleared at boot and on user switch.
 * Releasing a hidden display (its app is back on the main screen or gone, or on any failure)
 * moves whatever is still on it back to the main screen.
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
    private final Runnable mRefresh = this::refresh;

    // Everything below is only touched on mHandler.
    private int mUserId = UserHandle.USER_SYSTEM;
    /** package -> entry, in the order they were sent to Backstage. */
    private final Map<String, Entry> mEntries = new LinkedHashMap<>();
    /** Last value written/seen for KEY_PACKAGES, so our own writes don't re-trigger reconcile. */
    private String mLastPackagesValue = "";
    /** The Backstage app that was on screen when the screen went off; it returns on unlock. */
    private String mOnScreenAtLock;
    /** The main screen's top task right after that screen-off; unlocking into another skips it. */
    private int mTopAfterLock = ActivityTaskManager.INVALID_TASK_ID;
    /** Whether the default display is on (not off or dozing). */
    private boolean mDefaultScreenOn = true;

    /** A hidden display and what keeps it alive. */
    private static final class Stage {
        final VirtualDisplay display;
        final ImageReader reader;
        final PowerManager.WakeLock wakeLock;

        Stage(VirtualDisplay display, ImageReader reader, PowerManager.WakeLock wakeLock) {
            this.display = display;
            this.reader = reader;
            this.wakeLock = wakeLock;
        }

        int id() {
            return display.getDisplay().getDisplayId();
        }

        void release() {
            wakeLock.release();
            // Without DESTROY_CONTENT_ON_REMOVAL, anything still on the display moves to the
            // main screen instead of being killed.
            display.release();
            reader.close();
        }
    }

    private static final class Entry {
        final String pkg;
        final long startedAt = SystemClock.elapsedRealtime();
        /** When we last moved its task (either way); events right after are our own. */
        long movedAt = SystemClock.elapsedRealtime();
        /** Whether the notification is up (the app is on its hidden display). */
        boolean notified;
        /** Its hidden display, only while it is (being) hidden. */
        Stage stage;
        /** Its uid, once hidden (for the activity-start interceptor). */
        int uid = Process.INVALID_UID;

        Entry(String pkg) {
            this.pkg = pkg;
        }

        boolean settling() {
            return SystemClock.elapsedRealtime() - movedAt < MOVE_SETTLE_MS;
        }
    }

    // IchthysOS: what the activity-start interceptor and ActivityTaskManagerService read, under
    // the window manager's lock: the uids of the apps on a hidden display -> that display, and
    // those apps' packages. Written on mHandler (publishHidden), replaced whole, never changed.
    private static volatile SparseIntArray sHiddenUidStages = new SparseIntArray();
    private static volatile ArraySet<String> sHiddenPackages = new ArraySet<>();
    /** package -> when the launcher, SystemUI or a notification last started it (elapsed ms). */
    private final ConcurrentHashMap<String, Long> mUserOpenedAt = new ConcurrentHashMap<>();
    /** package -> when a hidden app last started an activity of it itself (elapsed ms). */
    private final ConcurrentHashMap<String, Long> mSelfStartedAt = new ConcurrentHashMap<>();
    /** How recent a start must be to explain a task coming forward. */
    private static final long START_EXPLAINS_MS = 3000;

    /** Whether {@code uid} is an app in Backstage on its hidden display right now. */
    public static boolean isHiddenUid(int uid) {
        return sHiddenUidStages.indexOfKey(uid) >= 0;
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
        LocalServices.getService(com.android.server.wm.ActivityTaskManagerInternal.class)
                .registerActivityStartInterceptor(
                        com.android.server.wm.ActivityInterceptorCallback
                                .ICHTHYS_BACKSTAGE_ORDERED_ID,
                        this::interceptStart);
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
                    if (stageId(entry) == Display.INVALID_DISPLAY
                            && findTask(entry.pkg, Display.DEFAULT_DISPLAY) == null) {
                        launchOnMainScreen(entry); // it is gone: "Open" starts it again
                    } else {
                        show(entry);
                    }
                } else {
                    stop(entry.pkg);
                }
            }
        }, actions, null, mHandler, Context.RECEIVER_NOT_EXPORTED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Another user's apps must never stay running (or be brought back) under the
                // new user: return everything to the main screen and forget it. Cleared while
                // mUserId is still the old user, so its notifications really go away.
                final int oldUser = mUserId;
                clearAll(oldUser);
                mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        ActivityManager.getCurrentUser());
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        // An uninstalled app leaves Backstage (it would hold a slot and couldn't be unticked).
        final IntentFilter removed = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        removed.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        || getSendingUserId() != mUserId || intent.getData() == null) {
                    return;
                }
                final String pkg = intent.getData().getSchemeSpecificPart();
                if (pkg != null && mEntries.containsKey(pkg)) {
                    remove(pkg, false);
                    writePackages();
                }
            }
        }, UserHandle.ALL, removed, null, mHandler);

        // Locking hides the app on screen too (that is when apps stop most), and unlocking
        // brings that one back where it was. Driven by the default display's state and the
        // keyguard: ACTION_SCREEN_OFF / USER_PRESENT follow the global interactive state, which
        // a hidden display keeps "awake", so they never come while an app is hidden.
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        mDefaultScreenOn = isDefaultScreenOn();
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    onDefaultDisplayChanged();
                }
            }
        }, mHandler, DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                | DisplayManager.EVENT_TYPE_DISPLAY_STATE);
        final KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
        if (km != null) {
            km.addKeyguardLockedStateListener(mHandler::post, locked -> {
                if (!locked && mDefaultScreenOn) {
                    onUnlocked();
                }
            });
        }

        try {
            mAtm.registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    mHandler.post(() -> closeForeignTasks());
                }

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
                        closeForeignTasks();
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
        final Entry entry = new Entry(pkg);
        mEntries.put(pkg, entry);
        if (findTask(pkg, Display.DEFAULT_DISPLAY) != null) {
            hide(entry);
        } else {
            // Not on the main screen: start it on a hidden display of its own.
            if (!ensureStage(entry, false)) {
                mEntries.remove(pkg);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(stageId(entry));
            try {
                mContext.startActivityAsUser(launch, options.toBundle(), UserHandle.of(mUserId));
                entry.movedAt = SystemClock.elapsedRealtime();
                postNotification(entry);
            } catch (RuntimeException e) {
                Slog.w(TAG, "launching " + pkg + " in Backstage failed", e);
                releaseStage(entry);
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
        final ActivityManager.RunningTaskInfo task = findTask(pkg, stageId(entry));
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
        if (kill) {
            // IchthysOS: Stop and the hour limit end the app, not only its task: its foreground
            // services and wake locks too, wherever its task was (the main screen included).
            final ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am != null) {
                am.forceStopPackageAsUser(pkg, mUserId);
            }
        }
        final Stage stage = entry.stage;
        entry.stage = null;
        if (stage != null) {
            if (kill && task != null) {
                // Let the task finish where it is instead of flashing on the main screen.
                mHandler.postDelayed(stage::release, MOVE_SETTLE_MS);
            } else {
                stage.release();
            }
        }
        Slog.i(TAG, pkg + (kill ? " stopped" : " taken out of Backstage"));
    }

    private void stop(String pkg) {
        remove(pkg, true);
        writePackages();
    }

    /** Returns everything to the main screen and forgets it (user switch). */
    private void clearAll(int userId) {
        for (Entry entry : mEntries.values()) {
            cancelNotification(entry);
            releaseStage(entry);
        }
        mEntries.clear();
        mLastPackagesValue = "";
        mOnScreenAtLock = null;
        mTopAfterLock = ActivityTaskManager.INVALID_TASK_ID;
        Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_PACKAGES, null, userId);
    }

    /**
     * IchthysOS: every activity start, under the window manager's lock (read only the volatile
     * snapshots). What an app in Backstage starts itself, of its own or of another app, stays on
     * its hidden display: it can't put itself, or anything else, over what the user is doing.
     * Starts of a Backstage app by anyone else (the launcher, SystemUI, a notification sent by
     * SystemUI) are noted, so onOpened() can tell the user asked for it.
     */
    private com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptResult
            interceptStart(com.android.server.wm.ActivityInterceptorCallback
                    .ActivityInterceptorInfo info) {
        final SparseIntArray hidden = sHiddenUidStages;
        if (hidden.size() == 0) {
            return null;
        }
        final String target = info.getActivityInfo() != null
                ? info.getActivityInfo().packageName
                : info.getIntent().getComponent() != null
                        ? info.getIntent().getComponent().getPackageName() : null;
        int stage = hidden.get(info.getRealCallingUid(), Display.INVALID_DISPLAY);
        // A PendingIntent of the hidden app sent by the system (an alarm, a job) is its own
        // start too; one sent by SystemUI (a notification tapped) is the user's.
        if (stage == Display.INVALID_DISPLAY && info.getRealCallingUid() == Process.SYSTEM_UID) {
            stage = hidden.get(info.getCallingUid(), Display.INVALID_DISPLAY);
        }
        if (stage == Display.INVALID_DISPLAY) {
            if (target != null && sHiddenPackages.contains(target)) {
                mUserOpenedAt.put(target, SystemClock.elapsedRealtime());
            }
            return null;
        }
        if (target != null) {
            mSelfStartedAt.put(target, SystemClock.elapsedRealtime());
        }
        final ActivityOptions options = info.getCheckedOptions() != null
                ? info.getCheckedOptions() : ActivityOptions.makeBasic();
        if (options.getLaunchDisplayId() == stage) {
            return null;
        }
        options.setLaunchDisplayId(stage);
        return new com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptResult(
                info.getIntent(), options);
    }

    /** Publishes the hidden apps for interceptStart() and isHiddenUid(). mHandler only. */
    private void publishHidden() {
        final SparseIntArray uids = new SparseIntArray();
        final ArraySet<String> pkgs = new ArraySet<>();
        for (Entry entry : mEntries.values()) {
            if (entry.stage != null && entry.uid != Process.INVALID_UID) {
                uids.put(entry.uid, entry.stage.id());
                pkgs.add(entry.pkg);
            }
        }
        sHiddenUidStages = uids;
        sHiddenPackages = pkgs;
    }

    private static boolean recent(Long at) {
        return at != null && SystemClock.elapsedRealtime() - at < START_EXPLAINS_MS;
    }

    // ---- Moving between the screens ----

    /** Main screen -> its hidden display. */
    private void hide(Entry entry) {
        final ActivityManager.RunningTaskInfo task = findTask(entry.pkg, Display.DEFAULT_DISPLAY);
        // A display in the app's current orientation, so a landscape app isn't reconfigured
        // (and, without configChanges, recreated) by the move.
        if (task == null || !ensureStage(entry, task.configuration.orientation
                == Configuration.ORIENTATION_LANDSCAPE)) {
            return;
        }
        try {
            mAtm.moveRootTaskToDisplay(task.taskId, stageId(entry));
        } catch (RemoteException | RuntimeException e) {
            // Not a root task (e.g. inside split screen) or refused: leave it where it is.
            Slog.w(TAG, "hiding " + entry.pkg + " failed: " + e);
            if (findTask(entry.pkg, stageId(entry)) == null) {
                releaseStage(entry);
            }
            return;
        }
        entry.movedAt = SystemClock.elapsedRealtime();
        entry.uid = task.effectiveUid;
        publishHidden();
        postNotification(entry);
        Slog.i(TAG, entry.pkg + " went to Backstage");
    }

    /** Hidden display -> main screen, on top. */
    private void show(Entry entry) {
        cancelNotification(entry);
        final ActivityManager.RunningTaskInfo task = findTask(entry.pkg, stageId(entry));
        if (task != null) {
            try {
                mAtm.moveRootTaskToDisplay(task.taskId, Display.DEFAULT_DISPLAY);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "bringing back " + entry.pkg + " failed: " + e);
                return;
            }
            entry.movedAt = SystemClock.elapsedRealtime();
            // Nothing of it is hidden any more (anything else still there comes along).
            releaseStage(entry);
            final StatusBarManager sbm = mContext.getSystemService(StatusBarManager.class);
            if (sbm != null) {
                sbm.collapsePanels();
            }
        }
        Slog.i(TAG, entry.pkg + " is on screen (still in Backstage)");
    }

    /** The app is gone (died, finished): start it again on the main screen. */
    private void launchOnMainScreen(Entry entry) {
        final Intent launch = mContext.createContextAsUser(UserHandle.of(mUserId), 0)
                .getPackageManager().getLaunchIntentForPackage(entry.pkg);
        if (launch == null) {
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(launch, UserHandle.of(mUserId));
            entry.movedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) {
            Slog.w(TAG, "starting " + entry.pkg + " failed", e);
        }
        final StatusBarManager sbm = mContext.getSystemService(StatusBarManager.class);
        if (sbm != null) {
            sbm.collapsePanels();
        }
    }

    /**
     * A task came to the front, or a launch was redirected into an existing task. If the user
     * opened a Backstage app, put it on the main screen.
     */
    private void onOpened(ActivityManager.RunningTaskInfo task) {
        if (mEntries.isEmpty() || task == null || task.userId != mUserId) {
            return;
        }
        final ComponentName base = task.baseActivity != null ? task.baseActivity
                : task.topActivity;
        final Entry entry = base != null ? mEntries.get(base.getPackageName()) : null;
        if (entry == null || entry.settling()) {
            return; // not ours, or the events of our own move
        }
        if (task.displayId == Display.DEFAULT_DISPLAY) {
            final ActivityManager.RunningTaskInfo hidden = findTask(entry.pkg, stageId(entry));
            // The launcher started a fresh copy on the main screen: show the Backstage one
            // instead and drop the copy. A deep link or notification that opened a new task of
            // the app is what the user wants to see: it stays on top, the hidden one stays.
            if (hidden != null && hidden.taskId != task.taskId && isPlainLaunch(task)) {
                show(entry);
                try {
                    mAtm.removeTask(task.taskId);
                } catch (RemoteException | RuntimeException e) {
                    Slog.w(TAG, "removing the duplicate of " + entry.pkg + " failed: " + e);
                }
            }
        } else if (task.displayId == stageId(entry)) {
            // Brought to the front on its hidden display (launcher, recents, a notification of
            // the app): the user wants to see it. With the screen off or locked that can only be
            // the app itself (restarting its own activity): moving it then would put it behind
            // the keyguard, where it stops. IchthysOS: and only when the user did it, through
            // the launcher or SystemUI (the shade, recents): the app restarting its own activity
            // or moving its task forward must not put itself over what the user is doing.
            // IchthysOS: by who started it, not only by who has focus: a start the app made
            // itself (interceptStart) keeps it hidden even with the launcher focused; one by the
            // launcher or SystemUI brings it back. Recents moves the task without a start, so
            // a move with neither and SystemUI or the launcher focused counts as the user's.
            final boolean asked = recent(mUserOpenedAt.get(entry.pkg));
            final boolean self = recent(mSelfStartedAt.get(entry.pkg));
            if (isUserPresent() && isSystemSurfaceFocused() && (asked || !self)) {
                show(entry);
            } else if (isUserPresent()) {
                Slog.i(TAG, entry.pkg + " came forward by itself; it stays in Backstage");
            }
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

    private void onDefaultDisplayChanged() {
        final boolean on = isDefaultScreenOn();
        if (on == mDefaultScreenOn) {
            return;
        }
        mDefaultScreenOn = on;
        if (!on) {
            onScreenOff();
        } else if (!isKeyguardLocked()) {
            onUnlocked(); // no keyguard, or it hadn't locked yet
        }
    }

    private boolean isDefaultScreenOn() {
        final Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        return display == null || Display.isOnState(display.getState());
    }

    private boolean isKeyguardLocked() {
        final KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    /** The main screen is on and unlocked. */
    /** Whether SystemUI (shade, recents) or the home app has input focus right now. */
    private boolean isSystemSurfaceFocused() {
        final com.android.server.wm.WindowManagerInternal wm =
                LocalServices.getService(com.android.server.wm.WindowManagerInternal.class);
        if (wm == null) {
            return false;  // can't tell: it stays hidden (the user can bring it back)
        }
        final PackageManager pm = mContext.getPackageManager();
        try {
            // On the top focused display only: SystemUI focused on a cast or desktop display
            // isn't the user asking for this app.
            if (wm.isUidFocusedOnTopFocusedDisplay(
                    pm.getPackageUidAsUser("com.android.systemui", UserHandle.USER_SYSTEM))) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // no SystemUI package: fall through to home
        }
        final ResolveInfo home = pm.resolveActivityAsUser(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY, mUserId);
        if (home != null && home.activityInfo != null) {
            return wm.isUidFocusedOnTopFocusedDisplay(home.activityInfo.applicationInfo.uid);
        }
        return false;
    }

    private boolean isUserPresent() {
        return mDefaultScreenOn && !isKeyguardLocked();
    }

    private void onScreenOff() {
        if (mEntries.isEmpty()) {
            return;
        }
        final ActivityManager.RunningTaskInfo top = topTask();
        final ComponentName base = top == null ? null
                : top.baseActivity != null ? top.baseActivity : top.topActivity;
        // Only replaced by another Backstage app: a second screen-off with just the lock screen
        // up must not forget the one to bring back.
        if (base != null && mEntries.containsKey(base.getPackageName())) {
            mOnScreenAtLock = base.getPackageName();
        }
        mHandler.removeCallbacks(mCheckLeft);
        hideLeftApps(true);
        if (mOnScreenAtLock != null) {
            final ActivityManager.RunningTaskInfo after = topTask();
            mTopAfterLock = after != null ? after.taskId : ActivityTaskManager.INVALID_TASK_ID;
        }
    }

    private void onUnlocked() {
        final Entry entry = mOnScreenAtLock != null ? mEntries.get(mOnScreenAtLock) : null;
        final int topAfterLock = mTopAfterLock;
        mOnScreenAtLock = null;
        mTopAfterLock = ActivityTaskManager.INVALID_TASK_ID;
        if (entry == null) {
            return;
        }
        // Back where it was, but not over what the user unlocked into (a notification's app,
        // the camera): only if the main screen still shows what was under it, or home.
        final ActivityManager.RunningTaskInfo top = topTask();
        if (top != null && top.taskId != topAfterLock
                && top.topActivityType != WindowConfiguration.ACTIVITY_TYPE_HOME) {
            return;
        }
        show(entry);
    }

    /**
     * Keeps the notifications and hidden displays in step with where each app is, after any
     * task change: a display whose app is no longer on it (it died, finished, or came back to
     * the main screen) is released.
     */
    private void refresh() {
        if (mEntries.isEmpty()) {
            return;
        }
        boolean settling = false;
        for (Entry entry : mEntries.values()) {
            final boolean hidden = findTask(entry.pkg, stageId(entry)) != null;
            if (hidden && !entry.notified) {
                postNotification(entry);
            } else if (!hidden && entry.settling()) {
                settling = true; // look again once our own move is done
            } else if (!hidden) {
                if (entry.notified) {
                    cancelNotification(entry);
                }
                releaseStage(entry);
            }
        }
        mHandler.removeCallbacks(mRefresh);
        if (settling) {
            mHandler.postDelayed(mRefresh, MOVE_SETTLE_MS);
        }
        closeForeignTasks();
    }

    /**
     * Something a hidden app started in a new task lands on the app's hidden display
     * (interceptStart keeps it there), covering the app. The user can't use a hidden app, so it
     * started it by itself: it must not come over what the user is doing, and it would stop the
     * app on its display. It is closed. IchthysOS: it used to move to the main screen, on top.
     */
    private void closeForeignTasks() {
        for (Entry entry : mEntries.values()) {
            final int displayId = stageId(entry);
            if (displayId == Display.INVALID_DISPLAY) {
                continue;
            }
            try {
                for (ActivityManager.RunningTaskInfo info
                        : mAtm.getTasks(Integer.MAX_VALUE, false, false, displayId)) {
                    final ComponentName base = info.baseActivity != null ? info.baseActivity
                            : info.topActivity;
                    if (info.displayId == displayId && base != null
                            && !entry.pkg.equals(base.getPackageName())) {
                        Slog.i(TAG, entry.pkg + " started " + base.getPackageName()
                                + " in Backstage; closed");
                        mAtm.removeTask(info.taskId);
                    }
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "closing a task on " + entry.pkg + "'s hidden display failed: " + e);
            }
        }
    }

    private static int stageId(Entry entry) {
        return entry.stage != null ? entry.stage.id() : Display.INVALID_DISPLAY;
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
        // An elapsed-realtime alarm, not the handler's uptime clock: the hours have to pass
        // while the phone sleeps too (uptime stops then, and the limit came hours late).
        final AlarmManager alarms = mContext.getSystemService(AlarmManager.class);
        final AlarmManager.OnAlarmListener expire = () -> {
            if (mEntries.get(entry.pkg) == entry) {
                Slog.i(TAG, entry.pkg + " reached the " + hours + " h Backstage limit");
                stop(entry.pkg);
            }
        };
        if (alarms == null) {
            mHandler.postDelayed(expire::onAlarm, hours * HOUR_MS);
            return;
        }
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + hours * HOUR_MS,
                "ichthys-backstage-limit", expire, mHandler);
    }

    // ---- The hidden displays ----

    /** Gives [entry] its own hidden display, unless it has one. */
    private boolean ensureStage(Entry entry, boolean landscape) {
        if (entry.stage != null) {
            return true;
        }
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        final DisplayMetrics metrics = new DisplayMetrics();
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
        // Same size and density as the real screen, so moving an app only changes the display
        // id, not its layout configuration.
        final int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        final int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        final int width = landscape ? longSide : shortSide;
        final int height = landscape ? shortSide : longSide;

        // GPU-only buffers, drained as soon as they arrive: nothing ever reads the pixels, and a
        // stalled consumer would block the app's rendering.
        final ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        reader.setOnImageAvailableListener(r -> {
            final Image image = r.acquireLatestImage();
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
                .setSurface(reader.getSurface())
                .setRequestedRefreshRate(REFRESH_RATE)
                .build();
        VirtualDisplay display;
        try {
            display = dm.createVirtualDisplay(config);
        } catch (RuntimeException e) {
            Slog.e(TAG, "creating a Backstage display failed", e);
            display = null;
        }
        if (display == null) {
            reader.close();
            return false;
        }
        // Keep only this display group awake. Keyguard and screen-off follow the default
        // display group, so the phone still locks normally.
        final PowerManager power = mContext.getSystemService(PowerManager.class);
        final PowerManager.WakeLock wakeLock = power.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG,
                display.getDisplay().getDisplayId());
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        entry.stage = new Stage(display, reader, wakeLock);
        Slog.i(TAG, "Backstage display " + entry.stage.id() + " created for " + entry.pkg);
        return true;
    }

    /** Releases [entry]'s hidden display; anything still on it moves to the main screen. */
    private void releaseStage(Entry entry) {
        if (entry.stage == null) {
            return;
        }
        final int id = entry.stage.id();
        entry.stage.release();
        entry.stage = null;
        publishHidden();
        Slog.i(TAG, "Backstage display " + id + " of " + entry.pkg + " released");
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
