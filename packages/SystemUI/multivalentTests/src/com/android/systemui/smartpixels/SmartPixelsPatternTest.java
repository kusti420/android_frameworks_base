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

package com.android.systemui.smartpixels;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SmartPixelsPatternTest extends SysuiTestCase {

    private static int countDimmed(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) {
            if (b) n++;
        }
        return n;
    }

    @Test
    public void clampPercent_clampsToTheRangeTheSliderCanProduce() {
        assertThat(SmartPixelsPattern.clampPercent(Integer.MIN_VALUE))
                .isEqualTo(SmartPixelsPattern.MIN_PERCENT);
        assertThat(SmartPixelsPattern.clampPercent(-1)).isEqualTo(SmartPixelsPattern.MIN_PERCENT);
        assertThat(SmartPixelsPattern.clampPercent(0)).isEqualTo(SmartPixelsPattern.MIN_PERCENT);
        assertThat(SmartPixelsPattern.clampPercent(10)).isEqualTo(10);
        assertThat(SmartPixelsPattern.clampPercent(75)).isEqualTo(75);
        assertThat(SmartPixelsPattern.clampPercent(76)).isEqualTo(SmartPixelsPattern.MAX_PERCENT);
        assertThat(SmartPixelsPattern.clampPercent(Integer.MAX_VALUE))
                .isEqualTo(SmartPixelsPattern.MAX_PERCENT);
    }

    @Test
    public void dimmedCells_isMonotonicAndNeverBlanksTheScreen() {
        int previous = -1;
        for (int percent = -50; percent <= 150; percent++) {
            int cells = SmartPixelsPattern.dimmedCells(percent);
            assertThat(cells).isAtLeast(previous);
            assertThat(cells).isGreaterThan(0);
            assertThat(cells).isLessThan(SmartPixelsPattern.CELLS);
            previous = cells;
        }
    }

    @Test
    public void dimmedCells_roundsToNearest() {
        assertThat(SmartPixelsPattern.dimmedCells(10)).isEqualTo(6);
        assertThat(SmartPixelsPattern.dimmedCells(25)).isEqualTo(16);
        assertThat(SmartPixelsPattern.dimmedCells(50)).isEqualTo(32);
        assertThat(SmartPixelsPattern.dimmedCells(75)).isEqualTo(48);
    }

    @Test
    public void mask_dimsExactlyTheRequestedNumberOfCells_atEveryPercentAndStep() {
        for (int percent = SmartPixelsPattern.MIN_PERCENT;
                percent <= SmartPixelsPattern.MAX_PERCENT; percent++) {
            int expected = SmartPixelsPattern.dimmedCells(percent);
            for (int step = 0; step < SmartPixelsPattern.shiftCycleLength(); step++) {
                assertThat(countDimmed(SmartPixelsPattern.maskAtStep(percent, step)))
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    public void maskAtStep_wrapsForNegativeAndOverflowingSteps() {
        int cycle = SmartPixelsPattern.shiftCycleLength();
        for (int percent : new int[] {10, 25, 50, 75}) {
            assertThat(SmartPixelsPattern.maskAtStep(percent, cycle))
                    .isEqualTo(SmartPixelsPattern.maskAtStep(percent, 0));
            assertThat(SmartPixelsPattern.maskAtStep(percent, -cycle + 5))
                    .isEqualTo(SmartPixelsPattern.maskAtStep(percent, 5));
            assertThat(SmartPixelsPattern.maskAtStep(percent, 1_000_000 * cycle + 3))
                    .isEqualTo(SmartPixelsPattern.maskAtStep(percent, 3));
        }
    }

    @Test
    public void everyPixelIsDimmedEquallyOverAFullShiftCycle() {
        int cycle = SmartPixelsPattern.shiftCycleLength();
        for (int percent = SmartPixelsPattern.MIN_PERCENT;
                percent <= SmartPixelsPattern.MAX_PERCENT; percent++) {
            int[] duty = new int[SmartPixelsPattern.CELLS];
            for (int step = 0; step < cycle; step++) {
                boolean[] mask = SmartPixelsPattern.maskAtStep(percent, step);
                for (int i = 0; i < mask.length; i++) {
                    if (mask[i]) duty[i]++;
                }
            }
            int expected = SmartPixelsPattern.dimmedCells(percent);
            for (int i = 0; i < duty.length; i++) {
                assertThat(duty[i]).isEqualTo(expected);
            }
        }
    }

    @Test
    public void dimmedPixelsDoNotClump() {
        // Every wrapping 4x4 window holds within one cell of its fair share, so the blacked-out
        // pixels stay evenly spread instead of forming a visible blotch.
        int grid = SmartPixelsPattern.GRID;
        for (int percent = SmartPixelsPattern.MIN_PERCENT;
                percent <= SmartPixelsPattern.MAX_PERCENT; percent++) {
            boolean[] mask = SmartPixelsPattern.mask(percent);
            double fair = SmartPixelsPattern.dimmedCells(percent) * 16.0 / SmartPixelsPattern.CELLS;
            for (int oy = 0; oy < grid; oy++) {
                for (int ox = 0; ox < grid; ox++) {
                    int count = 0;
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            if (mask[((oy + y) % grid) * grid + ((ox + x) % grid)]) count++;
                        }
                    }
                    assertThat(Math.abs(count - fair)).isAtMost(1.0);
                }
            }
        }
    }

    @Test
    public void percentsThatRoundToTheSameCellCountProduceTheSameMask() {
        assertThat(SmartPixelsPattern.dimmedCells(12)).isEqualTo(SmartPixelsPattern.dimmedCells(13));
        assertThat(SmartPixelsPattern.mask(12)).isEqualTo(SmartPixelsPattern.mask(13));
        assertThat(SmartPixelsPattern.mask(25)).isNotEqualTo(SmartPixelsPattern.mask(50));
    }
}
