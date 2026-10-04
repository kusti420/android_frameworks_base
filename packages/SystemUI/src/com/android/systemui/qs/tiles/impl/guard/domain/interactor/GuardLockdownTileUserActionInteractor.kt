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
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardLockdownTileModel
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * IchthysOS: a tap toggles Guard Lockdown (network off for every app outside the lockdown
 * allowlist). Turning it on works straight from the lock screen (it's the panic switch); turning
 * it off lifts protection, so a secure keyguard has to be dismissed first. A long press opens
 * Guard in Settings.
 */
class GuardLockdownTileUserActionInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val guardRepository: IchthysGuardRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val activityStarter: ActivityStarter,
    private val keyguardController: KeyguardStateController,
) : QSTileUserActionInteractor<GuardLockdownTileModel> {

    override suspend fun handleInput(input: QSTileInput<GuardLockdownTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    val enabled = data.isEnabled ?: return@with
                    if (
                        enabled &&
                            keyguardController.isMethodSecure() &&
                            keyguardController.isShowing()
                    ) {
                        activityStarter.postQSRunnableDismissingKeyguard {
                            CoroutineScope(
                                    applicationScope.coroutineContext +
                                        newTracingContext("GuardLockdownTileScope")
                                )
                                .launch { guardRepository.lockdown(user, enable = false) }
                        }
                    } else {
                        guardRepository.lockdown(user, enable = !enabled)
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
