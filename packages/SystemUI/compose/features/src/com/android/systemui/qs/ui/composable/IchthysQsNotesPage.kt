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

import android.icu.text.MessageFormat
import android.text.format.DateUtils
import java.text.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.notes.IchthysNote
import com.android.systemui.qs.notes.IchthysNoteKind
import com.android.systemui.qs.notes.IchthysQsNotesViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ShoalQsColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalAccent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsEnabled
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsFonts
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/**
 * IchthysOS: the notes page left of the quick settings tiles (owner design "N1/N2").
 *
 * Expanded quick settings always opens on the tiles; a swipe right shows the notes. The host is a
 * two-page [HorizontalPager] around the scrolling tile content only, so the header above and the
 * media dock and footer below stay put. The page indicator is a pin glyph for the notes page plus
 * one dot per tile page (the Shoal panel has one; the paginated grid reports its pages through
 * [LocalIchthysTilePager]).
 *
 * While the keyguard is showing every card is a frosted placeholder: its colour dot and grey bars,
 * never its text (no blur either: the text is simply not composed). The header says how many notes
 * there are and the only button is "Unlock to open".
 */
object IchthysQsNotes {
    const val PAGE_NOTES = 0
    const val PAGE_TILES = 1

    object Elements {
        /**
         * IchthysOS: the notes page. A scene element of the Quick Settings scene, so the scene
         * transitions that collapse quick settings can fade and move it like the tile content
         * (QuickSettings.Elements.QuickSettingsContent, which only lives on the tiles page).
         * Without a key the page was plain scene content with no transformation: it stayed fully
         * drawn under the collapsing tile row and the notification panel for the whole swipe.
         */
        val Page = ElementKey("IchthysQsNotesPage")
    }

    // Layout constants: SystemUI must not get new <dimen>s (resource-ID skew crash-loops Shell).
    val INDICATOR_HEIGHT = 24.dp
    val INDICATOR_PIN = 14.dp
    val INDICATOR_DOT = 6.dp
    val INDICATOR_PILL = 18.dp
    val INDICATOR_SPACE = 6.dp

    val CARD_RADIUS = 20.dp
    val CARD_PADDING = 14.dp
    val CARD_GAP = 10.dp
    val COLOR_DOT = 8.dp
    val CHECK_BOX = 11.dp
    val BAR_HEIGHT = 8.dp
    val BUTTON_HEIGHT = 40.dp
    val BUTTON_ICON = 18.dp
    val HEADER_ICON = 18.dp
    val COUNT_HEIGHT = 22.dp
    val EMPTY_BADGE = 48.dp
    val TITLE_SIZE = 20.sp
    val LABEL_SIZE = 11.sp
    val TEXT_SIZE = 14.sp
    val TEXT_LINE_HEIGHT = 19.sp
    const val WIDE_MAX_LINES = 6
    const val MAX_LINES = 5

    /** Colour dots a note can carry (index = [IchthysNote.color]); 0 is the theme accent. */
    val Colors = listOf(Color.Unspecified, Color(0xFF2DE2E6), Color(0xFFC6FF3D), Color(0xFFD27BFF), Color(0xFFF2F4F1))
}

/**
 * IchthysOS: how far the notes pager is on the notes page, for elements outside the pager that
 * belong to the tile pages only (the Shoal header's ringer pill). Provided around the header by
 * QuickSettingsScene through [LocalIchthysNotesPagerProgress]; [IchthysQsNotesPagerHost] attaches its
 * pager. Read [notesFraction] in a draw or layer block, so following the swipe only redraws.
 */
@Stable
class IchthysNotesPagerProgress {
    internal var pagerState by mutableStateOf<PagerState?>(null)

    /** 0 on the tiles, 1 on the notes page, in between while swiping. */
    fun notesFraction(): Float {
        val state = pagerState ?: return 0f
        val position = state.currentPage + state.currentPageOffsetFraction
        return (IchthysQsNotes.PAGE_TILES - position).coerceIn(0f, 1f)
    }
}

