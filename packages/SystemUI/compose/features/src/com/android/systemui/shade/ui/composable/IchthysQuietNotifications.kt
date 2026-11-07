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

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementKey
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.ichthys.quiet.IchthysQuietNotificationsInteractor
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietFoldOngoing
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietNotificationModel
import com.android.systemui.statusbar.notification.ichthys.quiet.QuietSilentStyle

/*
 * IchthysOS: quiet notifications in the shade. The ongoing system notifications that
 * IchthysQuietNotificationsCoordinator folds out of the stack show here, either as a strip of
 * small chips directly above the first notification (notifications.quiet.foldOngoing = chips) or,
 * together with a folded Silent section, as dashed rows at the end of the shade (oneRow /
 * silentStyle = folded). Sizes are code constants: SystemUI gets no new <dimen> in this ROM.
 */

/** Chip and fold row height. */
/** Scene elements, so shade transitions can fade the quiet strip and rows with the stack. */
object IchthysQuiet {
    object Elements {
        val ChipStrip = ElementKey("IchthysQuietChipStrip")
        val FoldRows = ElementKey("IchthysQuietFoldRows")
    }
}

private val QuietRowHeight = 32.dp
private val QuietChipGap = 8.dp
private val QuietChipIconSize = 16.dp
private val QuietChipPaddingStart = 10.dp
private val QuietChipPaddingEnd = 14.dp
private val QuietChipMaxTextWidth = 160.dp
private val QuietHairline = 1.dp
private val QuietDash = 4.dp
private const val QuietMutedAlpha = 0.7f
private const val QuietHairlineAlpha = 0.35f

/**
 * The strip of chips (icon + short text) above the first notification. Hidden while nothing is
 * folded or the style is not "chips". Tap opens the notification's contentIntent; long-press
 * hides that one for good (Guard > Notifications lists the hidden ones).
 */
@Composable
fun IchthysQuietChipStrip(
    interactor: IchthysQuietNotificationsInteractor,
    modifier: Modifier = Modifier,
) {
    val fold by interactor.foldOngoing.collectAsStateWithLifecycle()
    val chips by interactor.foldedOngoing.collectAsStateWithLifecycle()
    if (fold != QuietFoldOngoing.CHIPS || chips.isEmpty()) return
    val textColor = MaterialTheme.colorScheme.onSurface
    val muted = textColor.copy(alpha = QuietMutedAlpha)
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(QuietChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in chips) {
            key(chip.key) {
                QuietChip(
                    chip = chip,
                    textColor = textColor,
                    muted = muted,
                    onClick = { interactor.onChipClicked(chip) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        interactor.hide(chip)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuietChip(
    chip: QuietNotificationModel,
    textColor: Color,
    muted: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val drawable: Drawable? =
        remember(chip.icon) {
            try {
                chip.icon?.loadDrawable(context)
            } catch (e: Exception) {
                null
            }
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.height(QuietRowHeight)
                .clip(CircleShape)
                .border(QuietHairline, muted.copy(alpha = QuietHairlineAlpha), CircleShape)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.ichthys_quiet_chip_open),
                    onLongClickLabel = stringResource(R.string.ichthys_quiet_chip_hide),
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(start = QuietChipPaddingStart, end = QuietChipPaddingEnd),
    ) {
        if (drawable != null) {
            Image(
                painter = rememberDrawablePainter(drawable),
                contentDescription = null,
                colorFilter = ColorFilter.tint(textColor),
                modifier = Modifier.size(QuietChipIconSize),
            )
            Spacer(Modifier.width(QuietChipGap))
        }
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = QuietChipMaxTextWidth),
        )
    }
}

/**
 * The dashed fold rows at the end of the shade: "System · USB debugging, Charging, 2 in
 * Backstage" (foldOngoing = oneRow) and "Silent · WhatsApp, Gmail" (silentStyle = folded). A tap
 * unfolds: the notifications return to the stack and the row reads "shown in the list. Tap to
 * fold"; the shade closing folds them again.
 */
@Composable
fun IchthysQuietFoldRows(
    interactor: IchthysQuietNotificationsInteractor,
    modifier: Modifier = Modifier,
) {
    val fold by interactor.foldOngoing.collectAsStateWithLifecycle()
    val silentStyle by interactor.silentStyle.collectAsStateWithLifecycle()
    val ongoing by interactor.foldedOngoing.collectAsStateWithLifecycle()
    val silent by interactor.foldedSilent.collectAsStateWithLifecycle()
    val ongoingUnfolded by interactor.ongoingUnfolded.collectAsStateWithLifecycle()
    val silentUnfolded by interactor.silentUnfolded.collectAsStateWithLifecycle()
    val showOngoing = fold == QuietFoldOngoing.ONE_ROW && (ongoing.isNotEmpty() || ongoingUnfolded)
    val showSilent =
        silentStyle == QuietSilentStyle.FOLDED && (silent.isNotEmpty() || silentUnfolded)
    if (!showOngoing && !showSilent) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(QuietChipGap)) {
        if (showOngoing) {
            QuietFoldRow(
                label = stringResource(R.string.ichthys_quiet_system),
                summary =
                    if (ongoingUnfolded) stringResource(R.string.ichthys_quiet_unfolded)
                    else interactor.summary(ongoing),
                unfolded = ongoingUnfolded,
                onClick = interactor::toggleOngoingUnfolded,
            )
        }
        if (showSilent) {
            QuietFoldRow(
                label = stringResource(R.string.ichthys_quiet_silent),
                summary =
                    if (silentUnfolded) stringResource(R.string.ichthys_quiet_unfolded)
                    else interactor.summary(silent),
                unfolded = silentUnfolded,
                onClick = interactor::toggleSilentUnfolded,
            )
        }
    }
}

@Composable
private fun QuietFoldRow(label: String, summary: String, unfolded: Boolean, onClick: () -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val muted = textColor.copy(alpha = QuietMutedAlpha)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .height(QuietRowHeight)
                .clip(CircleShape)
                .drawBehind {
                    val dash = QuietDash.toPx()
                    drawRoundRect(
                        color = muted.copy(alpha = QuietHairlineAlpha + 0.15f),
                        cornerRadius = CornerRadius(size.height / 2f),
                        style =
                            Stroke(
                                width = QuietHairline.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                            ),
                    )
                }
                .clickable(onClick = onClick)
                .padding(start = QuietChipPaddingEnd, end = QuietChipPaddingEnd),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
            maxLines = 1,
        )
        Text(
            text = " · $summary",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = QuietChipGap).weight(1f, fill = true),
        )
        Text(
            text = if (unfolded) "▴" else "▾",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
        )
    }
}
