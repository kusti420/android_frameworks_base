/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.charging

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock as OsSystemClock
import android.os.SystemProperties
import android.telecom.TelecomManager
import android.util.Log
import android.view.RoundedCorner
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.shade.ui.ShoalShade
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.surfaceeffects.view.ripple.RippleView
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

private const val MAX_DEBOUNCE_LEVEL = 3
private const val BASE_DEBOUNCE_TIME = 2000
private const val TAG = "WiredChargingRipple"

// IchthysOS: how long to wait for the screen when power is connected with it off (wake on plug
// turns it on a moment after the battery broadcast); the animation never wakes it itself.
private const val WAIT_FOR_SCREEN_MS = 500L
// IchthysOS: the stock ripple runs 1.75 s; the charging animations are kept to ~1.5 s.
private const val RIPPLE_DURATION_MS = 1500L
// IchthysOS: waking the screen to play: how long to wait for it, and for the panel to light.
private const val WAKE_TIMEOUT_MS = 1500L
private const val SCREEN_ON_SETTLE_MS = 250L
private const val PROXIMITY_TIMEOUT_MS = 300L
private const val PROXIMITY_NEAR_CM = 5f

/**
 * Controls the ripple effect that shows when wired charging begins. The ripple uses the accent
 * color of the current theme.
 *
 * IchthysOS: this is the charger-connected animation for wired and wireless charging. The style
 * is Settings.System [ChargingAnimationSettings.KEY] (Look > Lock screen): "ripple" plays the
 * stock ripple (wired) or the stock wireless animation, recoloured to the Shoal accent;
 * the other styles play a [ShoalChargingView] on the same kind of untouchable overlay window;
 * "off" plays nothing. With the screen off (or dozing) nothing plays, unless "Turn on the screen to
 * play" ([ChargingAnimationSettings.WAKE_KEY]) is on: then the screen is woken (not in a pocket,
 * not during a call) and the animation plays once it is on. Nothing plays with animations turned
 * off (animator duration scale 0).
 */
