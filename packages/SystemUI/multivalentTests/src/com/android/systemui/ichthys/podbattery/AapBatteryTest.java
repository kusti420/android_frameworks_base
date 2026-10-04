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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Apart from {@link #documentedExample}, the packets here are built from the documented layout,
 * not captured from a device: they pin down what the parser does with each field, not that the
 * layout is right.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AapBatteryTest extends SysuiTestCase {

    private static byte[] bytes(int... values) {
        final byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static AapBattery parse(int... values) {
        return AapBattery.parse(bytes(values), values.length);
    }

    @Test
    public void documentedExample() {
        // The example of an AirPods Pro 2 in LibrePods' "AAP Definitions": right 100 % not
        // charging, left 99 % charging, case 17 % not charging. (The byte-by-byte table under
        // that example has the two buds the other way round; its table of part ids, both
        // LibrePods clients and CAPod have 02 as the right bud.)
        final AapBattery report = parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x02, 0x01, 0x64, 0x02, 0x01,
                0x04, 0x01, 0x63, 0x01, 0x01,
                0x08, 0x01, 0x11, 0x02, 0x01);
        assertThat(report.right).isEqualTo(100);
        assertThat(report.rightCharging).isFalse();
        assertThat(report.left).isEqualTo(99);
        assertThat(report.leftCharging).isTrue();
        assertThat(report.caseLevel).isEqualTo(17);
        assertThat(report.caseCharging).isFalse();
        assertThat(report.headphones).isEqualTo(AapBattery.NOT_REPORTED);
        assertThat(report.isSingle()).isFalse();
    }

    @Test
    public void disconnectedAndPlaceholderLevelsAreUnknown() {
        final AapBattery report = parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x04, 0x01, 0x50, 0x04, 0x01,  // left: disconnected
                0x02, 0x01, 0x37, 0x02, 0x01,  // right: 55 %
                0x08, 0x01, 0x7F, 0x01, 0x01); // case: placeholder level, "charging"
        assertThat(report.left).isEqualTo(AapBattery.UNKNOWN);
        assertThat(report.right).isEqualTo(55);
        assertThat(report.caseLevel).isEqualTo(AapBattery.UNKNOWN);
        assertThat(report.caseCharging).isFalse();
    }

    @Test
    public void optimisedChargingCountsAsCharging() {
        final AapBattery report = parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
                0x08, 0x01, 0x50, 0x05, 0x01);
        assertThat(report.caseLevel).isEqualTo(80);
        assertThat(report.caseCharging).isTrue();
    }

    @Test
    public void headphonesAreASingleBattery() {
        final AapBattery report = parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
                0x01, 0x01, 0x2A, 0x01, 0x01);
        assertThat(report.isSingle()).isTrue();
        assertThat(report.headphones).isEqualTo(42);
        assertThat(report.headphonesCharging).isTrue();
        assertThat(report.left).isEqualTo(AapBattery.NOT_REPORTED);
    }

    @Test
    public void laterNotificationsOnlyReplaceWhatTheyMention() {
        final AapBattery first = parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x04, 0x01, 0x5A, 0x02, 0x01,
                0x02, 0x01, 0x5B, 0x02, 0x01,
                0x08, 0x01, 0x3C, 0x01, 0x01);
        final AapBattery second = parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x02,
                0x04, 0x01, 0x59, 0x01, 0x01,
                0x08, 0x01, 0x00, 0x04, 0x01);
        final AapBattery merged = second.onTopOf(first);
        assertThat(merged.left).isEqualTo(89);
        assertThat(merged.leftCharging).isTrue();
        assertThat(merged.right).isEqualTo(91);
        assertThat(merged.caseLevel).isEqualTo(AapBattery.UNKNOWN);
        assertThat(merged.caseCharging).isFalse();
        assertThat(first.onTopOf(null)).isSameInstanceAs(first);
    }

    @Test
    public void onlyTheGivenLengthIsRead() {
        // A reused read buffer: a two-entry message followed by what an earlier one left behind.
        final byte[] buffer = bytes(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x02,
                0x04, 0x01, 0x10, 0x02, 0x01,
                0x02, 0x01, 0x20, 0x02, 0x01,
                0x08, 0x01, 0x30, 0x02, 0x01);
        final AapBattery report = AapBattery.parse(buffer, 17);
        assertThat(report.left).isEqualTo(16);
        assertThat(report.right).isEqualTo(32);
        assertThat(report.caseLevel).isEqualTo(AapBattery.NOT_REPORTED);
    }

    @Test
    public void rejectsWhatIsNotABatteryNotification() {
        assertThat(AapBattery.parse(null, 0)).isNull();
        assertThat(AapBattery.parse(new byte[0], 0)).isNull();
        // Another notification (ear detection), a count the message is too short for, a count of
        // zero, an unknown part only, and a length beyond the buffer.
        assertThat(parse(0x04, 0x00, 0x04, 0x00, 0x06, 0x00, 0x00, 0x00)).isNull();
        assertThat(parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x04, 0x01, 0x10, 0x02, 0x01)).isNull();
        assertThat(parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x00)).isNull();
        assertThat(parse(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
                0x10, 0x01, 0x10, 0x02, 0x01)).isNull();
        assertThat(AapBattery.parse(bytes(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01), 12))
                .isNull();
    }

    @Test
    public void sessionStartPackets() {
        assertThat(AapBattery.handshake()).isEqualTo(bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00,
                0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00));
        assertThat(AapBattery.requestNotifications()).isEqualTo(
                bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xFF, 0xFF));
    }
}
