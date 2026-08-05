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

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Handler;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.res.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Host activity for the IchthysOS recents styles that are card lists over the recent-task set:
 * the Samsung 7.1.1-era vertical cascade and the four Home Up-style layouts (grid, stack, list,
 * slim list).
 *
 * <p>All of them share this activity, {@link RecentsTaskSource} and {@link RecentsCardBinder}; the
 * only thing that varies is the {@link RecentsStyle} selected from the {@code recents_type} System
 * setting. The Classic (1) and Slim (2) styles predate this and keep their own activities.
 */
public class OneUiRecentsActivity extends Activity {

    private static final String TAG = "IchthysRecents";

    /** Traces the double-tap quick-switch decision path. {@code adb shell setprop log.tag.IchthysRecents DEBUG}. */
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String RECENTS_TYPE = "recents_type";

    /**
     * How long after recents is shown a second recents press still counts as the double-tap
     * quick-switch gesture. Mirrors Quickstep's
     * {@code OverviewCommandHelper.TOGGLE_PREVIOUS_TIMEOUT_MS} (5s), which is the window stock uses
     * to decide that a second press belongs with the first; that constant lives in Launcher3 and is
     * not on SystemUI's classpath, so it is matched by value.
     */
    private static final long TOGGLE_PREVIOUS_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    /**
     * Backdrop blur radius, in dp. One UI blurs the wallpaper behind recents rather than dimming it
     * flat; this device supports it (ro.surface_flinger.supports_background_blur=1).
     *
     * <p>Deliberately a constant rather than a dimen resource. Adding a single {@code <dimen>} to
     * SystemUI shifted its dimen resource IDs on an incremental build, and
     * {@code WindowManager-Shell}'s generated R constants were not re-assigned to match — which
     * crash-looped SystemUI at app-bind with "Resource ID #0x7f070a7b type #0x4 is not valid" from
     * WMShellBaseModule.providePipBoundsState, long before any of this class ran. A bare constant
     * cannot shift anything.
     */
    private static final int BLUR_RADIUS_DP = 72;

    /**
     * Breathing room between the "Close all" pill and the navigation bar, in dp. Applied on top of
     * the live bottom inset so it holds under gesture nav and 3-button nav alike.
     */
    private static final float PILL_GAP_ABOVE_NAVBAR_DP = 14f;

    /** Veil over the blurred wallpaper — light, because the blur is doing the separation. */
    private static final int SCRIM_WITH_BLUR = 0x33000000;
    /** Fallback when cross-window blur is off: the scrim alone has to separate cards from a
     *  sharp wallpaper, so it has to be considerably heavier. */
    private static final int SCRIM_NO_BLUR = 0x99000000;

    private RecyclerView mRecyclerView;
    private View mEmptyView;
    private RecentsTaskSource mSource;
    private RecentsStyle mStyle;
    private TaskAdapter mAdapter;