/** IchthysOS: see [IchthysNotesPagerProgress]; null outside expanded quick settings. */
val LocalIchthysNotesPagerProgress = compositionLocalOf<IchthysNotesPagerProgress?> { null }

/** Page count and current page of the tile pager inside the tiles page, for the indicator. */
class IchthysTilePagerInfo {
    var pageCount by mutableIntStateOf(1)
    var currentPage by mutableIntStateOf(0)
}

/**
 * Provided to the tiles page by [IchthysQsNotesPagerHost]; PaginatedGridLayout writes its pager
 * into it and leaves its own dots out, since the host's indicator shows them.
 */
val LocalIchthysTilePager = compositionLocalOf<IchthysTilePagerInfo?> { null }

/** The tokens the page draws with: Shoal's when that style is on, Material's for the grid. */
class IchthysNotesTokens(
    val card: Color,
    val hairline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val dotInactive: Color,
    val display: FontFamily,
    val mono: FontFamily,
    val body: FontFamily,
) {
    fun noteColor(index: Int): Color =
        IchthysQsNotes.Colors.getOrNull(index)?.takeIf { it.isSpecified } ?: accent
}

@Composable
fun rememberIchthysNotesTokens(): IchthysNotesTokens {
    val shoal = rememberShoalQsEnabled()
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()
    val scheme = MaterialTheme.colorScheme
    return if (shoal) {
        IchthysNotesTokens(
            card = ShoalQsColors.Track,
            hairline = ShoalQsColors.Hairline,
            text = ShoalQsColors.Label,
            muted = ShoalQsColors.Muted,
            accent = accent,
            onAccent = ShoalQsColors.OnAccent,
            dotInactive = ShoalQsColors.DotInactive,
            display = fonts.display,
            mono = fonts.mono,
            body = fonts.text,
        )
    } else {
        IchthysNotesTokens(
            card = scheme.surfaceContainer,
            hairline = scheme.outlineVariant,
            text = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            dotInactive = scheme.onSurfaceVariant.copy(alpha = .5f),
            display = FontFamily.Default,
            mono = FontFamily.Monospace,
            body = FontFamily.Default,
        )
    }
}

/**
 * Consumes horizontal drags that no child took, so the page swipe cannot start on this element.
 * Used on the brightness row: a drag beside the slider's thumb adjusts nothing and must not flip
 * the page either. Vertical drags pass through untouched.
 */
fun Modifier.ichthysKeepsHorizontalDrags(): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start =
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            if (start != null) {
                horizontalDrag(start.id) { it.consume() }
            }
        }
    }

/**
 * Two pages: the notes at [IchthysQsNotes.PAGE_NOTES], [tiles] at [IchthysQsNotes.PAGE_TILES].
 * Starts on, and returns to, the tiles whenever quick settings is not idle and visible, so every
 * shade opening lands on the tiles. [isScrollable] is the scene's "idle or leaving" flag.
 */
