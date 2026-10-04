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

package com.android.systemui.statusbar.pipeline.battery.data.repository

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.statusbar.pipeline.battery.shared.model.BatteryCustomization
import com.android.systemui.statusbar.pipeline.dagger.BatteryTableLog
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import lineageos.providers.LineageSettings

/** Repository-style state for battery information. */
interface BatteryRepository {
    /**
     * True if the phone is plugged in. Note that this does not always mean the device is charging
     */
    val isPluggedIn: Flow<Boolean>

    /** Is power saver enabled */
    val isPowerSaveEnabled: Flow<Boolean>

    /** Is extreme power saver enabled */
    val isExtremePowerSaveEnabled: Flow<Boolean>

    /** Battery defender means the device is plugged in but not charging to protect the battery */
    val isBatteryDefenderEnabled: Flow<Boolean>

    /** True if the system has detected an incompatible charger (and thus is not charging) */
    val isIncompatibleCharging: Flow<Boolean>

    /** The current level [0-100] */
    val level: Flow<Int?>

    /** State unknown means that we can't detect a battery */
    val isStateUnknown: Flow<Boolean>

    /**
     * [Settings.System.SHOW_BATTERY_PERCENT]. A user setting to indicate whether we should show the
     * battery percentage in the home screen status bar
     */
    val isShowBatteryPercentSettingEnabled: StateFlow<Boolean>

    /**
     * crDroid: true when the battery percentage should be rendered as text *next to* the battery
     * icon rather than inside it, i.e. [LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT] == 2
     * ([BatteryRepositoryImpl.PERCENT_MODE_NEXT_TO]). When true, the inside-the-icon glyphs are
     * suppressed (see [isShowBatteryPercentSettingEnabled]) and the percent is drawn beside the icon.
     */
    val isShowBatteryPercentNextToIcon: Flow<Boolean>

    /**
     * crDroid `qs_show_battery_percent`: a QS-header-scoped override of the status bar percent
     * setting, with the same 0 = hidden / 1 = inside / 2 = next-to-icon values.
     * [BatteryRepositoryImpl.QS_PERCENT_MODE_DEFAULT] keeps the stock QS behaviour, where the
     * percent is always shown and only its position follows the status bar.
     */
    val isQsShowBatteryPercentSettingEnabled: Flow<Boolean>

    /** @see isShowBatteryPercentNextToIcon — QS-header-scoped variant. */
    val isQsShowBatteryPercentNextToIcon: Flow<Boolean>

    /**
     * crDroid [Settings.System.status_bar_battery_text_charging]: when true, the battery percentage
     * text is force-shown while the device is charging, even if the percent-text setting is off.
     * Defaults to true (matching the crDroidSettings toggle default).
     */
    val isBatteryTextChargingEnabled: Flow<Boolean>

    /**
     * crDroid battery style, backed by [LineageSettings.System.STATUS_BAR_BATTERY_STYLE].
     *
     * See [BatteryRepositoryImpl.BATTERY_STYLE_PORTRAIT] and friends for the recognized values.
     * IchthysOS renders every one of them (see UnifiedBattery / IchthysBatteryStyles).
     */
    val batteryStyle: Flow<Int>

    /**
     * IchthysOS: the remaining battery icon options (colors, size, indicators, QS overrides).
     * Defaulted so fakes don't need to implement it.
     */
    val customization: Flow<BatteryCustomization>
        get() = flowOf(BatteryCustomization())

    /**
     * If available, this flow yields a string that describes the approximate time remaining for the
     * current battery charge and usage information. While subscribed, the estimate is updated every
     * 2 minutes.
     */
    val batteryTimeRemainingEstimate: Flow<String?>
}

/**
 * Currently we just use the [BatteryController] as our source of truth, but we could (should?)
 * migrate away from that eventually.
 */
