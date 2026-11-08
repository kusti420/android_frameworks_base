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

package com.android.systemui.ichthys.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.ichthys.notes.IchthysNotesViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/**
 * IchthysOS: the notes page left of the quick settings tiles.
 *
 * Expanded quick settings always opens on the tiles; a swipe right shows the notes. The host is a
 * two-page [HorizontalPager] around the scrolling tile content only, so the header above and the
 * media dock and footer below stay put. The page indicator is a pin glyph for the notes page plus
 * one dot per tile page.
 */
object IchthysQsNotes {
    const val PAGE_NOTES = 0
    const val PAGE_TILES = 1

    object Elements {
        /**
         * The notes page as a scene element of the Quick Settings scene, so the scene transitions
         * that collapse quick settings fade and move it like the tile content.
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
    val CARD_PADDING_H = 16.dp
    val CARD_PADDING_V = 13.dp
    val CARD_GAP = 10.dp
    val CHECK_BOX = 16.dp
    val ITEM_HEIGHT = 28.dp
    val BAR_HEIGHT = 9.dp
    val PILL_HEIGHT = 46.dp
    val ACTION_HEIGHT = 44.dp
    val TITLE_SIZE = 18.sp
    val CARD_TITLE_SIZE = 15.sp
    val LABEL_SIZE = 11.sp
    val TEXT_SIZE = 14.5.sp
    val TEXT_LINE_HEIGHT = 21.sp
    const val MAX_BODY_LINES = 6
    const val MAX_ITEMS = 6
    const val GRID_MAX_BODY_LINES = 4
    const val GRID_MAX_ITEMS = 4
}

/** IchthysOS: Settings.System keys of the notes page (ichthys.nix quickSettings.notes.*). */
object IchthysNotesSettings {
    /** 1 = the page exists. */
    const val PAGE = "qs_notes_page"
    /** 0 = list, 1 = two-column grid. */
    const val LAYOUT = "qs_notes_layout"
    /** 1 = codes, numbers, links and emails are tappable. */
    const val ACTIONS = "qs_notes_actions"
    /** 1 = a fresh clipboard text is offered as a row. */
    const val CLIPBOARD = "qs_notes_clipboard"
    /** 0 = ticked items sink to the bottom, 1 = stay put, 2 = hidden. */
    const val TICKED = "qs_notes_ticked"
    /** 0 = bars while locked, 1 = the page is left out while locked. */
    const val LOCKED = "qs_notes_locked"
    /** 1 = show when a note was last changed. */
    const val TIME = "qs_notes_time"
    /** 1 = per-note tints are offered. */
    const val COLOURS = "qs_notes_colours"
}

/**
 * IchthysOS: how far the notes pager is on the notes page, for elements outside the pager that
 * belong to the tile pages only (the Shoal header's ringer pill). Read [notesFraction] in a draw
 * or layer block, so following the swipe only redraws.
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
    viewModel: IchthysNotesViewModel,
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

    // On the notes page the tiles page is still composed, one page width to the right. A collapse
    // to the shade would animate the tiles as shared elements from there; stock quick settings
    // fades the tiles for its later pages instead: report the notes page as "not the first page".
    LaunchedEffect(pagerState, tilePager) {
        snapshotFlow {
                pagerState.currentPage == IchthysQsNotes.PAGE_TILES && tilePager.currentPage == 0
            }
            .collect { viewModel.setTilesOnFirstPage(it) }
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.setTilesOnFirstPage(true)
            viewModel.setTextInputActive(false)
        }
    }

    val visible = isAlwaysComposedContentVisible()
    // Quick settings stays composed when closed, and the notes page when paged away: drop the
    // composer's focus (and the shade window's keyboard focus with it) whenever the page is
    // not on screen, or the window would stay an IME target behind the lock screen or an app.
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val notesOnScreen = visible && pagerState.currentPage == IchthysQsNotes.PAGE_NOTES
    LaunchedEffect(notesOnScreen) {
        if (!notesOnScreen) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            viewModel.setTextInputActive(false)
        }
    }
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
    val onNotesPage = pagerState.currentPage == IchthysQsNotes.PAGE_NOTES && visible

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
                IchthysNotesPage(
                    viewModel = viewModel,
                    tokens = tokens,
                    isShowing = onNotesPage,
                    modifier = Modifier.fillMaxSize().element(IchthysQsNotes.Elements.Page),
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
            contentDescription = stringResource(R.string.ichthys_notes_indicator),
            tint = if (onNotes) tokens.accentMark else tokens.dotInactive,
            modifier =
                Modifier.clip(CircleShape)
                    .clickable {
                        scope.launch { pagerState.animateScrollToPage(IchthysQsNotes.PAGE_NOTES) }
                    }
                    .padding(IchthysQsNotes.INDICATOR_SPACE / 2)
                    .size(IchthysQsNotes.INDICATOR_PIN),
        )
        val tilesDescription = stringResource(R.string.ichthys_notes_indicator_tiles)
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
                    .background(if (active) tokens.accentMark else tokens.dotInactive, CircleShape)
            )
        }
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
