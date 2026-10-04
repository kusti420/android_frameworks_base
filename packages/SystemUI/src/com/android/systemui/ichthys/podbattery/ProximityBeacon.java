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

import java.util.Locale;

/**
 * One decoded Apple "proximity pairing" advertisement: the battery state AirPods and most Beats
 * broadcast over Bluetooth LE instead of reporting it through a standard profile.
 *
 * <p>The message is one entry of Apple's manufacturer data (company id {@link #APPLE_COMPANY_ID}),
 * which is a sequence of {@code type, length, payload} entries. The layout used here, with offsets
 * counted from the entry's type byte:
 *
 * <pre>
 *   0      0x07  entry type: proximity pairing
 *   1      0x19  payload length (25)
 *   2      0x01  payload prefix; other prefixes are not status broadcasts
 *   3..4         model, as the two bytes appear on air (0x0E20 = AirPods Pro)
 *   5            status. Bit 0x20 set: the low nibble of byte 6 and bit 0 of the charging flags
 *                are the left bud. Clear: they are the right bud.
 *   6            bud batteries, one nibble each
 *   7            high nibble: charging flags (bit 0, bit 1: the two buds; bit 2: the case)
 *                low nibble: case battery
 *   8            lid state, 9 colour, the rest is encrypted
 * </pre>
 *
 * <p>A battery nibble is 0..10 for 0..100 % in steps of ten, and 15 when that part is not there
 * (bud out of range, case closed). Headphones and neckbands have one battery: the low nibble of
 * byte 6, with bit 0 of the charging flags.
 *
 * <p>None of this is published by Apple. The layout is the one the open-source AirPods apps agree
 * on; see the notes at {@link IchthysPodBatteryController}.
 */
final class ProximityBeacon {

    /** Bluetooth SIG company identifier of Apple, Inc. */
    static final int APPLE_COMPANY_ID = 0x004C;

    static final int ENTRY_TYPE = 0x07;
    static final int ENTRY_LENGTH = 0x19;
    static final int PAYLOAD_PREFIX = 0x01;

    /** A level that is not available. Also what SettingsLib reads for a missing metadata key. */
    static final int UNKNOWN = -1;

    private static final int NIBBLE_NOT_AVAILABLE = 15;

    /**
     * Models with a single battery (over-ear, on-ear and neckband). Every other model, known or
     * not, is read as two buds and a case, which is what all current earbuds are.
     */
    private static final int[] SINGLE_BATTERY_MODELS = {
            0x0A20, // AirPods Max
            0x1F20, // AirPods Max (USB-C)
            0x2D20, // AirPods Max 2
            0x0320, // Powerbeats 3
            0x0520, // BeatsX
            0x0620, // Beats Solo 3
            0x0920, // Beats Studio 3
            0x0C20, // Beats Solo Pro
            0x0D20, // Powerbeats 4
            0x1020, // Beats Flex
            0x1720, // Beats Studio Pro
            0x2520, // Beats Solo 4
    };

    /** Model id, e.g. 0x0E20. */
    final int model;
    /** Whether the device has one battery rather than two buds and a case. */
    final boolean single;
    /** Whether the status byte says the low nibble is the left bud (bit 0x20 set). */
    final boolean leftIsLowNibble;
    /** Percent 0..100 or {@link #UNKNOWN}. For a single-battery device only {@link #left}. */
    final int left;
    final int right;
    final int caseLevel;
    final boolean leftCharging;
    final boolean rightCharging;
    final boolean caseCharging;

    private ProximityBeacon(int model, boolean single, boolean leftIsLowNibble, int left,
            int right, int caseLevel, boolean leftCharging, boolean rightCharging,
            boolean caseCharging) {
        this.model = model;
        this.single = single;
        this.leftIsLowNibble = leftIsLowNibble;
        this.left = left;
        this.right = right;
        this.caseLevel = caseLevel;
        this.leftCharging = leftCharging;
        this.rightCharging = rightCharging;
        this.caseCharging = caseCharging;
    }

    /**
     * Decodes Apple manufacturer data (without the company id, as {@code
     * ScanRecord#getManufacturerSpecificData} returns it).
     *
     * @return the beacon, or null if the data holds no proximity pairing status message
     */
    static ProximityBeacon parse(byte[] data) {
        if (data == null) {
            return null;
        }
        // Walk the entries: the status message is normally the only one, but nothing says it
        // has to be the first.
        int offset = 0;
        while (offset + 2 <= data.length) {
            final int type = data[offset] & 0xFF;
            final int length = data[offset + 1] & 0xFF;
            if (type == ENTRY_TYPE && length == ENTRY_LENGTH) {
                return offset + 2 + length <= data.length ? decode(data, offset) : null;
            }
            offset += 2 + length;
        }
        return null;
    }

    private static ProximityBeacon decode(byte[] data, int start) {
        if ((data[start + 2] & 0xFF) != PAYLOAD_PREFIX) {
            return null;
        }
        final int model = ((data[start + 3] & 0xFF) << 8) | (data[start + 4] & 0xFF);
        final int status = data[start + 5] & 0xFF;
        final int low = data[start + 6] & 0x0F;
        final int high = (data[start + 6] >> 4) & 0x0F;
        final int flags = (data[start + 7] >> 4) & 0x0F;
        final int caseNibble = data[start + 7] & 0x0F;

        if (isSingleBattery(model)) {
            return new ProximityBeacon(model, true, true, toPercent(low), UNKNOWN, UNKNOWN,
                    (flags & 0x1) != 0, false, false);
        }
        final boolean leftIsLow = (status & 0x20) != 0;
        final boolean lowCharging = (flags & 0x1) != 0;
        final boolean highCharging = (flags & 0x2) != 0;
        return new ProximityBeacon(model, false, leftIsLow,
                toPercent(leftIsLow ? low : high), toPercent(leftIsLow ? high : low),
                toPercent(caseNibble),
                leftIsLow ? lowCharging : highCharging, leftIsLow ? highCharging : lowCharging,
                (flags & 0x4) != 0);
    }

    private static boolean isSingleBattery(int model) {
        for (int single : SINGLE_BATTERY_MODELS) {
            if (single == model) {
                return true;
            }
        }
        return false;
    }

    private static int toPercent(int nibble) {
        if (nibble == NIBBLE_NOT_AVAILABLE) {
            return UNKNOWN;
        }
        // 11..14 are not known to be sent; read them as full rather than as 110 %.
        return Math.min(nibble, 10) * 10;
    }

    /** Whether this beacon carries no level at all, as one from a closed, idle case can. */
    boolean isEmpty() {
        return left == UNKNOWN && right == UNKNOWN && caseLevel == UNKNOWN;
    }

    /** Whether two beacons say the same thing, however their nibbles were ordered on air. */
    boolean sameReadingAs(ProximityBeacon other) {
        return other != null && model == other.model && left == other.left
                && right == other.right && caseLevel == other.caseLevel
                && leftCharging == other.leftCharging && rightCharging == other.rightCharging
                && caseCharging == other.caseCharging;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "model=0x%04X %s leftIsLowNibble=%b L=%d%s R=%d%s case=%d%s", model,
                single ? "single" : "dual", leftIsLowNibble, left, leftCharging ? "+" : "",
                right, rightCharging ? "+" : "", caseLevel, caseCharging ? "+" : "");
    }
}
