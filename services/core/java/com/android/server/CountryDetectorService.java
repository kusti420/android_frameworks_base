/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server;

import android.annotation.Nullable;
import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryDetector;
import android.location.ICountryListener;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.location.IchthysLocationNoFix;
import com.android.server.location.IchthysLocationSpoof;
import com.android.server.location.countrydetector.ComprehensiveCountryDetector;
import com.android.server.location.countrydetector.CountryDetectorBase;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/**
 * This class detects the country that the user is in. The default country detection is made through
 * {@link ComprehensiveCountryDetector}. It is possible to overlay the detection algorithm by
 * overlaying the attribute R.string.config_customCountryDetector with the custom class name to use
 * instead. The custom class must extend {@link CountryDetectorBase}
 *
 * @hide
 */
public class CountryDetectorService extends ICountryDetector.Stub {

    /**
     * The class represents the remote listener, it will also removes itself from listener list when
     * the remote process was died.
     */
    private final class Receiver implements IBinder.DeathRecipient {
        private final ICountryListener mListener;
        private final IBinder mKey;
        // IchthysOS: the registering app, for the "No location" check in notifyReceivers.
        private final int mUid;

        public Receiver(ICountryListener listener) {
            mListener = listener;
            mKey = listener.asBinder();
            mUid = Binder.getCallingUid();
        }

        public void binderDied() {
            removeListener(mKey);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj instanceof Receiver) {
                return mKey.equals(((Receiver) otherObj).mKey);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mKey.hashCode();
        }

        public ICountryListener getListener() {
            return mListener;
        }
    }

    private static final String TAG = "CountryDetector";

    /**
     * Whether to dump the state of the country detector service to bugreports
     */
    private static final boolean DEBUG = false;

    private final HashMap<IBinder, Receiver> mReceivers;
    private final Context mContext;
    private CountryDetectorBase mCountryDetector;
    private boolean mSystemReady;
    private Handler mHandler;
    private CountryListener mLocationBasedDetectorListener;

    public CountryDetectorService(Context context) {
        this(context, BackgroundThread.getHandler());
    }

    @VisibleForTesting
    CountryDetectorService(Context context, Handler handler) {
        super();
        mReceivers = new HashMap<>();
        mContext = context;
        mHandler = handler;
    }

    @Override
    public Country detectCountry() {
        if (!mSystemReady) {
            return null; // server not yet active
        }
        // IchthysOS "Spoofed values": a spoofed app is told the profile's country, and nothing
        // while the fake place is on without a profile; the real one would place the phone.
        final IchthysLocationSpoof.Profile spoof = IchthysLocationSpoof.getSpoofForCaller();
        if (spoof != null) {
            return spoofedCountry(spoof);
        }
        // IchthysOS "No location": a country taken from a location fix is a coarse fix itself.
        return withheldForNoFix(mCountryDetector.detectCountry(), Binder.getCallingUid());
    }