@Composable
fun ContentScope.IchthysQsNotesPagerHost(
    viewModel: IchthysQsNotesViewModel,
    isScrollable: Boolean,
    modifier: Modifier = Modifier,
    progress: IchthysNotesPagerProgress? = null,
    tiles: @Composable BoxScope.() -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = IchthysQsNotes.PAGE_TILES) { 2 }
    val tilePager = remember { IchthysTilePagerInfo() }
    val tokens = rememberIchthysNotesTokens()

    LaunchedEffect(Unit) { viewModel.start() }

    if (progress != null) {
        DisposableEffect(progress, pagerState) {
            progress.pagerState = pagerState
            onDispose { if (progress.pagerState === pagerState) progress.pagerState = null }
        }
    }

    // IchthysOS: on the notes page the tiles page is still composed, one page width to the right.
    // A collapse to the shade would then animate the tiles as shared elements from there and they
    // would fly in from the right edge. Stock quick settings has the same case for its later tile
    // pages and fades the tiles instead of moving them (AnimateQsTilesViewModel reads "in first
    // page"): report the notes page as "not the first page" too.
    LaunchedEffect(pagerState, tilePager) {
        snapshotFlow {
                pagerState.currentPage == IchthysQsNotes.PAGE_TILES && tilePager.currentPage == 0
            }
            .collect { viewModel.setTilesOnFirstPage(it) }
    }
    // Back to the repository's default when the host goes (the notes page switched off on it).
    DisposableEffect(viewModel) { onDispose { viewModel.setTilesOnFirstPage(true) } }

    val visible = isAlwaysComposedContentVisible()
    LaunchedEffect(visible, isScrollable) {
        if (!visible || !isScrollable) pagerState.scrollToPage(IchthysQsNotes.PAGE_TILES)
    }
    val openNotes by viewModel.openNotesWhenShown.collectAsStateWithLifecycle()
    LaunchedEffect(openNotes, visible, isScrollable) {
        if (openNotes && visible && isScrollable) {
            pagerState.animateScrollToPage(IchthysQsNotes.PAGE_NOTES)
            viewModel.consumeOpenNotes()
        }
    }

    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f).sysuiResTag("ichthys_qs_notes_pager"),
            // Both pages stay composed: the tiles carry scene elements (shared with the collapsed
            // shade) that must exist whatever page is showing.
            beyondViewportPageCount = 1,
            userScrollEnabled = isScrollable,
            verticalAlignment = Alignment.Top,
            key = { it },
        ) { page ->
            if (page == IchthysQsNotes.PAGE_NOTES) {
                // IchthysOS: an element, so the collapse transitions fade and move it (see
                // IchthysQsNotes.Elements.Page).
                IchthysQsNotesPage(
                    viewModel,
                    tokens,
                    Modifier.fillMaxSize().element(IchthysQsNotes.Elements.Page),
                )
            } else {
                CompositionLocalProvider(LocalIchthysTilePager provides tilePager) {
                    Box(Modifier.fillMaxSize(), content = tiles)
                }
            }
        }
        IchthysNotesIndicator(
            pagerState = pagerState,
            tilePager = tilePager,
            tokens = tokens,
            modifier = Modifier.fillMaxWidth().height(IchthysQsNotes.INDICATOR_HEIGHT),
        )
    }
}

@Composable
private fun IchthysNotesIndicator(
    pagerState: PagerState,
    tilePager: IchthysTilePagerInfo,
    tokens: IchthysNotesTokens,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val onNotes = pagerState.currentPage == IchthysQsNotes.PAGE_NOTES
    Row(
        modifier = modifier.sysuiResTag("ichthys_qs_notes_indicator"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ichthys_pin),
            contentDescription = stringResource(R.string.ichthys_qs_notes_indicator),
            tint = if (onNotes) tokens.accent else tokens.dotInactive,
            modifier =
                Modifier.clip(CircleShape)
                    .clickable {
                        scope.launch { pagerState.animateScrollToPage(IchthysQsNotes.PAGE_NOTES) }
                    }
                    .padding(IchthysQsNotes.INDICATOR_SPACE / 2)
                    .size(IchthysQsNotes.INDICATOR_PIN),
        )
        val tilesDescription = stringResource(R.string.ichthys_qs_notes_indicator_tiles)
        for (i in 0 until tilePager.pageCount) {
            val active = !onNotes && tilePager.currentPage == i
            Spacer(Modifier.width(IchthysQsNotes.INDICATOR_SPACE))
            Box(
                Modifier.clip(CircleShape)
                    .clickable {
                        scope.launch { pagerState.animateScrollToPage(IchthysQsNotes.PAGE_TILES) }
                    }
                    .semantics { contentDescription = tilesDescription }
                    .size(
                        width =
                            if (active) IchthysQsNotes.INDICATOR_PILL
                            else IchthysQsNotes.INDICATOR_DOT,
                        height = IchthysQsNotes.INDICATOR_DOT,
                    )
                    .background(if (active) tokens.accent else tokens.dotInactive, CircleShape)
            )
        }
    }
}

