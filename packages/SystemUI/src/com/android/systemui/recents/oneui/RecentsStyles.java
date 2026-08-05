/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.recents.oneui;

import com.android.systemui.recents.oneui.styles.GraceCascadeStyle;
import com.android.systemui.recents.oneui.styles.GraceUxStyle;
import com.android.systemui.recents.oneui.styles.GridStyle;
import com.android.systemui.recents.oneui.styles.ListStyle;
import com.android.systemui.recents.oneui.styles.SlimListStyle;
import com.android.systemui.recents.oneui.styles.StackStyle;

/**
 * Maps the {@code recents_type} System setting onto a {@link RecentsStyle}.
 *
 * <p>Values 0-2 are handled elsewhere (0 Stock/Quickstep, 1 Classic, 2 Slim); this class only owns
 * the styles hosted by {@link OneUiRecentsActivity}.
 */
public final class RecentsStyles {

    /** Samsung 7.1.1 (Grace UX) vertical cascade of overlapping cards. */
    public static final int TYPE_GRACE = 3;
    /** Two-column grid of task cards. */
    public static final int TYPE_GRID = 4;
    /** Overlapping deck, most-recent on top. */
    public static final int TYPE_STACK = 5;
    /** Full-width vertical cards with a large snapshot. */
    public static final int TYPE_LIST = 6;
    /** Compact rows — icon and title, many tasks visible at once. */
    public static final int TYPE_SLIM_LIST = 7;
    /**
     * Samsung Grace UX (Android 7.0) card stack. Distinct from {@link #TYPE_GRACE}, which is the
     * faithful AOSP Nougat recreation: measured from a real SM-A310F on stock firmware, Samsung's
     * differs structurally — cards taper by a uniform view scale with depth, the stack has two
     * regimes (a three-card fan then a bunched pile) rather than one curve, and there is no dim at
     * all, only shadow.
     */
    public static final int TYPE_GRACE_UX = 8;

    private RecentsStyles() {}

    /** True when {@code type} is one of the styles {@link OneUiRecentsActivity} hosts. */
    public static boolean isHostedType(int type) {
        return type >= TYPE_GRACE && type <= TYPE_GRACE_UX;
    }

    /** Never returns null; an unknown value falls back to the cascade. */
    public static RecentsStyle forType(int type) {
        switch (type) {
            case TYPE_GRID:
                return new GridStyle();
            case TYPE_STACK:
                return new StackStyle();
            case TYPE_LIST:
                return new ListStyle();
            case TYPE_SLIM_LIST:
                return new SlimListStyle();
            case TYPE_GRACE_UX:
                return new GraceUxStyle();
            case TYPE_GRACE:
            default:
                return new GraceCascadeStyle();
        }
    }
}
