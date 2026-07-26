/*
 * Copyright (C) 2016 The Dirty Unicorns Project
 * Copyright (C) 2026 crDroid Android / IchthysOS
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
 * Transparent, non-interactive view that hosts whichever Pulse renderer is
 * currently selected. It never intercepts touches so the lockscreen behaves
 * normally underneath it.
 */
package com.android.systemui.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

public class PulseView extends View {

    private Renderer mRenderer;

    public PulseView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setWillNotDraw(false);
    }

    public void setRenderer(Renderer renderer) {
        mRenderer = renderer;
        postInvalidate();
    }

    public Renderer getRenderer() {
        return mRenderer;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mRenderer != null) {
            mRenderer.onSizeChanged(w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final Renderer renderer = mRenderer;
        if (renderer != null) {
            renderer.draw(canvas);
        }
    }

    // Never steal input from the lockscreen.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }
}
