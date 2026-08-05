/*
 * Copyright (C) 2026 IchthysOS Project
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

package com.android.systemui.doze;

import android.app.AlarmManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeMachine.State;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.AlarmTimeout;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalTime;

import javax.inject.Inject;

/**
 * Turns the always-on display on and off at the boundaries of the crDroid AOD schedule
 * ({@code Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE} = "custom time").
 *
 * <p>{@link AmbientDisplayConfiguration#alwaysOnEnabled} already applies the schedule, which is
 * enough for the state the device enters when the screen turns off. This part exists for the device
 * that is *already* dozing when a boundary passes: nothing else re-evaluates AOD then, so the AOD
 * would stay in whatever state it entered until the user touched the phone.
 *
 * <p>The sunset/sunrise schedule modes are not handled here; see
 * {@link AmbientDisplayConfiguration#alwaysOnScheduleAllowsNow}.
 */
@DozeScope
public class DozeAlwaysOnScheduler implements DozeMachine.Part {

    private final AmbientDisplayConfiguration mConfig;
    private final UserTracker mUserTracker;
    private final Handler mMainHandler;
    private final AlarmTimeout mBoundaryAlarm;

    private DozeMachine mMachine;

    @Inject
    DozeAlwaysOnScheduler(AmbientDisplayConfiguration config, UserTracker userTracker,
            AlarmManager alarmManager, @Main Handler mainHandler,
            @Background Handler bgHandler) {
        mConfig = config;
        mUserTracker = userTracker;
        mMainHandler = mainHandler;
        mBoundaryAlarm = new AlarmTimeout(alarmManager, this::onBoundaryReached,
                "doze_aod_schedule", bgHandler);
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case DOZE:
            case DOZE_AOD:
                scheduleNextBoundary();
                break;
            case FINISH:
                mBoundaryAlarm.cancel();
                break;
            default:
                // no-op
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeAlwaysOnScheduler:");
        pw.println(" alwaysOnScheduleAllowsNow="
                + mConfig.alwaysOnScheduleAllowsNow(mUserTracker.getUserId()));
        pw.println(" boundaryAlarmScheduled=" + mBoundaryAlarm.isScheduled());
    }

    private void scheduleNextBoundary() {
        final LocalTime[] window = scheduleWindowOrNull();
        if (window == null) {
            mBoundaryAlarm.cancel();
            return;
        }
        final LocalTime now = LocalTime.now();
        final long delayMs = Math.min(millisUntil(now, window[0]), millisUntil(now, window[1]));
        mBoundaryAlarm.schedule(delayMs, AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
    }

    /**
     * The schedule window, or {@code null} when no boundary alarm is needed (AOD unavailable,
     * schedule off or in a mode this class does not handle, or unparsable times).
     */
    private LocalTime[] scheduleWindowOrNull() {
        final int userId = mUserTracker.getUserId();
        if (!mConfig.alwaysOnAvailableForUser(userId)
                || !mConfig.alwaysOnCustomTimeScheduleEnabled(userId)) {
            return null;
        }
        return mConfig.alwaysOnScheduleWindow(userId);
    }

    private static long millisUntil(LocalTime now, LocalTime target) {
        long millis = Duration.between(now, target).toMillis();
        if (millis <= 0) {
            millis += Duration.ofDays(1).toMillis();
        }
        return millis;
    }

    private void onBoundaryReached() {
        // The alarm fires on the background handler; DozeMachine is main-thread only.
        mMainHandler.post(() -> {
            if (mMachine == null) {
                return;
            }
            final State state = mMachine.getState();
            if ((state != State.DOZE && state != State.DOZE_AOD)
                    || mMachine.isExecutingTransition()) {
                // Some other part owns the current state; it will re-resolve AOD from
                // alwaysOnEnabled() when it is done.
                scheduleNextBoundary();
                return;
            }
            final boolean wantAod = mConfig.alwaysOnEnabled(mUserTracker.getUserId());
            if (wantAod && state == State.DOZE) {
                mMachine.requestState(State.DOZE_AOD);
            } else if (!wantAod && state == State.DOZE_AOD) {
                mMachine.requestState(State.DOZE);
            }
            scheduleNextBoundary();
        });
    }
}