    /**
     * IchthysOS: null instead of {@code country} when it comes from a location fix and
     * {@code uid} is on the "No location" list (IchthysLocationNoFix, any package of the uid).
     * Countries from the network, the SIM or the locale are left alone: those don't need a fix.
     */
    @Nullable
    private Country withheldForNoFix(@Nullable Country country, int uid) {
        if (country == null || country.getSource() != Country.COUNTRY_SOURCE_LOCATION
                || UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return country;
        }
        final String[] packages;
        final long token = Binder.clearCallingIdentity();
        try {
            packages = mContext.getPackageManager().getPackagesForUid(uid);
        } catch (RuntimeException e) {
            return country;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (packages != null) {
            final int userId = UserHandle.getUserId(uid);
            for (String pkg : packages) {
                if (IchthysLocationNoFix.isNoFix(pkg, userId)) {
                    return null;
                }
            }
        }
        return country;
    }

    @Nullable
    private static Country spoofedCountry(IchthysLocationSpoof.Profile spoof) {
        return spoof.country != null
                ? new Country(spoof.country, Country.COUNTRY_SOURCE_NETWORK) : null;
    }

    /**
     * Add the ICountryListener into the listener list.
     */
    @Override
    public void addCountryListener(ICountryListener listener) throws RemoteException {
        if (!mSystemReady) {
            throw new RemoteException();
        }
        // IchthysOS: a spoofed app hears the profile's country once and is never registered,
        // so no real detection ever reaches it (removeCountryListener is then a no-op).
        final IchthysLocationSpoof.Profile spoof = IchthysLocationSpoof.getSpoofForCaller();
        if (spoof != null) {
            final Country country = spoofedCountry(spoof);
            if (country != null) {
                listener.onCountryDetected(country);
            }
            return;
        }
        addListener(listener);
    }

    /**
     * Remove the ICountryListener from the listener list.
     */
    @Override
    public void removeCountryListener(ICountryListener listener) throws RemoteException {
        if (!mSystemReady) {
            throw new RemoteException();
        }
        removeListener(listener.asBinder());
    }

    private void addListener(ICountryListener listener) {
        synchronized (mReceivers) {
            Receiver r = new Receiver(listener);
            try {
                listener.asBinder().linkToDeath(r, 0);
                final Country country = detectCountry();
                if (country != null) {
                    listener.onCountryDetected(country);
                }
                mReceivers.put(listener.asBinder(), r);
                if (mReceivers.size() == 1) {
                    Slog.d(TAG, "The first listener is added");
                    setCountryListener(mLocationBasedDetectorListener);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed:", e);
            }
        }
    }

    private void removeListener(IBinder key) {
        synchronized (mReceivers) {
            mReceivers.remove(key);
            if (mReceivers.isEmpty()) {
                setCountryListener(null);
                Slog.d(TAG, "No listener is left");
            }
        }
    }

    protected void notifyReceivers(Country country) {
        synchronized (mReceivers) {
            for (Receiver receiver : mReceivers.values()) {
                // IchthysOS "No location": see withheldForNoFix; such an app hears nothing.
                final Country forReceiver = withheldForNoFix(country, receiver.mUid);
                if (forReceiver == null) {
                    continue;
                }
                try {
                    receiver.getListener().onCountryDetected(forReceiver);
                } catch (RemoteException e) {
                    // TODO: Shall we remove the receiver?
                    Slog.e(TAG, "notifyReceivers failed:", e);
                }
            }
        }
    }

    void systemRunning() {
        // Shall we wait for the initialization finish.
        mHandler.post(
                () -> {
                    initialize();
                    mSystemReady = true;
                });
    }

    @VisibleForTesting
    void initialize() {
        final String customCountryClass = mContext.getString(R.string.config_customCountryDetector);
        if (!TextUtils.isEmpty(customCountryClass)) {
            mCountryDetector = loadCustomCountryDetectorIfAvailable(customCountryClass);
        }

        if (mCountryDetector == null) {
            Slog.d(TAG, "Using default country detector");
            mCountryDetector = new ComprehensiveCountryDetector(mContext);
        }
        mLocationBasedDetectorListener = country -> mHandler.post(() -> notifyReceivers(country));
    }

    protected void setCountryListener(final CountryListener listener) {
        mHandler.post(() -> mCountryDetector.setCountryListener(listener));
    }

    @VisibleForTesting
    CountryDetectorBase getCountryDetector() {
        return mCountryDetector;
    }

    @VisibleForTesting
    boolean isSystemReady() {
        return mSystemReady;
    }

    private CountryDetectorBase loadCustomCountryDetectorIfAvailable(
            final String customCountryClass) {
        CountryDetectorBase customCountryDetector = null;

        Slog.d(TAG, "Using custom country detector class: " + customCountryClass);
        try {
            customCountryDetector = Class.forName(customCountryClass).asSubclass(
                    CountryDetectorBase.class).getConstructor(Context.class).newInstance(
                    mContext);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                | NoSuchMethodException | InvocationTargetException e) {
            Slog.e(TAG, "Could not instantiate the custom country detector class");
        }

        return customCountryDetector;
    }

    @SuppressWarnings("unused")
    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, fout)) return;
        if (!DEBUG) return;
        try {
            final Printer p = new PrintWriterPrinter(fout);
            p.println("CountryDetectorService state:");
            p.println("Country detector class=" + mCountryDetector.getClass().getName());
            p.println("  Number of listeners=" + mReceivers.keySet().size());
            if (mCountryDetector == null) {
                p.println("  CountryDetector not initialized");
            } else {
                p.println("  " + mCountryDetector.toString());
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to dump CountryDetectorService: ", e);
        }
    }
}
