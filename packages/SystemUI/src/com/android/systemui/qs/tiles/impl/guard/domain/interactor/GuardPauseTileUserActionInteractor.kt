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

package com.android.systemui.qs.tiles.impl.guard.domain.interactor

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.coroutines.newTracingContext
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.impl.guard.data.repository.IchthysGuardRepository
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardPauseTileModel
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * IchthysOS: a tap pauses Guard for [IchthysGuardRepository.PAUSE_MINUTES] minutes (every
 * firewall rule lifted, back by itself afterwards); a tap during the pause resumes. Pausing lifts
 * protection, so a secure keyguard has to be dismissed first; resuming works from the lock screen.
 * A long press opens Guard in Settings.
 */
class GuardPauseTileUserActionInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val guardRepository: IchthysGuardRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val activityStarter: ActivityStarter,
    private val keyguardController: KeyguardStateController,
) : QSTileUserActionInteractor<GuardPauseTileModel> {

    override suspend fun handleInput(input: QSTileInput<GuardPauseTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (!data.isReachable) return@with
                    if (data.isPaused) {
                        guardRepository.pause(0)
                    } else if (
                        keyguardController.isMethodSecure() && keyguardController.isShowing()
                    ) {
                        activityStarter.postQSRunnableDismissingKeyguard {
                            CoroutineScope(
                                    applicationScope.coroutineContext +
                                        newTracingContext("GuardPauseTileScope")
                                )
                                .launch {
                                    guardRepository.pause(IchthysGuardRepository.PAUSE_MINUTES)
                                }
                        }
                    } else {
                        guardRepository.pause(IchthysGuardRepository.PAUSE_MINUTES)
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        IchthysGuardRepository.guardScreenIntent(),
                    )
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
