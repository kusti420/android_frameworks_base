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

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.ichthys.notes.IchthysNote
import com.android.systemui.ichthys.notes.IchthysNoteEntities
import com.android.systemui.ichthys.notes.IchthysNoteEntity
import com.android.systemui.ichthys.notes.IchthysNoteItem
import com.android.systemui.ichthys.notes.IchthysNotesViewModel
import com.android.systemui.qs.ui.composable.rememberIchthysSystemInt
import com.android.systemui.res.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the page reads from Settings.System, resolved once per composition. */
private class NotesPrefs(
    val grid: Boolean,
    val actions: Boolean,
    val clipboard: Boolean,
    /** 0 sink, 1 stay, 2 hide. */
    val ticked: Int,
    /** 0 bars, 1 the page is left out while locked. */
    val locked: Int,
    val time: Boolean,
    val colours: Boolean,
)

@Composable
private fun rememberNotesPrefs(): NotesPrefs {
    val grid = rememberIchthysSystemInt(IchthysNotesSettings.LAYOUT, 0) == 1
    val actions = rememberIchthysSystemInt(IchthysNotesSettings.ACTIONS, 1) != 0
    val clipboard = rememberIchthysSystemInt(IchthysNotesSettings.CLIPBOARD, 1) != 0
    val ticked = rememberIchthysSystemInt(IchthysNotesSettings.TICKED, 0)
    val locked = rememberIchthysSystemInt(IchthysNotesSettings.LOCKED, 0)
    val time = rememberIchthysSystemInt(IchthysNotesSettings.TIME, 0) != 0
    val colours = rememberIchthysSystemInt(IchthysNotesSettings.COLOURS, 1) != 0
    return NotesPrefs(grid, actions, clipboard, ticked, locked, time, colours)
}

/**
 * IchthysOS: the notes page: title, the notes (a list, or a two-column grid), then the clipboard
 * offer, the undo strip and the composer at the foot. While the lock screen shows, notes are bars
 * unless marked readable on lock; the composer still works, the unlock pill sits above it.
 */
