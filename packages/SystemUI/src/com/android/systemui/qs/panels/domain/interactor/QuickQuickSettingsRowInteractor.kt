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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.QSPreferencesRepository
import com.android.systemui.qs.panels.data.repository.QuickQuickSettingsRowRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QuickQuickSettingsRowInteractor
@Inject
constructor(
    private val quickQuickSettingsRowRepository: QuickQuickSettingsRowRepository,
    private val preferencesInteractor: QSPreferencesInteractor,
) {
    val rows = quickQuickSettingsRowRepository.rows

    val defaultRows = 1

    /** The row counts the user can pick. */
    val rowsRange: IntRange =
        QuickQuickSettingsRowRepository.MIN_ROWS..QuickQuickSettingsRowRepository.MAX_ROWS

    /** IchthysOS: tiles per row of the collapsed panel. Not the columns of the expanded grid. */
    val columns: Flow<Int> = preferencesInteractor.collapsedColumns

    val defaultColumns: Int = QSPreferencesRepository.COLLAPSED_COLUMNS_DEFAULT

    /** The tiles per row the user can pick. */
    val columnsRange: IntRange =
        QSPreferencesRepository.COLLAPSED_COLUMNS_MIN..QSPreferencesRepository.COLLAPSED_COLUMNS_MAX

    fun setRows(rows: Int) {
        quickQuickSettingsRowRepository.setRows(rows)
    }

    fun setColumns(columns: Int) {
        preferencesInteractor.setCollapsedColumns(columns)
    }

    /** Back to [defaultColumns]: the key is deleted, not set to the default. */
    fun resetColumns() {
        preferencesInteractor.setCollapsedColumns(null)
    }
}
