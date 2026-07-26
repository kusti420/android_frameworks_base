/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.net.wifi.ScanResult
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.dagger.WifiTableLog
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Models the UI state for the status bar wifi icon.
 *
 * This is a singleton so that we don't have duplicate logs and should *not* be used directly to
 * control views. Instead, use an instance of [LocationBasedWifiViewModel]. See
 * [LocationBasedWifiViewModel.viewModelForLocation].
 */
@SysUISingleton
class WifiViewModel
@Inject
constructor(
    connectivityConstants: ConnectivityConstants,
    private val context: Context,
    @WifiTableLog wifiTableLogBuffer: TableLogBuffer,
    private val interactor: WifiInteractor,
    @Background scope: CoroutineScope,
    wifiConstants: WifiConstants,
    private val systemSettings: SystemSettings,
) : WifiViewModelCommon {
    override val wifiIcon: StateFlow<WifiIcon> =
        combine(
                interactor.isEnabled,
                interactor.isDefault,
                interactor.isForceHidden,
                interactor.wifiNetwork,
            ) { isEnabled, isDefault, isForceHidden, wifiNetwork ->
                if (!isEnabled || isForceHidden || wifiNetwork is WifiNetworkModel.CarrierMerged) {
                    return@combine WifiIcon.Hidden
                }

                // Don't show any hotspot info in the status bar.
                val icon = WifiIcon.fromModel(wifiNetwork, context, showHotspotInfo = false)

                return@combine when {
                    isDefault -> icon
                    wifiConstants.alwaysShowIconIfEnabled -> icon
                    !connectivityConstants.hasDataCapabilities -> icon
                    // See b/272509965: Even if we have an active and validated wifi network, we
                    // don't want to show the icon if wifi isn't the default network.
                    else -> WifiIcon.Hidden
                }
            }
            .logDiffsForTable(wifiTableLogBuffer, initialValue = WifiIcon.Hidden)
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = WifiIcon.Hidden,
            )

    // crDroid wifi_standard_icon: reflects the Settings.System.WIFI_STANDARD_ICON toggle
    // (default 0/off, matching crdroid_settings_statusbar.xml).
    private val isWifiStandardIconEnabled: Flow<Boolean> =
        conflatedCallbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(readWifiStandardIconSetting())
                        }
                    }
                systemSettings.registerContentObserverSync(
                    Settings.System.WIFI_STANDARD_ICON,
                    observer,
                )
                trySend(readWifiStandardIconSetting())
                awaitClose { systemSettings.unregisterContentObserverSync(observer) }
            }
            .distinctUntilChanged()

    override val wifiStandard: StateFlow<Int> =
        combine(isWifiStandardIconEnabled, interactor.wifiNetwork) { enabled, network ->
                if (!enabled || network !is WifiNetworkModel.Active) {
                    ScanResult.WIFI_STANDARD_UNKNOWN
                } else {
                    // Only badge the standards we have an asset for (Wi-Fi 4/5/6/7); everything
                    // else (unknown, legacy, 11a/g, 11ad) resolves to UNKNOWN and stays hidden.
                    when (network.wifiStandard) {
                        ScanResult.WIFI_STANDARD_11N,
                        ScanResult.WIFI_STANDARD_11AC,
                        ScanResult.WIFI_STANDARD_11AX,
                        ScanResult.WIFI_STANDARD_11BE -> network.wifiStandard
                        else -> ScanResult.WIFI_STANDARD_UNKNOWN
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ScanResult.WIFI_STANDARD_UNKNOWN,
            )

    private fun readWifiStandardIconSetting(): Boolean =
        systemSettings.getInt(Settings.System.WIFI_STANDARD_ICON, 0) == 1

    /** The wifi activity status. Null if we shouldn't display the activity status. */
    private val activity: Flow<DataActivityModel?> =
        if (!connectivityConstants.shouldShowActivityConfig) {
                flowOf(null)
            } else {
                combine(interactor.activity, interactor.ssid) { activity, ssid ->
                    when (ssid) {
                        null -> null
                        else -> activity
                    }
                }
            }
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = null)

    override val isActivityInViewVisible: Flow<Boolean> =
        activity
            .map { it?.hasActivityIn ?: false }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isActivityOutViewVisible: Flow<Boolean> =
        activity
            .map { it?.hasActivityOut ?: false }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isActivityContainerVisible: Flow<Boolean> =
        if (SystemStatusIconsInCompose.isEnabled) {
                flowOf(connectivityConstants.shouldShowActivityConfig)
            } else {
                activity.map { it != null && (it.hasActivityIn || it.hasActivityOut) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
}
