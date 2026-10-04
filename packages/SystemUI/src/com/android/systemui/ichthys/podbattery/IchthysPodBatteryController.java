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

package com.android.systemui.ichthys.podbattery;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * IchthysOS: battery levels of AirPods (left bud, right bud, case, charging) without any app
 * installed.
 *
 * <p>AirPods do not report per-bud battery over a standard profile. This class gets it from the
 * connected earbuds and writes it into that device's metadata: the slots Fast Pair earbuds use
 * ({@code METADATA_UNTETHERED_*_BATTERY} and {@code _CHARGING}). Everything that already shows
 * Bluetooth battery reads those through SettingsLib, so the device details page, the Bluetooth
 * tile and the status bar pill need nothing else.
 *
 * <p><b>Why SystemUI.</b> It is always running, already holds BLUETOOTH_SCAN, BLUETOOTH_CONNECT
 * and BLUETOOTH_PRIVILEGED (needed to write metadata), and already tracks connected devices.
 * Nothing new is exported and no permission is added.
 *
 * <p><b>Which device.</b> A connected device counts if its service records list Apple's accessory
 * service ({@link #ACCESSORY_UUID}), the same test the Bluetooth stack uses for these devices in
 * {@code l2c_should_skip_ertm}. The name is only a fallback for clones without that record. With
 * two such devices connected at once nothing is done.
 *
 * <p><b>The direct channel</b> ({@link AapChannel}) is the source: the earbuds' own control
 * channel on the connection that is already there, so the numbers are certainly this device's,
 * and exact. It is opened once the audio profiles are up, retried a few times with growing
 * pauses, and then left alone until the device connects again. Nothing here touches the audio
 * profiles; if the channel fails, the outcome is no battery levels.
 *
 * <p><b>Companion apps.</b> The earbuds' channel has been seen to go to whoever takes it first,
 * leaving a second client on the same phone without one. An app that uses it does far more than
 * read the battery, so while one of {@link #COMPANION_APPS} is installed and enabled this class
 * does not open the channel.
 *
 * <p><b>Nearby broadcasts</b> ({@link ProximityBeacon}) are the fallback, off unless
 * {@link #KEY_BROADCAST_FALLBACK} is set, and used only while the direct channel is not to be
 * had. The earbuds also broadcast their state in Bluetooth LE advertisements, but from a random
 * address that cannot be tied to the paired device: a stranger's pair on the next seat looks the
 * same. The strongest advertisement of the last ten seconds that is at least {@link #MIN_RSSI}
 * dBm is taken, and a changed reading must repeat for {@link #CONFIRM_MS}. Without the fallback
 * and without the channel nothing is written, and what was written is taken back.
 *
 * <p><b>Other writers.</b> After somebody else writes a battery slot of the device this class
 * writes nothing for {@link #OTHER_WRITER_HOLD_MS}, and a broadcast, which only says tens of
 * percent, never replaces a stored value it agrees with to within ten points.
 *
 * <p>Verifying on a device: channel attempts and their outcome are logged at INFO. {@code adb
 * shell setprop log.tag.IchthysPodBattery DEBUG} adds every battery notification, every decoded
 * advertisement and every metadata write.
 */
@SysUISingleton
public class IchthysPodBatteryController implements CoreStartable, BluetoothController.Callback,
        AapChannel.Listener {

    private static final String TAG = "IchthysPodBattery";

    /**
     * {@code Settings.Secure}, per user: 0 turns the feature off; unset counts as on. The
     * {@code bluetooth.airpodsBattery} option and the switch in Bluetooth settings.
     */
    static final String KEY_ENABLED = "ichthys_airpods_battery";

    /**
     * {@code Settings.Secure}, per user: 1 allows reading nearby broadcasts while the direct
     * channel is unavailable; unset counts as off. The {@code bluetooth.airpodsBroadcastFallback}
     * option and the second switch in Bluetooth settings.
     */
    static final String KEY_BROADCAST_FALLBACK = "ichthys_airpods_battery_broadcast_fallback";

    /** Apple's accessory service, in the device's SDP records. Some stacks report it reversed. */
    private static final ParcelUuid ACCESSORY_UUID =
            ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");
    private static final ParcelUuid ACCESSORY_UUID_REVERSED =
            ParcelUuid.fromString("2a72e02b-7b99-778f-014d-ad0b7221ec74");

    /** Apps that use the earbuds' control channel themselves: LibrePods and CAPod. */
    private static final String[] COMPANION_APPS = {
            "me.kavishdevar.librepods",
            "eu.darken.capod",
    };

    /**
     * How long after the audio profiles are up the channel is opened. Earbuds whose control
     * channel came up before A2DP have been seen to stay without audio (on another stack).
     */
    private static final long FIRST_ATTEMPT_DELAY_MS = 5_000;
    /** A device with only the hands-free profile up is given this long to bring up A2DP. */
    private static final long WAIT_FOR_A2DP_MS = 30_000;
    /** Pauses before the second, third and fourth attempt; after those, no more. */
    private static final long[] RETRY_DELAYS_MS = {15_000, 45_000, 120_000};
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    /** The first battery report normally follows the notification request within seconds. */
    private static final long FIRST_REPORT_TIMEOUT_MS = 15_000;

    /** Weaker advertisements are more likely somebody else's earbuds than the user's. */
    private static final int MIN_RSSI = -60;
    private static final long BEACON_MAX_AGE_MS = 10_000;
    private static final long CONFIRM_MS = 4_000;
    /** Keep listening this long after the screen turns off, then stop to save power. */
    private static final long SCREEN_OFF_GRACE_MS = 30_000;
    private static final long OTHER_WRITER_HOLD_MS = 10 * 60_000;
    /** The controller's device list is refreshed in the background; look again once it is. */
    private static final long SETTLE_MS = 2_000;
    private static final long SCAN_RETRY_MS = 30_000;

    /** Devices this class wrote levels for, and those it marked as untethered headsets. */
    private static final String PREFS = "ichthys_pod_battery";
    private static final String PREF_WRITTEN = "written";
    private static final String PREF_MARKED = "marked_untethered";

    private static final int[] BATTERY_KEYS = {
            BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY,
            BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY,
            BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY,
            BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING,
            BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING,
            BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING,
            BluetoothDevice.METADATA_MAIN_BATTERY,
            BluetoothDevice.METADATA_MAIN_CHARGING,
    };

    private static final byte[] NO_VALUE = new byte[0];

    /** Where the direct channel to the tracked device stands. */
    private enum Channel {
        /** Not tried yet for this connection. */
        IDLE,
        /** An attempt is scheduled. */
        WAITING,
        CONNECTING,
        OPEN,
        /** Out of attempts; nothing more until the device connects again. */
        FAILED,
        /** Left to a companion app. */
        YIELDED,
    }

    private final Context mContext;
    private final BluetoothController mBluetoothController;
    @Nullable private final BluetoothAdapter mAdapter;
    private final SecureSettings mSecureSettings;
    private final UserTracker mUserTracker;
    private final BroadcastDispatcher mBroadcastDispatcher;
    /** All state below is confined to this handler's thread; callbacks only post to it. */
    private final Handler mBgHandler;
    private final Executor mBgExecutor;

    private boolean mEnabled;
    private boolean mBroadcastFallback;
    private boolean mInteractive = true;
    private long mScreenOffAt;
    @Nullable private CachedBluetoothDevice mTargetCached;
    @Nullable private BluetoothDevice mTarget;
    private long mTargetSince;
    /** Whether the device's levels were taken back and nothing has been written since. */
    private boolean mWithdrawn;

    private Channel mChannelState = Channel.IDLE;
    @Nullable private AapChannel mChannel;
    private int mAttempts;
    /** The channel is open, but the earbuds have not sent a battery report when they should. */
    private boolean mSilent;
    /** What the earbuds reported over the channel, all notifications of the session merged. */
    @Nullable private AapBattery mReported;
    /** Whether {@link #mReported} is still to be written, another writer having been at work. */
    private boolean mHeldBack;

    @Nullable private BluetoothLeScanner mScanner;
    /** Latest advertisement per (random) sender address. */
    private final ArrayMap<String, Seen> mSeen = new ArrayMap<>();
    @Nullable private ProximityBeacon mPublishedBeacon;
    @Nullable private ProximityBeacon mPendingBeacon;
    private long mPendingSince;

    /** Our own writes still to be echoed by the metadata listener, as "key=value". */
    private final ArraySet<String> mOwnWrites = new ArraySet<>();
    private long mOtherWriterUntil;

    private final Runnable mEvaluate = this::evaluate;
    private final Runnable mConnect = this::connect;
    private final Runnable mWatchdog = this::onChannelSilent;

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            // Delivered on the main thread.
            mBgHandler.post(() -> onAdvertisement(result));
        }

        @Override
        public void onScanFailed(int errorCode) {
            mBgHandler.post(() -> {
                Log.w(TAG, "scan failed: " + errorCode);
                if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                    mScanner = null;
                    schedule(SCAN_RETRY_MS);
                }
            });
        }
    };

    private final BluetoothAdapter.OnMetadataChangedListener mMetadataListener =
            (device, key, value) -> {
                if (!isBatteryKey(key) || !device.equals(mTarget)) {
                    return;
                }
                final String written = key + "=" + (value == null ? "" : text(value));
                if (mOwnWrites.remove(written)) {
                    return;
                }
                mOtherWriterUntil = SystemClock.elapsedRealtime() + OTHER_WRITER_HOLD_MS;
                if (debug()) {
                    Log.d(TAG, "another writer set " + keyName(key) + " (" + written
                            + "); standing back");
                }
            };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mInteractive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (!mInteractive) {
                mScreenOffAt = SystemClock.elapsedRealtime();
            }
            evaluate();
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Uri data = intent.getData();
            final String changed = data == null ? null : data.getSchemeSpecificPart();
            for (String app : COMPANION_APPS) {
                if (app.equals(changed)) {
                    evaluate();
                    return;
                }
            }
        }
    };

    @Inject
    public IchthysPodBatteryController(
            @Application Context context,
            BluetoothController bluetoothController,
            @Nullable BluetoothAdapter adapter,
            SecureSettings secureSettings,
            UserTracker userTracker,
            BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler) {
        mContext = context;
        mBluetoothController = bluetoothController;
        mAdapter = adapter;
        mSecureSettings = secureSettings;
        mUserTracker = userTracker;
        mBroadcastDispatcher = broadcastDispatcher;
        mBgHandler = bgHandler;
        mBgExecutor = new HandlerExecutor(bgHandler);
    }

    @Override
    public void start() {
        if (mAdapter == null) {
            return;
        }
        final ContentObserver observer = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange) {
                evaluate();
            }
        };
        mSecureSettings.registerContentObserverForUserAsync(KEY_ENABLED, observer,
                UserHandle.USER_ALL);
        mSecureSettings.registerContentObserverForUserAsync(KEY_BROADCAST_FALLBACK, observer,
                UserHandle.USER_ALL);
        mUserTracker.addCallback(new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                evaluate();
            }
        }, mBgExecutor);

        final IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        mBroadcastDispatcher.registerReceiver(mScreenReceiver, screen, mBgExecutor,
                UserHandle.ALL);

        // Not through the dispatcher: it takes no filters with a data scheme.
        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        mContext.registerReceiverForAllUsers(mPackageReceiver, packages, null, mBgHandler);

        mBluetoothController.addCallback(this);
        mBgHandler.post(() -> {
            final PowerManager power = mContext.getSystemService(PowerManager.class);
            mInteractive = power == null || power.isInteractive();
            evaluate();
        });
    }

    @Override
    public void onBluetoothStateChange(boolean enabled) {
        mBgHandler.post(this::evaluateNowAndOnceSettled);
    }

    @Override
    public void onBluetoothDevicesChanged() {
        mBgHandler.post(this::evaluateNowAndOnceSettled);
    }

    private void evaluateNowAndOnceSettled() {
        evaluate();
        schedule(SETTLE_MS);
    }

    private void schedule(long delayMs) {
        mBgHandler.removeCallbacks(mEvaluate);
        mBgHandler.postDelayed(mEvaluate, delayMs);
    }

    /**
     * Brings everything in line with the settings, the connection and the screen. Cheap and safe
     * to call at any time; it never starts a channel attempt that is not due.
     */
    private void evaluate() {
        final int user = mUserTracker.getUserId();
        mEnabled = mSecureSettings.getIntForUser(KEY_ENABLED, 1, user) != 0;
        mBroadcastFallback = mSecureSettings.getIntForUser(KEY_BROADCAST_FALLBACK, 0, user) != 0;
        final boolean bluetoothOn = mAdapter.isEnabled();
        if (!mEnabled && bluetoothOn) {
            // Off means stock: take back what was written, or the stale numbers stay on show.
            forgetWrittenDevices();
        }
        setTarget(mEnabled && bluetoothOn ? findTarget() : null);
        if (mTarget == null) {
            stopScan();
            return;
        }

        updateChannel();
        final boolean channelGone = mChannelState == Channel.FAILED
                || mChannelState == Channel.YIELDED || mSilent;
        if (mHeldBack) {
            publishReported();
        }
        if (channelGone && !mBroadcastFallback) {
            // Also when the fallback is switched off while it was the source.
            withdraw();
        }

        final long sinceScreenOff = SystemClock.elapsedRealtime() - mScreenOffAt;
        if (channelGone && mBroadcastFallback
                && (mInteractive || sinceScreenOff < SCREEN_OFF_GRACE_MS)) {
            startScan();
            if (!mInteractive) {
                schedule(SCREEN_OFF_GRACE_MS - sinceScreenOff);
            }
        } else {
            stopScan();
        }
    }

    /** The one connected AirPods-like device, or null if there is none or more than one. */
    @Nullable
    private CachedBluetoothDevice findTarget() {
        CachedBluetoothDevice found = null;
        for (CachedBluetoothDevice cached : mBluetoothController.getConnectedDevices()) {
            if (!isApplePods(cached.getDevice())) {
                continue;
            }
            if (found != null) {
                Log.i(TAG, "two candidate devices connected; leaving both alone");
                return null;
            }
            found = cached;
        }
        return found;
    }

    private static boolean isApplePods(BluetoothDevice device) {
        try {
            final ParcelUuid[] uuids = device.getUuids();
            if (uuids != null) {
                for (ParcelUuid uuid : uuids) {
                    if (ACCESSORY_UUID.equals(uuid) || ACCESSORY_UUID_REVERSED.equals(uuid)) {
                        return true;
                    }
                }
            }
            // Clones advertise the same way but may lack the service record. The name is the
            // device's own (not the alias given on this phone), and only audio devices count.
            final BluetoothClass btClass = device.getBluetoothClass();
            final String name = device.getName();
            return btClass != null
                    && btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO
                    && name != null && name.toLowerCase(Locale.ROOT).contains("airpods");
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read device properties", e);
            return false;
        }
    }

    private void setTarget(@Nullable CachedBluetoothDevice cached) {
        final BluetoothDevice device = cached == null ? null : cached.getDevice();
        if (Objects.equals(device, mTarget)) {
            mTargetCached = cached;
            return;
        }
        releaseChannel();
        if (mTarget != null) {
            try {
                mAdapter.removeOnMetadataChangedListener(mTarget, mMetadataListener);
            } catch (RuntimeException e) {
                // Not registered (any more): Bluetooth restarted in between.
            }
        }
        mTargetCached = cached;
        mTarget = device;
        mTargetSince = SystemClock.elapsedRealtime();
        mWithdrawn = false;
        mChannelState = Channel.IDLE;
        mAttempts = 0;
        mSilent = false;
        mReported = null;
        mHeldBack = false;
        mSeen.clear();
        mPublishedBeacon = null;
        mPendingBeacon = null;
        mOwnWrites.clear();
        mOtherWriterUntil = 0;
        if (device != null) {
            try {
                mAdapter.addOnMetadataChangedListener(device, mBgExecutor, mMetadataListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot watch metadata", e);
            }
        }
        Log.i(TAG, device == null ? "no device to track"
                : "tracking " + device.getAnonymizedAddress());
    }

    // ---------------------------------------------------------------------------------------------
    // The direct channel

    /** Schedules the first attempt when it is due, and gives way to a companion app. */
    private void updateChannel() {
        final String companion = enabledCompanionApp();
        if (companion != null) {
            if (mChannelState != Channel.YIELDED) {
                releaseChannel();
                mChannelState = Channel.YIELDED;
                Log.i(TAG, "leaving the channel to " + companion);
                withdraw();
            }
            return;
        }
        if (mChannelState == Channel.YIELDED) {
            mChannelState = Channel.IDLE;
            mAttempts = 0;
        }
        if (mChannelState != Channel.IDLE) {
            return;
        }
        final boolean a2dp = isProfileUp(true);
        final boolean handsFree = isProfileUp(false);
        final long tracked = SystemClock.elapsedRealtime() - mTargetSince;
        if (a2dp || (handsFree && tracked >= WAIT_FOR_A2DP_MS)) {
            mChannelState = Channel.WAITING;
            mBgHandler.postDelayed(mConnect, FIRST_ATTEMPT_DELAY_MS);
        } else if (handsFree) {
            schedule(WAIT_FOR_A2DP_MS - tracked);
        }
        // Neither profile up: the device list changes again when one is.
    }

    private boolean isProfileUp(boolean a2dp) {
        try {
            return a2dp ? mTargetCached.isConnectedA2dpDevice()
                    : mTargetCached.isConnectedHfpDevice();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private String enabledCompanionApp() {
        final PackageManager pm = mUserTracker.getUserContext().getPackageManager();
        for (String app : COMPANION_APPS) {
            try {
                if (pm.getApplicationInfo(app, 0).enabled) {
                    return app;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed for this user.
            }
        }
        return null;
    }

    private void connect() {
        if (mChannelState != Channel.WAITING || mTarget == null) {
            return;
        }
        // Connecting a socket to a device that has just gone would page it and bring up a link
        // with nothing but this channel on it. Wait for the audio profiles instead; the device
        // list changes when they are back, or the device is no longer tracked.
        if (!mTarget.isConnected() || !(isProfileUp(true) || isProfileUp(false))) {
            mChannelState = Channel.IDLE;
            Log.i(TAG, "channel: the audio profiles are down; not connecting");
            return;
        }
        mAttempts++;
        mChannelState = Channel.CONNECTING;
        Log.i(TAG, "channel: attempt " + mAttempts + " of " + (RETRY_DELAYS_MS.length + 1));
        mChannel = new AapChannel(mTarget, mBgExecutor, this);
        mChannel.start();
        mBgHandler.postDelayed(mWatchdog, CONNECT_TIMEOUT_MS);
    }

    /** Still connecting, or open without a first report, when it should long be done. */
    private void onChannelSilent() {
        if (mChannel == null) {
            return;
        }
        if (mChannelState == Channel.OPEN) {
            // Left open: a report may still come, and closing a channel the earbuds accepted
            // is the one thing here that could disturb them. It just counts as unavailable.
            mSilent = true;
            Log.i(TAG, "channel: open, but no battery report");
            withdraw();
            evaluate();
        } else {
            Log.i(TAG, "channel: connecting takes too long; giving this attempt up");
            // Its thread then reports the close, which is where the retry is decided.
            mChannel.close();
        }
    }

    /** Drops the channel and any pending attempt without counting it as a failure. */
    private void releaseChannel() {
        mBgHandler.removeCallbacks(mConnect);
        mBgHandler.removeCallbacks(mWatchdog);
        final AapChannel channel = mChannel;
        // Cleared first: the close is then reported for a channel that is no longer ours.
        mChannel = null;
        mSilent = false;
        if (channel != null) {
            channel.close();
            Log.i(TAG, "channel: closed");
        }
    }

    @Override
    public void onChannelOpen(AapChannel channel) {
        if (channel != mChannel) {
            return;
        }
        mChannelState = Channel.OPEN;
        mBgHandler.removeCallbacks(mWatchdog);
        mBgHandler.postDelayed(mWatchdog, FIRST_REPORT_TIMEOUT_MS);
        Log.i(TAG, "channel: open, notifications requested");
    }

    @Override
    public void onChannelBattery(AapChannel channel, AapBattery report) {
        if (channel != mChannel) {
            return;
        }
        mBgHandler.removeCallbacks(mWatchdog);
        mReported = report.onTopOf(mReported);
        if (debug()) {
            Log.d(TAG, "channel: battery " + report + " -> " + mReported);
        }
        publishReported();
        if (mSilent) {
            // Late, but there: the fallback is no longer needed.
            mSilent = false;
            evaluate();
        }
    }

    @Override
    public void onChannelClosed(AapChannel channel, String reason) {
        if (channel != mChannel) {
            return;
        }
        mChannel = null;
        mSilent = false;
        mBgHandler.removeCallbacks(mWatchdog);
        if (mAttempts <= RETRY_DELAYS_MS.length) {
            final long delay = RETRY_DELAYS_MS[mAttempts - 1];
            mChannelState = Channel.WAITING;
            mBgHandler.postDelayed(mConnect, delay);
            Log.i(TAG, "channel: " + reason + "; next attempt in " + delay / 1000 + " s");
        } else {
            mChannelState = Channel.FAILED;
            Log.i(TAG, "channel: " + reason + "; no more attempts until the device reconnects");
            withdraw();
            evaluate();
        }
    }

    private void publishReported() {
        final AapBattery levels = mReported;
        if (levels == null || mTarget == null || mChannelState != Channel.OPEN) {
            mHeldBack = false;
            return;
        }
        mHeldBack = SystemClock.elapsedRealtime() < mOtherWriterUntil;
        if (mHeldBack) {
            return;
        }
        if (levels.isSingle()) {
            publish(mTarget, true, levels.headphones, AapBattery.UNKNOWN, AapBattery.UNKNOWN,
                    levels.headphonesCharging, false, false, 1);
        } else {
            publish(mTarget, false, levels.left, levels.right, levels.caseLevel,
                    levels.leftCharging, levels.rightCharging, levels.caseCharging, 1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Nearby broadcasts (the fallback)

    private void startScan() {
        if (mScanner != null) {
            return;
        }
        try {
            final BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                return;
            }
            // Matched in the Bluetooth chip where it can be, so that other Apple traffic
            // (phones advertise all the time) does not wake this process.
            final byte[] prefix = {(byte) ProximityBeacon.ENTRY_TYPE,
                    (byte) ProximityBeacon.ENTRY_LENGTH, (byte) ProximityBeacon.PAYLOAD_PREFIX};
            final byte[] mask = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
            final ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(ProximityBeacon.APPLE_COMPANY_ID, prefix, mask)
                    .build();
            final ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, mScanCallback);
            mScanner = scanner;
            Log.i(TAG, "listening for broadcasts");
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start scan", e);
            schedule(SCAN_RETRY_MS);
        }
    }

    private void stopScan() {
        if (mScanner == null) {
            return;
        }
        try {
            mScanner.stopScan(mScanCallback);
        } catch (RuntimeException e) {
            // Bluetooth is off: the scan went with it.
        }
        mScanner = null;
        mSeen.clear();
        Log.i(TAG, "stopped listening for broadcasts");
    }

    private void onAdvertisement(ScanResult result) {
        if (mScanner == null || mTarget == null) {
            return;
        }
        final ScanRecord record = result.getScanRecord();
        final byte[] data = record == null ? null
                : record.getManufacturerSpecificData(ProximityBeacon.APPLE_COMPANY_ID);
        final ProximityBeacon beacon = ProximityBeacon.parse(data);
        if (beacon == null) {
            return;
        }
        final boolean usable = result.getRssi() >= MIN_RSSI && !beacon.isEmpty();
        if (debug()) {
            Log.d(TAG, "beacon " + beacon + " rssi=" + result.getRssi()
                    + (usable ? "" : " (ignored)") + " from="
                    + result.getDevice().getAnonymizedAddress() + " raw=" + hex(data, 10));
        }
        if (!usable) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        mSeen.put(result.getDevice().getAddress(), new Seen(beacon, result.getRssi(), now));
        final ProximityBeacon best = strongestRecent(now);
        if (best != null) {
            consider(best, now);
        }
    }

    /**
     * The strongest advertisement of the last {@link #BEACON_MAX_AGE_MS}. Once a model has been
     * published, advertisements of that model win over stronger ones of another: the connected
     * device does not change model, so the one seen before is the better guess.
     */
    @Nullable
    private ProximityBeacon strongestRecent(long now) {
        Seen best = null;
        boolean bestMatches = false;
        for (int i = mSeen.size() - 1; i >= 0; i--) {
            final Seen seen = mSeen.valueAt(i);
            if (now - seen.time > BEACON_MAX_AGE_MS) {
                mSeen.removeAt(i);
                continue;
            }
            final boolean matches = mPublishedBeacon != null
                    && seen.beacon.model == mPublishedBeacon.model;
            if (best == null || (matches && !bestMatches)
                    || (matches == bestMatches && seen.rssi > best.rssi)) {
                best = seen;
                bestMatches = matches;
            }
        }
        return best == null ? null : best.beacon;
    }

    private void consider(ProximityBeacon beacon, long now) {
        if (now < mOtherWriterUntil) {
            return;
        }
        if (beacon.sameReadingAs(mPublishedBeacon)) {
            mPendingBeacon = null;
            return;
        }
        // The first reading is shown at once; a change has to repeat, so that one stray
        // advertisement does not flip the numbers.
        if (mPublishedBeacon != null) {
            if (!beacon.sameReadingAs(mPendingBeacon)) {
                mPendingBeacon = beacon;
                mPendingSince = now;
                return;
            }
            if (now - mPendingSince < CONFIRM_MS) {
                return;
            }
        }
        mPendingBeacon = null;
        mPublishedBeacon = beacon;
        publish(mTarget, beacon.single, beacon.left, beacon.right, beacon.caseLevel,
                beacon.leftCharging, beacon.rightCharging, beacon.caseCharging, 10);
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata

    /**
     * Writes one reading. Levels are percent, or negative when that part is not there.
     *
     * @param single one battery ({@code left}) instead of two buds and a case
     * @param resolution how exact the levels are, in percent: a stored value closer than this
     *     is at least as good and is kept
     */
    private void publish(BluetoothDevice device, boolean single, int left, int right,
            int caseLevel, boolean leftCharging, boolean rightCharging, boolean caseCharging,
            int resolution) {
        remember(PREF_WRITTEN, device);
        mWithdrawn = false;
        if (single) {
            // The slot for a device with one battery. SettingsLib reads it only for input
            // devices today, so for headphones this changes nothing on screen yet.
            publishLevel(device, BluetoothDevice.METADATA_MAIN_BATTERY, left, resolution);
            publishFlag(device, BluetoothDevice.METADATA_MAIN_CHARGING, leftCharging);
            return;
        }
        // What makes SettingsLib and Settings read the three slots below.
        if (!readFlag(device, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            remember(PREF_MARKED, device);
            write(device, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, "true");
        }
        publishLevel(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, left, resolution);
        publishLevel(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, right,
                resolution);
        publishLevel(device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, caseLevel,
                resolution);
        publishFlag(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING, leftCharging);
        publishFlag(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING, rightCharging);
        publishFlag(device, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING, caseCharging);
    }

    private void publishLevel(BluetoothDevice device, int key, int level, int resolution) {
        final int current = readLevel(device, key);
        if (level < 0) {
            if (current >= 0) {
                write(device, key, "");
            }
            return;
        }
        if (current >= 0 && Math.abs(current - level) < resolution) {
            return;
        }
        write(device, key, Integer.toString(level));
    }

    private void publishFlag(BluetoothDevice device, int key, boolean value) {
        if (readFlag(device, key) != value) {
            write(device, key, Boolean.toString(value));
        }
    }

    /**
     * Takes back the levels of the tracked device: what kept them current is gone, and a stale
     * number is worse than none. Does nothing if that was done and nothing was written since,
     * and never removes another writer's fresh values.
     */
    private void withdraw() {
        if (mWithdrawn || mTarget == null) {
            return;
        }
        mWithdrawn = true;
        if (SystemClock.elapsedRealtime() < mOtherWriterUntil || !prefs()
                .getStringSet(PREF_WRITTEN, Collections.emptySet())
                .contains(mTarget.getAddress())) {
            return;
        }
        for (int key : BATTERY_KEYS) {
            write(mTarget, key, "");
        }
        Log.i(TAG, "no source for the levels; removed them");
    }

    /** The stored level, 0..100, or -1. */
    private static int readLevel(BluetoothDevice device, int key) {
        try {
            final byte[] value = device.getMetadata(key);
            final int level = value == null ? -1 : Integer.parseInt(text(value));
            return level >= 0 && level <= 100 ? level : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean readFlag(BluetoothDevice device, int key) {
        try {
            final byte[] value = device.getMetadata(key);
            return value != null && Boolean.parseBoolean(text(value));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Writes one metadata value; an empty value removes the key. */
    private void write(BluetoothDevice device, int key, String value) {
        // Only the tracked device has the listener that echoes writes back.
        final String written = device.equals(mTarget) ? key + "=" + value : null;
        if (written != null) {
            mOwnWrites.add(written);
        }
        boolean ok = false;
        try {
            ok = device.setMetadata(key,
                    value.isEmpty() ? NO_VALUE : value.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot write " + keyName(key), e);
        }
        if (!ok && written != null) {
            // Nothing changed, so no echo will come.
            mOwnWrites.remove(written);
        }
        if (debug()) {
            Log.d(TAG, "write " + device.getAnonymizedAddress() + " " + keyName(key) + "="
                    + (value.isEmpty() ? "(removed)" : value) + " ok=" + ok);
        }
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void remember(String pref, BluetoothDevice device) {
        final Set<String> stored = prefs().getStringSet(pref, Collections.emptySet());
        if (!stored.contains(device.getAddress())) {
            final Set<String> updated = new ArraySet<>(stored);
            updated.add(device.getAddress());
            prefs().edit().putStringSet(pref, updated).apply();
        }
    }

    /** Removes what this class wrote, for every device it wrote to. Bluetooth must be on. */
    private void forgetWrittenDevices() {
        final SharedPreferences prefs = prefs();
        final Set<String> written = prefs.getStringSet(PREF_WRITTEN, Collections.emptySet());
        final Set<String> marked = prefs.getStringSet(PREF_MARKED, Collections.emptySet());
        if (written.isEmpty() && marked.isEmpty()) {
            return;
        }
        final Set<String> addresses = new ArraySet<>(written);
        addresses.addAll(marked);
        for (String address : addresses) {
            final BluetoothDevice device;
            try {
                device = mAdapter.getRemoteDevice(address);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // Another writer was at work on the connected device a moment ago: the values are
            // its own, not ours to remove.
            final boolean othersValues = device.equals(mTarget)
                    && SystemClock.elapsedRealtime() < mOtherWriterUntil;
            if (written.contains(address) && !othersValues) {
                for (int key : BATTERY_KEYS) {
                    write(device, key, "");
                }
            }
            if (marked.contains(address) && !othersValues) {
                write(device, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, "");
            }
        }
        prefs.edit().remove(PREF_WRITTEN).remove(PREF_MARKED).apply();
        Log.i(TAG, "turned off: removed the levels of " + addresses.size() + " device(s)");
    }

    private static boolean isBatteryKey(int key) {
        for (int batteryKey : BATTERY_KEYS) {
            if (batteryKey == key) {
                return true;
            }
        }
        return false;
    }

    private static String keyName(int key) {
        switch (key) {
            case BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET: return "IS_UNTETHERED_HEADSET";
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY: return "LEFT_BATTERY";
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY: return "RIGHT_BATTERY";
            case BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY: return "CASE_BATTERY";
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING: return "LEFT_CHARGING";
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING: return "RIGHT_CHARGING";
            case BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING: return "CASE_CHARGING";
            case BluetoothDevice.METADATA_MAIN_BATTERY: return "MAIN_BATTERY";
            case BluetoothDevice.METADATA_MAIN_CHARGING: return "MAIN_CHARGING";
            default: return "key" + key;
        }
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static String hex(byte[] data, int count) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < data.length && i < count; i++) {
            out.append(String.format(Locale.ROOT, "%02X", data[i]));
        }
        return out.toString();
    }

    private static boolean debug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        // Read without the handler: a slightly stale line is fine in a dump.
        pw.println("IchthysPodBatteryController:");
        pw.println("  enabled=" + mEnabled);
        pw.println("  broadcastFallback=" + mBroadcastFallback);
        pw.println("  interactive=" + mInteractive);
        pw.println("  target=" + (mTarget == null ? null : mTarget.getAnonymizedAddress()));
        pw.println("  channel=" + mChannelState + " attempts=" + mAttempts + " silent=" + mSilent);
        pw.println("  reported=" + mReported);
        pw.println("  listeningForBroadcasts=" + (mScanner != null));
        pw.println("  publishedBeacon=" + mPublishedBeacon);
        pw.println("  pendingBeacon=" + mPendingBeacon);
        pw.println("  withdrawn=" + mWithdrawn);
        pw.println("  otherWriterHoldMs="
                + Math.max(0, mOtherWriterUntil - SystemClock.elapsedRealtime()));
    }

    private static final class Seen {
        final ProximityBeacon beacon;
        final int rssi;
        final long time;

        Seen(ProximityBeacon beacon, int rssi, long time) {
            this.beacon = beacon;
            this.rssi = rssi;
            this.time = time;
        }
    }
}
