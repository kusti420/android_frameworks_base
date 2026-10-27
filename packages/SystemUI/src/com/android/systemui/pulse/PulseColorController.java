/*
 * Copyright (C) 2020-2025 crDroid Android Project
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
 *
 * Supplies the color the renderers paint with, based on the "pulse_color"
 * secure setting. This is the A17/IchthysOS adaptation of crDroid's
 * ColorController: the "pulse_color" key here is a string
 * ("lavalamp"/"album"/"accent") rather than crDroid's int PULSE_COLOR_MODE,
 * so the mode plumbing is rewritten while the lavalamp itself reuses crDroid's
 * ColorAnimator verbatim.
 */
package com.android.systemui.pulse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;

import com.android.internal.util.ContrastColorUtil;
import com.android.settingslib.Utils;
import com.android.settingslib.utils.ThreadUtils;

import java.util.List;

public class PulseColorController implements ColorAnimator.ColorAnimationListener {

    public static final String MODE_LAVALAMP = "lavalamp";
    public static final String MODE_ALBUM = "album";
    public static final String MODE_ACCENT = "accent";

    // How often we re-sample the album artwork.
    private static final long ALBUM_REFRESH_MS = 1500L;

    public interface InvalidateCallback {
        void onColorChanged();
    }

    private final Context mContext;
    private final InvalidateCallback mCallback;
    private final ColorAnimator mLavaLamp;

    private String mMode = MODE_LAVALAMP;
    private boolean mRunning;

    private int mLavaColor = Color.WHITE;
    private long mLastAlbumSample;
    // IchthysOS: written by the background sampler, read by the renderers in onDraw.
    private volatile int mAlbumColor = Color.WHITE;
    private volatile boolean mAlbumSampling;

    public PulseColorController(Context context, InvalidateCallback callback) {
        mContext = context;
        mCallback = callback;
        mLavaLamp = new ColorAnimator();
        mLavaLamp.setColorAnimatorListener(this);
        mAlbumColor = Utils.getColorAccentDefaultColor(mContext);
    }

    public void setMode(String mode) {
        if (mode == null) {
            mode = MODE_LAVALAMP;
        }
        if (mode.equals(mMode)) {
            return;
        }
        mMode = mode;
        mLastAlbumSample = 0;
        if (mRunning) {
            if (MODE_LAVALAMP.equals(mMode)) {
                mLavaLamp.start();
            } else {
                mLavaLamp.stop();
            }
        }
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        if (MODE_LAVALAMP.equals(mMode)) {
            mLavaLamp.start();
        }
    }

    public void stop() {
        mRunning = false;
        mLavaLamp.stop();
    }

    /** Returns the color the renderers should currently use. */
    public int getColor() {
        switch (mMode) {
            case MODE_ACCENT:
                return Utils.getColorAccentDefaultColor(mContext);
            case MODE_ALBUM:
                return getAlbumColor();
            case MODE_LAVALAMP:
            default:
                return mLavaColor;
        }
    }

    @Override
    public void onColorChanged(ColorAnimator colorAnimator, int color) {
        mLavaColor = color;
        if (mCallback != null) {
            mCallback.onColorChanged();
        }
    }

    private int getAlbumColor() {
        long now = System.currentTimeMillis();
        if (now - mLastAlbumSample < ALBUM_REFRESH_MS || mAlbumSampling) {
            return mAlbumColor;
        }
        mLastAlbumSample = now;
        // IchthysOS: this is called from the renderers' onDraw. getActiveSessions / getMetadata
        // are binder calls and the artwork is scaled down, so sample on a background thread and
        // draw with the cached colour meanwhile.
        mAlbumSampling = true;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                int sampled = sampleArtworkColor();
                int color;
                if (sampled != 0) {
                    // Ensure the album color reads well against a dark lockscreen,
                    // mirroring crDroid's ColorController contrast handling.
                    int c = ContrastColorUtil.findContrastColorAgainstDark(sampled, 0x000000,
                            true, 2);
                    color = ContrastColorUtil.findContrastColor(c, 0xffffff, true, 2);
                } else {
                    color = Utils.getColorAccentDefaultColor(mContext);
                }
                if (color != mAlbumColor) {
                    mAlbumColor = color;
                    if (mCallback != null) {
                        mCallback.onColorChanged();
                    }
                }
            } finally {
                mAlbumSampling = false;
            }
        });
        return mAlbumColor;
    }

    private int sampleArtworkColor() {
        try {
            MediaSessionManager msm =
                    mContext.getSystemService(MediaSessionManager.class);
            if (msm == null) {
                return 0;
            }
            List<MediaController> controllers = msm.getActiveSessions(null);
            if (controllers == null) {
                return 0;
            }
            for (MediaController controller : controllers) {
                MediaMetadata md = controller.getMetadata();
                if (md == null) {
                    continue;
                }
                Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) {
                    art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                }
                if (art != null) {
                    return dominantColor(art);
                }
            }
        } catch (Exception e) {
            // Missing MEDIA_CONTENT_CONTROL or transient failure -> fallback.
        }
        return 0;
    }

    private int dominantColor(Bitmap bitmap) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true);
            int color = scaled.getPixel(0, 0);
            if (scaled != bitmap) {
                scaled.recycle();
            }
            return color;
        } catch (Exception e) {
            return 0;
        }
    }
}
