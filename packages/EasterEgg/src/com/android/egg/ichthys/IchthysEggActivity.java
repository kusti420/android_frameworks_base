/*
 * Copyright (C) 2026 IchthysOS Project
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

package com.android.egg.ichthys;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * IchthysOS platform easter egg. Reached from the "Android version" tap (framework
 * {@code PlatLogoActivity} → {@code com.android.internal.category.PLATLOGO}). Renders a fully
 * self-contained HTML page (all assets are inlined as data: URIs, animations are pure CSS/GIF,
 * no JavaScript and no network) shipped in assets/ichthys_egg.html.
 */
public class IchthysEggActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        final WebView web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        final WebSettings s = web.getSettings();
        // The page is pure CSS + an animated GIF; no scripting is required or wanted.
        s.setJavaScriptEnabled(false);
        // Assets under file:///android_asset remain readable regardless of these flags.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.loadUrl("file:///android_asset/ichthys_egg.html");

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }
}
