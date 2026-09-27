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

package com.android.server;

import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.Map;

/**
 * The LAN easter egg: what this device looks like to whoever is scanning the network.
 *
 * <p>Two independent halves make up the joke, and this class only owns the second one:
 *
 * <ol>
 *   <li>The <b>DHCP hostname</b> (option 12) -- the string a router's client list and a
 *       Wireshark capture show next to the lease. That one is not code at all: NetworkStack's
 *       {@code DhcpClient} builds it from the first readable property named by the
 *       {@code config_dhcp_client_hostname_preferred_props} overlay, so the ROM points that
 *       overlay at {@code ro.vendor.net.hostname} (see IchthysNetworkStackOverlay). Deliberately
 *       a property rather than {@link Settings.Global#DEVICE_NAME}, so Bluetooth, Wi-Fi Direct
 *       and Settings &gt; About keep showing the real device name.
 *   <li>The <b>mDNS/DNS-SD advertisements</b> below -- the entries that populate
 *       {@code avahi-browse}, Fing, Bonjour Browser and every "discovered devices" list. A
 *       handful of services are published that, taken together, read like a small doomsday rack
 *       hiding behind one IP address.
 * </ol>
 *
 * <p>Nothing listens on any of the advertised ports. The records are pure DNS-SD noise: no
 * socket is opened, no traffic is accepted, and no credential is ever asked for or collected.
 * The ports are only there because SRV records must carry one.
 *
 * <p>Killable at runtime, without a reboot or a rebuild, for the times when announcing a reactor
 * interlock on the corporate/airport Wi-Fi would be a bad idea:
 *
 * <pre>adb shell settings put global network_decoy_enabled 0</pre>
 */
public final class NetworkDecoyController {
    private static final String TAG = "NetworkDecoy";

    /** Global setting, default off. 1 enables the advertisements below. */
    private static final String KEY_ENABLED = "network_decoy_enabled";

    /** Re-arm delay after mDNS registration fails (the advertiser is usually just not up yet). */
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final int MAX_RETRIES = 5;

    /** One advertised service. Nothing is bound to {@link #port}; SRV records just require one. */
    private static final class Decoy {
        final String name;
        final String type;
        final int port;
        final Map<String, String> txt;

        Decoy(String name, String type, int port, String... txtPairs) {
            this.name = name;
            this.type = type;
            this.port = port;
            this.txt = new ArrayMap<>(txtPairs.length / 2);
            for (int i = 0; i + 1 < txtPairs.length; i += 2) {
                this.txt.put(txtPairs[i], txtPairs[i + 1]);
            }
        }
    }

    private static final Decoy[] DECOYS = {
            // The headline act. _workstation._tcp is what avahi publishes for a Linux box, so
            // this is the row that lands in the middle of an otherwise boring device list.
            new Decoy("RCE-THERMONUCLEAR-BOMB-v2", "_workstation._tcp", 9,
                    "state", "ARMED",
                    "yield", "1.2Mt",
                    "cve", "CVE-2026-31337"),
            // Unauthenticated telnet on a coolant pump: catnip for anyone running a scanner.
            new Decoy("PLC-COOLANT-LOOP-4 [NO AUTH]", "_telnet._tcp", 23,
                    "auth", "none",
                    "fw", "1.02b"),
            // Modbus is the protocol nobody expects to meet on a home network.
            new Decoy("REACTOR-4 SAFETY INTERLOCK", "_modbus._tcp", 502,
                    "warning", "do-not-power-off",
                    "unit", "4"),
            // For the one person on the network old enough to get it.
            new Decoy("WOPR - NORAD DEFCON 1", "_http._tcp", 8080,
                    "path", "/launch",
                    "q", "shall we play a game"),
    };

    private final Context mContext;
    private final Handler mHandler;

    /** Live registrations, decoy index -> listener. Touched on {@link #mHandler} only. */
    private final ArrayMap<Integer, NsdManager.RegistrationListener> mRegistered = new ArrayMap<>();

    private NsdManager mNsdManager;
    private boolean mEnabled;
    private int mRetries;

    public NetworkDecoyController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Called once the system is ready. Observes the kill switch and waits for a network. */
    public void onBootPhaseReady() {
        mHandler.post(() -> {
            mEnabled = isEnabled();

            final ContentObserver observer = new ContentObserver(mHandler) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    final boolean enabled = isEnabled();
                    if (enabled == mEnabled) return;
                    mEnabled = enabled;
                    if (enabled) {
                        mRetries = 0;
                        registerAll();
                    } else {
                        unregisterAll();
                    }
                }
            };
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(KEY_ENABLED), false, observer);

            // The advertiser can only announce once an interface is up, so hang the first
            // registration off the network callback rather than off boot.
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                Slog.w(TAG, "no ConnectivityManager; decoys disabled");
                return;
            }
            final NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    mHandler.post(() -> {
                        mRetries = 0;
                        registerAll();
                    });
                }
            }, mHandler);
        });
    }

    private boolean isEnabled() {
        // Off unless the user turns it on: fake mDNS services make the phone easier to spot and
        // fingerprint on shared networks. (kusti420/ichthysos-tracker#16)
        return Settings.Global.getInt(mContext.getContentResolver(), KEY_ENABLED, 0) != 0;
    }

    /** Idempotent: publishes every decoy that is not already live. */
    private void registerAll() {
        if (!mEnabled) return;
        if (mNsdManager == null) {
            mNsdManager = mContext.getSystemService(NsdManager.class);
            if (mNsdManager == null) {
                Slog.w(TAG, "no NsdManager; decoys disabled");
                return;
            }
        }
        for (int i = 0; i < DECOYS.length; i++) {
            if (mRegistered.containsKey(i)) continue;
            register(i, DECOYS[i]);
        }
    }

    private void register(int index, Decoy decoy) {
        final NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(decoy.name);
        info.setServiceType(decoy.type);
        info.setPort(decoy.port);
        for (Map.Entry<String, String> e : decoy.txt.entrySet()) {
            info.setAttribute(e.getKey(), e.getValue());
        }

        final NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo registered) {
                // NSD renames on conflict, so log what actually went on the wire.
                Slog.i(TAG, "advertising " + registered.getServiceName() + " " + decoy.type);
            }

            @Override public void onRegistrationFailed(NsdServiceInfo failed, int errorCode) {
                Slog.w(TAG, "registration of " + decoy.name + " failed, error " + errorCode);
                mHandler.post(() -> {
                    mRegistered.remove(index);
                    scheduleRetry();
                });
            }

            @Override public void onServiceUnregistered(NsdServiceInfo unregistered) { }

            @Override public void onUnregistrationFailed(NsdServiceInfo failed, int errorCode) {
                Slog.w(TAG, "unregistration of " + decoy.name + " failed, error " + errorCode);
            }
        };

        try {
            mNsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            mRegistered.put(index, listener);
        } catch (IllegalArgumentException e) {
            // A malformed name/type is a build-time mistake; do not retry it forever.
            Slog.e(TAG, "bad decoy " + decoy.name, e);
        }
    }

    private void scheduleRetry() {
        if (!mEnabled || mRetries >= MAX_RETRIES) return;
        mRetries++;
        mHandler.postDelayed(this::registerAll, RETRY_DELAY_MS);
    }

    private void unregisterAll() {
        if (mNsdManager == null) return;
        for (int i = mRegistered.size() - 1; i >= 0; i--) {
            try {
                mNsdManager.unregisterService(mRegistered.valueAt(i));
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "unregister failed", e);
            }
        }
        mRegistered.clear();
    }
}