/** The page itself: header, card grid, buttons. */
@Composable
fun ContentScope.IchthysQsNotesPage(
    viewModel: IchthysQsNotesViewModel,
    tokens: IchthysNotesTokens,
    modifier: Modifier = Modifier,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val entered by viewModel.isDeviceEntered.collectAsStateWithLifecycle()
    val locked = !entered
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // IchthysOS: the editor is IchthysNoteEditorActivity (viewModel.openEditor), launched with the
    // shade collapsing; it finishes itself when the device locks.

    // IchthysOS: the same side inset as the tile content (QuickSettingsScene pads it with the
    // panel margin, QuickSettingsContent with qs_horizontal_margin), so cards, header and button
    // line up with the tiles and the footer actions when paging.
    val sideInset =
        dimensionResource(R.dimen.notification_panel_margin_horizontal) +
            dimensionResource(R.dimen.qs_horizontal_margin)

    Column(modifier.padding(horizontal = sideInset)) {
        NotesHeader(count = notes.size, locked = locked, tokens = tokens)
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .disableSwipesWhenScrolling()
                    .verticalScroll(rememberScrollState())
                    .sysuiResTag("ichthys_qs_notes_page"),
            verticalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
        ) {
            if (notes.isEmpty()) {
                if (!locked) {
                    NotesEmptyState(tokens = tokens, onClick = { viewModel.openEditor(null) })
                }
            } else {
                val card: @Composable (IchthysNote, Boolean, Modifier) -> Unit =
                    { note, wide, m ->
                        if (locked) {
                            LockedNoteCard(
                                note = note,
                                wide = wide,
                                tokens = tokens,
                                onClick = { viewModel.unlockToOpen() },
                                modifier = m,
                            )
                        } else {
                            NoteCard(
                                note = note,
                                wide = wide,
                                tokens = tokens,
                                onClick = { viewModel.openEditor(note.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { viewModel.repository.remove(note.id) }
                                },
                                modifier = m,
                            )
                        }
                    }
                // The first pinned card spans both columns; the rest pair up.
                card(notes.first(), true, Modifier.fillMaxWidth())
                notes.drop(1).chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
                    ) {
                        pair.forEach { note -> card(note, false, Modifier.weight(1f).fillMaxHeight()) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(IchthysQsNotes.CARD_GAP))
        }

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = IchthysQsNotes.CARD_GAP / 2, bottom = IchthysQsNotes.CARD_GAP),
            horizontalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) {
                PillButton(
                    text = stringResource(R.string.ichthys_qs_notes_unlock),
                    filled = true,
                    tokens = tokens,
                    onClick = { viewModel.unlockToOpen() },
                )
            } else {
                PillButton(
                    text = stringResource(R.string.ichthys_qs_notes_new),
                    filled = true,
                    tokens = tokens,
                    icon = rememberVectorPainter(Icons.Rounded.Add),
                    onClick = { viewModel.openEditor(null) },
                )
            }
        }
    }
}

