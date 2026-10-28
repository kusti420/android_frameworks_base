/*
 * Copyright (C) 2026 IchthysOS
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package android.view;

import android.annotation.Nullable;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;

/**
 * IchthysOS clipboard lockdown (kusti420/ichthysos-tracker#31): the most recent genuine user
 * input this process received (a pointer up or a key down), kept as a copy so it still carries
 * the InputDispatcher HMAC. ClipboardManager hands it to ClipboardService as proof that a
 * clipboard read follows a real user action (e.g. tapping Paste); the service verifies it with
 * InputManager#verifyInputEvent, so apps can't forge one.
 *
 * @hide
 */
public final class IchthysPasteInput {
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static InputEvent sLastUserInput;
    /** The press of the last pointer gesture; paired with its release in {@link #sLastUserDown}. */
    @GuardedBy("sLock")
    private static MotionEvent sPendingDown;
    /** The press that began {@link #sLastUserInput} (a tap's ACTION_DOWN), or null. */
    @GuardedBy("sLock")
    private static MotionEvent sLastUserDown;

    private IchthysPasteInput() {}

    /** Called by ViewRootImpl for every input event delivered to one of this process' windows. */
    static void record(InputEvent event) {
        final boolean interesting;
        if (event instanceof MotionEvent) {
            final MotionEvent me = (MotionEvent) event;
            if (me.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                    && me.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // ClipboardService checks the press against the release: a tap, not a swipe.
                final MotionEvent down = me.copy();
                final MotionEvent oldDown;
                synchronized (sLock) {
                    oldDown = sPendingDown;
                    sPendingDown = down;
                }
                if (oldDown != null) {
                    oldDown.recycle();
                }
                return;
            }
            interesting = me.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                    && me.getActionMasked() == MotionEvent.ACTION_UP;
        } else if (event instanceof KeyEvent) {
            // Same keys ClipboardService accepts: typing or Back must not replace a tap.
            interesting = ((KeyEvent) event).getAction() == KeyEvent.ACTION_DOWN
                    && isPasteKey((KeyEvent) event);
        } else {
            interesting = false;
        }
        if (!interesting) {
            return;
        }
        final InputEvent copy = event.copy();
        final InputEvent old;
        final MotionEvent oldDown;
        synchronized (sLock) {
            old = sLastUserInput;
            oldDown = sLastUserDown;
            sLastUserInput = copy;
            sLastUserDown = null;
            if (event instanceof MotionEvent && sPendingDown != null
                    && sPendingDown.getEventTime() == ((MotionEvent) event).getDownTime()) {
                sLastUserDown = sPendingDown;
                sPendingDown = null;
            }
        }
        if (old != null) {
            old.recycle();
        }
        if (oldDown != null) {
            oldDown.recycle();
        }
    }

    /**
     * The key presses that can be a paste (keep in step with ClipboardService): the paste key,
     * Ctrl/Meta+V and Shift+Insert. Enter is not one: it sends a message or a search, and an app
     * reading the clip on it would get it every time.
     */
    private static boolean isPasteKey(KeyEvent key) {
        switch (key.getKeyCode()) {
            case KeyEvent.KEYCODE_PASTE:
                return true;
            case KeyEvent.KEYCODE_V:
                return key.isCtrlPressed() || key.isMetaPressed();
            case KeyEvent.KEYCODE_INSERT:
                return key.isShiftPressed();
            default:
                return false;
        }
    }

    /**
     * Returns a copy of the last genuine user input event if it is at most {@code maxAgeMillis}
     * old, else null. The caller owns (and should recycle) the copy.
     */
    @Nullable
    public static InputEvent getRecent(long maxAgeMillis) {
        synchronized (sLock) {
            final InputEvent last = sLastUserInput;
            if (last == null
                    || SystemClock.uptimeMillis() - last.getEventTime() > maxAgeMillis) {
                return null;
            }
            return last.copy();
        }
    }

    /**
     * Returns a copy of the press that began the input {@link #getRecent} returns (a tap's
     * ACTION_DOWN), or null when that input is a key press or its press was not seen.
     */
    @Nullable
    public static InputEvent getRecentDown(long maxAgeMillis) {
        synchronized (sLock) {
            final InputEvent last = sLastUserInput;
            final MotionEvent down = sLastUserDown;
            if (last == null || down == null
                    || SystemClock.uptimeMillis() - last.getEventTime() > maxAgeMillis) {
                return null;
            }
            return down.copy();
        }
    }
}
