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

import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.guard.data.repository.IchthysGuardRepository
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardPauseTileModel
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * IchthysOS: Guard Pause state from IchthysGuardService. The tile data is only collected while
 * the tile is listening (shade open), so it simply polls the cheap getPausedUntil(): every second
 * during a pause (the countdown), every few seconds otherwise (a pause started from Guard).
 */
class GuardPauseTileDataInteractor
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val guardRepository: IchthysGuardRepository,
    private val systemClock: SystemClock,
) : QSTileDataInteractor<GuardPauseTileModel> {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<GuardPauseTileModel> =
        triggers
            .flatMapLatest {
                flow {
                    while (true) {
                        val until = guardRepository.pausedUntil()
                        val now = systemClock.currentTimeMillis()
                        val remaining = if (until != null && until > now) until - now else 0L
                        emit(GuardPauseTileModel(until, remaining))
                        // Wake on the next whole second of the countdown so "4:59" turns over on
                        // time; idle polling only has to catch a pause started from Guard.
                        delay(if (remaining > 0) (remaining - 1) % 1000 + 1 else IDLE_POLL_MS)
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)

    override fun availability(user: UserHandle): Flow<Boolean> =
        flow { emit(guardRepository.isAvailable()) }.flowOn(bgDispatcher)

    private companion object {
        const val IDLE_POLL_MS = 5_000L
    }
}
