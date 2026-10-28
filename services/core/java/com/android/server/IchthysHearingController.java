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

package com.android.server;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;

/**
 * IchthysOS hearing compensation. Owns the one long-lived handle to the per-ear corrective
 * equaliser (libichthyshearing) on audio session 0, so the curve the user measured in the Hearing
 * Check app applies to every stream on the device rather than to one app's session.
 *
 * <p>The division of labour is deliberate: the app owns the measurement and the prescription
 * arithmetic and publishes only a finished curve into {@link Settings.Global}; this class owns
 * nothing but the act of applying it. That keeps the audiology out of system_server, and it means
 * the correction survives the app being uninstalled, force-stopped or swapped for a different one.
 *
 * <p>The settings live in Global rather than Secure because the effect sits on the global output
 * mix: there is one mixer for the device, so there can only be one answer, and reading a
 * per-profile setting would leave it ambiguous whose ears win after a user switch.
 */
public final class IchthysHearingController {
    private static final String TAG = "IchthysHearing";

    /** Effect type, matching ICHTHYS_HEARING_TYPE in effect_ichthyshearing.h. */
    private static final UUID EFFECT_TYPE = UUID.fromString("b7e5f001-6a1f-4c2e-9a3b-1c7d5e8f0a21");
    /** Effect implementation, matching ICHTHYS_HEARING_UUID. */
    private static final UUID EFFECT_UUID = UUID.fromString("b7e5f002-6a1f-4c2e-9a3b-1c7d5e8f0a21");

    /** Band centre frequencies in Hz, matching kBandFreqs in EffectIchthysHearing.cpp. */
    public static final int[] BAND_FREQS = {125, 250, 500, 1000, 2000, 4000, 6000, 8000};
    public static final int NUM_BANDS = 8;

    // t_ichthys_hearing_params.
    private static final int PARAM_BAND_GAINS = 2;
    private static final int PARAM_MAKEUP_GAIN = 3;

    private static final int BAND_GAIN_MIN_MB = -2400;
    private static final int BAND_GAIN_MAX_MB = 1200;
    private static final int MAKEUP_MAX_MB = 1200;

    // Above the priority any ordinary app asks for, so a music player's equaliser cannot take
    // control of the session-0 chain out from under us.
    private static final int EFFECT_PRIORITY = 1000;

    // AUDIO_SESSION_OUTPUT_MIX: attaches to the output mix itself rather than to one app's
    // session, which is what makes the correction device-wide.
    private static final int GLOBAL_SESSION = 0;

    private static final long RETRY_DELAY_MS = 5000;
    private static final int MAX_RETRIES = 6;

    private final Context mContext;
    private final Handler mHandler;

    private AudioEffect mEffect;
    private int mRetries;
    // Last curve actually pushed to the engine, so a burst of settings notifications does not
    // turn into a burst of setParameter calls into audioserver.
    private int[] mAppliedGainsMb;
    private int mAppliedMakeupMb = -1;

