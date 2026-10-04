/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.sensors;

import static com.android.server.sensors.SensorManagerInternal.ProximityActiveListener;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.utils.TimingsTraceAndSlog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

public class SensorService extends SystemService {
    private static final String START_NATIVE_SENSOR_SERVICE = "StartNativeSensorService";
    // IchthysOS: sensor fingerprinting protection (kusti420/ichthysos-tracker#30). Third-party
    // apps get a generic sensor list without uncalibrated sensors, 50 Hz motion sensors and
    // quantized values (native SensorService). Global kill switch, default on, and a per-user
    // comma-separated package exemption list (for Ichthys Guard). The ROM's own bundled FOSS
    // apps are put on that list once, see IchthysSensorExemptions.
    private static final String FINGERPRINT_PROTECTION_SETTING =
            "ichthys_sensor_fingerprint_protection";
    private static final String FINGERPRINT_EXEMPT_SETTING = "ichthys_sensor_exempt_pkgs";
    private final IchthysSensorExemptions mBundledExemptions = new IchthysSensorExemptions();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<ProximityActiveListener, ProximityListenerProxy> mProximityListeners =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private final Set<Integer> mRuntimeSensorHandles = new HashSet<>();
    @GuardedBy("mLock")
    private Future<?> mSensorServiceStart;
    @GuardedBy("mLock")
    private long mPtr;


    /** Start the sensor service. This is a blocking call and can take time. */
    private static native long startSensorServiceNative(ProximityActiveListener listener);

    private static native void registerProximityActiveListenerNative(long ptr);
    private static native void unregisterProximityActiveListenerNative(long ptr);

    private static native int registerRuntimeSensorNative(long ptr, int deviceId, int type,
            String name, String vendor, float maximumRange, float resolution, float power,
            int minDelay, int maxDelay, int flags,
            SensorManagerInternal.RuntimeSensorCallback callback);
    private static native void unregisterRuntimeSensorNative(long ptr, int handle);
    private static native boolean sendRuntimeSensorEventNative(long ptr, int handle, int type,
            long timestampNanos, float[] values);
    private static native boolean sendRuntimeSensorAdditionalInfoNative(long ptr, int handle,
            int type, int serial, long timestampNanos, float[] values);
    private static native void setFingerprintProtectionNative(long ptr, boolean enabled,
            String[] exemptions);


    public SensorService(Context ctx) {
        super(ctx);
        synchronized (mLock) {
            mSensorServiceStart = SystemServerInitThreadPool.submit(() -> {
                TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                traceLog.traceBegin(START_NATIVE_SENSOR_SERVICE);
                long ptr = startSensorServiceNative(new ProximityListenerDelegate());
                synchronized (mLock) {
                    mPtr = ptr;
                }
                traceLog.traceEnd();
            }, START_NATIVE_SENSOR_SERVICE);
        }
    }

