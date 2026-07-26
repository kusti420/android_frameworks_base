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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.res.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A revival of the pre-Quickstep (Android 7.1.1-style) recents/task switcher: a vertical stack of
 * task cards, most-recent nearest the bottom, that you tap to launch or swipe/✕ to dismiss.
 *
 * <p>Launched by {@link LegacyRecentsImpl} in response to the recents affordance.
 */
public class LegacyRecentsActivity extends Activity {

    private static final String TAG = "LegacyRecents";
    private static final int MAX_TASKS = 20;

    private RecyclerView mRecyclerView;
    private View mEmptyView;
    private final List<TaskItem> mTasks = new ArrayList<>();
    private TaskAdapter mAdapter;

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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        loadTasks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always refresh when shown (including when a singleInstance task is brought to front).
        loadTasks();
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
        if (position < 0 || position >= mTasks.size()) {
            return;
        }
        int taskId = mTasks.get(position).taskId;
        ActivityManagerWrapper.getInstance().startActivityFromRecents(
                taskId, ActivityOptions.makeBasic());
        finish();
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
