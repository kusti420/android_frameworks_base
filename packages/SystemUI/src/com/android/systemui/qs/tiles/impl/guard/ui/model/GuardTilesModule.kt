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

package com.android.systemui.qs.tiles.impl.guard.ui.model

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.guard.domain.interactor.GuardLockdownTileDataInteractor
import com.android.systemui.qs.tiles.impl.guard.domain.interactor.GuardLockdownTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.guard.domain.interactor.GuardPauseTileDataInteractor
import com.android.systemui.qs.tiles.impl.guard.domain.interactor.GuardPauseTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardLockdownTileModel
import com.android.systemui.qs.tiles.impl.guard.domain.model.GuardPauseTileModel
import com.android.systemui.qs.tiles.impl.guard.ui.mapper.GuardLockdownTileMapper
import com.android.systemui.qs.tiles.impl.guard.ui.mapper.GuardPauseTileMapper
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * IchthysOS: the native Ichthys Guard tiles (Lockdown, Pause). New-pipeline tiles only
 * (qs_new_tiles is on in this release config); there is no legacy QSTileImpl behind them.
 */
@Module
interface GuardTilesModule {

    @Binds
    @IntoMap
    @StringKey(GUARD_LOCKDOWN_TILE_SPEC)
    fun provideGuardLockdownAvailabilityInteractor(
        impl: GuardLockdownTileDataInteractor
    ): QSTileAvailabilityInteractor

    @Binds
    @IntoMap
    @StringKey(GUARD_PAUSE_TILE_SPEC)
    fun provideGuardPauseAvailabilityInteractor(
        impl: GuardPauseTileDataInteractor
    ): QSTileAvailabilityInteractor

    companion object {
        const val GUARD_LOCKDOWN_TILE_SPEC = "guard_lockdown"
        const val GUARD_PAUSE_TILE_SPEC = "guard_pause"

        @Provides
        @IntoMap
        @StringKey(GUARD_LOCKDOWN_TILE_SPEC)
        fun provideGuardLockdownTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(GUARD_LOCKDOWN_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_ichthys_guard_lockdown,
                        labelRes = R.string.ichthys_guard_tile_lockdown,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.PRIVACY,
            )

        /** Inject the Guard Lockdown tile into tileViewModelMap in QSModule. */
        @Provides
        @IntoMap
        @StringKey(GUARD_LOCKDOWN_TILE_SPEC)
        fun provideGuardLockdownTileViewModel(
            factory: QSTileViewModelFactory.Static<GuardLockdownTileModel>,
            mapper: GuardLockdownTileMapper,
            stateInteractor: GuardLockdownTileDataInteractor,
            userActionInteractor: GuardLockdownTileUserActionInteractor,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(GUARD_LOCKDOWN_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )

        @Provides
        @IntoMap
        @StringKey(GUARD_PAUSE_TILE_SPEC)
        fun provideGuardPauseTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(GUARD_PAUSE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_ichthys_guard_pause,
                        labelRes = R.string.ichthys_guard_tile_pause,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.PRIVACY,
            )

        /** Inject the Guard Pause tile into tileViewModelMap in QSModule. */
        @Provides
        @IntoMap
        @StringKey(GUARD_PAUSE_TILE_SPEC)
        fun provideGuardPauseTileViewModel(
            factory: QSTileViewModelFactory.Static<GuardPauseTileModel>,
            mapper: GuardPauseTileMapper,
            stateInteractor: GuardPauseTileDataInteractor,
            userActionInteractor: GuardPauseTileUserActionInteractor,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(GUARD_PAUSE_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}
