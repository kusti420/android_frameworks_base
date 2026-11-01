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

package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.settingslib.ichthys.charging.ChargingStylePainter

/**
 * IchthysOS "Shoal" charger-connected animation: one short, untouchable, full-screen view on the
 * charging ripple's overlay window. The drawing is SettingsLib's [ChargingStylePainter] (shared
 * with the Settings picker previews); this view only runs its clock.
 */
class ShoalChargingView(context: Context) : View(context) {

    private val painter = ChargingStylePainter(context)
    private var t = 0f

    private val animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
        }

    /** How long the configured play runs, in ms. */
    val durationMs: Float
        get() = painter.durationMs

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun isRunning(): Boolean = animator.isRunning

    /** See [ChargingStylePainter.configure]; sizes are the window's, in px. */
    fun configure(
        style: String,
        level: Int,
        portX: Float,
        portY: Float,
        width: Int,
        height: Int,
        corners: IntArray,
        chargeSpeed: Int = 0,
        onLockScreen: Boolean = false,
    ) {
        painter.configure(style, level, portX, portY, width, height, corners, chargeSpeed,
            onLockScreen)
        t = 0f
    }

    fun start(onEnd: Runnable) {
        val d = painter.durationMs
        animator.setFloatValues(0f, d)
        animator.duration = d.toLong()
        animator.removeAllListeners()
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd.run()
                }
            }
        )
        animator.start()
    }

    fun cancel() {
        animator.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.removeAllListeners()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, t)
    }
}