    @Override
    public void onStart() {
        LocalServices.addService(SensorManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE) {
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart,
                    START_NATIVE_SENSOR_SERVICE);
            synchronized (mLock) {
                mSensorServiceStart = null;
            }
        } else if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            startFingerprintProtectionSync();
        }
    }

    /** IchthysOS: keeps the native fingerprinting-protection state in sync with Settings. */
    private void startFingerprintProtectionSync() {
        final Handler handler = BackgroundThread.getHandler();
        final ContentResolver resolver = getContext().getContentResolver();
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                pushFingerprintProtection();
            }
        };
        resolver.registerContentObserver(
                Settings.Global.getUriFor(FINGERPRINT_PROTECTION_SETTING), false, observer,
                UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(FINGERPRINT_EXEMPT_SETTING), false, observer,
                UserHandle.USER_ALL);
        // Package and user changes: re-push so native drops its uid -> package cache.
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                pushFingerprintProtection();
            }
        };
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        getContext().registerReceiverAsUser(receiver, UserHandle.ALL, packageFilter, null,
                handler);
        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiverAsUser(receiver, UserHandle.ALL, userFilter, null, handler);
        handler.post(this::pushFingerprintProtection);
    }

    private void pushFingerprintProtection() {
        final ContentResolver resolver = getContext().getContentResolver();
        final boolean enabled =
                Settings.Global.getInt(resolver, FINGERPRINT_PROTECTION_SETTING, 1) != 0;
        final ArrayList<String> exemptions = new ArrayList<>();
        final UserManager userManager = getContext().getSystemService(UserManager.class);
        for (UserInfo user : userManager.getAliveUsers()) {
            try {
                // Runs on every push: at boot, and when a package or user is added.
                mBundledExemptions.seed(getContext(), FINGERPRINT_EXEMPT_SETTING, user.id);
            } catch (RuntimeException e) {
                Slog.w("SensorService", "bundled sensor exemptions not seeded for user "
                        + user.id, e);
            }
            final String value = Settings.Secure.getStringForUser(resolver,
                    FINGERPRINT_EXEMPT_SETTING, user.id);
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            for (String pkg : value.split(",")) {
                pkg = pkg.trim();
                if (!pkg.isEmpty()) {
                    exemptions.add(user.id + ":" + pkg);
                }
            }
        }
        synchronized (mLock) {
            if (mPtr != 0) {
                setFingerprintProtectionNative(mPtr, enabled, exemptions.toArray(new String[0]));
            }
        }
    }

    class LocalService extends SensorManagerInternal {
        @Override
        public int createRuntimeSensor(int deviceId, int type, @NonNull String name,
                @NonNull String vendor, float maximumRange, float resolution, float power,
                int minDelay, int maxDelay, int flags, @NonNull RuntimeSensorCallback callback) {
            synchronized (mLock) {
                int handle = registerRuntimeSensorNative(mPtr, deviceId, type, name, vendor,
                        maximumRange, resolution, power, minDelay, maxDelay, flags, callback);
                mRuntimeSensorHandles.add(handle);
                return handle;
            }
        }

        @Override
        public void removeRuntimeSensor(int handle) {
            synchronized (mLock) {
                if (mRuntimeSensorHandles.contains(handle)) {
                    mRuntimeSensorHandles.remove(handle);
                    unregisterRuntimeSensorNative(mPtr, handle);
                }
            }
        }

        @Override
        public boolean sendSensorEvent(int handle, int type, long timestampNanos,
                @NonNull float[] values) {
            synchronized (mLock) {
                if (!mRuntimeSensorHandles.contains(handle)) {
                    return false;
                }
                return sendRuntimeSensorEventNative(mPtr, handle, type, timestampNanos, values);
            }
        }

        @Override
        public boolean sendSensorAdditionalInfo(int handle, int type, int serial,
                long timestampNanos, @Nullable float[] values) {
            synchronized (mLock) {
                if (!mRuntimeSensorHandles.contains(handle)) {
                    return false;
                }
                return sendRuntimeSensorAdditionalInfoNative(mPtr, handle, type, serial,
                        timestampNanos, values);
            }
        }

        @Override
        public void addProximityActiveListener(@NonNull Executor executor,
                @NonNull ProximityActiveListener listener) {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            ProximityListenerProxy proxy = new ProximityListenerProxy(executor, listener);
            synchronized (mLock) {
                if (mProximityListeners.containsKey(listener)) {
                    throw new IllegalArgumentException("listener already registered");
                }
                mProximityListeners.put(listener, proxy);
                if (mProximityListeners.size() == 1) {
                    registerProximityActiveListenerNative(mPtr);
                }
            }
        }

        @Override
        public void removeProximityActiveListener(@NonNull ProximityActiveListener listener) {
            Objects.requireNonNull(listener, "listener must not be null");
            synchronized (mLock) {
                ProximityListenerProxy proxy = mProximityListeners.remove(listener);
                if (proxy == null) {
                    throw new IllegalArgumentException(
                            "listener was not registered with sensor service");
                }
                if (mProximityListeners.isEmpty()) {
                    unregisterProximityActiveListenerNative(mPtr);
                }
            }
        }
    }

    private static class ProximityListenerProxy implements ProximityActiveListener {
        private final Executor mExecutor;
        private final ProximityActiveListener mListener;

        ProximityListenerProxy(Executor executor, ProximityActiveListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onProximityActive(boolean isActive) {
            mExecutor.execute(() -> mListener.onProximityActive(isActive));
        }
    }

    private class ProximityListenerDelegate implements ProximityActiveListener {
        @Override
        public void onProximityActive(boolean isActive) {
            final ProximityListenerProxy[] listeners;
            // We can't call out while holding the lock because clients might be calling into us
            // while holding their own  locks (e.g. when registering / unregistering their
            // listeners).This would break lock ordering and create deadlocks. Instead, we need to
            // copy the listeners out and then only invoke them once we've dropped the lock.
            synchronized (mLock) {
                listeners = mProximityListeners.values().toArray(new ProximityListenerProxy[0]);
            }
            for (ProximityListenerProxy listener : listeners) {
                listener.onProximityActive(isActive);
            }
        }
    }
}
