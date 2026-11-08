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

package com.android.systemui.smartpixels

import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * IchthysOS: the unlock state Smart Pixels steps aside for (owner 2026-11-08: "keep the feature.
 * make it not conflict with the fingerprint and unlocking"). Java-friendly wrapper, so
 * [SmartPixelsController] can collect it through JavaAdapter.
 */
@SysUISingleton
class SmartPixelsUnlockState
@Inject
constructor(
    private val sceneInteractor: Lazy<SceneInteractor>,
    private val primaryBouncerInteractor: Lazy<PrimaryBouncerInteractor>,
) {
    /**
     * Whether the PIN / pattern / password bouncer is showing or on its way in. With the scene
     * container the bouncer is an overlay; without it, the legacy primary bouncer (same split as
     * SystemUIDeviceEntryFaceAuthInteractor.isBouncerVisible).
     */
    val bouncerShowing: Flow<Boolean> by lazy {
        (if (SceneContainerFlag.isEnabled) {
                sceneInteractor.get().transitionStateFlow.map {
                    it.isTransitioning(to = Overlays.Bouncer) || it.isIdle(Overlays.Bouncer)
                }
            } else {
                primaryBouncerInteractor.get().isShowing
            })
            .distinctUntilChanged()
    }
}