@Composable
fun ContentScope.IchthysNotesPage(
    viewModel: IchthysNotesViewModel,
    tokens: IchthysNotesTokens,
    isShowing: Boolean,
    modifier: Modifier = Modifier,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val entered by viewModel.isDeviceEntered.collectAsStateWithLifecycle()
    val ready by viewModel.storeReady.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val pending by viewModel.pendingRemoval.collectAsStateWithLifecycle()
    val clip by viewModel.clipSuggestion.collectAsStateWithLifecycle()
    val prefs = rememberNotesPrefs()
    val locked = !entered
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var heldId by remember { mutableStateOf<Long?>(null) }
    var copiedId by remember { mutableStateOf<Long?>(null) }
    var lockedSaved by remember { mutableStateOf(false) }

    LaunchedEffect(isShowing, entered, prefs.clipboard) {
        if (isShowing && entered && prefs.clipboard) viewModel.refreshClipSuggestion()
        if (!isShowing) heldId = null
    }
    LaunchedEffect(copiedId) {
        if (copiedId != null) {
            delay(1_200)
            copiedId = null
        }
    }
    LaunchedEffect(lockedSaved) {
        if (lockedSaved) {
            delay(3_000)
            lockedSaved = false
        }
    }

    // The same side inset as the tile content, so cards line up with the tiles when paging.
    val sideInset =
        dimensionResource(R.dimen.notification_panel_margin_horizontal) +
            dimensionResource(R.dimen.qs_horizontal_margin)

    Column(modifier.padding(horizontal = sideInset).imePadding()) {
        PageTitle(count = notes.size, locked = locked, tokens = tokens)

        val hidePage = locked && prefs.locked == 1
        val listModifier = Modifier.weight(1f).fillMaxWidth().disableSwipesWhenScrolling()
        when {
            hidePage || (locked && !ready) -> Spacer(listModifier)
            notes.isEmpty() -> EmptyState(tokens, listModifier)
            else -> {
                val cards: @Composable (IchthysNote, Modifier) -> Unit = { note, m ->
                    if (locked && !note.onLock) {
                        LockedCard(note, tokens, prefs, onClick = viewModel::unlockToOpen, modifier = m)
                    } else {
                        NoteCard(
                            note = note,
                            tokens = tokens,
                            prefs = prefs,
                            held = heldId == note.id,
                            dimmed = heldId != null && heldId != note.id,
                            editing = draft.editingId == note.id,
                            copied = copiedId == note.id,
                            locked = locked,
                            onClick = {
                                if (heldId != null) heldId = null
                                else if (!locked) viewModel.edit(note)
                            },
                            onHold = {
                                if (!locked) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    heldId = if (heldId == note.id) null else note.id
                                }
                            },
                            onToggleItem = { item ->
                                haptics.performHapticFeedback(
                                    if (item.done) HapticFeedbackType.ToggleOff
                                    else HapticFeedbackType.ToggleOn
                                )
                                viewModel.toggleItem(note, item.line, !item.done)
                            },
                            onEntity = { entity ->
                                viewModel.act(entity)
                                if (entity is IchthysNoteEntity.Code) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    copiedId = note.id
                                }
                            },
                            onAction = { action ->
                                heldId = null
                                when (action) {
                                    CardAction.PIN -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                        viewModel.togglePinned(note)
                                    }
                                    CardAction.COPY -> {
                                        viewModel.copyNote(note)
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        copiedId = note.id
                                    }
                                    CardAction.SHARE -> viewModel.shareNote(note)
                                    CardAction.ON_LOCK -> viewModel.toggleOnLock(note)
                                    CardAction.DELETE -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        viewModel.remove(note)
                                    }
                                }
                            },
                            modifier = m,
                        )
                    }
                }
                if (prefs.grid) {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = listModifier.sysuiResTag("ichthys_notes_grid"),
                        verticalItemSpacing = IchthysQsNotes.CARD_GAP,
                        horizontalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
                        contentPadding = PaddingValues(bottom = IchthysQsNotes.CARD_GAP),
                    ) {
                        items(notes, key = { it.id }) { note -> cards(note, Modifier.animateItem()) }
                    }
                } else {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = listModifier.sysuiResTag("ichthys_notes_list"),
                        verticalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
                        contentPadding = PaddingValues(bottom = IchthysQsNotes.CARD_GAP),
                    ) {
                        items(notes, key = { it.id }) { note -> cards(note, Modifier.animateItem()) }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = IchthysQsNotes.CARD_GAP),
            verticalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
        ) {
            AnimatedVisibility(
                visible = clip != null && entered && prefs.clipboard && !hidePage,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ClipboardRow(
                    text = clip ?: "",
                    tokens = tokens,
                    onPin = {
                        scope.launch {
                            if (viewModel.pinClipSuggestion()) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                        }
                    },
                    onDismiss = viewModel::dismissClipSuggestion,
                )
            }
            AnimatedVisibility(
                visible = pending != null && entered,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                UndoStrip(
                    note = pending,
                    tokens = tokens,
                    onUndo = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.undoRemove()
                    },
                )
            }
            AnimatedVisibility(visible = lockedSaved, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                QuietLine(stringResource(R.string.ichthys_notes_pinned_locked), tokens)
            }
            if (locked) {
                UnlockPill(tokens = tokens, onClick = viewModel::unlockToOpen)
            }
            if (ready && !hidePage) {
                IchthysNotesComposer(
                    viewModel = viewModel,
                    tokens = tokens,
                    draft = draft,
                    coloursOn = prefs.colours,
                    locked = locked,
                    onStored = { whileLocked -> if (whileLocked) lockedSaved = true },
                )
            }
        }
    }
}

