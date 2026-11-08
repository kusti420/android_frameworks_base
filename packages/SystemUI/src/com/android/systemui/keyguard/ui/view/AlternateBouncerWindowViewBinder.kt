/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import com.android.systemui.CoreStartable
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.composable.AlternateBouncer
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.repeatWhenAttachedToWindow
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Drives the showing and hiding of the alternate bouncer window. */
@SysUISingleton
class AlternateBouncerWindowViewBinder
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val context: Context,
    private val viewModel: AlternateBouncerViewModel,
    private val dependencies: AlternateBouncerDependencies,
    private val windowManager: WindowManager,
) : CoreStartable {

    /** The window currently showing the alternate bouncer, if any (main thread only). */
    private var currentRoot: View? = null

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        applicationScope.launch {
            viewModel.isVisible
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    // IchthysOS: a fast hide/show (double power press on the lock screen) used
                    // to add a second window while the first was still playing its hide
                    // animation. Both composed AlternateBouncer with the same activatable view
                    // model, and the second activate() crashed SystemUI ("Cannot activate an
                    // already active ExclusiveActivatable"). Remove the old window at once so
                    // only one alternate bouncer window ever exists.
                    currentRoot?.let { old ->
                        if (old.isAttachedToWindow) {
                            windowManager.removeViewImmediate(old)
                        }
                    }
                    val root = createView()
                    currentRoot = root
                    windowManager.addView(root, AlternateBouncerWindowViewLayoutParams.layoutParams)
                }
        }
    }

    private fun createView(): View {
        val root = FrameLayout(context)
        val composeView =
            ComposeView(context).apply {
                setContent {
                    AlternateBouncer(
                        alternateBouncerDependencies = dependencies,
                        onHideAnimationFinished = {
                            if (root.isAttachedToWindow) {
                                windowManager.removeView(root)
                            }
                            if (currentRoot === root) {
                                currentRoot = null
                            }
                        },
                    )
                }
            }

        root.repeatWhenAttached {
            root.repeatWhenAttachedToWindow {
                try {
                    ComposeInitializer.onAttachedToWindow(root)
                    root.addView(composeView)
                    awaitCancellation()
                } finally {
                    root.removeView(composeView)
                    ComposeInitializer.onDetachedFromWindow(root)
                }
            }
        }

        return root
    }
}
