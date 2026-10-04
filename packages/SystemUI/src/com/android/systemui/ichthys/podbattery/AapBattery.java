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

/**
 * The part of AAP, the control protocol AirPods speak with Apple devices, that battery levels
 * need: the two packets that start a session and the battery notification.
 *
 * <p>AAP runs on a classic L2CAP channel with PSM {@link #PSM}; every L2CAP packet is one
 * message. Apple does not publish it. The packets here are as documented by the LibrePods
 * project ("AAP Definitions"), and CAPod's independent implementation agrees with them:
 *
 * <pre>
 *   to the earbuds, once, first:   00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00
 *       the handshake; nothing else is answered before it
 *   to the earbuds, after it:      04 00 04 00 0F 00 FF FF FF FF
 *       asks for notifications; the last four bytes are a mask of which
 *   from the earbuds:              04 00 04 00 04 00 n (c 01 level status 01) x n
 *       battery, sent after the request and whenever something changes
 *         c       01 headphones (AirPods Max), 02 right bud, 04 left bud, 08 case
 *         level   percent; above 100 is a placeholder for "not there"
 *         status  01 charging, 02 not charging, 04 disconnected,
 *                 05 on the charger but held below full ("optimised charging")
 * </pre>
 *
 * <p>A notification need not list every part, so a session's state is each notification laid
 * {@link #onTopOf} the one before.
 */
final class AapBattery {

    /** The L2CAP PSM of AAP on the earbuds. */
    static final int PSM = 0x1001;

    /** A part the earbuds reported as not there (disconnected, or a placeholder level). */
    static final int UNKNOWN = -1;
    /** A part this notification said nothing about. */
    static final int NOT_REPORTED = -2;

    private static final byte[] HANDSHAKE = {
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    private static final byte[] REQUEST_NOTIFICATIONS = {
            0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    private static final byte[] BATTERY_HEADER = {0x04, 0x00, 0x04, 0x00, 0x04, 0x00};

    private static final int ENTRY_SIZE = 5;
    /** Four parts exist; anything far above that is not a battery notification. */
    private static final int MAX_ENTRIES = 8;

    private static final int PART_HEADPHONES = 0x01;
    private static final int PART_RIGHT = 0x02;
    private static final int PART_LEFT = 0x04;
    private static final int PART_CASE = 0x08;

    private static final int STATUS_CHARGING = 0x01;
    private static final int STATUS_DISCONNECTED = 0x04;
    private static final int STATUS_CHARGING_OPTIMISED = 0x05;

    /** Percent 0..100, {@link #UNKNOWN} or {@link #NOT_REPORTED}. */
    final int headphones;
    final int left;
    final int right;
    final int caseLevel;
    final boolean headphonesCharging;
    final boolean leftCharging;
    final boolean rightCharging;
    final boolean caseCharging;

    private AapBattery(int headphones, int left, int right, int caseLevel,
            boolean headphonesCharging, boolean leftCharging, boolean rightCharging,
            boolean caseCharging) {
        this.headphones = headphones;
        this.left = left;
        this.right = right;
        this.caseLevel = caseLevel;
        this.headphonesCharging = headphonesCharging;
        this.leftCharging = leftCharging;
        this.rightCharging = rightCharging;
        this.caseCharging = caseCharging;
    }

    static byte[] handshake() {
        return HANDSHAKE.clone();
    }

    static byte[] requestNotifications() {
        return REQUEST_NOTIFICATIONS.clone();
    }

    /**
     * Decodes one received message.
     *
     * @param length how much of {@code packet} is the message
     * @return the notification, or null if the message is something else or lists no known part
     */
    static AapBattery parse(byte[] packet, int length) {
        if (packet == null || length > packet.length || length < BATTERY_HEADER.length + 1) {
            return null;
        }
        for (int i = 0; i < BATTERY_HEADER.length; i++) {
            if (packet[i] != BATTERY_HEADER[i]) {
                return null;
            }
        }
        final int count = packet[BATTERY_HEADER.length] & 0xFF;
        final int first = BATTERY_HEADER.length + 1;
        if (count == 0 || count > MAX_ENTRIES || first + count * ENTRY_SIZE > length) {
            return null;
        }
        final int[] level = {NOT_REPORTED, NOT_REPORTED, NOT_REPORTED, NOT_REPORTED};
        final boolean[] charging = new boolean[4];
        boolean any = false;
        for (int entry = 0; entry < count; entry++) {
            final int offset = first + entry * ENTRY_SIZE;
            final int slot = slotOf(packet[offset] & 0xFF);
            if (slot < 0) {
                continue;
            }
            // Bytes 1 and 4 of an entry have only ever been seen as 01; their meaning is unknown.
            final int percent = packet[offset + 2] & 0xFF;
            final int status = packet[offset + 3] & 0xFF;
            final boolean there = status != STATUS_DISCONNECTED && percent <= 100;
            level[slot] = there ? percent : UNKNOWN;
            charging[slot] = there
                    && (status == STATUS_CHARGING || status == STATUS_CHARGING_OPTIMISED);
            any = true;
        }
        return any ? new AapBattery(level[0], level[1], level[2], level[3], charging[0],
                charging[1], charging[2], charging[3]) : null;
    }

    private static int slotOf(int part) {
        switch (part) {
            case PART_HEADPHONES: return 0;
            case PART_LEFT: return 1;
            case PART_RIGHT: return 2;
            case PART_CASE: return 3;
            default: return -1;
        }
    }

    /** This notification with every part it does not mention taken from {@code earlier}. */
    AapBattery onTopOf(AapBattery earlier) {
        if (earlier == null) {
            return this;
        }
        return new AapBattery(
                headphones != NOT_REPORTED ? headphones : earlier.headphones,
                left != NOT_REPORTED ? left : earlier.left,
                right != NOT_REPORTED ? right : earlier.right,
                caseLevel != NOT_REPORTED ? caseLevel : earlier.caseLevel,
                headphones != NOT_REPORTED ? headphonesCharging : earlier.headphonesCharging,
                left != NOT_REPORTED ? leftCharging : earlier.leftCharging,
                right != NOT_REPORTED ? rightCharging : earlier.rightCharging,
                caseLevel != NOT_REPORTED ? caseCharging : earlier.caseCharging);
    }

    /** Whether this is a device with one battery: headphones reported, and no bud. */
    boolean isSingle() {
        return headphones != NOT_REPORTED && left == NOT_REPORTED && right == NOT_REPORTED;
    }

    @Override
    public String toString() {
        return "headphones=" + text(headphones, headphonesCharging)
                + " L=" + text(left, leftCharging) + " R=" + text(right, rightCharging)
                + " case=" + text(caseLevel, caseCharging);
    }

    private static String text(int level, boolean charging) {
        return level == NOT_REPORTED ? "-" : level == UNKNOWN ? "?" : level + (charging ? "+" : "");
    }
}
