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

package com.android.systemui.adaptiveplayback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.KeyEvent;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.settings.SystemSettings;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * IchthysOS: backend for the crDroid "Adaptive playback" screen
 * ({@code crDroidSettings/res/xml/adaptive_playback_settings.xml}).
 *
 * <p>Pauses media when the media stream goes silent, and resumes it if the volume comes back
 * within the configured timeout. The decision logic lives in {@link AdaptivePlaybackState}; this
 * class only supplies it with audio events and turns its answers into media key events.
 *
 * <p>The setting keys are written by {@code lineageos.preference.SystemSettingMainSwitchPreference}
 * and {@code SystemSettingSeekBarPreference} in crDroidSettings, which persist the preference key
 * verbatim into {@code Settings.System}. They are spelled out here rather than referenced through
 * {@code android.provider.Settings.System} so that this feature is a SystemUI-only change.
 */
@SysUISingleton
public class AdaptivePlaybackController implements CoreStartable {

    private static final String TAG = "AdaptivePlayback";

    /** Mirrors {@code android:key="adaptive_playback_enabled"}. */
    static final String KEY_ENABLED = "adaptive_playback_enabled";

    /** Mirrors {@code android:key="adaptive_playback_timeout"}, in seconds. */
    static final String KEY_TIMEOUT = "adaptive_playback_timeout";

    private final Context mContext;
    private final SystemSettings mSystemSettings;
    private final Handler mHandler;
    private final Executor mMainExecutor;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final AdaptivePlaybackState mState = new AdaptivePlaybackState();

    private AudioManager mAudioManager;
    private boolean mEnabled;
    private int mTimeoutSeconds = AdaptivePlaybackState.DEFAULT_TIMEOUT_SECONDS;

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (mState.isAwaitingResume()) {
                Log.i(TAG, "resume window expired after " + mTimeoutSeconds + "s");
            }
            mState.onTimeout();
        }
    };

    private final BroadcastReceiver mAudioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
            if (stream != AudioManager.STREAM_MUSIC) {
                return;
            }
            onMediaStreamChanged();
        }
    };

    @Inject
    public AdaptivePlaybackController(
            @Application Context context,
            SystemSettings systemSettings,
            @Main Handler handler,
            @Main Executor mainExecutor,
            BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mSystemSettings = systemSettings;
        mHandler = handler;
        mMainExecutor = mainExecutor;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void start() {
        mAudioManager = mContext.getSystemService(AudioManager.class);
        if (mAudioManager == null) {
            Log.w(TAG, "no AudioManager; adaptive playback disabled");
            return;
        }

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final boolean wasEnabled = mEnabled;
                readSettings();
                if (wasEnabled && !mEnabled) {
                    mHandler.removeCallbacks(mTimeoutRunnable);
                    mState.reset();
                }
            }
        };
        mSystemSettings.registerContentObserverForUserAsync(
                KEY_ENABLED, observer, UserHandle.USER_ALL);
        mSystemSettings.registerContentObserverForUserAsync(
                KEY_TIMEOUT, observer, UserHandle.USER_ALL);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        mBroadcastDispatcher.registerReceiver(mAudioReceiver, filter, mMainExecutor,
                UserHandle.ALL, Context.RECEIVER_NOT_EXPORTED, null);

        readSettings();
    }

    private void readSettings() {
        mEnabled = mSystemSettings.getInt(KEY_ENABLED, 0) != 0;
        mTimeoutSeconds = AdaptivePlaybackState.clampTimeoutSeconds(mSystemSettings.getInt(
                KEY_TIMEOUT, AdaptivePlaybackState.DEFAULT_TIMEOUT_SECONDS));
    }

    private void onMediaStreamChanged() {
        final boolean muted = isMediaSilent();
        final boolean musicActive = mAudioManager.isMusicActive();
        switch (mState.onMuteStateChanged(mEnabled, muted, musicActive)) {
            case PAUSE:
                dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE);
                mHandler.removeCallbacks(mTimeoutRunnable);
                mHandler.postDelayed(mTimeoutRunnable, mTimeoutSeconds * 1000L);
                Log.i(TAG, "media muted -> paused, resumable for " + mTimeoutSeconds + "s");
                break;
            case RESUME:
                mHandler.removeCallbacks(mTimeoutRunnable);
                dispatch(KeyEvent.KEYCODE_MEDIA_PLAY);
                Log.i(TAG, "media unmuted inside the window -> resumed");
                break;
            case NONE:
            default:
                break;
        }
    }

    /** True when nothing routed to the media stream could be heard. */
    private boolean isMediaSilent() {
        return mAudioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                || mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
    }

    private void dispatch(int keyCode) {
        final long now = SystemClock.uptimeMillis();
        mAudioManager.dispatchMediaKeyEvent(
                new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        mAudioManager.dispatchMediaKeyEvent(
                new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("AdaptivePlaybackController:");
        pw.println("  enabled=" + mEnabled);
        pw.println("  timeoutSeconds=" + mTimeoutSeconds);
        pw.println("  muted=" + mState.isMuted());
        pw.println("  awaitingResume=" + mState.isAwaitingResume());
        if (mAudioManager != null) {
            pw.println("  musicActive=" + mAudioManager.isMusicActive());
            pw.println("  mediaSilent=" + isMediaSilent());
        }
    }
}
