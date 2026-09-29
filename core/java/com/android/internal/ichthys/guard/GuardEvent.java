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

package com.android.internal.ichthys.guard;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * One Guard monitor event: a DNS lookup or a connect() made by {@link #uid}.
 * {@hide}
 */
public final class GuardEvent implements Parcelable {
    public static final int TYPE_DNS = 0;
    public static final int TYPE_CONNECT = 1;

    /** Monotonic sequence number (starts at 1, survives nothing: reset on boot). */
    public final long seq;
    /** Wall clock time in ms. */
    public final long timeMs;
    public final int uid;
    /** {@link #TYPE_DNS} or {@link #TYPE_CONNECT}. */
    public final int type;
    /** DNS: the queried name. CONNECT: the name this uid last resolved to {@link #ip}, or null. */
    @Nullable public final String host;
    /** DNS: the returned addresses (possibly a subset). CONNECT: empty. */
    @NonNull public final String[] ips;
    /** CONNECT: destination address. DNS: null. */
    @Nullable public final String ip;
    /** CONNECT: destination port. DNS: 0. */
    public final int port;
    /** DNS: getaddrinfo()-style return code (0 = success). CONNECT: 0. */
    public final int returnCode;
    /** Whether Guard's policy blocked the uid at the time of the event. */
    public final boolean blocked;

    public GuardEvent(long seq, long timeMs, int uid, int type, @Nullable String host,
            @Nullable String[] ips, @Nullable String ip, int port, int returnCode,
            boolean blocked) {
        this.seq = seq;
        this.timeMs = timeMs;
        this.uid = uid;
        this.type = type;
        this.host = host;
        this.ips = ips != null ? ips : new String[0];
        this.ip = ip;
        this.port = port;
        this.returnCode = returnCode;
        this.blocked = blocked;
    }

    private GuardEvent(Parcel in) {
        seq = in.readLong();
        timeMs = in.readLong();
        uid = in.readInt();
        type = in.readInt();
        host = in.readString();
        final String[] a = in.createStringArray();
        ips = a != null ? a : new String[0];
        ip = in.readString();
        port = in.readInt();
        returnCode = in.readInt();
        blocked = in.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(seq);
        dest.writeLong(timeMs);
        dest.writeInt(uid);
        dest.writeInt(type);
        dest.writeString(host);
        dest.writeStringArray(ips);
        dest.writeString(ip);
        dest.writeInt(port);
        dest.writeInt(returnCode);
        dest.writeBoolean(blocked);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GuardEvent{#" + seq + " uid=" + uid + (type == TYPE_DNS ? " DNS " : " CONNECT ")
                + host + (ip != null ? " " + ip + ":" + port : "")
                + (blocked ? " blocked" : "") + "}";
    }

    public static final @NonNull Creator<GuardEvent> CREATOR = new Creator<GuardEvent>() {
        @Override
        public GuardEvent createFromParcel(Parcel in) {
            return new GuardEvent(in);
        }

        @Override
        public GuardEvent[] newArray(int size) {
            return new GuardEvent[size];
        }
    };
}
