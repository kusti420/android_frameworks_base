/*
 * Copyright (C) 2026 The IchthysOS Project
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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.QSRowsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Rows per page of the expanded QS grid — see [QSRowsRepository]. */
@SysUISingleton
class QSRowsInteractor @Inject constructor(private val repo: QSRowsRepository) {
    val rows: Flow<Int> = repo.rows

    val defaultRows: Int
        get() = repo.defaultRows
}
