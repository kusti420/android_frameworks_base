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

package com.android.systemui.recents;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.res.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A revival of the pre-Quickstep (Android 7.1.1-style) recents/task switcher: a vertical stack of
 * task cards, most-recent nearest the bottom, that you tap to launch or swipe/✕ to dismiss.
 *
 * <p>Launched by {@link LegacyRecentsImpl} in response to the recents affordance.
 */
public class LegacyRecentsActivity extends Activity {

    private static final String TAG = "LegacyRecents";

    /** Traces the double-tap quick-switch decision path. {@code adb shell setprop log.tag.LegacyRecents DEBUG}. */
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MAX_TASKS = 20;

    /**
     * How long after recents is shown a second recents press still counts as the double-tap
     * quick-switch gesture. Mirrors Quickstep's
     * {@code OverviewCommandHelper.TOGGLE_PREVIOUS_TIMEOUT_MS} (5s), which is the window stock uses
     * to decide that a second press belongs with the first; that constant lives in Launcher3 and is
     * not on SystemUI's classpath, so it is matched by value.
     */
    private static final long TOGGLE_PREVIOUS_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    private RecyclerView mRecyclerView;
    private View mEmptyView;
    private final List<TaskItem> mTasks = new ArrayList<>();
    private TaskAdapter mAdapter;

    /**
     * {@link SystemClock#elapsedRealtime()} of the moment recents last came to the front, i.e. of
     * the recents press that showed it. Anchors the double-tap window in {@link #onNewIntent}.
     */
    private long mShownAtElapsedRealtime;

    /**
     * {@link SystemClock#elapsedRealtime()} of the last {@link #onStop}, i.e. of the moment recents
     * stopped being on screen. Compared against {@link #mShownAtElapsedRealtime} to tell "recents is
     * still up, this is the second half of a double tap" from "recents is being brought back".
     *
     * <p>Do not replace this pair with an {@link #isResumed()} check. Delivering a new intent cycles
     * the activity down through {@code onPause} first and resumes it afterwards, so
     * {@code isResumed()} is false inside {@link #onNewIntent} in <em>both</em> cases and cannot
     * separate them — the same trap that kept this feature from ever firing in
     * {@link com.android.systemui.recents.oneui.OneUiRecentsActivity}. Two timestamps can, and —
     * unlike a flag set from {@code onStart}/{@code onRestart} — the comparison does not depend on
     * whether those callbacks run before or after {@code onNewIntent} when the activity is
     * re-fronted, only on {@code onResume} running after it, which it always does.
     */
    private long mStoppedAtElapsedRealtime;

    /** One recent task, resolved to the bits we need to render a card. */
    private static final class TaskItem {
        int taskId;
        CharSequence label;
        Drawable icon;
        int color;
        Bitmap thumbnail;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Draw our own (transparent) system bar backgrounds. Without this the decor puts an opaque
        // black rectangle exactly the height of the status bar across the top of the window:
        // config_forceWindowDrawsStatusBarBackground is true on this device, so DecorView creates
        // the status bar colour view whether or not the window asked for one, and
        // DecorView.calculateBarColor returns Color.BLACK outright for any window without
        // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS — the theme's statusBarColor is never even consulted.
        // Theme.Translucent.NoTitleBar leaves that flag off, so recents inherited the black bar,
        // which rides up the screen with the window as it opens and then sits over the scrim for as
        // long as recents is up. Claiming the flag and asking for a transparent bar removes it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        setContentView(R.layout.legacy_recents_activity);

        mRecyclerView = findViewById(R.id.legacy_recents_list);
        mEmptyView = findViewById(R.id.legacy_recents_empty);

