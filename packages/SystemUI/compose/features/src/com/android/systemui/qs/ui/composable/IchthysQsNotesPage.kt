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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
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

    // Layout constants: SystemUI must not get new <dimen>s (resource-ID skew crash-loops Shell).
    val INDICATOR_HEIGHT = 24.dp
    val INDICATOR_PIN = 14.dp
    val INDICATOR_DOT = 6.dp
    val INDICATOR_PILL = 18.dp
    val INDICATOR_SPACE = 6.dp

    val PAGE_PADDING = 4.dp
    val CARD_RADIUS = 16.dp
    val CARD_PADDING = 12.dp
    val CARD_GAP = 10.dp
    val COLOR_DOT = 8.dp
    val CHECK_BOX = 10.dp
    val BAR_HEIGHT = 8.dp
    val BUTTON_HEIGHT = 36.dp
    val TITLE_SIZE = 18.sp
    val LABEL_SIZE = 11.sp
    val TEXT_SIZE = 13.sp
    val TEXT_LINE_HEIGHT = 17.sp
    const val WIDE_MAX_LINES = 8
    const val MAX_LINES = 6

    /** Colour dots a note can carry (index = [IchthysNote.color]); 0 is the theme accent. */
    val Colors = listOf(Color.Unspecified, Color(0xFF2DE2E6), Color(0xFFC6FF3D), Color(0xFFD27BFF), Color(0xFFF2F4F1))
}

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
    tiles: @Composable BoxScope.() -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = IchthysQsNotes.PAGE_TILES) { 2 }
    val tilePager = remember { IchthysTilePagerInfo() }
    val tokens = rememberIchthysNotesTokens()

    LaunchedEffect(Unit) { viewModel.start() }

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
                IchthysQsNotesPage(viewModel, tokens, Modifier.fillMaxSize())
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
    var editing by remember { mutableStateOf<IchthysNoteEditorTarget?>(null) }
    // Checked when the page is (re)composed: cheap, and p!n is rarely installed or removed.

    // Locking closes the editor (the dialog itself also dismisses on device lock).
    LaunchedEffect(locked) { if (locked) editing = null }
    IchthysQsNoteEditorDialog(
        target = if (locked) null else editing,
        viewModel = viewModel,
        tokens = tokens,
        onDismiss = { editing = null },
    )

    Column(modifier.padding(horizontal = IchthysQsNotes.PAGE_PADDING)) {
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .disableSwipesWhenScrolling()
                    .verticalScroll(rememberScrollState())
                    .sysuiResTag("ichthys_qs_notes_page"),
            verticalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
        ) {
            NotesHeader(count = notes.size, locked = locked, tokens = tokens)

            if (notes.isEmpty()) {
                if (!locked) {
                    BasicText(
                        text = stringResource(R.string.ichthys_qs_notes_empty),
                        style =
                            TextStyle(
                                color = tokens.muted,
                                fontSize = IchthysQsNotes.TEXT_SIZE,
                                lineHeight = IchthysQsNotes.TEXT_LINE_HEIGHT,
                                fontFamily = tokens.body,
                            ),
                        modifier = Modifier.padding(vertical = IchthysQsNotes.CARD_PADDING),
                    )
                }
            } else {
                val card: @Composable (IchthysNote, Boolean, Modifier) -> Unit =
                    { note, wide, m ->
                        if (locked) {
                            LockedNoteCard(note, wide, tokens, m.clickable { viewModel.unlockToOpen() })
                        } else {
                            NoteCard(
                                note = note,
                                wide = wide,
                                tokens = tokens,
                                modifier =
                                    m.combinedClickable(
                                        onLongClick = {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            scope.launch { viewModel.repository.remove(note.id) }
                                        },
                                        onClick = { editing = IchthysNoteEditorTarget.Edit(note) },
                                    ),
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
            modifier = Modifier.fillMaxWidth().padding(bottom = IchthysQsNotes.CARD_GAP),
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
                    onClick = { editing = IchthysNoteEditorTarget.New },
                )
            }
        }
    }
}

@Composable
private fun NotesHeader(count: Int, locked: Boolean, tokens: IchthysNotesTokens) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = IchthysQsNotes.CARD_GAP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ichthys_pin),
            contentDescription = null,
            tint = tokens.accent,
            modifier = Modifier.size(IchthysQsNotes.TITLE_SIZE.value.dp),
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
        )
        val pattern =
            stringResource(
                if (locked) R.string.ichthys_qs_notes_locked_count
                else R.string.ichthys_qs_notes_pinned_count
            )
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

@Composable
private fun NoteCard(note: IchthysNote, wide: Boolean, tokens: IchthysNotesTokens, modifier: Modifier) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(IchthysQsNotes.CARD_RADIUS))
                .background(tokens.card)
                .border(1.dp, tokens.hairline, RoundedCornerShape(IchthysQsNotes.CARD_RADIUS))
                .padding(IchthysQsNotes.CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            note.items.take(maxLines).forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(IchthysQsNotes.CHECK_BOX)
                            .border(1.dp, if (item.done) tokens.muted else tokens.text, RoundedCornerShape(2.dp))
                            .padding(2.dp)
                            .background(if (item.done) tokens.muted else Color.Transparent)
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

@Composable
private fun CardLabel(note: IchthysNote, tokens: IchthysNotesTokens) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(IchthysQsNotes.COLOR_DOT).background(tokens.noteColor(note.color), CircleShape))
        BasicText(
            text = stringResource(note.kind.labelRes()),
            style =
                TextStyle(
                    color = tokens.muted,
                    fontSize = IchthysQsNotes.LABEL_SIZE,
                    fontFamily = tokens.mono,
                ),
        )
    }
}

fun IchthysNoteKind.labelRes(): Int =
    when (this) {
        IchthysNoteKind.NOTE -> R.string.ichthys_qs_notes_kind_note
        IchthysNoteKind.LIST -> R.string.ichthys_qs_notes_kind_list
        IchthysNoteKind.IDEA -> R.string.ichthys_qs_notes_kind_idea
    }

/**
 * Locked: the card's colour dot and grey bars, nothing of its text. The bars' widths come from the
 * note's id, not its contents, so nothing about the text leaks.
 */
@Composable
private fun LockedNoteCard(note: IchthysNote, wide: Boolean, tokens: IchthysNotesTokens, modifier: Modifier) {
    val description = stringResource(R.string.ichthys_qs_notes_card_locked)
    val widths = remember(note.id) {
        val seed = (note.id % 3).toInt().let { if (it < 0) it + 3 else it }
        val base = listOf(0.9f, 0.65f, 0.45f)
        List(if (wide) 3 else 2) { base[(it + seed) % base.size] }
    }
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(IchthysQsNotes.CARD_RADIUS))
                .background(tokens.card)
                .border(1.dp, tokens.hairline, RoundedCornerShape(IchthysQsNotes.CARD_RADIUS))
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

@Composable
internal fun PillButton(
    text: String,
    filled: Boolean,
    tokens: IchthysNotesTokens,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(IchthysQsNotes.BUTTON_HEIGHT / 2)
    Box(
        modifier =
            modifier
                .height(IchthysQsNotes.BUTTON_HEIGHT)
                .clip(shape)
                .background(if (filled) tokens.accent else Color.Transparent)
                .border(1.dp, if (filled) tokens.accent else tokens.hairline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = if (filled) tokens.onAccent else tokens.text,
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