    private View mRootView;
    private int mBlurRadiusPx;
    private Consumer<Boolean> mBlurEnabledListener;

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
     * <p>This replaces an {@link #isResumed()} check that could never fire. Delivering a new intent
     * cycles the activity down through {@code onPause} first and resumes it afterwards, so
     * {@code isResumed()} is false inside {@link #onNewIntent} in <em>both</em> cases and cannot
     * separate them. Two timestamps can, and — unlike a flag set from {@code onStart}/{@code
     * onRestart} — the comparison does not depend on whether those callbacks run before or after
     * {@code onNewIntent} when the activity is re-fronted, only on {@code onResume} running after
     * it, which it always does.
     */
    private long mStoppedAtElapsedRealtime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // No window open animation. Once the load cost was taken off the critical path, time to
        // displayed fell to ~84ms — below the point where launch latency reads as lag — and the
        // remaining perceived delay was the transition itself, which costs the same no matter how
        // fast this activity is. Recents is an overlay on what is already on screen, so appearing
        // immediately suits it better than sliding in.
        overridePendingTransition(0, 0);

        // Draw our own (transparent) system bar backgrounds. Without this the decor puts an opaque
        // black rectangle exactly the height of the status bar across the top of the window:
        // config_forceWindowDrawsStatusBarBackground is true on this device, so DecorView creates
        // the status bar colour view whether or not the window asked for one, and
        // DecorView.calculateBarColor returns Color.BLACK outright for any window without
        // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS — the theme's statusBarColor is never even consulted.
        // Theme.Translucent.NoTitleBar leaves that flag off, so recents inherited the black bar:
        // measured as a 100px (== the statusBars inset) pure #000000 band that rides up the screen
        // with the window as it opens and then sits over the blurred backdrop for as long as
        // recents is up. Claiming the flag and asking for a transparent bar removes it.
        //
        // The navigation bar background comes from the same branch, so it stops being opaque black
        // too; it falls back to the enforced-contrast scrim over the blur, which is what One UI
        // shows there. Insets are unaffected: DecorView only consumes them when the window fits
        // system windows, and edge-to-edge enforcement has already turned that off.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        mStyle = RecentsStyles.forType(readRecentsType());
        mSource = new RecentsTaskSource(this);

        setContentView(R.layout.oneui_recents_activity);
        mRecyclerView = findViewById(R.id.oneui_recents_list);
        mEmptyView = findViewById(R.id.oneui_recents_empty);

        // One UI blurs the wallpaper behind recents rather than dimming it flat — verified on both
        // a Galaxy S25 FE (One UI 8) and an A3 2016 (Grace UX), and it's the most recognisable part
        // of the backdrop. Same API SystemUI already uses for the power menu
        // (GlobalActionsDialogLite), and this device declares support via
        // ro.surface_flinger.supports_background_blur=1.
        mBlurRadiusPx = Math.round(
                BLUR_RADIUS_DP * getResources().getDisplayMetrics().density);
        mRootView = findViewById(R.id.oneui_recents_root);
        // Blur can be disabled system-wide at any moment (battery saver, or a low-end-device
        // config), so track it and fall back to a heavier scrim — the light veil alone leaves the
        // cards poorly separated from a sharp wallpaper.
        mBlurEnabledListener = this::applyBackdrop;
        applyBackdrop(getSystemService(WindowManager.class).isCrossWindowBlurEnabled());

        // Give the style a real dismiss entry point before it configures anything, so a style with
        // no ✕ can drive dismissal from its own gesture instead of faking a click on a hidden view.
        mStyle.setDismisser(this::dismissTask);

        mRecyclerView.setLayoutManager(mStyle.createLayoutManager(this));
        mAdapter = new TaskAdapter();
        mRecyclerView.setAdapter(mAdapter);

        final int swipeDirs = mStyle.getSwipeDirs();
        if (swipeDirs != 0) {
            new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, swipeDirs) {
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
        }

        // Let the style add decorations / scroll transforms once everything is attached.
        mStyle.onRecyclerViewCreated(mRecyclerView);

        // Pad by the live insets on ALL FOUR edges. Deliberately installed *after* the style has
        // configured the RecyclerView, so whatever padding the style chose is the base we add to —
        // several styles set their own horizontal padding for outer margins, and a listener that
        // overwrote it would silently undo them.
        //
        // Horizontal matters specifically in landscape: this device puts an 88px display cutout on
        // the left in ROTATION_90 and a 135px navigation bar on the right in ROTATION_270 (both
        // measured). The previous top/bottom-only listener let cards sit underneath each of them.
        // displayCutout is included alongside systemBars for the same reason.
        final int baseLeft = mRecyclerView.getPaddingLeft();
        final int baseTop = mRecyclerView.getPaddingTop();
        final int baseRight = mRecyclerView.getPaddingRight();
        final int baseBottom = mRecyclerView.getPaddingBottom();
        final View clearAllPill = findViewById(R.id.oneui_recents_clear_all);
        mRecyclerView.setOnApplyWindowInsetsListener((v, insets) -> {
            final Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            // Sit the pill a real gap above the navigation bar rather than flush against it. This
            // used to be a hardcoded 48dp bottom margin, which happened to equal this device's
            // navigation bar height exactly — so it looked deliberate while actually leaving zero
            // separation, and it would have floated on gesture nav or overlapped a taller bar.
            if (clearAllPill != null
                    && clearAllPill.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                final ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) clearAllPill.getLayoutParams();
                final int wanted = bars.bottom
                        + Math.round(PILL_GAP_ABOVE_NAVBAR_DP * getResources()
                                .getDisplayMetrics().density);
                if (lp.bottomMargin != wanted) {
                    lp.bottomMargin = wanted;
                    clearAllPill.setLayoutParams(lp);
                }
            }
            return insets;
        });
        mRecyclerView.requestApplyInsets();

        if (mStyle.dismissOnScrimTap()) {
            findViewById(R.id.oneui_recents_root).setOnClickListener(v -> finish());
        }

        final View clearAll = findViewById(R.id.oneui_recents_clear_all);
        if (clearAll != null) {
            clearAll.setOnClickListener(v -> {
                mSource.dismissAll();
                refreshEmptyState();
                mAdapter.notifyDataSetChanged();
                finish();
            });
        }

        // Deliberately no loadTasks() here. onResume always runs before the first frame is drawn
        // and reloads unconditionally, so loading here as well simply did the whole job twice —
        // measured at 199ms + 98ms on a four-task list, against a 345ms time-to-displayed.
    }