    public IchthysHearingController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Called from SystemServer once the audio service is up. */
    public void onBootPhaseReady() {
        final ContentResolver cr = mContext.getContentResolver();

        // The bypass flag is only ever meant to last for the duration of a measurement. If the app
        // died mid-test the flag is still set, and without this the user's correction would stay
        // silently off with nothing in the UI to explain it.
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_BYPASS, 0);

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reevaluate();
            }
        };
        for (String key : new String[] {
                Settings.Global.HEARING_COMPENSATION_ENABLED,
                Settings.Global.HEARING_COMPENSATION_GAINS,
                Settings.Global.HEARING_COMPENSATION_MAKEUP,
                Settings.Global.HEARING_COMPENSATION_BYPASS}) {
            cr.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
        }

        // audioserver taking a crash tears the effect down with it; the handle we hold afterwards
        // is dead and silently drops every call, so rebuild from scratch when it comes back.
        try {
            final AudioManager am = mContext.getSystemService(AudioManager.class);
            if (am != null) {
                am.setAudioServerStateCallback(mHandler::post,
                        new AudioManager.AudioServerStateCallback() {
                            @Override
                            public void onAudioServerDown() {
                                releaseEffect();
                            }

                            @Override
                            public void onAudioServerUp() {
                                mRetries = 0;
                                apply();
                            }
                        });
            }
        } catch (Exception e) {
            Slog.w(TAG, "could not watch audio server state", e);
        }

        reevaluate();
    }

    private void reevaluate() {
        mHandler.post(() -> {
            mRetries = 0;
            apply();
        });
    }

    private void apply() {
        final ContentResolver cr = mContext.getContentResolver();
        final boolean enabled =
                Settings.Global.getInt(cr, Settings.Global.HEARING_COMPENSATION_ENABLED, 0) == 1;
        final boolean bypass =
                Settings.Global.getInt(cr, Settings.Global.HEARING_COMPENSATION_BYPASS, 0) == 1;
        final int[] gainsMb =
                parseGains(Settings.Global.getString(cr, Settings.Global.HEARING_COMPENSATION_GAINS));

        if (!enabled || bypass || gainsMb == null) {
            releaseEffect();
            return;
        }

        int makeupMb = Settings.Global.getInt(cr, Settings.Global.HEARING_COMPENSATION_MAKEUP, 0);
        makeupMb = Math.max(0, Math.min(MAKEUP_MAX_MB, makeupMb));

        if (!ensureEffect()) {
            return;
        }

        try {
            // IchthysOS: a value counts as applied only when the effect took it; a rejected
            // curve is pushed again on the retry instead of being remembered as set.
            boolean ok = true;
            if (!Arrays.equals(gainsMb, mAppliedGainsMb)) {
                if (checkParam("band gains",
                        mEffect.setParameter(PARAM_BAND_GAINS, packInts(gainsMb)))) {
                    mAppliedGainsMb = gainsMb;
                } else {
                    ok = false;
                }
            }
            if (makeupMb != mAppliedMakeupMb) {
                if (checkParam("makeup gain",
                        mEffect.setParameter(PARAM_MAKEUP_GAIN, packInts(new int[] {makeupMb})))) {
                    mAppliedMakeupMb = makeupMb;
                } else {
                    ok = false;
                }
            }
            if (!ok) {
                releaseEffect();
                scheduleRetry();
                return;
            }
            if (!mEffect.getEnabled()) {
                mEffect.setEnabled(true);
            }
        } catch (RuntimeException e) {
            // Most often this is a dead handle after an audioserver restart we were not told
            // about. Drop it and rebuild on the next tick rather than staying wedged.
            Slog.w(TAG, "failed to push hearing profile, rebuilding effect", e);
            releaseEffect();
            scheduleRetry();
        }
    }

    private boolean checkParam(String what, int status) {
        if (status != AudioEffect.SUCCESS) {
            Slog.w(TAG, "setParameter(" + what + ") returned " + status);
            return false;
        }
        return true;
    }

    private boolean ensureEffect() {
        if (mEffect != null) {
            return true;
        }
        try {
            mEffect = new AudioEffect(EFFECT_TYPE, EFFECT_UUID, EFFECT_PRIORITY, GLOBAL_SESSION);
        } catch (RuntimeException e) {
            // Thrown while audioserver is still coming up, and permanently if the vendor effects
            // config never loaded libichthyshearing — one warning either way, then retry.
            Slog.w(TAG, "could not attach hearing effect to the output mix: " + e.getMessage());
            mEffect = null;
            scheduleRetry();
        }
        return mEffect != null;
    }

    private void scheduleRetry() {
        // IchthysOS: keep trying, backing off (audioserver can take longer than the old 30 s
        // budget at boot, and the EQ then stayed off until a setting changed).
        if (mRetries == MAX_RETRIES) {
            Slog.w(TAG, "hearing effect still not attached; retrying less often");
        }
        final long delay = mRetries < MAX_RETRIES
                ? RETRY_DELAY_MS : Math.min(RETRY_DELAY_MS * (mRetries - MAX_RETRIES + 2), 60_000L);
        mRetries++;
        mHandler.removeCallbacks(mApplyRunnable);
        mHandler.postDelayed(mApplyRunnable, delay);
    }

    private final Runnable mApplyRunnable = this::apply;

    private void releaseEffect() {
        if (mEffect == null) {
            return;
        }
        try {
            mEffect.setEnabled(false);
        } catch (RuntimeException ignored) {
            // Already dead; release below is still worth attempting.
        }
        try {
            mEffect.release();
        } catch (RuntimeException ignored) {
        }
        mEffect = null;
        mAppliedGainsMb = null;
        mAppliedMakeupMb = -1;
    }

    /**
     * Parses "L:mB,mB,...;R:mB,mB,..." into a flat left-then-right array of
     * {@code 2 * NUM_BANDS} millibel gains, or null if the string is absent or does not describe a
     * complete curve. Partial curves are rejected outright: half an EQ is worse than none.
     */
    static int[] parseGains(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        final int[] out = new int[2 * NUM_BANDS];
        final String[] ears = value.split(";");
        if (ears.length != 2) {
            return null;
        }
        for (int e = 0; e < 2; e++) {
            final String expectedPrefix = (e == 0) ? "L:" : "R:";
            final String ear = ears[e].trim();
            if (!ear.startsWith(expectedPrefix)) {
                return null;
            }
            final String[] parts = ear.substring(expectedPrefix.length()).split(",");
            if (parts.length != NUM_BANDS) {
                return null;
            }
            for (int b = 0; b < NUM_BANDS; b++) {
                final int mb;
                try {
                    mb = Integer.parseInt(parts[b].trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
                out[e * NUM_BANDS + b] = Math.max(BAND_GAIN_MIN_MB, Math.min(BAND_GAIN_MAX_MB, mb));
            }
        }
        return out;
    }

    private static byte[] packInts(int[] values) {
        final ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.nativeOrder());
        for (int v : values) {
            buf.putInt(v);
        }
        return buf.array();
    }
}