@SysUISingleton
class BatteryRepositoryImpl
@Inject
constructor(
    // Custom Options: made vals so the LineageSettings flow helper (a member fun) can use them.
    @Application private val context: Context,
    @Background scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val controller: BatteryController,
    settingsRepository: SystemSettingsRepository,
    @BatteryTableLog tableLog: TableLogBuffer,
) : BatteryRepository {
    private val batteryState: StateFlow<BatteryCallbackState> =
        // Never use conflatedCallbackFlow here because that could cause us to drop events.
        // See b/433239990.
        callbackFlow<(BatteryCallbackState) -> BatteryCallbackState> {
                val callback =
                    object : BatteryController.BatteryStateChangeCallback {
                        override fun onBatteryLevelChanged(
                            level: Int,
                            pluggedIn: Boolean,
                            charging: Boolean,
                        ) {
                            trySend { prev -> prev.copy(level = level, isPluggedIn = pluggedIn) }
                        }

                        override fun onPowerSaveChanged(isPowerSave: Boolean) {
                            trySend { prev -> prev.copy(isPowerSaveEnabled = isPowerSave) }
                        }

                        override fun onExtremeBatterySaverChanged(isExtreme: Boolean) {
                            trySend { prev -> prev.copy(isExtremePowerSaveEnabled = isExtreme) }
                        }

                        override fun onIsBatteryDefenderChanged(isBatteryDefender: Boolean) {
                            trySend { prev ->
                                prev.copy(isBatteryDefenderEnabled = isBatteryDefender)
                            }
                        }

                        override fun onIsIncompatibleChargingChanged(
                            isIncompatibleCharging: Boolean
                        ) {
                            trySend { prev ->
                                prev.copy(isIncompatibleCharging = isIncompatibleCharging)
                            }
                        }

                        override fun onBatteryUnknownStateChanged(isUnknown: Boolean) {
                            // If the state is unknown, then all other fields are invalid
                            trySend { prev ->
                                if (isUnknown) {
                                    // Forget everything before now
                                    BatteryCallbackState(isStateUnknown = true)
                                } else {
                                    prev.copy(isStateUnknown = false)
                                }
                            }
                        }
                    }

                controller.addCallback(callback)
                awaitClose { controller.removeCallback(callback) }
            }
            .scan(initial = BatteryCallbackState()) { state, eventF -> eventF(state) }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Lazily, BatteryCallbackState())

    override val isPluggedIn =
        batteryState
            .map { it.isPluggedIn }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_PLUGGED_IN,
                initialValue = batteryState.value.isPluggedIn,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), batteryState.value.isPluggedIn)

    override val isPowerSaveEnabled =
        batteryState
            .map { it.isPowerSaveEnabled }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_POWER_SAVE,
                initialValue = batteryState.value.isPowerSaveEnabled,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), batteryState.value.isPowerSaveEnabled)

    override val isExtremePowerSaveEnabled =
        batteryState
            .map { it.isExtremePowerSaveEnabled }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_EXTREME_POWER_SAVE,
                initialValue = batteryState.value.isExtremePowerSaveEnabled,
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                batteryState.value.isExtremePowerSaveEnabled,
            )

    override val isBatteryDefenderEnabled =
        batteryState
            .map { it.isBatteryDefenderEnabled }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_DEFEND,
                initialValue = batteryState.value.isBatteryDefenderEnabled,
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                batteryState.value.isBatteryDefenderEnabled,
            )

    override val isIncompatibleCharging =
        batteryState
            .map { it.isIncompatibleCharging }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_INCOMPATIBLE_CHARGING,
                initialValue = batteryState.value.isIncompatibleCharging,
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                batteryState.value.isIncompatibleCharging,
            )

    override val level =
        batteryState
            .map { it.level }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_LEVEL,
                initialValue = batteryState.value.level,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), batteryState.value.level)

    override val isStateUnknown =
        batteryState
            .map { it.isStateUnknown }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_UNKNOWN,
                initialValue = batteryState.value.isStateUnknown,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), batteryState.value.isStateUnknown)

    /**
     * Emits the current value of a [LineageSettings.System] integer key, re-reading on every
     * change. Emits [default] when the key is unset or unparseable.
     */
    private fun lineageSystemIntFlow(name: String, default: Int): Flow<Int> =
        callbackFlow {
                val resolver = context.contentResolver
                fun current(): Int =
                    LineageSettings.System.getIntForUser(
                        resolver,
                        name,
                        default,
                        UserHandle.USER_CURRENT,
                    )
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(current())
                        }
                    }
                resolver.registerContentObserver(
                    LineageSettings.System.getUriFor(name),
                    /* notifyForDescendants = */ false,
                    observer,
                )
                trySend(current())
                awaitClose { resolver.unregisterContentObserver(observer) }
            }
            .flowOn(bgDispatcher)

    /**
     * crDroid [LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT]:
     * 0 = hidden, 1 = inside the icon, 2 = next to the icon.
     *
     * [PERCENT_MODE_UNSET] means the key has never been written, in which case we defer to the AOSP
     * [Settings.System.SHOW_BATTERY_PERCENT] boolean.
     */
    private val lineageShowBatteryPercentMode: Flow<Int> =
        lineageSystemIntFlow(
            LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT,
            PERCENT_MODE_UNSET,
        )

    override val isShowBatteryPercentSettingEnabled = run {
        val default =
            context.resources.getBoolean(
                com.android.internal.R.bool.config_defaultBatteryPercentageSetting
            )
        combine(
                settingsRepository.boolSetting(
                    name = Settings.System.SHOW_BATTERY_PERCENT,
                    defaultValue = default,
                ),
                lineageShowBatteryPercentMode,
            ) { aospEnabled, crMode ->
                when (crMode) {
                    // Hidden
                    0 -> false
                    // 1 = inside icon.
                    1 -> true
                    // 2 = next to icon: the percent is drawn as text beside the icon
                    // (see [isShowBatteryPercentNextToIcon]), so the inside-the-icon glyphs are
                    // suppressed here. IchthysOS: 3 = next to icon, on its right.
                    PERCENT_MODE_NEXT_TO,
                    PERCENT_MODE_NEXT_TO_RIGHT -> false
                    // Unset (or unexpected) -> fall back to the AOSP boolean setting.
                    else -> aospEnabled
                }
            }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_SHOW_PERCENT_SETTING,
                initialValue = default,
            )
            .stateIn(scope, SharingStarted.Lazily, default)
    }

    override val isShowBatteryPercentNextToIcon: StateFlow<Boolean> =
        lineageShowBatteryPercentMode
            .map { it == PERCENT_MODE_NEXT_TO || it == PERCENT_MODE_NEXT_TO_RIGHT }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_SHOW_PERCENT_NEXT_TO,
                initialValue = false,
            )
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Lazily, false)

    /**
     * crDroid `qs_show_battery_percent`. Same 0/1/2 values as the status bar key, plus
     * [QS_PERCENT_MODE_DEFAULT] for "leave the QS header alone".
     */
    private val qsShowBatteryPercentMode: Flow<Int> =
        settingsRepository.intSetting(
            name = SETTING_QS_SHOW_BATTERY_PERCENT,
            defaultValue = QS_PERCENT_MODE_DEFAULT,
        )

    override val isQsShowBatteryPercentSettingEnabled: StateFlow<Boolean> =
        qsShowBatteryPercentMode
            .map { mode ->
                when (mode) {
                    // Hidden
                    0 -> false
                    // Inside the icon
                    1 -> true
                    // Next to the icon: drawn as separate text, so the inside glyphs are
                    // suppressed here exactly as they are for the status bar.
                    PERCENT_MODE_NEXT_TO,
                    PERCENT_MODE_NEXT_TO_RIGHT -> false
                    // Default: the QS header always shows the percent.
                    else -> true
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_QS_SHOW_PERCENT_SETTING,
                initialValue = true,
            )
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Lazily, true)

    override val isQsShowBatteryPercentNextToIcon: StateFlow<Boolean> =
        combine(qsShowBatteryPercentMode, isShowBatteryPercentNextToIcon) { mode, statusBarNextTo ->
                // In default mode the QS header follows wherever the status bar puts the percent.
                if (mode == QS_PERCENT_MODE_DEFAULT) statusBarNextTo
                else mode == PERCENT_MODE_NEXT_TO || mode == PERCENT_MODE_NEXT_TO_RIGHT
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_QS_SHOW_PERCENT_NEXT_TO,
                initialValue = false,
            )
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Lazily, false)

    override val isBatteryTextChargingEnabled: StateFlow<Boolean> =
        settingsRepository
            .boolSetting(name = SETTING_BATTERY_TEXT_CHARGING, defaultValue = true)
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_BATTERY_TEXT_CHARGING,
                initialValue = true,
            )
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Lazily, true)

    override val batteryStyle: StateFlow<Int> =
        lineageSystemIntFlow(
                LineageSettings.System.STATUS_BAR_BATTERY_STYLE,
                BATTERY_STYLE_PORTRAIT,
            )
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_BATTERY_STYLE,
                initialValue = BATTERY_STYLE_PORTRAIT,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), BATTERY_STYLE_PORTRAIT)

    /** IchthysOS: all battery icon options in one object, re-emitted when any key changes. */
    override val customization: StateFlow<BatteryCustomization> = run {
        val c = BatteryCustomization.Companion
        fun int(key: String, default: Int) = settingsRepository.intSetting(key, default)
        combine(
                listOf(
                    lineageShowBatteryPercentMode,
                    qsShowBatteryPercentMode,
                    int(c.KEY_QS_STYLE, c.QS_STYLE_FOLLOW),
                    int(c.KEY_PERCENT_CHARGING_ONLY, 0),
                    int(c.KEY_PERCENT_BOLD, 0),
                    int(c.KEY_CHARGING_INDICATOR, 1),
                    int(c.KEY_POWER_SAVE_INDICATOR, 1),
                    int(c.KEY_FILL_MODE, c.FILL_MODE_TINT),
                    int(c.KEY_FILL_COLOR, c.FILL_COLOR_UNSET),
                    int(c.KEY_STATE_COLORS, c.STATE_COLORS_THEME),
                    int(c.KEY_CHARGING_COLOR, c.DEFAULT_CHARGING_COLOR),
                    int(c.KEY_POWER_SAVE_COLOR, c.DEFAULT_POWER_SAVE_COLOR),
                    int(c.KEY_LOW_COLOR, c.DEFAULT_LOW_COLOR),
                    int(c.KEY_LOW_LEVEL, c.DEFAULT_LOW_LEVEL),
                    int(c.KEY_SCALE, c.DEFAULT_SCALE),
                    int(c.KEY_FRAME_THICKNESS, c.DEFAULT_FRAME_THICKNESS),
                )
            ) { v ->
                val sbMode = v[0]
                val qsMode = v[1]
                BatteryCustomization(
                    percentAfterIcon = sbMode == PERCENT_MODE_NEXT_TO_RIGHT,
                    // QS default mode follows the status bar position
                    qsPercentAfterIcon =
                        if (qsMode == QS_PERCENT_MODE_DEFAULT) {
                            sbMode == PERCENT_MODE_NEXT_TO_RIGHT
                        } else {
                            qsMode == PERCENT_MODE_NEXT_TO_RIGHT
                        },
                    qsStyle = v[2],
                    percentChargingOnly = v[3] != 0,
                    percentBold = v[4] != 0,
                    showChargingIndicator = v[5] != 0,
                    showPowerSaveIndicator = v[6] != 0,
                    fillMode = v[7],
                    fillColor = v[8],
                    stateColorMode = v[9],
                    chargingColor = v[10],
                    powerSaveColor = v[11],
                    lowColor = v[12],
                    lowLevel = v[13].coerceIn(c.MIN_LOW_LEVEL, c.MAX_LOW_LEVEL),
                    scalePercent = v[14].coerceIn(c.MIN_SCALE, c.MAX_SCALE),
                    frameThickness =
                        v[15].coerceIn(c.MIN_FRAME_THICKNESS, c.MAX_FRAME_THICKNESS),
                )
            }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), BatteryCustomization())
    }

    /** Get and re-fetch the estimate every 2 minutes while active */
    private val estimate: Flow<String?> = flow {
        while (true) {
            val estimate = fetchEstimate()
            emit(estimate)
            delay(2.minutes)
        }
    }

    override val batteryTimeRemainingEstimate: Flow<String?> =
        estimate
            .flowOn(bgDispatcher)
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLog,
                columnName = COL_TIME_REMAINING_EST,
                initialValue = null,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private suspend fun fetchEstimate() = suspendCancellableCoroutine { continuation ->
        val callback =
            BatteryController.EstimateFetchCompletion { estimate -> continuation.resume(estimate) }

        controller.getEstimatedTimeRemainingString(callback)
    }

    companion object {
        /** crDroid [LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT] sentinel for "unset". */
        const val PERCENT_MODE_UNSET = -1

        /**
         * crDroid [LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT] value for "next to icon":
         * render the percent as text beside the battery glyph rather than inside it.
         */
        const val PERCENT_MODE_NEXT_TO = 2

        /** IchthysOS: percent as text beside the icon, on its right (end) side. */
        const val PERCENT_MODE_NEXT_TO_RIGHT = 3

        /**
         * crDroid [Settings.System] key: force-show the battery percent text while charging.
         * No android.provider.Settings constant exists for it, so the raw key is used.
         */
        private const val SETTING_BATTERY_TEXT_CHARGING = "status_bar_battery_text_charging"

        /**
         * crDroid [Settings.System] key: QS-header-scoped battery percent mode. Raw key, as there
         * is no android.provider.Settings constant for it.
         */
        private const val SETTING_QS_SHOW_BATTERY_PERCENT = "qs_show_battery_percent"

        /**
         * [SETTING_QS_SHOW_BATTERY_PERCENT] value meaning "do not override the QS header": the
         * percent stays always-visible and only its position follows the status bar setting.
         */
        const val QS_PERCENT_MODE_DEFAULT = -1

        /** crDroid [LineageSettings.System.STATUS_BAR_BATTERY_STYLE] values. */
        const val BATTERY_STYLE_PORTRAIT = 0
        const val BATTERY_STYLE_CIRCLE = 1
        const val BATTERY_STYLE_TEXT = 2
        const val BATTERY_STYLE_DOTTED_CIRCLE = 3
        /** IchthysOS additions. Landscape is the A17 unified pill. */
        const val BATTERY_STYLE_LANDSCAPE = 4
        const val BATTERY_STYLE_FULL_CIRCLE = 5
        const val BATTERY_STYLE_HIDDEN = 6

        private const val COL_PLUGGED_IN = "pluggedIn"
        private const val COL_POWER_SAVE = "powerSave"
        private const val COL_EXTREME_POWER_SAVE = "extremePowerSave"
        private const val COL_DEFEND = "defend"
        private const val COL_INCOMPATIBLE_CHARGING = "incompatibleCharging"
        private const val COL_LEVEL = "level"
        private const val COL_UNKNOWN = "unknown"
        private const val COL_SHOW_PERCENT_SETTING = "showPercentSetting"
        private const val COL_SHOW_PERCENT_NEXT_TO = "showPercentNextToIcon"
        private const val COL_QS_SHOW_PERCENT_SETTING = "qsShowPercentSetting"
        private const val COL_QS_SHOW_PERCENT_NEXT_TO = "qsShowPercentNextToIcon"
        private const val COL_BATTERY_STYLE = "batteryStyle"
        private const val COL_BATTERY_TEXT_CHARGING = "batteryTextCharging"
        private const val COL_TIME_REMAINING_EST = "timeRemainingEstimate"
    }
}

/** Data object to track the current battery callback state */
private data class BatteryCallbackState(
    val level: Int? = null,
    val isPluggedIn: Boolean = false,
    val isPowerSaveEnabled: Boolean = false,
    val isExtremePowerSaveEnabled: Boolean = false,
    val isBatteryDefenderEnabled: Boolean = false,
    val isStateUnknown: Boolean = false,
    val isIncompatibleCharging: Boolean = false,
)
