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
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardLockdownTileModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge

/**
 * IchthysOS: Guard Lockdown state. The repository keeps the last state Settings' Guard reported
 * and follows its change broadcasts, so a tile refresh (shade opened, after a tap) only asks
 * Settings while the state is still unknown.
 */
class GuardLockdownTileDataInteractor
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val guardRepository: IchthysGuardRepository,
) : QSTileDataInteractor<GuardLockdownTileModel> {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<GuardLockdownTileModel> =
        merge(triggers.map {}, guardRepository.lockdownChanges(user))
            .mapLatest { GuardLockdownTileModel(guardRepository.lockdownState(user)) }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)

    override fun availability(user: UserHandle): Flow<Boolean> =
        flow { emit(guardRepository.isAvailable()) }.flowOn(bgDispatcher)
}
