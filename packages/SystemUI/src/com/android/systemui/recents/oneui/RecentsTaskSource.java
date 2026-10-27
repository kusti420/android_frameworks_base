/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.recents.oneui;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * The task data layer shared by every IchthysOS recents style.
 *
 * <p>This is the same loading/launch/dismiss logic {@link
 * com.android.systemui.recents.LegacyRecentsActivity} uses, pulled out so the styles only have to
 * supply a layout manager and a card. Everything here is pure AOSP — there is no Samsung framework
 * on this device, so labels, icons, colours and snapshots all come from {@link ActivityManager} and
 * {@link ActivityManagerWrapper} exactly as stock recents gets them.
 */
public final class RecentsTaskSource {

    private static final String TAG = "IchthysRecents";

    /** Matches the cap the Classic style uses; more than this never fits on screen anyway. */
    private static final int MAX_TASKS = 20;

    /**
     * IchthysOS: Settings.Secure list (comma separated) of the packages Backstage manages. Their
     * tasks, and any task on a non-default display, survive "Close all", as in Slim recents.
     */
    private static final String BACKSTAGE_PACKAGES = "ichthys_backstage_pkgs";

    /**
     * Single background thread for task snapshots. One thread keeps them arriving in list order —
     * the top cards, which are the ones on screen, resolve first.
     */
    private final ExecutorService mThumbnailExecutor = Executors.newSingleThreadExecutor();

    /**
     * Bumped on every reload so results from a superseded load are discarded. Written on the main
     * thread only; IchthysOS: volatile because the snapshot loop also reads it, to stop a
     * superseded pass early instead of fetching every remaining snapshot first (a Close all queued
     * behind such a pass used to wait for it).
     */
    private volatile int mThumbnailGeneration;

    /** IchthysOS: set by {@link #destroy}; stops a snapshot pass still running for a dead host. */
    private volatile boolean mDestroyed;

    /** One recent task, resolved to the bits a card needs to render. */
    public static final class TaskItem {
        public int taskId;
        public CharSequence label;
        public Drawable icon;
        /** Always opaque; falls back to blue grey when the app declares no primary colour. */
        public int color;
        /** The app's last-frame snapshot, or {@code null} (secure window / snapshot evicted). */
        public Bitmap thumbnail;
        /** IchthysOS: where the task lives; Backstage parks its apps on a virtual display. */
        public int displayId = Display.INVALID_DISPLAY;
        /** IchthysOS: the task's base package, or {@code null}. */
        public String packageName;
    }

    private final Context mContext;
    private final List<TaskItem> mTasks = new ArrayList<>();

    public RecentsTaskSource(Context context) {
        mContext = context;
    }

    /** The currently loaded tasks, index 0 == most recent. */
    public List<TaskItem> getTasks() {
        return mTasks;
    }

    public int size() {
        return mTasks.size();
    }

    public boolean isEmpty() {
        return mTasks.isEmpty();
    }

    public TaskItem get(int position) {
        return (position < 0 || position >= mTasks.size()) ? null : mTasks.get(position);
    }

