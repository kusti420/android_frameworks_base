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

package com.android.systemui.qs.ui.composable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.load
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.media.remedia.ui.compose.rememberLastNonNull
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaNavigationViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaPlayPauseActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSecondaryActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsFonts
import com.android.systemui.qs.rememberSecureSettingBoolean
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * IchthysOS "Shoal": the docked media player of quick settings.
 *
 * In the Shoal style the media carousel no longer sits at the end of the scrolling quick settings
 * content, where it had to be scrolled to. A compact strip sits between the scrolling part and the
 * footer actions instead, so the player is always in reach while something plays; tapping the strip
 * opens the system's full media carousel over the tiles (see [ShoalMediaDockHost]).
 *
 * The strip is only another presentation of the media pipeline: it reads a [MediaViewModel] like
 * every other media surface and shows the first active card of it.
 */
object ShoalMediaDock {
    object Elements {
        /** The strip. Scene transitions fade it together with the footer actions below it. */
        val Dock = ElementKey("ShoalMediaDock")
    }

    /**
     * Settings.Secure, per user: keep the strip's space while nothing plays, so the footer never
     * moves (quickSettings.mediaDock.reserveSpace). Unset means off.
     */
    const val SETTING_RESERVE_SPACE = "ichthys_qs_media_dock_reserve"

    /**
     * Settings.Secure, per user: a fourth button with the app's first custom action
     * (quickSettings.mediaDock.customAction). Unset means off.
     */
    const val SETTING_CUSTOM_ACTION = "ichthys_qs_media_dock_custom_action"

    // Kotlin constants on purpose: a new <dimen> in SystemUI shifts its resource table, which other
    // packages link against. The numbers are the concept's (Panel-Media, "Docked player").
    internal val Height = 60.dp
    /** Space between the scrolling content and the strip. The footer brings its own top padding. */
    internal val TopGap = 8.dp
    internal val Radius = 22.dp
    internal val ContentPadding = 8.dp
    internal val ContentSpacing = 10.dp
    internal val ArtSize = 44.dp
    internal val ArtRadius = 14.dp
    internal val SideButtonSize = 36.dp
    internal val PlayButtonSize = 40.dp
    internal val GlyphSize = 16.dp
    internal val NoteSize = 20.dp
    /** App-provided icons are drawn on the usual 24 dp grid with their own padding. */
    internal val IconSize = 18.dp
    internal val ProgressHeight = 2.dp
    internal val ProgressInset = 22.dp
    internal val Hairline = 1.dp
    internal val TitleSize = 14.sp
    internal val TitleLineHeight = 17.sp
    internal val SubtitleSize = 12.sp
    internal val SubtitleLineHeight = 15.sp

    internal const val DisabledAlpha = 0.4f
    /** How dark the tiles get behind the full player. */
    internal const val ScrimAlpha = 0.6f
    /** The media pipeline reports the position twice a second; the line glides between reports. */
    internal const val ProgressGlideMillis = 500

    /** A swipe dismisses once the strip is this far out, as a fraction of its width... */
    internal const val DismissDistanceFraction = 0.4f
    /** ...or when it is flung at least this fast (per second) in the direction it was dragged. */
    internal val DismissVelocity = 600.dp
    /** How much of the finger's travel a session that can't be dismissed follows. */
    internal const val LockedDragResistance = 0.2f
    internal const val SlideOutMillis = 180
    /** How long to wait for the pipeline to remove a dismissed session before springing back. */
    internal const val DismissTimeoutMillis = 1000L
}

/**
 * The box that holds the scrolling part of quick settings ([content]). With [isDocked] it also lays
 * out the docked media strip below that part, and presents the full media player on top of both
 * when the strip is tapped; without, it is the plain [Box] the grid style always had.
 *
 * The caller keeps the footer actions below the host. The full player is an overlay inside the
 * host's bounds, anchored to the bottom edge (it covers the strip), so opening it never moves the
 * footer.
 *
 * @param isDocked whether the player is docked: the Shoal style, see `rememberShoalQsEnabled`.
 * @param viewModelFactory the media view-model factory of the surrounding quick settings.
 * @param onCarouselDismissed passed to the full [Media] carousel as its `onDismissed`.
 * @param horizontalPadding inset of the strip and the full player from the host's sides.
 */
