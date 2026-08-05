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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.AmbientDisplayConfiguration;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeMachine.State;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Applies the crDroid "always show when charging" option
 * ({@code Settings.Secure.DOZE_ON_CHARGE}) while the device is already dozing.
 *
 * {@link AmbientDisplayConfiguration#alwaysOnEnabled} already accounts for the charger, which
 * covers the case of the screen turning off with the charger already attached (DozeMachine reads it
 * when it resolves DOZE vs DOZE_AOD). It does not cover plugging or unplugging the device *while*
 * it is dozing, because nothing re-evaluates the state then -- that is what this part is for.
 * Modelled on {@link DozeDockHandler}, which has the same shape.
 */
@DozeScope
public class DozeChargeHandler implements DozeMachine.Part {

    private final AmbientDisplayConfiguration mConfig;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserTracker mUserTracker;
    private final Executor mMainExecutor;
    private final ChargeReceiver mChargeReceiver = new ChargeReceiver();

    private DozeMachine mMachine;

    @Inject
    DozeChargeHandler(AmbientDisplayConfiguration config, BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker, @Main Executor mainExecutor) {
        mConfig = config;
        mBroadcastDispatcher = broadcastDispatcher;
        mUserTracker = userTracker;
        mMainExecutor = mainExecutor;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mChargeReceiver.register();
                break;
            case FINISH:
                mChargeReceiver.unregister();
                break;
            default:
                // no-op
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeChargeHandler:");
        pw.println(" dozeOnChargeEnabled="
                + mConfig.alwaysOnChargingEnabledSetting(mUserTracker.getUserId()));
    }

    private class ChargeReceiver extends BroadcastReceiver {
        private boolean mRegistered;

        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = mUserTracker.getUserId();
            if (mMachine == null || !mConfig.alwaysOnChargingEnabledSetting(userId)) {
                return;
            }
            final State state = mMachine.getState();
            // Only DOZE <-> DOZE_AOD are ours to flip. Docked, pulsing, min mode and the
            // intermediate states are owned by other parts, and DozeMachine re-resolves them from
            // alwaysOnEnabled() on its own once they finish.
            if (mMachine.isExecutingTransition()) {
                return;
            }
            if (state == State.DOZE
                    && Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                mMachine.requestState(State.DOZE_AOD);
            } else if (state == State.DOZE_AOD
                    && Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())
                    // The plain always-on setting wins; only drop back to DOZE if the charger was
                    // the only reason AOD was on.
                    && !(mConfig.alwaysOnEnabledSetting(userId)
                            && mConfig.alwaysOnScheduleAllowsNow(userId))) {
                mMachine.requestState(State.DOZE);
            }
        }

        void register() {
            if (mRegistered) {
                return;
            }
            final IntentFilter filter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            // DozeMachine#requestState is @MainThread, so the receiver must not run on the
            // dispatcher's background executor.
            mBroadcastDispatcher.registerReceiver(this, filter, mMainExecutor);
            mRegistered = true;
        }

        void unregister() {
            if (!mRegistered) {
                return;
            }
            mBroadcastDispatcher.unregisterReceiver(this);
            mRegistered = false;
        }
    }
}
