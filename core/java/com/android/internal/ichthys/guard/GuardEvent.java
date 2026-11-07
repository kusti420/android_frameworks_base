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
 * One entry of IchthysGuardService's event ring. Three kinds share the ring and its sequence
 * numbers, so Guard's Activity page shows them in one timeline:
 *
 * <ul>
 * <li>{@link #TYPE_DNS} / {@link #TYPE_CONNECT}: a monitor event. {@link #blocked} says whether
 *     the uid was on the firewall's deny chain, and {@link #code} is the {@code REASON_*} why
 *     (policy, lockdown, screen-off rule, background rule, schedule, dormant).
 * <li>{@link #TYPE_JOURNAL}: a state change (pause, lockdown, quarantine, rules applied, an alert,
 *     a failed firewall push). {@link #code} is a {@code JOURNAL_*} value, {@link #detail} the
 *     text; {@link #uid} is the app it concerns or 0.
 * <li>{@link #TYPE_SHIELD}: proof that a data shield did its job for an app (a location fix
 *     withheld or faked, a contacts query emptied). {@link #code} is a {@code SHIELD_*} value.
 * </ul>
 *
 * IchthysOS: {@link #code} and {@link #detail} were added behind the original fields; the parcel
 * stays readable by the older Guard.
 */
public final class GuardEvent implements Parcelable {
    public static final int TYPE_DNS = 0;
    public static final int TYPE_CONNECT = 1;
    /** A state change of Guard itself; {@link #code} is a JOURNAL_* value. */
    public static final int TYPE_JOURNAL = 2;
    /** A data shield decided for an app; {@link #code} is a SHIELD_* value. */
    public static final int TYPE_SHIELD = 3;

    // ---- TYPE_DNS / TYPE_CONNECT: why the uid was blocked (0 = it wasn't) ------------------
    public static final int REASON_NONE = 0;
    /** The app's own rule: Block (or Wi-Fi only, on a metered network). */
    public static final int REASON_POLICY = 1;
    public static final int REASON_LOCKDOWN = 2;
    /** The screen was off or locked and the app follows the screen-off rule. */
    public static final int REASON_SCREEN_OFF = 3;
    /** The app was not on screen and has network only while it is. */
    public static final int REASON_BACKGROUND = 4;
    /** A schedule (Bedtime, Focus) cut it. */
    public static final int REASON_SCHEDULE = 5;
    /** Put to sleep after weeks unused. */
    public static final int REASON_DORMANT = 6;
    /** Reasons fit in one byte; Settings may pack one above the access value (ACCESS_REASON_SHIFT). */
    public static final int REASON_MASK = 0xff;

    // ---- TYPE_JOURNAL codes --------------------------------------------------------------------
    public static final int JOURNAL_PAUSE_START = 1;
    public static final int JOURNAL_PAUSE_END = 2;
    public static final int JOURNAL_LOCKDOWN_ON = 3;
    public static final int JOURNAL_LOCKDOWN_OFF = 4;
    /** The network rule table was replaced; detail = how many uids are restricted. */
    public static final int JOURNAL_RULES_APPLIED = 5;
    /** The behaviour table (screen-off, background, LAN) was replaced. */
    public static final int JOURNAL_BEHAVIOURS_APPLIED = 6;
    /** A new app was quarantined until its first launch. */
    public static final int JOURNAL_QUARANTINE = 7;
    /** A quarantine was lifted. */
    public static final int JOURNAL_RELEASED = 8;
    /** Settings applied a preset to an app; detail = the preset's name. */
    public static final int JOURNAL_PRESET = 9;
    public static final int JOURNAL_SCHEDULE_START = 10;
    public static final int JOURNAL_SCHEDULE_END = 11;
    /** An unused app was put to sleep (auto-tighten). */
    public static final int JOURNAL_SLEEP = 12;
    /** A behaviour alert was delivered to Settings; detail = the alert text. */
    public static final int JOURNAL_ALERT = 13;
    /** A firewall or metered-chain write failed; the rules may not be enforced. */
    public static final int JOURNAL_FIREWALL_FAILED = 14;
    /** A verification run (IchthysProbe) finished; detail = its verdict line. */
    public static final int JOURNAL_SELF_TEST = 15;
    /** The local-network grant of a uid was changed; detail = "blocked" or "allowed". */
    public static final int JOURNAL_LAN_APPLIED = 16;
    /** A reinstalled app got its old rules back. */
    public static final int JOURNAL_RULES_RESTORED = 17;
    /** The service came up (boot). */
    public static final int JOURNAL_SERVICE_START = 18;
    /** An app woke from sleep (dormant lifted). */
    public static final int JOURNAL_WAKE = 19;

    // ---- TYPE_SHIELD codes ----------------------------------------------------------------------
    /** A real location fix was delivered to the app (it is on the real list, or the fake place is off). */
    public static final int SHIELD_LOCATION_REAL = 1;
    /** The fake place was delivered instead of the position. */
    public static final int SHIELD_LOCATION_FAKE = 2;
    /** No fix at all ("No location"). */
    public static final int SHIELD_LOCATION_NONE = 3;
    /** A Wi-Fi or Bluetooth scan came back empty for the app. */
    public static final int SHIELD_SCANS_EMPTY = 4;
    /** A contacts query was answered with an empty address book. */
    public static final int SHIELD_CONTACTS = 5;
    public static final int SHIELD_CALENDAR = 6;
    public static final int SHIELD_CALL_LOG = 7;
    public static final int SHIELD_SMS = 8;
    /** A write was reported as saved but dropped. */
    public static final int SHIELD_CONTACTS_WRITE = 9;
    public static final int SHIELD_CALENDAR_WRITE = 10;
    public static final int SHIELD_CALL_LOG_WRITE = 11;
    /** A clipboard read without a paste action answered with nothing (clipboard lockdown). */
    public static final int SHIELD_CLIPBOARD = 12;
    /** A usage-stats query answered with an empty history. */
    public static final int SHIELD_USAGE = 13;
    /** A call's number withheld from a live call-state listener (call-log shield). */
    public static final int SHIELD_CALL_NUMBER = 14;
    /** The highest SHIELD_* code; the service refuses anything above it. */
    public static final int SHIELD_MAX = SHIELD_CALL_NUMBER;

    /** Monotonic sequence number (starts at 1, survives nothing: reset on boot). */
    public final long seq;
    /** Wall clock time in ms. */
    public final long timeMs;
    public final int uid;
    /** {@link #TYPE_DNS}, {@link #TYPE_CONNECT}, {@link #TYPE_JOURNAL} or {@link #TYPE_SHIELD}. */
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
    /** REASON_* (network events), JOURNAL_* or SHIELD_*, by {@link #type}. */
    public final int code;
    /** Journal text, or a short detail of a shield event; null for network events. */
    @Nullable public final String detail;

    public GuardEvent(long seq, long timeMs, int uid, int type, @Nullable String host,
            @Nullable String[] ips, @Nullable String ip, int port, int returnCode,
            boolean blocked) {
        this(seq, timeMs, uid, type, host, ips, ip, port, returnCode, blocked,
                blocked ? REASON_POLICY : REASON_NONE, null);
    }

    public GuardEvent(long seq, long timeMs, int uid, int type, @Nullable String host,
            @Nullable String[] ips, @Nullable String ip, int port, int returnCode,
            boolean blocked, int code, @Nullable String detail) {
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
        this.code = code;
        this.detail = detail;
    }

    /** A journal entry ({@link #TYPE_JOURNAL}). */
    public static GuardEvent journal(long seq, long timeMs, int uid, int code,
            @Nullable String detail) {
        return new GuardEvent(seq, timeMs, uid, TYPE_JOURNAL, null, null, null, 0, 0, false,
                code, detail);
    }

    /** A shield proof ({@link #TYPE_SHIELD}). */
    public static GuardEvent shield(long seq, long timeMs, int uid, int code,
            @Nullable String detail) {
        return new GuardEvent(seq, timeMs, uid, TYPE_SHIELD, null, null, null, 0, 0, false,
                code, detail);
    }

    /** True for a DNS or connect event. */
    public boolean isNetwork() {
        return type == TYPE_DNS || type == TYPE_CONNECT;
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
        code = in.readInt();
        detail = in.readString();
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
        dest.writeInt(code);
        dest.writeString(detail);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        switch (type) {
            case TYPE_JOURNAL:
                return "GuardEvent{#" + seq + " JOURNAL " + code + " uid=" + uid + " " + detail
                        + "}";
            case TYPE_SHIELD:
                return "GuardEvent{#" + seq + " SHIELD " + code + " uid=" + uid + " " + detail
                        + "}";
            default:
                return "GuardEvent{#" + seq + " uid=" + uid
                        + (type == TYPE_DNS ? " DNS " : " CONNECT ") + host
                        + (ip != null ? " " + ip + ":" + port : "")
                        + (blocked ? " blocked(" + code + ")" : "") + "}";
        }
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
