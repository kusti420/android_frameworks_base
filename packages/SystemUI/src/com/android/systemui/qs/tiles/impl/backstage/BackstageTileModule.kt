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

package com.android.systemui.qs.tiles.impl.backstage

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModelFactory
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/** IchthysOS: the Backstage tile. New-pipeline only, like the Guard tiles. */
@Module
interface BackstageTileModule {

    @Binds
    @IntoMap
    @StringKey(BACKSTAGE_TILE_SPEC)
    fun provideBackstageAvailabilityInteractor(
        impl: BackstageTileDataInteractor
    ): QSTileAvailabilityInteractor

    companion object {
        const val BACKSTAGE_TILE_SPEC = "backstage"

        @Provides
        @IntoMap
        @StringKey(BACKSTAGE_TILE_SPEC)
        fun provideBackstageTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(BACKSTAGE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_ichthys_backstage,
                        labelRes = R.string.ichthys_backstage_tile_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(BACKSTAGE_TILE_SPEC)
        fun provideBackstageTileViewModel(
            factory: QSTileViewModelFactory.Static<BackstageTileModel>,
            mapper: BackstageTileMapper,
            stateInteractor: BackstageTileDataInteractor,
            userActionInteractor: BackstageTileUserActionInteractor,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(BACKSTAGE_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}