@SysUISingleton
class WiredChargingRippleController
@Inject
constructor(
    commandRegistry: CommandRegistry,
    private val batteryController: BatteryController,
    private val configurationController: ConfigurationController,
    featureFlags: FeatureFlags,
    private val context: Context,
    private val windowManager: WindowManager,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
) {
    private var pluggedIn: Boolean = false
    private val rippleEnabled: Boolean =
        featureFlags.isEnabled(Flags.CHARGING_RIPPLE) &&
            !SystemProperties.getBoolean("persist.debug.suppress-charging-ripple", false)
    private var normalizedPortPosX: Float =
        context.resources.getFloat(R.dimen.physical_charger_port_location_normalized_x)
    private var normalizedPortPosY: Float =
        context.resources.getFloat(R.dimen.physical_charger_port_location_normalized_y)
    private val windowLayoutParams =
        WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
            fitInsetsTypes = 0 // Ignore insets from all system bars
            title = "Wired Charging Animation"
            flags =
                (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            setTrustedOverlay()
        }
    private var lastTriggerTime: Long? = null
    private var debounceLevel = 0

    // IchthysOS: the Shoal styles' view and state.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)
    private var batteryLevel = -1
    private var shoalView: ShoalChargingView? = null
    private val shoalWindowLayoutParams =
        WindowManager.LayoutParams().apply {
            copyFrom(windowLayoutParams)
            title = "Charging Animation"
        }
    private var pendingPlay: Runnable? = null

    @VisibleForTesting
    var rippleView: RippleView = RippleView(context, attrs = null).also { it.setupShader() }

    init {
        pluggedIn = batteryController.isPluggedIn
        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
        updateRippleColor()
    }

    fun registerCallbacks() {
        val batteryStateChangeCallback =
            object : BatteryController.BatteryStateChangeCallback {
                override fun onBatteryLevelChanged(
                    level: Int,
                    nowPluggedIn: Boolean,
                    charging: Boolean,
                ) {
                    // Suppresses the ripple when the state change comes from wireless charging or
                    // its dock.
                    if (
                        batteryController.isPluggedInWireless ||
                            batteryController.isChargingSourceDock
                    ) {
                        return
                    }

                    batteryLevel = level
                    if (!pluggedIn && nowPluggedIn) {
                        startRippleWithDebounce()
                    }
                    pluggedIn = nowPluggedIn
                }
            }
        batteryController.addCallback(batteryStateChangeCallback)

        val configurationChangedListener =
            object : ConfigurationController.ConfigurationListener {
                override fun onUiModeChanged() {
                    updateRippleColor()
                }

                override fun onThemeChanged() {
                    updateRippleColor()
                }

                override fun onConfigChanged(newConfig: Configuration?) {
                    normalizedPortPosX =
                        context.resources.getFloat(
                            R.dimen.physical_charger_port_location_normalized_x
                        )
                    normalizedPortPosY =
                        context.resources.getFloat(
                            R.dimen.physical_charger_port_location_normalized_y
                        )
                }
            }
        configurationController.addCallback(configurationChangedListener)
    }

    // Lazily debounce ripple to avoid triggering ripple constantly (e.g. from flaky chargers).
    internal fun startRippleWithDebounce() {
        val now = systemClock.elapsedRealtime()
        // Debounce wait time = 2 ^ debounce level
        if (
            lastTriggerTime == null ||
                (now - lastTriggerTime!!) > BASE_DEBOUNCE_TIME * (2.0.pow(debounceLevel))
        ) {
            // Not waiting for debounce. Start ripple.
            // IchthysOS: the chosen charger-connected style instead of always the ripple.
            playChargingAnimation(wireless = false, level = batteryLevel)
            debounceLevel = 0
        } else {
            // Still waiting for debounce. Ignore ripple and bump debounce level.
            debounceLevel = min(MAX_DEBOUNCE_LEVEL, debounceLevel + 1)
        }
        lastTriggerTime = now
    }

    fun startRipple() {
        if (rippleView.rippleInProgress() || rippleView.parent != null) {
            // Skip if ripple is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        windowLayoutParams.packageName = context.opPackageName
        // IchthysOS: Shoal accent and a 1.5 s run.
        updateRippleColor()
        rippleView.duration = RIPPLE_DURATION_MS
        rippleView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(view: View) {}

                override fun onViewAttachedToWindow(view: View) {
                    layoutRipple()
                    rippleView.startRipple(Runnable { windowManager.removeView(rippleView) })
                    rippleView.removeOnAttachStateChangeListener(this)
                }
            }
        )
        windowManager.addView(rippleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun layoutRipple() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val maxDiameter = Integer.max(width, height) * 2f
        rippleView.setMaxSize(maxDiameter, maxDiameter)
        val p = portPosition(width, height)
        rippleView.setCenter(p.x, p.y)
    }

    /** IchthysOS: the charging port in window coordinates for the current rotation. */
    private fun portPosition(width: Int, height: Int): PointF =
        when (context.display?.rotation) {
            Surface.ROTATION_90 ->
                PointF(width * normalizedPortPosY, height * (1 - normalizedPortPosX))
            Surface.ROTATION_180 ->
                PointF(width * (1 - normalizedPortPosX), height * (1 - normalizedPortPosY))
            Surface.ROTATION_270 ->
                PointF(width * (1 - normalizedPortPosY), height * normalizedPortPosX)
            else -> PointF(width * normalizedPortPosX, height * normalizedPortPosY)
        }

    private fun updateRippleColor() {
        // IchthysOS: the Shoal theme accent (the picked seed, else the Monet accent), not the
        // Monet-derived colorAccent.
        rippleView.setColor(
            try {
                ShoalShade.accent(context)
            } catch (e: Exception) {
                Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor
            }
        )
    }

    // region IchthysOS charger-connected styles

    /**
     * Wireless charging started (system_server's Notifier via CommandQueue). Returns false when
     * the stock wireless animation should play (style "ripple"); true when this handled it, which
     * includes playing nothing ("off", screen off, animations off).
     */
    fun showWirelessChargingAnimation(level: Int): Boolean {
        val style = ChargingAnimationSettings.read(context)
        if (style == ChargingAnimationSettings.RIPPLE) return false
        if (level >= 0) batteryLevel = level
        playStyle(style, level, wireless = true)
        return true
    }

    /** Plays the chosen style for a charger that was just connected. */
    private fun playChargingAnimation(wireless: Boolean, level: Int) {
        playStyle(ChargingAnimationSettings.read(context), level, wireless)
    }

    private fun playStyle(style: String, levelIn: Int, wireless: Boolean) {
        if (style == ChargingAnimationSettings.OFF) return
        val level = if (levelIn >= 0) levelIn else currentLevel()
        if (SystemProperties.getBoolean("persist.debug.suppress-charging-ripple", false)) return
        // Reduced motion: animator duration scale 0 means no animations at all.
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pendingPlay?.let { mainHandler.removeCallbacks(it) }
        pendingPlay = null
        val play = Runnable {
            pendingPlay = null
            if (!isScreenOn()) return@Runnable
            if (style == ChargingAnimationSettings.RIPPLE) {
                // Only reached for wired: wireless "ripple" is the stock wireless animation.
                if (!wireless) startRipple()
            } else {
                startShoal(style, level)
            }
        }
        if (isScreenOn()) {
            play.run()
        } else if (ChargingAnimationSettings.readWake(context) && !isInCall()) {
            // IchthysOS: "Turn on the screen to play" - wake it unless the phone is in a pocket
            // (proximity covered), then play once the display is on.
            checkProximity { near ->
                if (near || isScreenOn()) {
                    if (!near) play.run()
                    return@checkProximity
                }
                try {
                    powerManager?.wakeUp(
                        OsSystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_PLUGGED_IN,
                        "ichthys:charging",
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "wake for the charging animation failed", e)
                    return@checkProximity
                }
                pendingPlay = play
                waitForScreen(play, OsSystemClock.uptimeMillis() + WAKE_TIMEOUT_MS)
            }
        } else {
            // The platform's own wake on plug (if on) turns the screen on a moment later.
            pendingPlay = play
            mainHandler.postDelayed(play, WAIT_FOR_SCREEN_MS)
        }
    }

    /** Runs [play] shortly after the screen is on (the panel needs a moment), or drops it. */
    private fun waitForScreen(play: Runnable, deadline: Long) {
        if (pendingPlay !== play) return
        if (isScreenOn()) {
            mainHandler.postDelayed(play, SCREEN_ON_SETTLE_MS)
        } else if (OsSystemClock.uptimeMillis() < deadline) {
            mainHandler.postDelayed({ waitForScreen(play, deadline) }, 50L)
        } else {
            pendingPlay = null
        }
    }

    private fun isInCall(): Boolean =
        try {
            context.getSystemService(TelecomManager::class.java)?.isInCall ?: false
        } catch (e: Exception) {
            // Without the permission, don't risk waking the screen against an ear.
            true
        }

    /**
     * One proximity reading: [done] gets true when the sensor is covered (pocket, face down).
     * No sensor, or no reading within [PROXIMITY_TIMEOUT_MS], counts as uncovered.
     */
    private fun checkProximity(done: (Boolean) -> Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sm == null || sensor == null) {
            done(false)
            return
        }
        var finished = false
        lateinit var listener: SensorEventListener
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            sm.unregisterListener(listener)
            done(false)
        }
        listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (finished) return
                    finished = true
                    sm.unregisterListener(this)
                    mainHandler.removeCallbacks(timeout)
                    val v = event.values.firstOrNull() ?: Float.MAX_VALUE
                    done(v < min(sensor.maximumRange, PROXIMITY_NEAR_CM))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
        if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)) {
            done(false)
            return
        }
        mainHandler.postDelayed(timeout, PROXIMITY_TIMEOUT_MS)
    }

    /**
     * The charger's advertised power, as One UI's charging levels: 0 normal, 1 fast (7.5 W+,
     * the platform's fast threshold), 2 super fast (20 W+), 3 super fast 2.0 (35 W+).
     */
    private fun chargeSpeed(): Int {
        val intent =
            try {
                context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } catch (e: Exception) {
                null
            } ?: return 0
        val ua = intent.getIntExtra(android.os.BatteryManager.EXTRA_MAX_CHARGING_CURRENT, -1)
        var uv = intent.getIntExtra(android.os.BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, -1)
        if (ua <= 0) return 0
        if (uv <= 0) uv = 5_000_000
        val microWatts = (ua / 1000L) * (uv / 1000L)
        return when {
            microWatts >= 35_000_000L -> 3
            microWatts >= 20_000_000L -> 2
            microWatts >= 7_500_000L -> 1
            else -> 0
        }
    }

    private fun currentLevel(): Int =
        try {
            context
                .getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            -1
        }

    // Interactive = the screen is on and awake (not off, not the ambient display).
    private fun isScreenOn(): Boolean = powerManager?.isInteractive ?: true

    private fun startShoal(style: String, level: Int) {
        val current = shoalView
        if (current != null && (current.isRunning() || current.parent != null)) return
        if (rippleView.rippleInProgress() || rippleView.parent != null) return
        val view = current ?: ShoalChargingView(context).also { shoalView = it }
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val p = portPosition(width, height)
        val insets = metrics.windowInsets
        val corners =
            intArrayOf(
                insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
                insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0,
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
            )
        try {
            val locked =
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
            view.configure(style, level, p.x, p.y, width, height, corners, chargeSpeed(), locked)
        } catch (e: Exception) {
            Log.w(TAG, "charging animation setup failed", e)
            return
        }
        shoalWindowLayoutParams.packageName = context.opPackageName
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(v: View) {}

                override fun onViewAttachedToWindow(v: View) {
                    view.removeOnAttachStateChangeListener(this)
                    view.start(Runnable { removeShoal(view) })
                }
            }
        )
        try {
            windowManager.addView(view, shoalWindowLayoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "charging animation window failed", e)
            return
        }
        // Belt and braces: the window never outlives the animation by much.
        mainHandler.postDelayed(
            { removeShoal(view) },
            view.durationMs.toLong() + 1000L,
        )
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun removeShoal(view: ShoalChargingView) {
        if (view.parent == null) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "charging animation removal failed", e)
        }
    }

    // endregion

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            // IchthysOS: optional style and level, e.g. "charging-ripple fill 63".
            val style = args.getOrNull(0)
            if (style == null) {
                startRipple()
                return
            }
            if (!ChargingAnimationSettings.isStyle(style)) {
                help(pw)
                return
            }
            val level =
                args.getOrNull(1)?.toIntOrNull()
                    ?: if (batteryLevel >= 0) batteryLevel else currentLevel()
            if (style == ChargingAnimationSettings.RIPPLE) startRipple()
            else if (style != ChargingAnimationSettings.OFF) startShoal(style, level)
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple [STYLE [LEVEL]]")
            pw.println(
                "  STYLE: " + ChargingAnimationSettings.STYLES.filter {
                    it != ChargingAnimationSettings.OFF
                }.joinToString(", ") + " (default: the stock ripple)"
            )
            pw.println("  LEVEL: battery level 0-100 (default: the current level)")
        }
    }

    enum class WiredChargingRippleEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Wired charging ripple effect played") CHARGING_RIPPLE_PLAYED(829);

        override fun getId() = _id
    }
}