/** IchthysOS: pin glyph, "Notes" in the display face, and the count as a small hairline chip. */
@Composable
private fun NotesHeader(count: Int, locked: Boolean, tokens: IchthysNotesTokens) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = IchthysQsNotes.CARD_GAP, bottom = IchthysQsNotes.CARD_GAP + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ichthys_pin),
            contentDescription = null,
            tint = tokens.accent,
            modifier = Modifier.size(IchthysQsNotes.HEADER_ICON),
        )
        BasicText(
            text = stringResource(R.string.ichthys_qs_notes_title),
            style =
                TextStyle(
                    color = tokens.text,
                    fontSize = IchthysQsNotes.TITLE_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = tokens.display,
                ),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        val pattern =
            stringResource(
                if (locked) R.string.ichthys_qs_notes_locked_count
                else R.string.ichthys_qs_notes_pinned_count
            )
        val shape = RoundedCornerShape(IchthysQsNotes.COUNT_HEIGHT / 2)
        Box(
            modifier =
                Modifier.height(IchthysQsNotes.COUNT_HEIGHT)
                    .border(1.dp, tokens.hairline, shape)
                    .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = MessageFormat.format(pattern, mapOf<String, Any>("count" to count)),
                style =
                    TextStyle(
                        color = tokens.muted,
                        fontSize = IchthysQsNotes.LABEL_SIZE,
                        fontFamily = tokens.mono,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** IchthysOS: nothing pinned yet: a quiet badge and the hint; a tap starts a new note. */
@Composable
private fun NotesEmptyState(tokens: IchthysNotesTokens, onClick: () -> Unit) {
    val shape = RoundedCornerShape(IchthysQsNotes.CARD_RADIUS)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .border(1.dp, tokens.hairline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier.size(IchthysQsNotes.EMPTY_BADGE)
                    .background(tokens.accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ichthys_pin),
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        BasicText(
            text = stringResource(R.string.ichthys_qs_notes_empty),
            style =
                TextStyle(
                    color = tokens.muted,
                    fontSize = IchthysQsNotes.TEXT_SIZE,
                    lineHeight = IchthysQsNotes.TEXT_LINE_HEIGHT,
                    fontFamily = tokens.body,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

/** IchthysOS: the shape, fill and hairline every note card shares; the ripple stays inside. */
private fun Modifier.noteCardSurface(tokens: IchthysNotesTokens): Modifier {
    val shape = RoundedCornerShape(IchthysQsNotes.CARD_RADIUS)
    return clip(shape).background(tokens.card).border(1.dp, tokens.hairline, shape)
}

@Composable
private fun NoteCard(
    note: IchthysNote,
    wide: Boolean,
    tokens: IchthysNotesTokens,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .noteCardSurface(tokens)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick)
                .padding(IchthysQsNotes.CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardLabel(note, tokens)
        val maxLines = if (wide) IchthysQsNotes.WIDE_MAX_LINES else IchthysQsNotes.MAX_LINES
        val bodyStyle =
            TextStyle(
                color = tokens.text,
                fontSize = IchthysQsNotes.TEXT_SIZE,
                lineHeight = IchthysQsNotes.TEXT_LINE_HEIGHT,
                fontFamily = tokens.body,
            )
        if (note.kind == IchthysNoteKind.LIST) {
            // One line per item; past the clamp the last line says how many more there are.
            val items = note.items
            val shown = if (items.size > maxLines) items.take(maxLines - 1) else items
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val boxShape = RoundedCornerShape(3.dp)
                        Box(
                            Modifier.size(IchthysQsNotes.CHECK_BOX)
                                .border(1.dp, if (item.done) tokens.muted else tokens.text, boxShape)
                                .padding(2.dp)
                                .background(
                                    if (item.done) tokens.muted else Color.Transparent,
                                    RoundedCornerShape(1.dp),
                                )
                        )
                        BasicText(
                            text = item.text,
                            style =
                                if (item.done)
                                    bodyStyle.copy(
                                        color = tokens.muted,
                                        textDecoration = TextDecoration.LineThrough,
                                    )
                                else bodyStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (shown.size < items.size) {
                    BasicText(
                        text = "+" + (items.size - shown.size),
                        style =
                            TextStyle(
                                color = tokens.muted,
                                fontSize = IchthysQsNotes.LABEL_SIZE,
                                fontFamily = tokens.mono,
                            ),
                    )
                }
            }
        } else {
            BasicText(
                text = note.text,
                style = bodyStyle,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** IchthysOS: colour dot, label, and when it was pinned (time today, date before). */
@Composable
private fun CardLabel(note: IchthysNote, tokens: IchthysNotesTokens) {
    val labelStyle =
        TextStyle(color = tokens.muted, fontSize = IchthysQsNotes.LABEL_SIZE, fontFamily = tokens.mono)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(IchthysQsNotes.COLOR_DOT).background(tokens.noteColor(note.color), CircleShape))
        BasicText(text = stringResource(note.kind.labelRes()), style = labelStyle, maxLines = 1)
        Spacer(Modifier.weight(1f))
        BasicText(text = ichthysNoteTime(note.createdAt), style = labelStyle, maxLines = 1)
    }
}

/** IchthysOS: a note's pin time as the card and the editor show it: time today, date before. */
internal fun ichthysNoteTime(createdAt: Long): String =
    DateUtils.formatSameDayTime(createdAt, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT)
        .toString()

fun IchthysNoteKind.labelRes(): Int =
    when (this) {
        IchthysNoteKind.NOTE -> R.string.ichthys_qs_notes_kind_note
        IchthysNoteKind.LIST -> R.string.ichthys_qs_notes_kind_list
        IchthysNoteKind.IDEA -> R.string.ichthys_qs_notes_kind_idea
    }

/**
 * Locked: the card's colour dot and grey bars, nothing of its text (nor its time). The bars' widths
 * come from the note's id, not its contents, so nothing about the text leaks.
 */
@Composable
private fun LockedNoteCard(
    note: IchthysNote,
    wide: Boolean,
    tokens: IchthysNotesTokens,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val description = stringResource(R.string.ichthys_qs_notes_card_locked)
    val widths = remember(note.id) {
        val seed = (note.id % 3).toInt().let { if (it < 0) it + 3 else it }
        val base = listOf(0.9f, 0.65f, 0.45f)
        List(if (wide) 3 else 2) { base[(it + seed) % base.size] }
    }
    Column(
        modifier =
            modifier
                .noteCardSurface(tokens)
                .clickable(onClick = onClick)
                .padding(IchthysQsNotes.CARD_PADDING)
                .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(IchthysQsNotes.COLOR_DOT).background(tokens.noteColor(note.color), CircleShape))
            Box(
                Modifier.size(width = 28.dp, height = IchthysQsNotes.BAR_HEIGHT)
                    .background(tokens.hairline, CircleShape)
            )
        }
        widths.forEach { fraction ->
            Box(
                Modifier.fillMaxWidth(fraction)
                    .height(IchthysQsNotes.BAR_HEIGHT)
                    .background(tokens.hairline, CircleShape)
            )
        }
    }
}

/**
 * IchthysOS: the notes page's pill button (page and editor): accent-filled or hairline, with an
 * optional leading [icon]. Disabled = dimmed and not clickable, so a row of them never jumps.
 */
@Composable
internal fun PillButton(
    text: String,
    filled: Boolean,
    tokens: IchthysNotesTokens,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(IchthysQsNotes.BUTTON_HEIGHT / 2)
    val fill = if (filled) tokens.accent else Color.Transparent
    val content = if (filled) tokens.onAccent else tokens.text
    val dim = if (enabled) 1f else 0.38f
    Row(
        modifier =
            modifier
                .height(IchthysQsNotes.BUTTON_HEIGHT)
                .clip(shape)
                .background(if (filled) fill.copy(alpha = fill.alpha * dim) else fill)
                .border(1.dp, if (filled) Color.Transparent else tokens.hairline, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = if (icon != null) 14.dp else 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = content.copy(alpha = content.alpha * dim),
                modifier = Modifier.size(IchthysQsNotes.BUTTON_ICON),
            )
        }
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = content.copy(alpha = content.alpha * dim),
                    fontSize = IchthysQsNotes.TEXT_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = tokens.body,
                ),
            maxLines = 1,
        )
    }
}

/** Keeps [LocalIchthysTilePager] fed from PaginatedGridLayout's pager. */
@Composable
fun ReportIchthysTilePager(pagerState: PagerState) {
    val info = LocalIchthysTilePager.current ?: return
    LaunchedEffect(pagerState, info) {
        snapshotFlow { pagerState.pageCount to pagerState.currentPage }
            .collect { (count, current) ->
                info.pageCount = count
                info.currentPage = current
            }
    }
}

/** True when the tiles page is hosted by [IchthysQsNotesPagerHost], which then draws the dots. */
@Composable
fun ichthysNotesHostDrawsDots(): Boolean = LocalIchthysTilePager.current != null