    /**
     * A second recents press while recents is already up lands here rather than in {@link #onCreate}
     * — this activity is {@code singleInstance}, and Quickstep's {@code OverviewCommandHelper} just
     * starts the same component again — so this is where the standard double-tap quick switch has to
     * be handled.
     *
     * <p>Telling the two apart is the whole problem: a genuine first press that re-fronts a
     * backgrounded recents also arrives here, and must not be mistaken for the second half of a
     * double tap. {@link #mStoppedAtElapsedRealtime} versus {@link #mShownAtElapsedRealtime} is the
     * signal — if we have not been stopped since we were last shown, we are still on screen and this
     * really is the second press.
     *
     * <p>Do <em>not</em> reach for {@code isResumed()} here. It reads false in both cases, because
     * delivering a new intent cycles the activity down through {@code onPause} before this runs and
     * resumes it afterwards; an earlier version of this method gated on it and the double tap
     * therefore never fired at all.
     *
     * <p>Having a local answer at all is why this lives here rather than in
     * {@code OverviewCommandHelper}, which has no way to know whether the SystemUI recents window is
     * still on screen and would have to ask the system on every press to find out — latency the
     * first press must not pay.
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
                    + " tasks=" + mSource.size());
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
        // Always refresh when shown, including when this singleInstance task is re-fronted.
        loadTasks();
    }

    /**
     * Double-tap recents: go straight to the app used before the current one, matching what stock
     * Quickstep does for a TOGGLE that arrives while overview is visible
     * ({@code OverviewCommandHelper.getNextToggledTaskView}).
     *
     * <p>Which entry that is depends on where recents was opened from, exactly as it does in stock:
     * <ul>
     *   <li>From an app, that app is still position 0 of the recent-task list, so the previous app
     *       is position 1 — stock's {@code getNextTaskView()}, which is the task view one past the
     *       running one.</li>
     *   <li>From home there is nothing to skip: the framework drops {@code ACTIVITY_TYPE_HOME} from
     *       {@code getRecentTasks()}, so position 0 is already the most recently used app — stock's
     *       {@code firstNonDesktopTaskView} for a null running task view.</li>
     * </ul>
     *
     * <p>Deliberately uses the already-loaded list instead of reloading it: the order cannot have
     * changed while recents has been on top (anything coming to the front would have paused us), and
     * mutating the source behind the adapter's back invites a RecyclerView inconsistency.
     *
     * @return true when the press was consumed, false to fall back to a plain refresh
     */
    private boolean quickSwitchToPreviousTask() {
        if (mSource.isEmpty()) {
            if (DEBUG) Log.d(TAG, "quickSwitch: source empty");
            return false;
        }
        final RecentsTaskSource.TaskItem mostRecent = mSource.get(0);
        final int behind = taskIdBehindRecents();
        if (DEBUG) {
            Log.d(TAG, "quickSwitch: mostRecentTaskId="
                    + (mostRecent == null ? "null" : mostRecent.taskId)
                    + " taskBehindRecents=" + behind + " size=" + mSource.size());
        }
        if (mostRecent == null || mostRecent.taskId != behind) {
            // Opened over home, or over a task recents does not list: nothing to skip over.
            launchTask(0, true /* quickSwitch */);
            return true;
        }
        if (mSource.size() < 2) {
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
     * from home?" — the home task is never in it. Asked lazily, on the second press only, so the
     * press that opens recents pays nothing for it.
     */
    private int taskIdBehindRecents() {
        try {
            for (ActivityManager.RunningTaskInfo info
                    : ActivityManagerWrapper.getInstance().getRunningTasks(
                            false /* filterOnlyVisibleRecents */)) {
                final ComponentName cn =
                        info.topActivity != null ? info.topActivity : info.baseActivity;
                // Skip our own task, which is on top right now, and any other SystemUI task —
                // RecentsTaskSource filters those out of the list too, so they can never match.
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

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        getSystemService(WindowManager.class)
                .addCrossWindowBlurEnabledListener(mBlurEnabledListener);
    }

    @Override
    public void onDetachedFromWindow() {
        getSystemService(WindowManager.class)
                .removeCrossWindowBlurEnabledListener(mBlurEnabledListener);
        super.onDetachedFromWindow();
    }

    /**
     * Blurs the wallpaper behind the window, or falls back to a heavier scrim when cross-window
     * blur is unavailable. Called once during {@code onCreate} and again whenever the system
     * toggles blur (battery saver being the common trigger).
     */
    private void applyBackdrop(boolean blurEnabled) {
        final Window window = getWindow();
        final WindowManager.LayoutParams attrs = window.getAttributes();
        if (blurEnabled) {
            attrs.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attrs.setBlurBehindRadius(mBlurRadiusPx);
        } else {
            attrs.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attrs.setBlurBehindRadius(0);
        }
        window.setAttributes(attrs);
        if (mRootView != null) {
            mRootView.setBackgroundColor(blurEnabled ? SCRIM_WITH_BLUR : SCRIM_NO_BLUR);
        }
    }

    private int readRecentsType() {
        return Settings.System.getIntForUser(getContentResolver(), RECENTS_TYPE,
                RecentsStyles.TYPE_GRACE, UserHandle.USER_CURRENT);
    }

    private void loadTasks() {
        final long t0 = SystemClock.elapsedRealtime();
        mSource.reload();
        final long reloadMs = SystemClock.elapsedRealtime() - t0;
        mAdapter.notifyDataSetChanged();
        refreshEmptyState();
        // Snapshots arrive afterwards and rebind their own card; the list is already usable.
        mSource.loadThumbnailsAsync(mRecyclerView.getHandler() != null
                        ? mRecyclerView.getHandler() : new Handler(getMainLooper()),
                position -> {
                    if (position >= 0 && position < mAdapter.getItemCount()) {
                        mAdapter.notifyItemChanged(position);
                    }
                });
        if (DEBUG) {
            Log.d(TAG, "loadTasks: reload=" + reloadMs + "ms tasks=" + mSource.size()
                    + " total=" + (SystemClock.elapsedRealtime() - t0) + "ms");
        }
        if (!mSource.isEmpty()) {
            mRecyclerView.scrollToPosition(0); // 0 == most recent
        }
    }

    private void refreshEmptyState() {
        final boolean empty = mSource.isEmpty();
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        final View clearAll = findViewById(R.id.oneui_recents_clear_all);
        if (clearAll != null) {
            clearAll.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void launchTask(int position) {
        launchTask(position, false /* quickSwitch */);
    }

    /**
     * @param quickSwitch when true, trade the launch transition for speed. A double tap is meant to
     *     feel like flipping back to the previous app, and the default transition plus recents' own
     *     exit animation measured ~350ms of animation for a switch whose logic takes under 10ms.
     */
    private void launchTask(int position, boolean quickSwitch) {
        final ActivityOptions options = quickSwitch
                ? ActivityOptions.makeCustomAnimation(
                        this, android.R.anim.fade_in, android.R.anim.fade_out)
                : ActivityOptions.makeBasic();
        final boolean launched = mSource.launchTask(position, options);
        if (DEBUG) Log.d(TAG, "launchTask(" + position + ", quick=" + quickSwitch + ") -> " + launched);
        if (launched) {
            finish();
            if (quickSwitch) {
                // Recents is already being covered by the incoming app; animating it out as well
                // just adds latency to a gesture that should feel immediate.
                overridePendingTransition(0, 0);
            }
        }
    }

    private void dismissTask(int position) {
        if (!mSource.dismissTask(position)) {
            return;
        }
        mAdapter.notifyItemRemoved(position);
        // Positions shift after a removal, and the stack/cascade styles bind per-position
        // transforms, so the tail has to be rebound rather than left as-is.
        mAdapter.notifyItemRangeChanged(position, mSource.size());
        refreshEmptyState();
    }

    private final class TaskAdapter extends RecyclerView.Adapter<TaskViewHolder> {
        @Override
        public TaskViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(mStyle.getCardLayoutRes(), parent, false);
            return new TaskViewHolder(v);
        }

        @Override
        public void onBindViewHolder(TaskViewHolder holder, int position) {
            holder.bind(mSource.get(position), position, mSource.size());
        }

        @Override
        public int getItemCount() {
            return mSource.size();
        }
    }

    private final class TaskViewHolder extends RecyclerView.ViewHolder {
        TaskViewHolder(View itemView) {
            super(itemView);
            itemView.setOnClickListener(v -> launchTask(getBindingAdapterPosition()));
            final View dismiss = itemView.findViewById(R.id.oneui_recents_card_dismiss);
            if (dismiss != null) {
                dismiss.setOnClickListener(v -> dismissTask(getBindingAdapterPosition()));
            }
        }

        void bind(RecentsTaskSource.TaskItem item, int position, int count) {
            if (item == null) {
                return;
            }
            RecentsCardBinder.bind(itemView, item);
            mStyle.onBindCard(itemView, item, position, count);
        }
    }
}
