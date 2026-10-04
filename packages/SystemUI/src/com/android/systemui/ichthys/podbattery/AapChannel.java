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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * One AAP session with a connected pair of earbuds: opens the channel, starts the session and
 * hands every battery notification to the listener until the channel closes. See
 * {@link AapBattery} for the protocol.
 *
 * <p>One attempt per instance; who retries, and when, is the owner's business. Connecting and
 * reading block, so each instance has a thread of its own, which ends when the channel does. The
 * listener is called on the executor given, and after {@link Listener#onChannelClosed} never
 * again.
 */
final class AapChannel {

    interface Listener {
        /** The channel is connected and the session start has been sent. */
        void onChannelOpen(AapChannel channel);

        void onChannelBattery(AapChannel channel, AapBattery report);

        /** The attempt failed or the session ended, whoever ended it. Always the last call. */
        void onChannelClosed(AapChannel channel, String reason);
    }

    private static final String TAG = "IchthysPodBattery";

    /** LibrePods spaces its opening packets like this; whether the earbuds need it is unknown. */
    private static final long AFTER_HANDSHAKE_MS = 200;
    /** A read that returns nothing is an empty packet; a run of them is a broken channel. */
    private static final int MAX_EMPTY_READS = 16;
    private static final int DEFAULT_PACKET_SIZE = 4096;

    private final BluetoothDevice mDevice;
    private final Executor mExecutor;
    private final Listener mListener;

    private final Object mLock = new Object();
    private BluetoothSocket mSocket;
    private boolean mClosed;

    AapChannel(BluetoothDevice device, Executor executor, Listener listener) {
        mDevice = device;
        mExecutor = executor;
        mListener = listener;
    }

    void start() {
        new Thread(this::run, "IchthysPodAap").start();
    }

    /** Ends the attempt or the session, from any thread. The listener still hears of it. */
    void close() {
        final BluetoothSocket socket;
        synchronized (mLock) {
            mClosed = true;
            socket = mSocket;
            mSocket = null;
        }
        // Also what gets the thread out of a blocking connect or read.
        closeQuietly(socket);
    }

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        String reason;
        BluetoothSocket socket = null;
        try {
            socket = createSocket(mDevice);
            synchronized (mLock) {
                if (mClosed) {
                    throw new IOException("cancelled");
                }
                mSocket = socket;
            }
            socket.connect();

            // Each write is one L2CAP packet, and so one message.
            final OutputStream out = socket.getOutputStream();
            out.write(AapBattery.handshake());
            Thread.sleep(AFTER_HANDSHAKE_MS);
            out.write(AapBattery.requestNotifications());
            mExecutor.execute(() -> mListener.onChannelOpen(this));

            reason = read(socket);
        } catch (IOException | InterruptedException | RuntimeException e) {
            reason = e.toString();
        } catch (ReflectiveOperationException e) {
            final Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            reason = "no L2CAP socket: " + cause;
        }
        synchronized (mLock) {
            mSocket = null;
        }
        closeQuietly(socket);
        final String closedBecause = reason;
        mExecutor.execute(() -> mListener.onChannelClosed(this, closedBecause));
    }

    /** Reads messages until the channel closes; returns why it did. */
    private String read(BluetoothSocket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        // A read returns one whole packet as long as the buffer can hold it.
        final int packetSize = socket.getMaxReceivePacketSize();
        final byte[] buffer = new byte[packetSize > 0 ? packetSize : DEFAULT_PACKET_SIZE];
        int emptyReads = 0;
        while (true) {
            final int length = in.read(buffer);
            if (length < 0) {
                return "closed by the device";
            }
            if (length == 0) {
                if (++emptyReads > MAX_EMPTY_READS) {
                    return "empty reads";
                }
                continue;
            }
            emptyReads = 0;
            final AapBattery report = AapBattery.parse(buffer, length);
            if (report != null) {
                mExecutor.execute(() -> mListener.onChannelBattery(this, report));
            } else if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "aap: other message, " + length + " bytes: " + hex(buffer, length));
            }
        }
    }

    /**
     * A classic (BR/EDR) L2CAP socket to the earbuds' AAP channel, authenticated and encrypted.
     *
     * <p>{@code BluetoothDevice#createL2capSocket(int)} is the framework's method for exactly
     * this, but it is hidden inside the Bluetooth module and so not in the stubs SystemUI is
     * built against; the public socket APIs only do RFCOMM and LE. Hence the lookup by name,
     * which SystemUI, being signed with the platform key, is allowed.
     */
    private static BluetoothSocket createSocket(BluetoothDevice device)
            throws ReflectiveOperationException {
        return (BluetoothSocket) BluetoothDevice.class
                .getMethod("createL2capSocket", int.class)
                .invoke(device, AapBattery.PSM);
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException | RuntimeException e) {
            // Already gone.
        }
    }

    private static String hex(byte[] data, int length) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < length && i < 12; i++) {
            out.append(String.format(Locale.ROOT, "%02X", data[i]));
        }
        return out.toString();
    }
}
