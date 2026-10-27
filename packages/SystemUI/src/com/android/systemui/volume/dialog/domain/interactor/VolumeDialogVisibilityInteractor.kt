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

package com.android.systemui.volume.dialog.domain.interactor

import android.annotation.SuppressLint
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.android.systemui.accessibility.data.repository.AccessibilityRepository
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.data.VolumeDialogVisibilityRepository
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogSafetyWarningModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel.Dismissed
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel.Invisible
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel.Visible
import com.android.systemui.volume.dialog.sliders.domain.OneUiExpandState
import com.android.systemui.volume.dialog.utils.VolumeTracer
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Handles Volume Dialog visibility state. It might change from several sources:
 * - [com.android.systemui.plugins.VolumeDialogController] requests visibility change;
 * - it might be dismissed by the inactivity timeout;
 * - it can be dismissed by the user;
 */
@VolumeDialogPluginScope
class VolumeDialogVisibilityInteractor
@Inject
constructor(
    @VolumeDialogPlugin coroutineScope: CoroutineScope,
    callbacksInteractor: VolumeDialogCallbacksInteractor,
    private val stateInteractor: VolumeDialogStateInteractor,
    private val tracer: VolumeTracer,
    private val repository: VolumeDialogVisibilityRepository,
    private val accessibilityRepository: AccessibilityRepository,
    private val controller: VolumeDialogController,
    private val secureSettingsRepository: SecureSettingsRepository,
    private val shadeInteractor: ShadeInteractor,
    private val expandedAudioTileDetailsFeatureInteractor:
        ExpandedAudioTileDetailsFeatureInteractor,
) {

    /** @see computeTimeout */
    private val defaultTimeout = 3.seconds
    /**
     * IchthysOS: One UI's expanded panel timeout (VolumePanelState: 5 s; any touch on the panel
     * reschedules it, and accessibility's recommended timeout still applies on top).
     */
    private val expandedTimeout = 5.seconds

    // IchthysOS: evaluated per show / per QS expansion rather than cached when the plugin starts,
    // like VolumeDialogPluginViewModel.isVolumeDialogVertical, so the crDroid
    // "custom_volume_horizontal" switch changes the dialog layout and its "suppress while QS is
    // expanded" behaviour together, without a SystemUI restart.
    private val showVolumeSliderInQsShade: Boolean
        get() = expandedAudioTileDetailsFeatureInteractor.isEnabled()

    @SuppressLint("SharedFlowCreation")
    private val mutableDismissDialogEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dialogVisibility: Flow<VolumeDialogVisibilityModel> =
        repository.dialogVisibility
            .onEach { controller.notifyVisible(it is Visible) }
            .stateIn(coroutineScope, SharingStarted.Eagerly, Invisible)

    init {
        merge(
                mutableDismissDialogEvents.mapLatest {
                    delay(computeTimeout())
                    VolumeDialogEventModel.DismissRequested(Events.DISMISS_REASON_TIMEOUT)
                },
                callbacksInteractor.event,
            )
            .mapNotNull { event ->
                val visibilityModel = event.toVisibilityModel()

                // If volume slider in QS is not supposed to be shown, or if the event doesn't map
                // to a Visible state, then we don't need to check for QS visibility.
                if (!showVolumeSliderInQsShade || visibilityModel !is Visible) {
                    return@mapNotNull visibilityModel
                }

                // At this point, showVolumeSliderInQsShade is true AND visibilityModel is Visible.
                // Now check if Quick Settings is expanded.
                val isQsExpanded = shadeInteractor.isQsExpanded.first()

                if (isQsExpanded) {
                    // Suppress volume dialog
                    null
                } else {
                    // Show volume dialog
                    visibilityModel
                }
            }
            .onEach { model ->
                updateVisibility { model }
                if (model is Visible) {
                    resetDismissTimeout()
                }
            }
            .launchIn(coroutineScope)

        // IchthysOS: when the One UI expand state changes, restart the dismiss timer so the
        // freshly computed (longer or shorter) timeout takes effect immediately.
        OneUiExpandState.expanded
            .onEach { resetDismissTimeout() }
            .launchIn(coroutineScope)

        // Dismiss the volume dialog if QS becomes expanded while the dialog is visible (only while
        // the volume slider lives in the QS shade; checked each time, see above).
        combine(shadeInteractor.isQsExpanded, dialogVisibility) {
                isQsExpandedNow,
                currentDialogVisibility ->
                when {
                    isQsExpandedNow &&
                        currentDialogVisibility is Visible &&
                        showVolumeSliderInQsShade -> {
                        dismissDialog(Events.DISMISS_REASON_QUICK_SETTINGS_EXPANDED)
                    }
                }
            }
            .launchIn(coroutineScope)
    }

    /**
     * Dismisses the dialog with a given [reason]. The new state will be emitted in the
     * [dialogVisibility].
     */
    fun dismissDialog(reason: Int) {
        updateVisibility { Dismissed(reason) }
    }

    /** Resets current dialog timeout. */
    fun resetDismissTimeout() {
        controller.userActivity()
        mutableDismissDialogEvents.tryEmit(Unit)
    }

    private fun updateVisibility(
        update: (VolumeDialogVisibilityModel) -> VolumeDialogVisibilityModel
    ) {
        repository.updateVisibility { currentVisibility ->
            val newVisibility = update(currentVisibility)
            // Don't update if the visibility is of the same type
            if (currentVisibility::class == newVisibility::class) {
                currentVisibility
            } else {
                tracer.traceVisibilityStart(newVisibility)
                newVisibility
            }
        }
    }

    private suspend fun computeTimeout(): Duration {
        // IchthysOS: while the One UI panel is expanded it stays at least the longer One UI
        // timeout, but never less than the user's "Volume panel timeout" (up to 10 s).
        val isExpanded = OneUiExpandState.expanded.value
        val settingVal =
            secureSettingsRepository
                .getInt(
                    Settings.Secure.VOLUME_DIALOG_DISMISS_TIMEOUT,
                    defaultTimeout.toInt(DurationUnit.MILLISECONDS),
                )
                .milliseconds
        val defaultDialogTimeoutMillis =
            if (isExpanded) maxOf(expandedTimeout, settingVal) else settingVal
        val currentDialogState = stateInteractor.volumeDialogState.first()
        return when {
            currentDialogState.isHovering ->
                accessibilityRepository.getRecommendedTimeout(
                    defaultDialogTimeoutMillis,
                    AccessibilityManager.FLAG_CONTENT_CONTROLS,
                )

            currentDialogState.isShowingSafetyWarning is VolumeDialogSafetyWarningModel.Visible ->
                accessibilityRepository.getRecommendedTimeout(
                    defaultDialogTimeoutMillis,
                    AccessibilityManager.FLAG_CONTENT_TEXT or
                        AccessibilityManager.FLAG_CONTENT_CONTROLS,
                )

            else ->
                accessibilityRepository.getRecommendedTimeout(
                    defaultDialogTimeoutMillis,
                    AccessibilityManager.FLAG_CONTENT_CONTROLS,
                )
        }
    }

    private fun VolumeDialogEventModel.toVisibilityModel(): VolumeDialogVisibilityModel? {
        return when (this) {
            is VolumeDialogEventModel.DismissRequested -> Dismissed(reason)
            is VolumeDialogEventModel.ShowRequested ->
                Visible(reason, keyguardLocked, lockTaskModeState)

            else -> null
        }
    }
}
