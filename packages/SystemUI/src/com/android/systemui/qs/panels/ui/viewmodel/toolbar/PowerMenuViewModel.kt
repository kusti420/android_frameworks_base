/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.viewmodel.toolbar

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.android.systemui.globalactions.domain.interactor.GlobalActionsInteractor
import com.android.systemui.globalactions.shared.model.GlobalActionType
import com.android.systemui.globalactions.ui.viewmodel.GlobalActionUiState
import com.android.systemui.globalactions.ui.viewmodel.GlobalActionViewModel
import com.android.systemui.globalactions.ui.viewmodel.LockGlobalActionViewModel
import com.android.systemui.globalactions.ui.viewmodel.LogoutGlobalActionViewModel
import com.android.systemui.globalactions.ui.viewmodel.RestartGlobalActionViewModel
import com.android.systemui.globalactions.ui.viewmodel.ShutdownGlobalActionViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.PowerMenuLog
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import lineageos.providers.LineageSettings

/**
 * Aggregator ViewModel for the power menu shown when the power button in the QuickSettings panel
 * toolbar is clicked on large screens.
 */
class PowerMenuViewModel
@AssistedInject
constructor(
    lockFactory: LockGlobalActionViewModel.Factory,
    logoutFactory: LogoutGlobalActionViewModel.Factory,
    restartFactory: RestartGlobalActionViewModel.Factory,
    shutdownFactory: ShutdownGlobalActionViewModel.Factory,
    interactor: GlobalActionsInteractor,
    private val contentResolver: ContentResolver,
    @PowerMenuLog logBuffer: LogBuffer,
) : HydratedActivatable() {

    private val logger = Logger(logBuffer, "PowerMenuViewModel")

    /**
     * The definitive list of all global actions supported by this menu, in their fixed display
     * order.. The Power Menu has a fixed ordering of actions that should not be changed.
     */
    private val actions: List<GlobalActionViewModel> =
        listOf(
            lockFactory.create(),
            logoutFactory.create(),
            restartFactory.create(),
            shutdownFactory.create(),
        )

    /**
     * IchthysOS: the user's power-menu selection, re-read whenever it changes.
     *
     * `null` means "no usable selection stored" — callers must then fall back to stock behaviour.
     * Mirrors `GlobalActionsDialogLite.readUserPowerMenuActions()` so the QS toolbar menu and the
     * long-press power menu never disagree about what the user picked.
     */
    private val userSelectedActions: Flow<List<GlobalActionType>?> = conflatedCallbackFlow {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(readUserPowerMenuActions())
                }
            }
        contentResolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.POWER_MENU_ACTIONS),
            /* notifyForDescendants= */ false,
            observer,
        )
        trySend(readUserPowerMenuActions())
        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }

    /**
     * The list of global actions that are currently available to be displayed to the user. The
     * ordering in [actions] is preserved while filtering out actions that are not available.
     *
     * IchthysOS: additionally filtered by the user's crDroid power-menu selection
     * (`LineageSettings.Secure.POWER_MENU_ACTIONS`). Device state still has the final say: an action
     * the user picked but that [GlobalActionsInteractor.availableGlobalActions] hid (locked or
     * unprovisioned device) stays hidden.
     */
    val visibleActions: List<GlobalActionUiState.Visible> by
        combine(interactor.availableGlobalActions, userSelectedActions) { available, selected ->
                val effective = effectiveActions(available, selected)
                actions
                    .filter { it.key in effective }
                    .map { it.state }
                    .filterIsInstance<GlobalActionUiState.Visible>()
            }
            .onEach { visibleActions ->
                logger.d(
                    "Visible actions: ${visibleActions.map { it.key }} " +
                        "Available actions ${actions.map {it.key} }"
                )
            }
            .hydratedStateOf(initialValue = emptyList())

    override suspend fun onActivated(): Unit = coroutineScope {
        actions.forEach { child -> launch { child.activate() } }
    }

    /**
     * Reads `LineageSettings.Secure.POWER_MENU_ACTIONS` — a `|`-separated list of
     * `org.lineageos.internal.util.PowerMenuConstants` keys written by crDroid's power-menu
     * settings.
     *
     * Returns `null` when there is nothing usable stored (unset, empty, or only separators), which
     * keeps the caller on stock behaviour. A broken LineageSettings provider must never cost the
     * user their power menu, hence the catch-all.
     */
    private fun readUserPowerMenuActions(): List<GlobalActionType>? {
        val saved =
            try {
                LineageSettings.Secure.getStringForUser(
                    contentResolver,
                    LineageSettings.Secure.POWER_MENU_ACTIONS,
                    UserHandle.USER_CURRENT,
                )
            } catch (e: Exception) {
                logger.e("Could not read POWER_MENU_ACTIONS", e)
                return null
            }
        if (saved.isNullOrEmpty()) {
            return null
        }
        val keys = saved.split("|").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) {
            return null
        }
        // Keys this build has no action for (crDroid/Lineage-only entries such as "devicecontrols"
        // or "onthego") are skipped rather than treated as an error.
        return keys.mapNotNull { GlobalActionType.fromConfigKey(it) }.distinct()
    }

    /**
     * The action types this menu should offer, given what the device state allows ([available]) and
     * what the user picked ([selected], `null` when nothing usable is stored).
     *
     * [GlobalActionType.POWER] and [GlobalActionType.RESTART] are re-added unconditionally: a
     * mangled or over-trimmed selection must never leave the user unable to power off or reboot the
     * device. This matches `GlobalActionsDialogLite.getEffectiveGlobalActions()`.
     */
    private fun effectiveActions(
        available: List<GlobalActionType>,
        selected: List<GlobalActionType>?,
    ): List<GlobalActionType> {
        if (selected == null) {
            return available
        }
        val kept =
            (selected + GlobalActionType.POWER + GlobalActionType.RESTART).distinct().filter {
                it in available
            }
        // Safety net for the impossible case where device state hid everything the user chose *and*
        // both safety actions: an empty toolbar menu is worse than an unfiltered one.
        return kept.ifEmpty { available }
    }

    @AssistedFactory
    interface Factory {
        fun create(): PowerMenuViewModel
    }
}
