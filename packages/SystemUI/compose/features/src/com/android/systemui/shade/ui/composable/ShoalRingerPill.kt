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

package com.android.systemui.shade.ui.composable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R

/**
 * IchthysOS "Shoal": Mute | Vibrate | Ring as one connected pill on the date line of the shade
 * header, in the space between the date and the status icons. The selected mode sits on an accent
 * thumb that springs between the segments; it follows ringer changes made anywhere (volume keys,
 * Do Not Disturb, the volume panel) through the internal ringer mode broadcast.
 */
@Composable
fun ShoalRingerPill(
    accent: Color,
    onAccent: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var mode by remember { mutableIntStateOf(audio.ringerModeInternal) }

    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    mode = audio.ringerModeInternal
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        // Pick up a change made while the header was off screen.
        mode = audio.ringerModeInternal
        onDispose { context.unregisterReceiver(receiver) }
    }

    val index = Segments.indexOfFirst { it.mode == mode }.coerceAtLeast(0)
    val thumbOffset by
        animateDpAsState(
            targetValue = SegmentWidth * index,
            animationSpec =
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
            label = "ringerThumb",
        )

    Box(
        modifier =
            modifier
                .height(PillHeight)
                .width(SegmentWidth * Segments.size)
                .clip(CircleShape)
                .border(1.dp, mutedColor.copy(alpha = 0.35f), CircleShape)
    ) {
        // The thumb: drawn under the icons, slides to the selected segment.
        Box(
            Modifier.offset(x = thumbOffset)
                .width(SegmentWidth)
                .height(PillHeight)
                .clip(CircleShape)
                .background(accent)
        )
        Row(Modifier.selectableGroup()) {
            Segments.forEachIndexed { i, segment ->
                val selected = i == index
                val tint by
                    animateColorAsState(
                        targetValue = if (selected) onAccent else mutedColor,
                        label = "ringerTint",
                    )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.width(SegmentWidth)
                            .height(PillHeight)
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    if (mode != segment.mode) {
                                        view.performHapticFeedback(
                                            HapticFeedbackConstants.SEGMENT_TICK
                                        )
                                        audio.ringerModeInternal = segment.mode
                                        mode = segment.mode
                                    }
                                },
                            ),
                ) {
                    Icon(
                        painter = painterResource(segment.icon),
                        contentDescription = stringResource(segment.label),
                        tint = tint,
                        modifier = Modifier.size(IconSize),
                    )
                }
            }
        }
    }
}

private class Segment(val mode: Int, val icon: Int, val label: Int)

private val Segments =
    listOf(
        Segment(
            AudioManager.RINGER_MODE_SILENT,
            R.drawable.ic_volume_ringer_mute,
            R.string.volume_ringer_status_silent,
        ),
        Segment(
            AudioManager.RINGER_MODE_VIBRATE,
            R.drawable.ic_volume_ringer_vibrate,
            R.string.volume_ringer_status_vibrate,
        ),
        Segment(
            AudioManager.RINGER_MODE_NORMAL,
            R.drawable.ic_volume_ringer,
            R.string.volume_ringer_status_normal,
        ),
    )

private val SegmentWidth = 34.dp
private val PillHeight = 24.dp
private val IconSize = 15.dp