@Composable
fun ContentScope.ShoalMediaDockHost(
    isDocked: Boolean,
    viewModelFactory: MediaViewModel.Factory,
    onCarouselDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    if (isDocked) {
        DockedHost(viewModelFactory, onCarouselDismissed, modifier, horizontalPadding, content)
    } else {
        Box(modifier = modifier, content = content)
    }
}

@Composable
private fun ContentScope.DockedHost(
    viewModelFactory: MediaViewModel.Factory,
    onCarouselDismissed: () -> Unit,
    modifier: Modifier,
    horizontalPadding: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    // Created with the host rather than with the strip: quick settings is always composed, and a
    // view-model that only starts when the panel opens would report "on the lock screen" for its
    // first frames and let the strip pop in late. Nothing reads its cards while the panel is
    // hidden, so it costs no recompositions then.
    val viewModel: MediaViewModel =
        rememberViewModel("ShoalMediaDock.viewModel") {
            viewModelFactory.create(
                context = context,
                carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
            )
        }

    val isVisible = isAlwaysComposedContentVisible()
    var expanded by remember { mutableStateOf(false) }
    // Leaving quick settings, or starting to (a scene swipe, edit mode), closes the full player.
    val isTransitioning = layoutState.isTransitioning()
    LaunchedEffect(isVisible, isTransitioning) {
        if (!isVisible || isTransitioning) {
            expanded = false
        }
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)

            if (isVisible) {
                Element(
                    key = ShoalMediaDock.Elements.Dock,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                ) {
                    DockSlot(
                        viewModel = viewModel,
                        isExpanded = expanded,
                        onExpandedChange = { expanded = it },
                    )
                }
            }
        }

        if (isVisible) {
            FullPlayer(
                isExpanded = expanded,
                onClose = { expanded = false },
                viewModelFactory = viewModelFactory,
                onCarouselDismissed = onCarouselDismissed,
                horizontalPadding = horizontalPadding,
            )
        }
    }
}

/**
 * The place of the strip: empty (or, with [ShoalMediaDock.SETTING_RESERVE_SPACE], an empty 60 dp)
 * while no session is active, the strip of the first active session otherwise.
 */
@Composable
private fun DockSlot(
    viewModel: MediaViewModel,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reserveSpace =
        rememberSecureSettingBoolean(ShoalMediaDock.SETTING_RESERVE_SPACE, default = false)
    val showCustomAction =
        rememberSecureSettingBoolean(ShoalMediaDock.SETTING_CUSTOM_ACTION, default = false)

    // isCarouselVisible is the pipeline's own rule for this kind of surface: some session is
    // active, and media may be shown where the device currently is (lock screen setting). The cards
    // come in the pipeline's ranking, so the first active one is the session it ranks first.
    val card: MediaCardViewModel? =
        if (viewModel.isCarouselVisible) viewModel.cards.firstOrNull { it.isActive } else null
    // Keeps the strip's content while it animates away.
    val shownCard = rememberLastNonNull(card)
    val hasCard = card != null
    LaunchedEffect(hasCard) {
        if (!hasCard) {
            onExpandedChange(false)
        }
    }

    // The full player covers the strip; fade the strip so its corners don't show behind the
    // player's rounder ones.
    val stripAlpha by
        animateFloatAsState(
            targetValue = if (isExpanded) 0f else 1f,
            label = "ShoalMediaDock.stripAlpha",
        )

    Box(
        modifier.fillMaxWidth().thenIf(reserveSpace) {
            Modifier.padding(top = ShoalMediaDock.TopGap).height(ShoalMediaDock.Height)
        }
    ) {
        AnimatedVisibility(
            visible = hasCard,
            // With the space reserved nothing around the strip moves, so it only fades.
            enter = if (reserveSpace) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reserveSpace) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            if (shownCard != null) {
                DockStrip(
                    card = shownCard,
                    showCustomAction = showCustomAction,
                    stripAlpha = { stripAlpha },
                    onClick = { onExpandedChange(true) },
                    modifier =
                        Modifier.thenIf(!reserveSpace) {
                                Modifier.padding(top = ShoalMediaDock.TopGap)
                            }
                            .height(ShoalMediaDock.Height),
                )
            }
        }
    }
}

