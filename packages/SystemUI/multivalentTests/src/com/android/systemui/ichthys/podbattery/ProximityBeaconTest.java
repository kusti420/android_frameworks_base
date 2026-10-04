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
 * The packets here are built from the documented layout, not captured from a device: they pin
 * down what the decoder does with each field, not that the layout is right.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ProximityBeaconTest extends SysuiTestCase {

    /** A 27-byte status message with the given model, status, bud and flags/case bytes. */
    private static byte[] message(int model, int status, int buds, int flagsAndCase) {
        final byte[] data = new byte[27];
        data[0] = 0x07;
        data[1] = 0x19;
        data[2] = 0x01;
        data[3] = (byte) (model >> 8);
        data[4] = (byte) model;
        data[5] = (byte) status;
        data[6] = (byte) buds;
        data[7] = (byte) flagsAndCase;
        return data;
    }

    @Test
    public void lowNibbleIsLeftWhenTheStatusBitIsSet() {
        final ProximityBeacon beacon = ProximityBeacon.parse(message(0x0E20, 0x2B, 0x98, 0x15));
        assertThat(beacon.model).isEqualTo(0x0E20);
        assertThat(beacon.single).isFalse();
        assertThat(beacon.left).isEqualTo(80);
        assertThat(beacon.right).isEqualTo(90);
        assertThat(beacon.caseLevel).isEqualTo(50);
        assertThat(beacon.leftCharging).isTrue();
        assertThat(beacon.rightCharging).isFalse();
        assertThat(beacon.caseCharging).isFalse();
    }

    @Test
    public void nibblesAndChargingBitsSwapWhenTheStatusBitIsClear() {
        final ProximityBeacon beacon = ProximityBeacon.parse(message(0x0E20, 0x0B, 0x98, 0x55));
        assertThat(beacon.left).isEqualTo(90);
        assertThat(beacon.right).isEqualTo(80);
        assertThat(beacon.leftCharging).isFalse();
        assertThat(beacon.rightCharging).isTrue();
        assertThat(beacon.caseCharging).isTrue();
    }

    @Test
    public void fifteenIsNotAvailable() {
        final ProximityBeacon beacon = ProximityBeacon.parse(message(0x1420, 0x20, 0xF7, 0x0F));
        assertThat(beacon.left).isEqualTo(70);
        assertThat(beacon.right).isEqualTo(ProximityBeacon.UNKNOWN);
        assertThat(beacon.caseLevel).isEqualTo(ProximityBeacon.UNKNOWN);
        assertThat(beacon.isEmpty()).isFalse();
        assertThat(ProximityBeacon.parse(message(0x1420, 0x20, 0xFF, 0x0F)).isEmpty()).isTrue();
    }

    @Test
    public void headphonesHaveOneBattery() {
        final ProximityBeacon beacon = ProximityBeacon.parse(message(0x0A20, 0x00, 0x06, 0x1F));
        assertThat(beacon.single).isTrue();
        assertThat(beacon.left).isEqualTo(60);
        assertThat(beacon.leftCharging).isTrue();
        assertThat(beacon.right).isEqualTo(ProximityBeacon.UNKNOWN);
        assertThat(beacon.caseLevel).isEqualTo(ProximityBeacon.UNKNOWN);
    }

    @Test
    public void unknownModelsAreTwoBuds() {
        final ProximityBeacon beacon = ProximityBeacon.parse(message(0x7F20, 0x20, 0x45, 0x03));
        assertThat(beacon.single).isFalse();
        assertThat(beacon.left).isEqualTo(50);
        assertThat(beacon.right).isEqualTo(40);
        assertThat(beacon.caseLevel).isEqualTo(30);
    }

    @Test
    public void sameReadingIgnoresTheNibbleOrder() {
        final ProximityBeacon a = ProximityBeacon.parse(message(0x0E20, 0x20, 0x98, 0x15));
        final ProximityBeacon b = ProximityBeacon.parse(message(0x0E20, 0x00, 0x89, 0x25));
        assertThat(a.sameReadingAs(b)).isTrue();
        assertThat(a.sameReadingAs(ProximityBeacon.parse(message(0x0E20, 0x20, 0x97, 0x15))))
                .isFalse();
        assertThat(a.sameReadingAs(null)).isFalse();
    }

    @Test
    public void findsTheMessageAfterAnotherEntry() {
        final byte[] message = message(0x0F20, 0x20, 0x33, 0x04);
        final byte[] data = new byte[4 + message.length];
        data[0] = 0x10;
        data[1] = 0x02;
        System.arraycopy(message, 0, data, 4, message.length);
        final ProximityBeacon beacon = ProximityBeacon.parse(data);
        assertThat(beacon.model).isEqualTo(0x0F20);
        assertThat(beacon.left).isEqualTo(30);
        assertThat(beacon.caseLevel).isEqualTo(40);
    }

    @Test
    public void rejectsWhatIsNotAStatusMessage() {
        assertThat(ProximityBeacon.parse(null)).isNull();
        assertThat(ProximityBeacon.parse(new byte[0])).isNull();
        // Another entry type, another length, another payload prefix, and a truncated message.
        final byte[] otherType = message(0x0E20, 0x20, 0x98, 0x15);
        otherType[0] = 0x10;
        assertThat(ProximityBeacon.parse(otherType)).isNull();
        final byte[] otherLength = message(0x0E20, 0x20, 0x98, 0x15);
        otherLength[1] = 0x0F;
        assertThat(ProximityBeacon.parse(otherLength)).isNull();
        final byte[] otherPrefix = message(0x0E20, 0x20, 0x98, 0x15);
        otherPrefix[2] = 0x07;
        assertThat(ProximityBeacon.parse(otherPrefix)).isNull();
        assertThat(ProximityBeacon.parse(new byte[] {0x07, 0x19, 0x01, 0x0E, 0x20})).isNull();
    }
}