@Composable
private fun PageTitle(count: Int, locked: Boolean, tokens: IchthysNotesTokens) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = IchthysQsNotes.CARD_GAP, bottom = IchthysQsNotes.CARD_GAP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = stringResource(R.string.ichthys_notes_title),
            style =
                TextStyle(
                    color = tokens.text,
                    fontSize = IchthysQsNotes.TITLE_SIZE,
                    fontWeight = FontWeight.Bold,
                    fontFamily = tokens.display,
                ),
            maxLines = 1,
        )
        if (count > 0) {
            BasicText(
                text = count.toString(),
                style =
                    TextStyle(color = tokens.muted, fontSize = 12.sp, fontFamily = tokens.mono),
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        if (locked) {
            Icon(
                painter = painterResource(R.drawable.ic_ichthys_notes_lock),
                contentDescription = null,
                tint = tokens.muted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(tokens: IchthysNotesTokens, modifier: Modifier) {
    Column(
        modifier = modifier.padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ichthys_pin),
            contentDescription = null,
            tint = tokens.dotInactive,
            modifier = Modifier.size(28.dp),
        )
        BasicText(
            text = stringResource(R.string.ichthys_notes_empty),
            style =
                TextStyle(
                    color = tokens.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = tokens.bodyFont,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.widthIn(max = 230.dp),
        )
    }
}

// region cards

internal enum class CardAction { PIN, COPY, SHARE, ON_LOCK, DELETE }

private fun Modifier.cardSurface(tokens: IchthysNotesTokens, border: Color): Modifier {
    val shape = RoundedCornerShape(IchthysQsNotes.CARD_RADIUS)
    return clip(shape).background(tokens.card).border(1.dp, border, shape)
}

@Composable
private fun NoteCard(
    note: IchthysNote,
    tokens: IchthysNotesTokens,
    prefs: NotesPrefs,
    held: Boolean,
    dimmed: Boolean,
    editing: Boolean,
    copied: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    onToggleItem: (IchthysNoteItem) -> Unit,
    onEntity: (IchthysNoteEntity) -> Unit,
    onAction: (CardAction) -> Unit,
    modifier: Modifier,
) {
    val alpha by animateFloatAsState(if (dimmed) 0.4f else 1f, label = "cardDim")
    // No size "lift" for the held card: it stayed 2 % larger while its actions were open and
    // stuck out past the other cards. The accent border and the dimmed rest mark it.
    val border by
        androidx.compose.animation.animateColorAsState(
            if (held || editing) tokens.accentMark else tokens.hairline,
            label = "cardBorder",
        )
    val mark = if (prefs.colours) tokens.markColor(note.tint) else tokens.accentMark
    val maxBody = if (prefs.grid) IchthysQsNotes.GRID_MAX_BODY_LINES else IchthysQsNotes.MAX_BODY_LINES
    val maxItems = if (prefs.grid) IchthysQsNotes.GRID_MAX_ITEMS else IchthysQsNotes.MAX_ITEMS

    Column(
        modifier =
            modifier.graphicsLayer { this.alpha = alpha },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .cardSurface(tokens, border)
                    .combinedClickable(onLongClick = onHold, onClick = onClick)
                    .padding(
                        horizontal = if (prefs.grid) 14.dp else IchthysQsNotes.CARD_PADDING_H,
                        vertical = IchthysQsNotes.CARD_PADDING_V,
                    ),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val title = note.title
            val glyphs: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (note.onLock) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ichthys_notes_eye),
                            contentDescription = stringResource(R.string.ichthys_notes_on_lock_glyph),
                            tint = tokens.muted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (note.pinned) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ichthys_pin),
                            contentDescription = stringResource(R.string.ichthys_notes_pinned_glyph),
                            tint = mark,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
            val showGlyphs = note.pinned || note.onLock
            val tintDot: @Composable () -> Unit = {
                if (prefs.colours && note.tint > 0) {
                    Box(Modifier.size(7.dp).background(mark, CircleShape))
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (title != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tintDot()
                            BasicText(
                                text = title,
                                style =
                                    TextStyle(
                                        color = tokens.text,
                                        fontSize = if (prefs.grid) 14.sp else IchthysQsNotes.CARD_TITLE_SIZE,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = tokens.display,
                                    ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val body = note.bodyLines
                    if (body.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (title == null) {
                                Box(Modifier.padding(top = 7.dp)) { tintDot() }
                            }
                            EntityText(
                                lines = body,
                                tokens = tokens,
                                mark = mark,
                                actions = prefs.actions,
                                maxLines = maxBody,
                                grid = prefs.grid,
                                onEntity = onEntity,
                            )
                        }
                    }
                }
                if (showGlyphs) glyphs()
            }

            val items = note.items
            if (items.isNotEmpty()) {
                val ordered =
                    when (prefs.ticked) {
                        2 -> items.filter { !it.done }
                        1 -> items
                        else -> items.sortedBy { it.done }
                    }
                val shown = if (ordered.size > maxItems) ordered.take(maxItems - 1) else ordered
                Column {
                    shown.forEach { item ->
                        ItemRow(item, tokens, mark, onToggle = { onToggleItem(item) }, grid = prefs.grid)
                    }
                    if (shown.size < ordered.size) {
                        BasicText(
                            text = "+" + (ordered.size - shown.size),
                            style = TextStyle(color = tokens.muted, fontSize = IchthysQsNotes.LABEL_SIZE, fontFamily = tokens.mono),
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                        )
                    }
                }
            }

            val showProgress = items.isNotEmpty()
            val showTime = prefs.time
            if (showProgress || showTime || copied) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val labelStyle = TextStyle(color = tokens.muted, fontSize = IchthysQsNotes.LABEL_SIZE, fontFamily = tokens.mono)
                    if (showProgress) {
                        ProgressRing(done = note.doneCount, total = items.size, tokens = tokens, mark = mark)
                        Spacer(Modifier.width(6.dp))
                        BasicText(
                            text = stringResource(R.string.ichthys_notes_progress, note.doneCount, items.size),
                            style = labelStyle,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (copied) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = mark,
                                modifier = Modifier.size(12.dp),
                            )
                            BasicText(
                                text = stringResource(R.string.ichthys_notes_copied),
                                style = labelStyle.copy(color = mark),
                                maxLines = 1,
                            )
                        }
                    } else if (showTime) {
                        BasicText(text = relativeTime(note.updatedAt), style = labelStyle, maxLines = 1)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = held && !locked,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ActionsRow(note = note, tokens = tokens, onAction = onAction)
        }
    }
}

@Composable
private fun ItemRow(
    item: IchthysNoteItem,
    tokens: IchthysNotesTokens,
    mark: Color,
    onToggle: () -> Unit,
    grid: Boolean,
) {
    val description = stringResource(R.string.ichthys_notes_item_toggle)
    Row(
        modifier = Modifier.fillMaxWidth().height(IchthysQsNotes.ITEM_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (grid) 8.dp else 10.dp),
    ) {
        Box(
            modifier =
                Modifier.size(IchthysQsNotes.ITEM_HEIGHT)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle)
                    .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            val fill by animateFloatAsState(if (item.done) 1f else 0f, spring(stiffness = 900f), label = "tick")
            Box(
                Modifier.size(IchthysQsNotes.CHECK_BOX)
                    .border(1.5.dp, if (item.done) mark else tokens.muted, RoundedCornerShape(5.dp))
                    .padding(1.5.dp)
                    .graphicsLayer {
                        scaleX = fill
                        scaleY = fill
                    }
                    .background(mark, RoundedCornerShape(3.5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.done) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = tokens.onAccent,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        BasicText(
            text = item.text,
            style =
                TextStyle(
                    color = if (item.done) tokens.muted else tokens.body,
                    fontSize = if (grid) 13.5.sp else IchthysQsNotes.TEXT_SIZE,
                    fontFamily = tokens.bodyFont,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgressRing(done: Int, total: Int, tokens: IchthysNotesTokens, mark: Color) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    val animated by animateFloatAsState(fraction, label = "progress")
    val track = tokens.hairline
    Box(
        Modifier.size(14.dp).drawBehind {
            val stroke = Stroke(width = 2.5.dp.toPx())
            val inset = stroke.width / 2
            drawArc(track, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width), style = stroke)
            if (animated > 0f) {
                drawArc(mark, -90f, 360f * animated, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width), style = Stroke(width = stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    )
}

/** Body text with its codes, numbers, links and emails as tappable, marked spans. */
@Composable
private fun EntityText(
    lines: List<String>,
    tokens: IchthysNotesTokens,
    mark: Color,
    actions: Boolean,
    maxLines: Int,
    grid: Boolean,
    onEntity: (IchthysNoteEntity) -> Unit,
) {
    val style =
        TextStyle(
            color = tokens.body,
            fontSize = if (grid) 13.5.sp else IchthysQsNotes.TEXT_SIZE,
            lineHeight = IchthysQsNotes.TEXT_LINE_HEIGHT,
            fontFamily = tokens.bodyFont,
        )
    val text: AnnotatedString =
        remember(lines, actions, mark, tokens.mono) {
            buildAnnotatedString {
                lines.forEachIndexed { index, line ->
                    val start = length
                    append(line)
                    if (actions) {
                        IchthysNoteEntities.detect(line).forEach { entity ->
                            val mono = entity is IchthysNoteEntity.Code || entity is IchthysNoteEntity.Phone
                            val span =
                                SpanStyle(
                                    color = mark,
                                    textDecoration = TextDecoration.Underline,
                                    fontFamily = if (mono) tokens.mono else null,
                                    fontWeight = if (mono) FontWeight.Medium else null,
                                )
                            addLink(
                                LinkAnnotation.Clickable(
                                    tag = entity.value,
                                    styles = TextLinkStyles(style = span, pressedStyle = span.copy(color = tokens.text)),
                                    linkInteractionListener = { onEntity(entity) },
                                ),
                                start + entity.start,
                                start + entity.end,
                            )
                        }
                    }
                    if (index < lines.lastIndex) append("\n")
                }
            }
        }
    BasicText(text = text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ActionsRow(note: IchthysNote, tokens: IchthysNotesTokens, onAction: (CardAction) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ActionButton(
            label = stringResource(if (note.pinned) R.string.ichthys_notes_action_unpin else R.string.ichthys_notes_action_pin),
            painter = painterResource(R.drawable.ic_ichthys_pin),
            tokens = tokens,
            active = note.pinned,
            onClick = { onAction(CardAction.PIN) },
        )
        ActionButton(
            label = stringResource(R.string.ichthys_notes_action_copy),
            painter = painterResource(R.drawable.ic_ichthys_notes_copy),
            tokens = tokens,
            onClick = { onAction(CardAction.COPY) },
        )
        ActionButton(
            label = stringResource(R.string.ichthys_notes_action_share),
            painter = painterResource(R.drawable.ic_ichthys_notes_share),
            tokens = tokens,
            onClick = { onAction(CardAction.SHARE) },
        )
        ActionButton(
            label = stringResource(if (note.onLock) R.string.ichthys_notes_action_off_lock else R.string.ichthys_notes_action_on_lock),
            painter = painterResource(R.drawable.ic_ichthys_notes_eye),
            tokens = tokens,
            active = note.onLock,
            onClick = { onAction(CardAction.ON_LOCK) },
        )
        ActionButton(
            label = stringResource(R.string.ichthys_notes_action_delete),
            painter = painterResource(R.drawable.ic_ichthys_notes_trash),
            tokens = tokens,
            onClick = { onAction(CardAction.DELETE) },
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    painter: androidx.compose.ui.graphics.painter.Painter,
    tokens: IchthysNotesTokens,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Column(
        modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier =
                Modifier.size(IchthysQsNotes.ACTION_HEIGHT)
                    .background(if (active) tokens.accent else tokens.card, CircleShape)
                    .border(1.dp, if (active) Color.Transparent else tokens.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = if (active) tokens.onAccent else tokens.text,
                modifier = Modifier.size(17.dp),
            )
        }
        BasicText(
            text = label,
            style = TextStyle(color = tokens.body, fontSize = IchthysQsNotes.LABEL_SIZE, fontFamily = tokens.bodyFont),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Locked: the card's shape with bars, nothing of its text. The bars' widths come from the note's
 * id, not its contents, so nothing about the text leaks; its pin glyph and tint dot stay.
 */
@Composable
private fun LockedCard(
    note: IchthysNote,
    tokens: IchthysNotesTokens,
    prefs: NotesPrefs,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val description = stringResource(R.string.ichthys_notes_card_locked)
    val widths =
        remember(note.id) {
            val seed = (note.id % 3).toInt().let { if (it < 0) it + 3 else it }
            val base = listOf(0.82f, 0.58f, 0.4f)
            List(if (seed == 0) 2 else 1) { base[(it + seed) % base.size] }
        }
    val mark = if (prefs.colours) tokens.markColor(note.tint) else tokens.accentMark
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .cardSurface(tokens, tokens.hairline)
                .clickable(onClick = onClick)
                .padding(horizontal = IchthysQsNotes.CARD_PADDING_H, vertical = 15.dp)
                .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (prefs.colours && note.tint > 0) {
                Box(Modifier.size(7.dp).background(mark.copy(alpha = 0.6f), CircleShape))
            }
            Box(Modifier.fillMaxWidth(widths[0]).height(IchthysQsNotes.BAR_HEIGHT).background(tokens.hairline, CircleShape))
            if (note.pinned) {
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_ichthys_pin),
                    contentDescription = null,
                    tint = mark.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        widths.drop(1).forEach { fraction ->
            Box(Modifier.fillMaxWidth(fraction).height(IchthysQsNotes.BAR_HEIGHT).background(tokens.hairline, CircleShape))
        }
    }
}

// endregion

// region foot

@Composable
private fun ClipboardRow(text: String, tokens: IchthysNotesTokens, onPin: () -> Unit, onDismiss: () -> Unit) {
    val dash = tokens.dotInactive
    val radius = IchthysQsNotes.CARD_RADIUS
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .drawBehind {
                    drawRoundRect(
                        color = dash,
                        cornerRadius = CornerRadius(radius.toPx()),
                        style =
                            Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                            ),
                    )
                }
                .clickable(onClick = onPin)
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ichthys_notes_clipboard),
            contentDescription = null,
            tint = tokens.accentMark,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                text = stringResource(R.string.ichthys_notes_clipboard_label),
                style = TextStyle(color = tokens.muted, fontSize = IchthysQsNotes.LABEL_SIZE, fontFamily = tokens.mono),
                maxLines = 1,
            )
            BasicText(
                text = text.replace('\n', ' '),
                style = TextStyle(color = tokens.body, fontSize = 14.sp, fontFamily = tokens.bodyFont),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier =
                Modifier.height(32.dp)
                    .clip(CircleShape)
                    .background(tokens.accent)
                    .clickable(onClick = onPin)
                    .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.ichthys_notes_clipboard_pin),
                style = TextStyle(color = tokens.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = tokens.bodyFont),
            )
        }
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.ichthys_notes_clipboard_dismiss),
                tint = tokens.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun UndoStrip(note: IchthysNote?, tokens: IchthysNotesTokens, onUndo: () -> Unit) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(note?.id) {
        if (note != null) {
            progress.snapTo(1f)
            progress.animateTo(0f, tween(IchthysNotesViewModel.UNDO_MILLIS.toInt(), easing = LinearEasing))
        }
    }
    val shape = RoundedCornerShape(IchthysQsNotes.ACTION_HEIGHT / 2)
    val mark = tokens.accentMark
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(IchthysQsNotes.ACTION_HEIGHT)
                .clip(shape)
                .background(tokens.card)
                .border(1.dp, tokens.hairline, shape)
                .drawBehind {
                    drawRect(
                        mark.copy(alpha = 0.8f),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width * progress.value, 2.dp.toPx()),
                    )
                }
                .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            text = stringResource(R.string.ichthys_notes_removed, "“" + (note?.summary ?: "") + "”"),
            style = TextStyle(color = tokens.body, fontSize = 13.5.sp, fontFamily = tokens.bodyFont),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier.height(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, mark, CircleShape)
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.ichthys_notes_undo),
                style = TextStyle(color = mark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = tokens.bodyFont),
            )
        }
    }
}

@Composable
private fun QuietLine(text: String, tokens: IchthysNotesTokens) {
    BasicText(
        text = text,
        style = TextStyle(color = tokens.muted, fontSize = 12.sp, fontFamily = tokens.mono, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        maxLines = 1,
    )
}

@Composable
private fun UnlockPill(tokens: IchthysNotesTokens, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(40.dp)
                .clip(shape)
                .border(1.dp, tokens.hairline, shape)
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ichthys_notes_lock),
            contentDescription = null,
            tint = tokens.body,
            modifier = Modifier.size(14.dp),
        )
        BasicText(
            text = stringResource(R.string.ichthys_notes_unlock),
            style = TextStyle(color = tokens.body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = tokens.bodyFont),
            maxLines = 1,
        )
    }
}

// endregion

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        .toString()