/** The 60 dp strip: cover art, title and artist, the action buttons, and a progress line. */
@Composable
private fun DockStrip(
    card: MediaCardViewModel,
    showCustomAction: Boolean,
    stripAlpha: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberDockColors()
    val fonts = rememberShoalQsFonts()
    val shape = remember { RoundedCornerShape(ShoalMediaDock.Radius) }

    // The navigation model is built on every read; read it once per composition.
    val navigation = card.navigation
    val progress = (navigation as? MediaNavigationViewModel.Showing)?.progress
    val animatedProgress by
        animateFloatAsState(
            targetValue = progress?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f,
            animationSpec =
                tween(durationMillis = ShoalMediaDock.ProgressGlideMillis, easing = LinearEasing),
            label = "ShoalMediaDock.progress",
        )

    // Swipe sideways to dismiss. A session the pipeline doesn't let go of (typically one that is
    // playing: its notification is ongoing) follows the finger a little and springs back.
    val scope = rememberCoroutineScope()
    val offsetX = remember(card.key) { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val currentCard by rememberUpdatedState(card)
    val dismissVelocityPx = with(LocalDensity.current) { ShoalMediaDock.DismissVelocity.toPx() }
    val draggableState = rememberDraggableState { delta ->
        val follow =
            if (currentCard.onSwipeDismiss != null) 1f else ShoalMediaDock.LockedDragResistance
        scope.launch { offsetX.snapTo(offsetX.value + delta * follow) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShoalMediaDock.ContentSpacing),
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val offset = offsetX.value
                        val farEnough =
                            abs(offset) > widthPx * ShoalMediaDock.DismissDistanceFraction ||
                                (abs(velocity) > dismissVelocityPx && velocity * offset > 0f)
                        if (farEnough && currentCard.onSwipeDismiss?.invoke() == true) {
                            offsetX.animateTo(
                                targetValue = sign(offset) * widthPx,
                                animationSpec = tween(ShoalMediaDock.SlideOutMillis),
                            )
                            // The pipeline removes the session a moment later, which takes the
                            // strip away or puts the next session in it. Should it not, come back
                            // rather than stay off screen.
                            delay(ShoalMediaDock.DismissTimeoutMillis)
                        }
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        )
                    },
                )
                .graphicsLayer {
                    translationX = offsetX.value
                    val out = if (widthPx > 0) abs(offsetX.value) / widthPx else 0f
                    alpha = stripAlpha() * (1f - out).coerceIn(0f, 1f)
                }
                .clip(shape)
                .background(colors.surface)
                .border(ShoalMediaDock.Hairline, colors.hairline, shape)
                .clickable(
                    onClickLabel = stringResource(R.string.ichthys_qs_media_dock_open),
                    onClick = onClick,
                )
                .drawWithContent {
                    drawContent()
                    if (progress != null) {
                        // Along the bottom edge, just inside the hairline.
                        val inset = ShoalMediaDock.ProgressInset.toPx()
                        val height = ShoalMediaDock.ProgressHeight.toPx()
                        val top = size.height - ShoalMediaDock.Hairline.toPx() - height
                        val width = (size.width - 2 * inset).coerceAtLeast(0f)
                        drawRect(colors.hairline, Offset(inset, top), Size(width, height))
                        drawRect(
                            colors.accent,
                            Offset(inset, top),
                            Size(width * animatedProgress, height),
                        )
                    }
                }
                .padding(horizontal = ShoalMediaDock.ContentPadding),
    ) {
        DockArt(image = card.background, colors = colors)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            BasicText(
                text = card.title,
                style =
                    TextStyle(
                        color = colors.text,
                        fontSize = ShoalMediaDock.TitleSize,
                        lineHeight = ShoalMediaDock.TitleLineHeight,
                        lineHeightStyle = TightLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fonts.text,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.subtitle.isNotEmpty()) {
                BasicText(
                    text = card.subtitle,
                    style =
                        TextStyle(
                            color = colors.muted,
                            fontSize = ShoalMediaDock.SubtitleSize,
                            lineHeight = ShoalMediaDock.SubtitleLineHeight,
                            lineHeightStyle = TightLineHeight,
                            fontFamily = fonts.text,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Transport controls keep their order in right-to-left locales, as on the media card.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ShoalMediaDock.ContentSpacing),
            ) {
                when (val layout = card.actionButtonLayout) {
                    is MediaCardActionButtonLayout.WithPlayPause -> {
                        // The slots as the pipeline resolved them: previous (or the app's custom
                        // action, or an empty reserved slot), play/pause, next (likewise).
                        DockActionButton(navigation.left, DockGlyph.Previous, colors)
                        card.playPauseAction?.let { DockPlayPauseButton(it, colors) }
                        DockActionButton(navigation.right, DockGlyph.Next, colors)
                        if (showCustomAction) {
                            // In this layout the additional actions are custom0 and custom1.
                            card.additionalActions.firstOrNull()?.let {
                                DockActionButton(it, standardGlyph = null, colors)
                            }
                        }
                    }
                    is MediaCardActionButtonLayout.SecondaryActionsOnly -> {
                        // A session without playback-state actions only has its notification's
                        // buttons. Show the ones the app picked for compact layouts, as the
                        // two-row media card does.
                        layout.indicesForCompressed.take(MaxNotificationActions).forEach { index ->
                            card.additionalActions.getOrNull(index)?.let {
                                DockActionButton(it, standardGlyph = null, colors)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The 44 dp cover art, or a note on the accent tint while the session has none. */
@Composable
private fun DockArt(image: Icon?, colors: DockColors, modifier: Modifier = Modifier) {
    val bitmap = remember(image) { (image as? Icon.Loaded)?.asImageBitmap() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(ShoalMediaDock.ArtSize)
                .clip(RoundedCornerShape(ShoalMediaDock.ArtRadius))
                .background(colors.artGround),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Glyph(DockGlyph.Note, colors.accent, Modifier.size(ShoalMediaDock.NoteSize))
        }
    }
}

/**
 * A 36 dp hairline circle for a secondary slot.
 *
 * [standardGlyph] is only drawn when the pipeline put the system's own skip button into the slot;
 * an app's custom action always keeps the app's icon.
 */
@Composable
private fun DockActionButton(
    action: MediaSecondaryActionViewModel,
    standardGlyph: DockGlyph?,
    colors: DockColors,
) {
    when (action) {
        is MediaSecondaryActionViewModel.Action -> {
            val description = action.icon.contentDescription?.load()
            val onClick = action.onClick
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(ShoalMediaDock.SideButtonSize)
                        .graphicsLayer {
                            alpha = if (onClick != null) 1f else ShoalMediaDock.DisabledAlpha
                        }
                        .clip(CircleShape)
                        .border(ShoalMediaDock.Hairline, colors.hairline, CircleShape)
                        .clickable(
                            enabled = onClick != null,
                            role = Role.Button,
                            onClick = { onClick?.invoke() },
                        )
                        .semantics {
                            if (description != null) {
                                contentDescription = description
                            }
                        },
            ) {
                if (action.isStandardSkip && standardGlyph != null) {
                    Glyph(standardGlyph, colors.text, Modifier.size(ShoalMediaDock.GlyphSize))
                } else {
                    DockIcon(action.icon, colors.text, Modifier.size(ShoalMediaDock.IconSize))
                }
            }
        }
        // The app asked for the slot to stay empty, so the buttons around it don't move.
        is MediaSecondaryActionViewModel.ReserveSpace ->
            Spacer(Modifier.size(ShoalMediaDock.SideButtonSize))
        is MediaSecondaryActionViewModel.None -> Unit
    }
}

/** The 40 dp accent disc. Its glyph follows the session state the pipeline reports. */
@Composable
private fun DockPlayPauseButton(action: MediaPlayPauseActionViewModel, colors: DockColors) {
    val description = action.icon?.contentDescription?.load()
    val onClick = action.onClick
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(ShoalMediaDock.PlayButtonSize)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(
                    enabled = onClick != null,
                    role = Role.Button,
                    onClick = { onClick?.invoke() },
                )
                .semantics {
                    if (description != null) {
                        contentDescription = description
                    }
                },
    ) {
        val glyphModifier = Modifier.size(ShoalMediaDock.GlyphSize)
        when (action.state) {
            is MediaSessionState.Playing -> Glyph(DockGlyph.Pause, colors.onAccent, glyphModifier)
            is MediaSessionState.Paused -> Glyph(DockGlyph.Play, colors.onAccent, glyphModifier)
            is MediaSessionState.Buffering ->
                CircularProgressIndicator(
                    color = colors.onAccent,
                    strokeWidth = 2.dp,
                    modifier = glyphModifier,
                )
        }
    }
}

/** An icon of the pipeline (the app's own for custom actions), tinted like the Shoal glyphs. */
@Composable
private fun DockIcon(icon: Icon, tint: Color, modifier: Modifier = Modifier) {
    val painter =
        when (icon) {
            is Icon.Loaded -> rememberDrawablePainter(icon.drawable)
            is Icon.Resource -> painterResource(icon.resId)
        }
    // The button around it carries the content description.
    Icon(painter = painter, contentDescription = null, tint = tint, modifier = modifier)
}

/** The Shoal line glyphs of the strip, on a 24 unit grid. */
private enum class DockGlyph {
    Previous,
    Next,
    Play,
    Pause,
    Note,
}

@Composable
private fun Glyph(glyph: DockGlyph, color: Color, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier) {
        val u = size.minDimension / 24f
        fun line(width: Float) = Stroke(width * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        path.reset()
        when (glyph) {
            DockGlyph.Previous -> {
                path.moveTo(18f * u, 6f * u)
                path.lineTo(9f * u, 12f * u)
                path.lineTo(18f * u, 18f * u)
                path.close()
                path.moveTo(6f * u, 6f * u)
                path.lineTo(6f * u, 18f * u)
                drawPath(path, color, style = line(1.9f))
            }
            DockGlyph.Next -> {
                path.moveTo(6f * u, 6f * u)
                path.lineTo(15f * u, 12f * u)
                path.lineTo(6f * u, 18f * u)
                path.close()
                path.moveTo(18f * u, 6f * u)
                path.lineTo(18f * u, 18f * u)
                drawPath(path, color, style = line(1.9f))
            }
            DockGlyph.Play -> {
                path.moveTo(8.5f * u, 6f * u)
                path.lineTo(18f * u, 12f * u)
                path.lineTo(8.5f * u, 18f * u)
                path.close()
                // Filled, with the outline on top for the rounded corners: as heavy as the pause
                // bars it alternates with.
                drawPath(path, color)
                drawPath(path, color, style = line(2.2f))
            }
            DockGlyph.Pause -> {
                path.moveTo(9f * u, 6f * u)
                path.lineTo(9f * u, 18f * u)
                path.moveTo(15f * u, 6f * u)
                path.lineTo(15f * u, 18f * u)
                drawPath(path, color, style = line(2.2f))
            }
            DockGlyph.Note -> {
                path.moveTo(9f * u, 18f * u)
                path.lineTo(9f * u, 6f * u)
                path.lineTo(19f * u, 4f * u)
                path.lineTo(19f * u, 16f * u)
                drawPath(path, color, style = line(1.8f))
                drawCircle(color, 2f * u, Offset(7f * u, 18f * u), style = line(1.8f))
                drawCircle(color, 2f * u, Offset(17f * u, 16f * u), style = line(1.8f))
            }
        }
    }
}

/**
 * The full player: the system's media carousel (seek bar, output switcher, every action slot, all
 * sessions), shown over the tiles and the strip. Tapping the dimmed tiles or going back closes it.
 */
@Composable
private fun BoxScope.FullPlayer(
    isExpanded: Boolean,
    onClose: () -> Unit,
    viewModelFactory: MediaViewModel.Factory,
    onCarouselDismissed: () -> Unit,
    horizontalPadding: Dp,
) {
    val scrim = colorResource(com.android.internal.R.color.ichthys_ink)
    val closeLabel = stringResource(R.string.ichthys_qs_media_dock_close)
    AnimatedVisibility(
        visible = isExpanded,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // Covers the scrolling part and the strip, so nothing under the player reacts while it is
        // open; scene swipes still pass, they are detected further up.
        Box(
            Modifier.fillMaxSize()
                .background(scrim.copy(alpha = ShoalMediaDock.ScrimAlpha))
                .clickable(interactionSource = null, indication = null, onClick = onClose)
                .semantics { contentDescription = closeLabel }
        )
    }

    AnimatedVisibility(
        visible = isExpanded,
        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = horizontalPadding),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        // The same carousel, with the same behaviour, that the grid style keeps inside the
        // scrolling content.
        Media(
            viewModelFactory = viewModelFactory,
            presentationStyle = MediaPresentationStyle.Default,
            behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
            onDismissed = onCarouselDismissed,
            location = Media.Location.QS,
        )
    }

    // Composed only while open, so it is registered after (and therefore wins over) the scene's own
    // back handling, which would leave quick settings.
    if (isExpanded) {
        BackHandler(onBack = onClose)
    }
}

/** The Shoal tokens the strip is drawn with. */
private class DockColors(
    /** The strip's recessed surface. */
    val surface: Color,
    /** Its outline, the side buttons' outlines and the progress track. */
    val hairline: Color,
    /** Title and side button glyphs. */
    val text: Color,
    /** Artist. */
    val muted: Color,
    /** Play/pause disc, progress, placeholder note. */
    val accent: Color,
    /** Glyph on the play/pause disc. */
    val onAccent: Color,
    /** Behind the cover art. */
    val artGround: Color,
)

/**
 * The framework Shoal tokens (com.android.internal.R.color.ichthys_*), as the brightness block
 * above the tiles uses them, so the strip follows the theme and nothing here is a literal colour.
 */
@Composable
private fun rememberDockColors(): DockColors {
    val surface = colorResource(com.android.internal.R.color.ichthys_card)
    val hairline = colorResource(com.android.internal.R.color.ichthys_hairline)
    val text = colorResource(com.android.internal.R.color.ichthys_text)
    val muted = colorResource(com.android.internal.R.color.ichthys_muted)
    val accent = colorResource(com.android.internal.R.color.ichthys_accent)
    val onAccent = colorResource(com.android.internal.R.color.ichthys_on_accent)
    val artGround = colorResource(com.android.internal.R.color.ichthys_accent_fill)
    return remember(surface, hairline, text, muted, accent, onAccent, artGround) {
        DockColors(
            surface = surface,
            hairline = hairline,
            text = text,
            muted = muted,
            accent = accent,
            onAccent = onAccent,
            artGround = artGround,
        )
    }
}

private val TightLineHeight =
    LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)

/** As many notification buttons as a compact media notification may name. */
private const val MaxNotificationActions = 3
