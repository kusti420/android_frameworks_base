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

package com.android.systemui.privacy

import android.database.ContentObserver
import android.location.flags.Flags.locationIndicatorsEnabled
import android.location.flags.Flags.locationIndicatorsOutline
import android.os.UserHandle
import android.provider.DeviceConfig
import android.provider.Settings
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.annotations.WeaklyReferencedCallback
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject

@SysUISingleton
class PrivacyConfig
@Inject
constructor(
    @Main private val uiExecutor: DelayableExecutor,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val secureSettings: SecureSettings,
    dumpManager: DumpManager,
) : Dumpable {

    @VisibleForTesting
    companion object {
        const val TAG = "PrivacyConfig"
        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
        private const val MEDIA_PROJECTION =
            SystemUiDeviceConfigFlags.PROPERTY_MEDIA_PROJECTION_INDICATORS_ENABLED
        private const val DEFAULT_MIC_CAMERA = true
        private const val DEFAULT_MEDIA_PROJECTION = true

        // Custom Options: crDroid per-user toggles (Settings.Secure) that let the user hide
        // individual privacy indicators regardless of the DeviceConfig availability flags.
        private const val CAMERA_INDICATOR = Settings.Secure.ENABLE_CAMERA_PRIVACY_INDICATOR
        private const val LOCATION_INDICATOR = Settings.Secure.ENABLE_LOCATION_PRIVACY_INDICATOR
        private const val PROJECTION_INDICATOR =
            Settings.Secure.ENABLE_PROJECTION_PRIVACY_INDICATOR
        // Indicators default to shown when the setting is absent.
        private const val DEFAULT_INDICATOR = 1

        fun getPrivacyColor(locationOnly: Boolean): Int {
            if (locationIndicatorsEnabled() && locationOnly) {
                return R.color.privacy_chip_location_only_background
            }
            return R.color.privacy_chip_background
        }

        fun getPrivacyOutlineColor(locationOnly: Boolean): Int {
            if (locationIndicatorsEnabled() && locationIndicatorsOutline() && locationOnly) {
                return R.color.privacy_chip_location_only_outline
            }
            return R.color.transparent
        }

        fun getPrivacyOutlineStroke(locationOnly: Boolean): Int {
            if (locationIndicatorsEnabled() && locationIndicatorsOutline() && locationOnly) {
                return 1
            }
            return 0
        }

        fun privacyItemsAreLocationOnly(privacyItems: List<PrivacyItem>): Boolean {
            return privacyItems.isNotEmpty() &&
                privacyItems.all { it.privacyType == PrivacyType.TYPE_LOCATION }
        }
    }

    private val callbacks = mutableListOf<WeakReference<Callback>>()

    var micCameraAvailable = isMicCameraEnabled() && isCameraIndicatorEnabled()
        private set

    var locationAvailable = locationIndicatorsEnabled() && isLocationIndicatorEnabled()
        private set

    var mediaProjectionAvailable = isMediaProjectionEnabled() && isProjectionIndicatorEnabled()
        private set

    private val devicePropertiesChangedListener =
        DeviceConfig.OnPropertiesChangedListener { properties ->
            if (DeviceConfig.NAMESPACE_PRIVACY == properties.namespace) {
                // Running on the ui executor so can iterate on callbacks
                if (properties.keyset.contains(MIC_CAMERA)) {
                    micCameraAvailable =
                        properties.getBoolean(MIC_CAMERA, DEFAULT_MIC_CAMERA) &&
                            isCameraIndicatorEnabled()
                    callbacks.forEach { it.get()?.onFlagMicCameraChanged(micCameraAvailable) }
                }

                if (locationAvailable) {
                    callbacks.forEach { it.get()?.onFlagLocationChanged(locationAvailable) }
                }

                if (properties.keyset.contains(MEDIA_PROJECTION)) {
                    mediaProjectionAvailable =
                        properties.getBoolean(MEDIA_PROJECTION, DEFAULT_MEDIA_PROJECTION) &&
                            isProjectionIndicatorEnabled()
                    callbacks.forEach {
                        it.get()?.onFlagMediaProjectionChanged(mediaProjectionAvailable)
                    }
                }
            }
        }

    // Custom Options: observes the crDroid per-user indicator toggles and recomputes
    // availability, notifying callbacks for whichever indicator flipped.
    private val indicatorSettingsObserver =
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                uiExecutor.execute { updateIndicatorToggles() }
            }
        }

    init {
        dumpManager.registerNormalDumpable(TAG, this)
        deviceConfigProxy.addOnPropertiesChangedListener(
            DeviceConfig.NAMESPACE_PRIVACY,
            uiExecutor,
            devicePropertiesChangedListener,
        )
        for (key in arrayOf(CAMERA_INDICATOR, LOCATION_INDICATOR, PROJECTION_INDICATOR)) {
            secureSettings.registerContentObserverForUserSync(
                key,
                indicatorSettingsObserver,
                UserHandle.USER_ALL,
            )
        }
    }

    private fun isMicCameraEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            MIC_CAMERA,
            DEFAULT_MIC_CAMERA,
        )
    }

    private fun isMediaProjectionEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            MEDIA_PROJECTION,
            DEFAULT_MEDIA_PROJECTION,
        )
    }

    private fun isCameraIndicatorEnabled(): Boolean =
        secureSettings.getInt(CAMERA_INDICATOR, DEFAULT_INDICATOR) != 0

    private fun isLocationIndicatorEnabled(): Boolean =
        secureSettings.getInt(LOCATION_INDICATOR, DEFAULT_INDICATOR) != 0

    private fun isProjectionIndicatorEnabled(): Boolean =
        secureSettings.getInt(PROJECTION_INDICATOR, DEFAULT_INDICATOR) != 0

    // Custom Options: recompute availability from DeviceConfig AND the user toggles, and notify
    // callbacks for whichever indicator changed. Runs on the ui executor so callbacks can iterate.
    private fun updateIndicatorToggles() {
        val newMicCamera = isMicCameraEnabled() && isCameraIndicatorEnabled()
        if (newMicCamera != micCameraAvailable) {
            micCameraAvailable = newMicCamera
            callbacks.forEach { it.get()?.onFlagMicCameraChanged(micCameraAvailable) }
        }
        val newLocation = locationIndicatorsEnabled() && isLocationIndicatorEnabled()
        if (newLocation != locationAvailable) {
            locationAvailable = newLocation
            callbacks.forEach { it.get()?.onFlagLocationChanged(locationAvailable) }
        }
        val newMediaProjection = isMediaProjectionEnabled() && isProjectionIndicatorEnabled()
        if (newMediaProjection != mediaProjectionAvailable) {
            mediaProjectionAvailable = newMediaProjection
            callbacks.forEach { it.get()?.onFlagMediaProjectionChanged(mediaProjectionAvailable) }
        }
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute { callbacks.add(callback) }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute {
            // Removes also if the callback is null
            callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("PrivacyConfig state:")
        ipw.withIncreasedIndent {
            ipw.println("micCameraAvailable: $micCameraAvailable")
            ipw.println("locationAvailable: $locationAvailable")
            ipw.println("mediaProjectionAvailable: $mediaProjectionAvailable")
            ipw.println("Callbacks:")
            ipw.withIncreasedIndent {
                callbacks.forEach { callback -> callback.get()?.let { ipw.println(it) } }
            }
        }
        ipw.flush()
    }

    @WeaklyReferencedCallback
    interface Callback {
        fun onFlagMicCameraChanged(flag: Boolean) {}

        fun onFlagLocationChanged(flag: Boolean) {}

        fun onFlagMediaProjectionChanged(flag: Boolean) {}
    }
}
