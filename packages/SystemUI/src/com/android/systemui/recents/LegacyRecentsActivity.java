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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.res.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** IchthysOS: size of the "Close all" button, matching the card-list styles' pill (dp). */
    private static final int CLEAR_ALL_HEIGHT_DP = 34;
    private static final int CLEAR_ALL_GAP_DP = 8;

    /**
     * IchthysOS: how long an Alt release that arrived before recents came up still applies to it.
     * A quick Alt+Tab tap releases Alt before the activity has even resumed.
     */
    private static final long ALT_TAB_RELEASE_PENDING_MS = 1000;

    /** IchthysOS: the instance on screen, for {@link #onAltTabReleased}. Main thread only. */
    private static WeakReference<LegacyRecentsActivity> sShowing;
    /** IchthysOS: elapsedRealtime of an Alt release no instance was up for yet, or 0. */
    private static long sAltTabReleasedAt;

    private RecyclerView mRecyclerView;
    private View mEmptyView;
    private TextView mClearAll;
    private final List<TaskItem> mTasks = new ArrayList<>();
    private TaskAdapter mAdapter;

    /**
     * IchthysOS: snapshots are fetched here, off the main thread, as the card-list styles do
     * (RecentsTaskSource): each is a binder call returning a full-screen bitmap, and up to 20 of
     * them used to run inline before recents could draw.
     */
    private ExecutorService mThumbnailExecutor;
    /** Bumped on every load/clear so snapshots from a superseded list are dropped. Main thread. */
    private int mThumbnailGeneration;
    private volatile boolean mDestroyed;

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
        mThumbnailExecutor = Executors.newSingleThreadExecutor();
        addClearAllButton();

        // Pad the list by the system-bar insets on top of the base 24dp so the top-most
        // and (crucially) the bottom-most card can scroll fully clear of the status bar and
        // navigation bar. Previously only a fixed 24dp bottom padding was used, so the
        // most-recent card (which sits at the bottom via reverseLayout+stackFromEnd) stayed
        // partly hidden behind the nav bar and could not be scrolled into full view. Using
        // the live insets handles both 3-button and gesture navigation.
        final int baseListPadding = mRecyclerView.getPaddingTop();
        // IchthysOS: the top also clears the "Close all" button, which floats top-end as in
        // 7.1.1, so the oldest card can scroll out from under it.
        final int clearAllSpace = dp(CLEAR_ALL_HEIGHT_DP + CLEAR_ALL_GAP_DP);
        mRecyclerView.setOnApplyWindowInsetsListener((v, insets) -> {
            final android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), baseListPadding + bars.top + clearAllSpace,
                    v.getPaddingRight(), baseListPadding + bars.bottom);
            final ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mClearAll.getLayoutParams();
            final int wantedTop = bars.top + dp(CLEAR_ALL_GAP_DP);
            if (lp.topMargin != wantedTop) {
                lp.topMargin = wantedTop;
                mClearAll.setLayoutParams(lp);
            }
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
        // IchthysOS: the list is match_parent and takes every touch once it has a layout
        // manager, so the root's click above only fired beside the list or in the empty state.
        // Also close on a tap that lands between the cards.
        final GestureDetector tapOutside = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    private boolean mDownWhileScrolling;

                    @Override
                    public boolean onDown(MotionEvent e) {
                        // A tap that only stops a fling is not a request to close.
                        mDownWhileScrolling =
                                mRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;
                        return false;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (!mDownWhileScrolling
                                && mRecyclerView.findChildViewUnder(e.getX(), e.getY()) == null) {
                            finish();
                            return true;
                        }
                        return false;
                    }
                });
        // Only reaches the list when no card took the touch; returning false keeps scrolling.
        mRecyclerView.setOnTouchListener((v, e) -> {
            tapOutside.onTouchEvent(e);
            return false;
        });

        // IchthysOS: no loadTasks() here. onResume always runs before the first frame and
        // reloads, so loading here as well did the whole job twice.
    }

    /**
     * IchthysOS: the "Close all" control 7.1.1 had above the stack. Built in code on purpose: a
     * new id or dimen resource would shift SystemUI's resource IDs (see BLUR_RADIUS_DP in
     * OneUiRecentsActivity). Reuses the card-list styles' string and background.
     */
    private void addClearAllButton() {
        mClearAll = new TextView(this);
        mClearAll.setText(R.string.recents_clear_all);
        mClearAll.setAllCaps(true);
        mClearAll.setTextColor(Color.WHITE);
        mClearAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mClearAll.setTypeface(Typeface.DEFAULT_BOLD);
        mClearAll.setGravity(Gravity.CENTER);
        mClearAll.setPadding(dp(16), 0, dp(16), 0);
        mClearAll.setBackgroundResource(R.drawable.oneui_recents_clear_all_bg);
        mClearAll.setFocusable(true);
        mClearAll.setVisibility(View.GONE);
        mClearAll.setOnClickListener(v -> clearAll());
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(CLEAR_ALL_HEIGHT_DP),
                Gravity.TOP | Gravity.END);
        lp.setMarginEnd(dp(16));
        ((ViewGroup) findViewById(R.id.legacy_recents_root)).addView(mClearAll, lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * IchthysOS: removes every task this list would show, not only the loaded (capped) ones, off
     * the main thread, then closes recents.
     */
    private void clearAll() {
        mTasks.clear();
        ++mThumbnailGeneration;
        mAdapter.notifyDataSetChanged();
        final String ownPackage = getPackageName();
        mThumbnailExecutor.execute(() -> {
            try {
                final List<ActivityManager.RecentTaskInfo> infos =
                        ActivityTaskManager.getInstance().getRecentTasks(
                                ActivityTaskManager.getMaxRecentTasksStatic(),
                                ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                                ActivityManager.getCurrentUser());
                for (ActivityManager.RecentTaskInfo info : infos) {
                    if (info.taskId < 0 || isExcluded(info, ownPackage)) {
                        continue;
                    }
                    ActivityManagerWrapper.getInstance().removeTask(info.taskId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Clear all failed", e);
            }
        });
        finish();
    }

    /**
     * IchthysOS: Alt released after Alt+Tab ({@link LegacyRecentsImpl#hideRecentApps}). Launches
     * the card the keyboard focus is on, or does the same quick switch a second recents press
     * does, like stock's Keyboard Quick Switch. If recents is not up yet, the release is kept
     * briefly for the instance about to resume.
     */
    static void onAltTabReleased() {
        final LegacyRecentsActivity showing = sShowing != null ? sShowing.get() : null;
        if (showing == null || showing.isFinishing()) {
            sAltTabReleasedAt = SystemClock.elapsedRealtime();
            return;
        }
        sAltTabReleasedAt = 0;
        showing.launchFocusedOrPrevious();
    }

    private void launchFocusedOrPrevious() {
        final View focused = mRecyclerView.getFocusedChild();
        final int position = focused != null
                ? mRecyclerView.getChildAdapterPosition(focused) : RecyclerView.NO_POSITION;
        if (position != RecyclerView.NO_POSITION) {
            launchTask(position, true /* quickSwitch */);
        } else {
            quickSwitchToPreviousTask();
        }
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
        if (DEBUG) {
            Log.d(TAG, "onNewIntent: stillShowing=" + stillShowing + " finishing=" + isFinishing()
                    + " sinceShownMs=" + sinceShown + " tasks=" + mTasks.size());
        }
        // IchthysOS: no time limit. A second press while recents is still up always switches,
        // as in stock Quickstep, where OverviewCommandHelper.TOGGLE_PREVIOUS_TIMEOUT_MS only picks
        // which task to go to; a 5 s gate here made a later press just reload the list.
        if (stillShowing && !isFinishing() && quickSwitchToPreviousTask()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onNewIntent: not consumed as double-tap, refresh in onResume");
        }
        // IchthysOS: no loadTasks() here; onResume always follows a new intent and reloads.
    }

    @Override
    protected void onStop() {
        super.onStop();
        mStoppedAtElapsedRealtime = SystemClock.elapsedRealtime();
        if (sShowing != null && sShowing.get() == this) {
            sShowing = null;
        }
        // IchthysOS: this singleInstance activity lingers after Home; don't keep up to 20
        // full-screen snapshots alive meanwhile. onResume reloads the list anyway.
        ++mThumbnailGeneration;
        mTasks.clear();
        mAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mThumbnailExecutor != null) {
            // Queued work (a clear all) still runs; a snapshot pass stops at the next task.
            mThumbnailExecutor.shutdown();
        }
        if (sShowing != null && sShowing.get() == this) {
            sShowing = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mShownAtElapsedRealtime = SystemClock.elapsedRealtime();
        sShowing = new WeakReference<>(this);
        // Always refresh when shown (including when a singleInstance task is brought to front).
        loadTasks();
        // IchthysOS: an Alt release that beat us to the screen (quick Alt+Tab).
        final long releasedAt = sAltTabReleasedAt;
        sAltTabReleasedAt = 0;
        if (releasedAt != 0
                && SystemClock.elapsedRealtime() - releasedAt < ALT_TAB_RELEASE_PENDING_MS) {
            launchFocusedOrPrevious();
        }
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
                if (info.taskId < 0 || isExcluded(info, getPackageName())) {
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
        refreshEmptyState();
        if (!mTasks.isEmpty()) {
            mRecyclerView.scrollToPosition(0); // index 0 == most recent == bottom
        }
        loadThumbnailsAsync();
    }

    private void refreshEmptyState() {
        boolean empty = mTasks.isEmpty();
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        mClearAll.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /**
     * IchthysOS: fetches the loaded tasks' snapshots on {@link #mThumbnailExecutor} and rebinds
     * each card as its snapshot lands (at its current position, so a dismiss meanwhile is safe).
     * Cards show the app icon placeholder until then.
     */
    private void loadThumbnailsAsync() {
        final int generation = ++mThumbnailGeneration;
        final List<TaskItem> items = new ArrayList<>(mTasks);
        final Handler main = new Handler(getMainLooper());
        mThumbnailExecutor.execute(() -> {
            for (TaskItem item : items) {
                if (mDestroyed) {
                    return;
                }
                Bitmap bitmap = null;
                try {
                    bitmap = ActivityManagerWrapper.getInstance()
                            .getTaskThumbnail(item.taskId, false).getThumbnail();
                } catch (Exception e) {
                    // A task can die mid-load; the card keeps its icon placeholder.
                }
                final Bitmap loaded = bitmap;
                main.post(() -> {
                    if (generation != mThumbnailGeneration || isDestroyed()) {
                        return; // superseded by a newer load, a clear or onStop
                    }
                    item.thumbnail = loaded;
                    final int index = mTasks.indexOf(item);
                    if (index >= 0) {
                        mAdapter.notifyItemChanged(index);
                    }
                });
            }
        });
    }

    /** Skip the home/launcher task and SystemUI's own tasks. */
    private static boolean isExcluded(ActivityManager.RecentTaskInfo info, String ownPackage) {
        ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
        if (cn != null && ownPackage.equals(cn.getPackageName())) {
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
        // IchthysOS: fetched afterwards, off the main thread (loadThumbnailsAsync).
        item.thumbnail = null;
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
            refreshEmptyState();
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
