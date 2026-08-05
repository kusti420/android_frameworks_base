/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.recents.oneui.styles;

import android.graphics.Path;

/**
 * Samples a {@link Path} that describes a function {@code y = f(x)} and lets it be evaluated in
 * both directions, plus reports the arc length of the sampled curve.
 *
 * <p>This is a straight port of {@code com.android.systemui.recents.misc.FreePathInterpolator} from
 * AOSP 7.1.1, which was deleted along with the rest of the old recents. It is carried here rather
 * than replaced with {@code androidx.core.view.animation.PathInterpolatorCompat} because
 * {@link CascadeLayoutManager} needs the two things the androidx/platform path interpolators do not
 * expose:
 *
 * <ul>
 *   <li>{@link #getX(float)} — the inverse. The 7.1.1 layout derives its scroll bounds by asking
 *       "which point on the curve puts a card's top at this screen y", i.e. it solves x for a known
 *       y ({@code TaskStackLayoutAlgorithm#getNormalizedXFromUnfocusedY}).
 *   <li>{@link #getArcLength()} — the constant that maps finger travel in pixels to travel along
 *       the curve ({@code TaskStackLayoutAlgorithm#getDeltaPForY}). The curve is close to flat, so
 *       AOSP just divides screen distance by the arc length instead of walking the path.
 * </ul>
 *
 * <p>Unlike the original this does not extend {@code BaseInterpolator}: nothing here is used to
 * drive an animation, so the {@code Interpolator} contract would only be noise.
 */
final class CascadePathInterpolator {

    /** How accurately the path is approximated; the AOSP value. */
    private static final float PRECISION = 0.002f;

    private final float[] mX;
    private final float[] mY;
    private final float mArcLength;

    CascadePathInterpolator(Path path) {
        final float[] pointComponents = path.approximate(PRECISION);
        final int numPoints = pointComponents.length / 3;

        mX = new float[numPoints];
        mY = new float[numPoints];
        float arcLength = 0;
        float prevX = 0;
        float prevY = 0;
        float prevFraction = 0;
        int componentIndex = 0;
        for (int i = 0; i < numPoints; i++) {
            float fraction = pointComponents[componentIndex++];
            float x = pointComponents[componentIndex++];
            float y = pointComponents[componentIndex++];
            if (fraction == prevFraction && x != prevX) {
                throw new IllegalArgumentException(
                        "The Path cannot have discontinuity in the X axis.");
            }
            if (x < prevX) {
                throw new IllegalArgumentException("The Path cannot loop back on itself.");
            }
            mX[i] = x;
            mY[i] = y;
            arcLength += (float) Math.hypot(x - prevX, y - prevY);
            prevX = x;
            prevY = y;
            prevFraction = fraction;
        }
        mArcLength = arcLength;
    }

    /** The y of the curve at {@code t}, clamped to the endpoints outside [0,1]. */
    float getInterpolation(float t) {
        int startIndex = 0;
        int endIndex = mX.length - 1;

        if (t <= 0) {
            return mY[startIndex];
        } else if (t >= 1) {
            return mY[endIndex];
        }

        while (endIndex - startIndex > 1) {
            int midIndex = (startIndex + endIndex) / 2;
            if (t < mX[midIndex]) {
                endIndex = midIndex;
            } else {
                startIndex = midIndex;
            }
        }

        float xRange = mX[endIndex] - mX[startIndex];
        if (xRange == 0) {
            return mY[startIndex];
        }

        float fraction = (t - mX[startIndex]) / xRange;
        float startY = mY[startIndex];
        float endY = mY[endIndex];
        return startY + (fraction * (endY - startY));
    }

    /**
     * The x that produces the given {@code y}. Assumes, as the cascade curve does, that y decreases
     * as x increases.
     */
    float getX(float y) {
        int startIndex = 0;
        int endIndex = mY.length - 1;

        if (y <= 0) {
            return mX[endIndex];
        } else if (y >= 1) {
            return mX[startIndex];
        }

        while (endIndex - startIndex > 1) {
            int midIndex = (startIndex + endIndex) / 2;
            if (y < mY[midIndex]) {
                startIndex = midIndex;
            } else {
                endIndex = midIndex;
            }
        }

        float yRange = mY[endIndex] - mY[startIndex];
        if (yRange == 0) {
            return mX[startIndex];
        }

        float fraction = (y - mY[startIndex]) / yRange;
        float startX = mX[startIndex];
        float endX = mX[endIndex];
        return startX + (fraction * (endX - startX));
    }

    /** The arc length of the sampled curve. ~1.577 for the unfocused cascade curve. */
    float getArcLength() {
        return mArcLength;
    }
}