        // Pad the list by the system-bar insets on top of the base 24dp so the top-most
        // and (crucially) the bottom-most card can scroll fully clear of the status bar and
        // navigation bar. Previously only a fixed 24dp bottom padding was used, so the
        // most-recent card (which sits at the bottom via reverseLayout+stackFromEnd) stayed
        // partly hidden behind the nav bar and could not be scrolled into full view. Using
        // the live insets handles both 3-button and gesture navigation.
        final int baseListPadding = mRecyclerView.getPaddingTop();
        mRecyclerView.setOnApplyWindowInsetsListener((v, insets) -> {
            final android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), baseListPadding + bars.top,
                    v.getPaddingRight(), baseListPadding + bars.bottom);
            return insets;
        });
        mRecyclerView.requestApplyInsets();

        LinearLayoutManager lm = new LinearLayoutManager(this);
        // Most-recent task sits at the bottom (nearest the nav bar), like 7.1.1.
        lm.setReverseLayout(true);
        lm.setStackFromEnd(true);
        mRecyclerView.setLayoutManager(lm);

        mAdapter = new TaskAdapter();
        mRecyclerView.setAdapter(mAdapter);

        // Swipe horizontally to dismiss a task.
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder a,
                    RecyclerView.ViewHolder b) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                dismissTask(vh.getBindingAdapterPosition());
            }
        }).attachToRecyclerView(mRecyclerView);

        // Tapping the dimmed background closes recents.
        findViewById(R.id.legacy_recents_root).setOnClickListener(v -> finish());

        loadTasks();
    }

    /**
     * A second recents press while recents is already up lands here rather than in {@link #onCreate}
     * — this activity is {@code singleInstance}, and both {@link LegacyRecentsImpl} and Quickstep's
     * {@code OverviewCommandHelper} (which short-circuits {@code recents_type} 1 straight to this
     * component) just start it again with {@code FLAG_ACTIVITY_NEW_TASK} — so this is where the
     * standard double-tap quick switch has to be handled.
     *
     * <p>Telling the two apart is the whole problem: a genuine first press that re-fronts a
     * backgrounded recents also arrives here, and must not be mistaken for the second half of a
     * double tap. {@link #mStoppedAtElapsedRealtime} versus {@link #mShownAtElapsedRealtime} is the
     * signal — if we have not been stopped since we were last shown, we are still on screen and this
     * really is the second press. A time window on its own is not enough; a re-front can easily land
     * inside it.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final long sinceShown = SystemClock.elapsedRealtime() - mShownAtElapsedRealtime;
        // Still on screen: we have not been stopped since we were last shown.
        final boolean stillShowing = mStoppedAtElapsedRealtime <= mShownAtElapsedRealtime;
        final boolean inWindow = sinceShown < TOGGLE_PREVIOUS_TIMEOUT_MS;
        if (DEBUG) {
            Log.d(TAG, "onNewIntent: stillShowing=" + stillShowing + " finishing=" + isFinishing()
                    + " sinceShownMs=" + sinceShown + " inWindow=" + inWindow
                    + " tasks=" + mTasks.size());
        }
        if (stillShowing && !isFinishing() && inWindow && quickSwitchToPreviousTask()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onNewIntent: not consumed as double-tap, refreshing");
        }
        loadTasks();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mStoppedAtElapsedRealtime = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mShownAtElapsedRealtime = SystemClock.elapsedRealtime();
        // Always refresh when shown (including when a singleInstance task is brought to front).
        loadTasks();
    }

    /**
     * Double-tap recents: go straight to the app used before the current one, matching what stock
     * Quickstep does for a TOGGLE that arrives while overview is visible
     * ({@code OverviewCommandHelper.getNextToggledTaskView}).
     *
     * <p>Which entry that is depends on where recents was opened from, exactly as it does in stock:
     * <ul>
     *   <li>From an app, that app is still index 0 of {@link #mTasks}, so the previous app is index
     *       1 — stock's {@code getNextTaskView()}, the task view one past the running one.</li>
     *   <li>From home there is nothing to skip: {@link #isExcluded} drops the home task, so index 0
     *       is already the most recently used app.</li>
     * </ul>
     *
     * <p>Deliberately uses the already-loaded list instead of reloading it: the order cannot have
     * changed while recents has been on top (anything coming to the front would have paused us), and
     * mutating the list behind the adapter's back invites a RecyclerView inconsistency.
     *
     * @return true when the press was consumed, false to fall back to a plain refresh
     */
    private boolean quickSwitchToPreviousTask() {
        if (mTasks.isEmpty()) {
            if (DEBUG) Log.d(TAG, "quickSwitch: no tasks");
            return false;
        }
        final int mostRecentTaskId = mTasks.get(0).taskId;
        final int behind = taskIdBehindRecents();
        if (DEBUG) {
            Log.d(TAG, "quickSwitch: mostRecentTaskId=" + mostRecentTaskId
                    + " taskBehindRecents=" + behind + " size=" + mTasks.size());
        }
        if (mostRecentTaskId != behind) {
            // Opened over home, or over a task recents does not list: nothing to skip over.
            launchTask(0, true /* quickSwitch */);
            return true;
        }
        if (mTasks.size() < 2) {
            // Only the app we came from is left. Stock falls back to the running task view here,
            // which for us just means getting out of its way.
            finish();
            return true;
        }
        launchTask(1, true /* quickSwitch */);
        return true;
    }

    /**
     * Task id of the topmost task that is not one of ours, i.e. whatever recents was opened over, or
     * {@link ActivityTaskManager#INVALID_TASK_ID}.
     *
     * <p>Needed because the recent-task list alone cannot answer "was recents opened from an app or
     * from home?" — the home task is filtered out of it. Asked lazily, on the second press only, so
     * the press that opens recents pays nothing for it.
     */
    private int taskIdBehindRecents() {
        try {
            for (ActivityManager.RunningTaskInfo info
                    : ActivityManagerWrapper.getInstance().getRunningTasks(
                            false /* filterOnlyVisibleRecents */)) {
                final ComponentName cn =
                        info.topActivity != null ? info.topActivity : info.baseActivity;
                // Skip our own task, which is on top right now, and any other SystemUI task —
                // isExcluded keeps those out of the list too, so they can never match.
                if (cn == null || getPackageName().equals(cn.getPackageName())) {
                    continue;
                }
                return info.taskId;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read the task behind recents", e);
        }
        return ActivityTaskManager.INVALID_TASK_ID;
    }

    private void loadTasks() {
        mTasks.clear();
        try {
            List<ActivityManager.RecentTaskInfo> infos =
                    ActivityTaskManager.getInstance().getRecentTasks(
                            MAX_TASKS,
                            ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                            ActivityManager.getCurrentUser());
            PackageManager pm = getPackageManager();
            for (ActivityManager.RecentTaskInfo info : infos) {
                if (info.taskId < 0 || isExcluded(info)) {
                    continue;
                }
                TaskItem item = toItem(info, pm);
                // Skip tasks that resolve to nothing meaningful (e.g. the launcher/home or a
                // task with no label) so we don't render an empty card.
                if (item.label == null || item.label.toString().trim().isEmpty()) {
                    continue;
                }
                mTasks.add(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load recent tasks", e);
        }
        mAdapter.notifyDataSetChanged();
        boolean empty = mTasks.isEmpty();
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            mRecyclerView.scrollToPosition(0); // index 0 == most recent == bottom
        }
    }

    /** Skip the home/launcher task and SystemUI's own tasks. */
    private boolean isExcluded(ActivityManager.RecentTaskInfo info) {
        ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
        if (cn != null && getPackageName().equals(cn.getPackageName())) {
            return true;
        }
        if (info.baseIntent != null && info.baseIntent.hasCategory(Intent.CATEGORY_HOME)) {
            return true;
        }
        return false;
    }

    private TaskItem toItem(ActivityManager.RecentTaskInfo info, PackageManager pm) {
        TaskItem item = new TaskItem();
        item.taskId = info.taskId;
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
            item.icon = new android.graphics.drawable.BitmapDrawable(getResources(), iconBitmap);
        } else if (cn != null) {
            try {
                item.icon = pm.getActivityIcon(cn);
            } catch (Exception e) {
                item.icon = getPackageManager().getDefaultActivityIcon();
            }
        }

        int color = td != null ? td.getPrimaryColor() : 0;
        item.color = (color == 0) ? 0xFF37474F /* blue grey */ : (0xFF000000 | color);

        // The app's last-frame snapshot (what Android 7.1.1 showed inside each card).
        try {
            item.thumbnail =
                    ActivityManagerWrapper.getInstance().getTaskThumbnail(item.taskId, false)
                            .getThumbnail();
        } catch (Exception e) {
            item.thumbnail = null;
        }
        return item;
    }

    private void launchTask(int position) {
        launchTask(position, false /* quickSwitch */);
    }

    /**
     * @param quickSwitch when true, trade the launch transition for speed. A double tap is meant to
     *     feel like flipping back to the previous app, and the default transition plus recents' own
     *     exit animation is a few hundred milliseconds of animation for a switch whose logic takes
     *     under 10ms.
     */
    private void launchTask(int position, boolean quickSwitch) {
        if (position < 0 || position >= mTasks.size()) {
            return;
        }
        int taskId = mTasks.get(position).taskId;
        ActivityOptions options = quickSwitch
                ? ActivityOptions.makeCustomAnimation(
                        this, android.R.anim.fade_in, android.R.anim.fade_out)
                : ActivityOptions.makeBasic();
        ActivityManagerWrapper.getInstance().startActivityFromRecents(taskId, options);
        finish();
        if (quickSwitch) {
            // Recents is already being covered by the incoming app; animating it out as well just
            // adds latency to a gesture that should feel immediate.
            overridePendingTransition(0, 0);
        }
    }

    private void dismissTask(int position) {
        if (position < 0 || position >= mTasks.size()) {
            return;
        }
        TaskItem item = mTasks.remove(position);
        ActivityManagerWrapper.getInstance().removeTask(item.taskId);
        mAdapter.notifyItemRemoved(position);
        if (mTasks.isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
        }
    }

    private final class TaskAdapter extends RecyclerView.Adapter<TaskViewHolder> {
        @Override
        public TaskViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.legacy_recents_card, parent, false);
            return new TaskViewHolder(v);
        }

        @Override
        public void onBindViewHolder(TaskViewHolder holder, int position) {
            holder.bind(mTasks.get(position));
        }

        @Override
        public int getItemCount() {
            return mTasks.size();
        }
    }

    private final class TaskViewHolder extends RecyclerView.ViewHolder {
        final View header;
        final ImageView icon;
        final TextView title;
        final View dismiss;
        final ImageView thumbnail;

        TaskViewHolder(View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.legacy_recents_card_header);
            icon = itemView.findViewById(R.id.legacy_recents_card_icon);
            title = itemView.findViewById(R.id.legacy_recents_card_title);
            dismiss = itemView.findViewById(R.id.legacy_recents_card_dismiss);
            thumbnail = itemView.findViewById(R.id.legacy_recents_card_thumbnail);

            itemView.setOnClickListener(v -> launchTask(getBindingAdapterPosition()));
            dismiss.setOnClickListener(v -> dismissTask(getBindingAdapterPosition()));
        }

        void bind(TaskItem item) {
            title.setText(item.label);
            icon.setImageDrawable(item.icon);

            // Colour the header bar with the app's primary colour; pick a legible foreground.
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(item.color);
            header.setBackground(bg);
            int fg = isDarkColor(item.color) ? Color.WHITE : 0xDE000000;
            title.setTextColor(fg);
            ((TextView) dismiss).setTextColor(fg);

            // Show the app snapshot, scaled to the card width and aligned to the top (7.1.1 style).
            final Bitmap bmp = item.thumbnail;
            if (bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0) {
                thumbnail.setVisibility(View.VISIBLE);
                thumbnail.setScaleType(ImageView.ScaleType.MATRIX);
                thumbnail.setImageBitmap(bmp);
                thumbnail.post(() -> {
                    int vw = thumbnail.getWidth();
                    if (vw <= 0) return;
                    float scale = (float) vw / bmp.getWidth();
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    thumbnail.setImageMatrix(m);
                });
            } else {
                // No snapshot for this task (secure window, snapshot evicted, or the just-left
                // app whose snapshot isn't ready). Keep the thumbnail area at full height with
                // the app icon centered as a placeholder, so the card stays the same size as
                // the others instead of collapsing to a header-only strip. (Setting it GONE
                // caused the intermittent "last card shows only a title bar" bug.)
                thumbnail.setVisibility(View.VISIBLE);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                thumbnail.setImageMatrix(new Matrix());
                thumbnail.setImageDrawable(item.icon);
            }
        }
    }

    private static boolean isDarkColor(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance < 0.6;
    }
}