    /** Re-reads the recent task list. Safe to call on every resume. */
    public void reload() {
        mTasks.clear();
        try {
            List<ActivityManager.RecentTaskInfo> infos =
                    ActivityTaskManager.getInstance().getRecentTasks(
                            MAX_TASKS,
                            ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                            ActivityManager.getCurrentUser());
            PackageManager pm = mContext.getPackageManager();
            for (ActivityManager.RecentTaskInfo info : infos) {
                if (info.taskId < 0 || isExcluded(info)) {
                    continue;
                }
                TaskItem item = toItem(info, pm);
                // Skip tasks that resolve to nothing meaningful so we don't render an empty card.
                if (item.label == null || item.label.toString().trim().isEmpty()) {
                    continue;
                }
                mTasks.add(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load recent tasks", e);
        }
    }

    /** Skip the home/launcher task and SystemUI's own tasks. */
    private boolean isExcluded(ActivityManager.RecentTaskInfo info) {
        ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
        if (cn != null && mContext.getPackageName().equals(cn.getPackageName())) {
            return true;
        }
        return info.baseIntent != null && info.baseIntent.hasCategory(Intent.CATEGORY_HOME);
    }

    private TaskItem toItem(ActivityManager.RecentTaskInfo info, PackageManager pm) {
        TaskItem item = new TaskItem();
        item.taskId = info.taskId;
        item.displayId = info.displayId;
        item.packageName = basePackage(info);
        ComponentName cn = info.topActivity != null ? info.topActivity : info.baseActivity;

        ActivityManager.TaskDescription td = info.taskDescription;
        if (td != null && td.getLabel() != null) {
            item.label = td.getLabel();
        } else if (cn != null) {
            try {
                item.label = pm.getActivityInfo(cn, 0).loadLabel(pm);
            } catch (Exception e) {
                item.label = cn.getPackageName();
            }
        } else {
            item.label = "";
        }

        Bitmap iconBitmap = td != null ? td.getInMemoryIcon() : null;
        if (iconBitmap != null) {
            item.icon = new BitmapDrawable(mContext.getResources(), iconBitmap);
        } else if (cn != null) {
            try {
                item.icon = pm.getActivityIcon(cn);
            } catch (Exception e) {
                item.icon = pm.getDefaultActivityIcon();
            }
        }

        int color = td != null ? td.getPrimaryColor() : 0;
        item.color = (color == 0) ? 0xFF37474F /* blue grey */ : (0xFF000000 | color);

        // Thumbnails are deliberately NOT fetched here — see loadThumbnailsAsync. Each one is a
        // binder round trip returning a full-screen bitmap, and fetching them inline measured 194ms
        // of a 199ms reload on a four-task list (the cap is 20), all of it before recents could
        // draw its first frame.
        item.thumbnail = null;
        return item;
    }

    /**
     * Fetches every loaded task's snapshot off the main thread, reporting each one as it lands so
     * the card can be rebound individually.
     *
     * <p>Cards already have their label, icon and primary colour from {@link #reload()}, so recents
     * renders complete-looking immediately and snapshots fill in behind it, rather than the whole
     * screen waiting on ~50ms of binder per task.
     *
     * @param onReady invoked on the main thread with the current index of each task whose thumbnail
     *     arrived (looked up when it lands, so a dismiss meanwhile cannot misdirect it)
     */
    public void loadThumbnailsAsync(Handler mainHandler, IntConsumer onReady) {
        // Anything already in flight belongs to a previous list; its results must not be applied to
        // this one. Bumping the generation on the main thread and re-checking it there is enough —
        // both the bump and the check happen on the same thread.
        final int generation = ++mThumbnailGeneration;
        final List<TaskItem> snapshot = new ArrayList<>(mTasks);
        mThumbnailExecutor.execute(() -> {
            for (int i = 0; i < snapshot.size(); i++) {
                if (mDestroyed || generation != mThumbnailGeneration) {
                    return;
                }
                final TaskItem item = snapshot.get(i);
                Bitmap bitmap = null;
                try {
                    bitmap = ActivityManagerWrapper.getInstance()
                            .getTaskThumbnail(item.taskId, false).getThumbnail();
                } catch (Exception e) {
                    // A task can die mid-load; leaving the thumbnail null renders the colour card.
                }
                final Bitmap loaded = bitmap;
                mainHandler.post(() -> {
                    if (generation != mThumbnailGeneration) {
                        return; // superseded by a newer reload
                    }
                    item.thumbnail = loaded;
                    // IchthysOS: positions shift when a card is dismissed while snapshots are
                    // still loading, so report where the item is now, not where it was.
                    final int index = mTasks.indexOf(item);
                    if (index >= 0) {
                        onReady.accept(index);
                    }
                });
            }
        });
    }

    /** Brings the task at {@code position} to the front. Returns false if the index was stale. */
    public boolean launchTask(int position) {
        return launchTask(position, ActivityOptions.makeBasic());
    }

    /**
     * Brings the task at {@code position} to the front with a caller-supplied transition.
     *
     * <p>Exists for the double-tap quick switch, which is meant to feel like flipping between two
     * apps rather than like opening one: {@code makeBasic()} plays the full default launch
     * transition, which measured ~350ms on top of recents' own exit animation.
     *
     * @return false if the index was stale
     */
    public boolean launchTask(int position, ActivityOptions options) {
        TaskItem item = get(position);
        if (item == null) {
            return false;
        }
        // IchthysOS: report whether the start actually succeeded (the task may have died since
        // the list was loaded), so the host can refresh instead of closing over nothing.
        return ActivityManagerWrapper.getInstance().startActivityFromRecents(item.taskId, options);
    }

    /** IchthysOS: drops every loaded snapshot, so a backgrounded host does not pin them. */
    public void releaseThumbnails() {
        ++mThumbnailGeneration;
        for (TaskItem item : mTasks) {
            item.thumbnail = null;
        }
    }

    /** Removes the task at {@code position} from both the system and this list. */
    public boolean dismissTask(int position) {
        TaskItem item = get(position);
        if (item == null) {
            return false;
        }
        mTasks.remove(position);
        ActivityManagerWrapper.getInstance().removeTask(item.taskId);
        return true;
    }

    /**
     * Removes every task this list would show ("clear all"), not only the loaded ones.
     *
     * <p>IchthysOS: the loaded list is capped at {@link #MAX_TASKS} and skips tasks without a
     * label, so clearing just that list left older tasks behind. The removals run on the snapshot
     * thread: each one is a binder call.
     *
     * <p>Backstage tasks are kept, as Slim recents keeps them: a task on a display other than the
     * main screen, and a task of a package in the Backstage list. They stay in the loaded list, so
     * the host can keep showing them. Swiping one away still removes it.
     */
    public void dismissAll() {
        final Set<String> backstage = readBackstagePackages();
        mTasks.removeIf(item -> !isBackstage(item.displayId, item.packageName, backstage));
        // Stop a snapshot pass still in flight, so the removals queued below run right away. A
        // host that keeps showing the remaining (Backstage) cards requests their snapshots again.
        ++mThumbnailGeneration;
        mThumbnailExecutor.execute(() -> {
            final List<ActivityManager.RecentTaskInfo> infos;
            try {
                infos = ActivityTaskManager.getInstance().getRecentTasks(
                        ActivityTaskManager.getMaxRecentTasksStatic(),
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                        ActivityManager.getCurrentUser());
            } catch (Exception e) {
                Log.w(TAG, "Failed to read recent tasks for clear all", e);
                return;
            }
            for (ActivityManager.RecentTaskInfo info : infos) {
                if (info.taskId < 0 || isExcluded(info)
                        || isBackstage(info.displayId, basePackage(info), backstage)) {
                    continue;
                }
                try {
                    ActivityManagerWrapper.getInstance().removeTask(info.taskId);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to remove task " + info.taskId, e);
                }
            }
        });
    }

    private Set<String> readBackstagePackages() {
        final Set<String> packages = new HashSet<>();
        try {
            final String list = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    BACKSTAGE_PACKAGES, UserHandle.USER_CURRENT);
            if (list != null) {
                for (String pkg : list.split(",")) {
                    if (!pkg.trim().isEmpty()) {
                        packages.add(pkg.trim());
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read the Backstage list", e);
        }
        return packages;
    }

    private static boolean isBackstage(int displayId, String pkg, Set<String> backstage) {
        return (displayId != Display.DEFAULT_DISPLAY && displayId != Display.INVALID_DISPLAY)
                || (pkg != null && backstage.contains(pkg));
    }

    private static String basePackage(ActivityManager.RecentTaskInfo info) {
        final ComponentName base = info.baseIntent != null ? info.baseIntent.getComponent() : null;
        if (base != null) {
            return base.getPackageName();
        }
        return info.baseActivity != null ? info.baseActivity.getPackageName() : null;
    }

    /**
     * IchthysOS: releases the snapshot thread. Work already queued (a clear all) still runs; a
     * snapshot pass in progress stops at the next task.
     */
    public void destroy() {
        mDestroyed = true;
        ++mThumbnailGeneration;
        mThumbnailExecutor.shutdown();
    }

    /** True when {@code color} is dark enough that white foreground text stays legible on it. */
    public static boolean isDarkColor(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance < 0.6;
    }
}
