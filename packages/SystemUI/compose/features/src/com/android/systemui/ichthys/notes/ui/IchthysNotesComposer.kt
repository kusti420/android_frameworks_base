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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.ichthys.notes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.ichthys.notes.IchthysNoteDraft
import com.android.systemui.ichthys.notes.IchthysNoteText
import com.android.systemui.ichthys.notes.IchthysNotesRepository
import com.android.systemui.ichthys.notes.IchthysNotesViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

private object Composer {
    val ACTION = 38.dp
    val TINT_DOT = 24.dp
    val MENU_HEIGHT = 36.dp
    val FIELD_MIN_LINES = 1
    val FIELD_MAX_LINES = 8
}

/**
 * IchthysOS: the composer at the foot of the notes page. Idle it is a hairline pill with a hint;
 * focused (the keyboard rises under it, quick settings stays open) it grows into a card with the
 * text and an action row: checklist item, colour, keep at the top, the count, Done.
 *
 * Checklist items are `[ ] ` / `[x] ` lines in the stored text; here they show as box glyphs
 * (output transformation), Enter after an item starts the next one and Enter on an empty item ends
 * the list (input transformation). The text selection menu (cut, copy, paste, select all) is drawn
 * by this composable above the field: the platform's floating toolbar is a popup window that a
 * SystemUI window cannot host.
 */
@Composable
fun IchthysNotesComposer(
    viewModel: IchthysNotesViewModel,
    tokens: IchthysNotesTokens,
    draft: IchthysNoteDraft,
    coloursOn: Boolean,
    locked: Boolean,
    onStored: (storedWhileLocked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { TextFieldState(draft.text) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var tintOpen by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }

    // The draft lives in the view model (a closed shade keeps it); the field mirrors it both ways.
    LaunchedEffect(draft) {
        if (state.text.toString() != draft.text) state.setTextAndPlaceCursorAtEnd(draft.text)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .collect {
                viewModel.setDraftText(it)
                full = false
            }
    }

    val expanded = focused || state.text.isNotEmpty() || draft.editingId != null
    val borderColor by
        animateColorAsState(
            if (focused) tokens.accentMark else tokens.hairline,
            label = "composerBorder",
        )
    val shape = RoundedCornerShape(if (expanded) IchthysQsNotes.CARD_RADIUS else IchthysQsNotes.PILL_HEIGHT / 2)
    val bodyStyle =
        TextStyle(
            color = tokens.text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontFamily = tokens.bodyFont,
        )

    val commit: () -> Unit = {
        scope.launch {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            val ok = viewModel.commitDraft()
            if (ok) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                tintOpen = false
                onStored(locked)
            } else {
                full = true
            }
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize()
                .background(if (expanded) tokens.card else Color.Transparent, shape)
                .border(1.dp, borderColor, shape)
                .padding(
                    start = if (expanded) 16.dp else 18.dp,
                    end = if (expanded) 12.dp else 6.dp,
                    top = if (expanded) 12.dp else 0.dp,
                    bottom = if (expanded) 10.dp else 0.dp,
                )
                .sysuiResTag("ichthys_notes_composer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.height(IchthysQsNotes.PILL_HEIGHT - 2.dp)),
        ) {
            if (!expanded) {
                Box(Modifier.width(2.dp).height(18.dp).background(tokens.accentMark, RoundedCornerShape(1.dp)))
            }
            Box(Modifier.weight(1f)) {
                TextMenuHost(tokens) {
                BasicTextField(
                    state = state,
                    modifier =
                        Modifier.fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                focused = it.isFocused
                                viewModel.setTextInputActive(it.isFocused)
                                if (!it.isFocused) tintOpen = false
                            },
                    textStyle = bodyStyle,
                    cursorBrush = SolidColor(tokens.accentMark),
                    lineLimits =
                        TextFieldLineLimits.MultiLine(Composer.FIELD_MIN_LINES, Composer.FIELD_MAX_LINES),
                    inputTransformation = ChecklistInput,
                    outputTransformation = ChecklistGlyphs,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorator = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.text.isEmpty()) {
                                BasicText(
                                    text = stringResource(R.string.ichthys_notes_composer_hint),
                                    style = bodyStyle.copy(color = tokens.muted),
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                }
            }
            if (!expanded) {
                GlyphButton(
                    painter = painterResource(R.drawable.ic_ichthys_notes_list),
                    description = stringResource(R.string.ichthys_notes_make_item),
                    tokens = tokens,
                    outlined = false,
                    onClick = {
                        state.toggleItemOnCurrentLine()
                        focusRequester.requestFocus()
                        keyboard?.show()
                    },
                )
            }
        }

        if (expanded) {
            if (tintOpen && coloursOn) {
                TintRow(
                    tokens = tokens,
                    selected = draft.tint,
                    onSelect = {
                        viewModel.setDraftTint(it)
                        tintOpen = false
                    },
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (draft.editingId != null) {
                        GlyphButton(
                            vector = Icons.Rounded.Close,
                            description = stringResource(R.string.ichthys_notes_cancel_edit),
                            tokens = tokens,
                            onClick = {
                                keyboard?.hide()
                                focusManager.clearFocus(force = true)
                                viewModel.clearDraft()
                            },
                        )
                    }
                    GlyphButton(
                        painter = painterResource(R.drawable.ic_ichthys_notes_list),
                        description = stringResource(R.string.ichthys_notes_make_item),
                        tokens = tokens,
                        onClick = { state.toggleItemOnCurrentLine() },
                    )
                    if (coloursOn) {
                        GlyphButton(
                            painter = painterResource(R.drawable.ic_ichthys_notes_tint),
                            description = stringResource(R.string.ichthys_notes_tint),
                            tokens = tokens,
                            tint = if (draft.tint > 0) tokens.markColor(draft.tint) else null,
                            onClick = { tintOpen = true },
                        )
                    }
                    GlyphButton(
                        painter = painterResource(R.drawable.ic_ichthys_pin),
                        description = stringResource(R.string.ichthys_notes_pin_toggle),
                        tokens = tokens,
                        filled = draft.pinned,
                        onClick = {
                            haptics.performHapticFeedback(
                                if (draft.pinned) HapticFeedbackType.ToggleOff
                                else HapticFeedbackType.ToggleOn
                            )
                            viewModel.toggleDraftPinned()
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    val count = state.text.length
                    BasicText(
                        text =
                            if (full) stringResource(R.string.ichthys_notes_full)
                            else if (count > 0) count.toString() else "",
                        style =
                            TextStyle(
                                color =
                                    if (full || count >= IchthysNotesRepository.MAX_TEXT_CHARS * 9 / 10)
                                        tokens.accentMark
                                    else tokens.muted,
                                fontSize = IchthysQsNotes.LABEL_SIZE,
                                fontFamily = tokens.mono,
                            ),
                        maxLines = 1,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    DonePill(tokens = tokens, enabled = state.text.isNotBlank() || draft.editingId != null, onClick = commit)
                }
            }
        }
    }
}

/**
 * IchthysOS: supplies the text field's selection menu as a row drawn right above it, in place of
 * the platform's floating toolbar (a popup window, which this window cannot host) and of the
 * platform's dropdown. The labels come from the field (localised Cut / Copy / Paste / Select all).
 */
@Composable
private fun TextMenuHost(tokens: IchthysNotesTokens, content: @Composable () -> Unit) {
    val provider = remember { InlineMenuProvider() }
    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides provider,
        LocalTextContextMenuDropdownProvider provides provider,
    ) {
        Box {
            content()
            provider.open?.let { InlineTextMenu(it.session, it.dataProvider, tokens) }
        }
    }
}

/**
 * The field asks this provider to show its menu and suspends until the menu closes; the open
 * session is snapshot state that [TextMenuHost] renders inline. Cancellation (the selection went
 * away) takes the row down too. (Foundation's own ProvideBasicTextContextMenu is internal.)
 */
private class InlineMenuProvider : TextContextMenuProvider {
    class OpenMenu(val session: TextContextMenuSession, val dataProvider: TextContextMenuDataProvider)

    var open by mutableStateOf<OpenMenu?>(null)
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val closed = CompletableDeferred<Unit>()
        val session =
            object : TextContextMenuSession {
                override fun close() {
                    closed.complete(Unit)
                }
            }
        val menu = OpenMenu(session, dataProvider)
        open = menu
        try {
            closed.await()
        } finally {
            if (open === menu) open = null
        }
    }
}

@Composable
private fun InlineTextMenu(
    session: TextContextMenuSession,
    dataProvider: TextContextMenuDataProvider,
    tokens: IchthysNotesTokens,
) {
    val items = dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
    if (items.isEmpty()) return
    val shape = RoundedCornerShape(Composer.MENU_HEIGHT / 2)
    Row(
        modifier =
            Modifier.offset(y = -(Composer.MENU_HEIGHT + 8.dp))
                .zIndex(1f)
                .height(Composer.MENU_HEIGHT)
                .background(tokens.card, shape)
                .border(1.dp, tokens.hairline, shape)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            BasicText(
                text = item.label,
                style =
                    TextStyle(
                        color = tokens.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = tokens.bodyFont,
                    ),
                maxLines = 1,
                modifier =
                    Modifier.clip(shape)
                        .clickable { item.onClick(session) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TintRow(tokens: IchthysNotesTokens, selected: Int, onSelect: (Int) -> Unit) {
    val label = stringResource(R.string.ichthys_notes_tint)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(Composer.ACTION).semantics { contentDescription = label },
    ) {
        for (i in 0..IchthysNotesRepository.TINT_COUNT) {
            val isSelected = i == selected
            Box(
                Modifier.size(Composer.TINT_DOT)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) tokens.text else tokens.hairline,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(i) }
                    .padding(4.dp)
                    .background(
                        if (i == 0) Color.Transparent else tokens.markColor(i),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (i == 0) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ichthys_notes_tint_none),
                        tint = tokens.muted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/** A round hairline glyph button of the composer; [filled] = accent fill (a toggle that is on). */
@Composable
internal fun GlyphButton(
    description: String,
    tokens: IchthysNotesTokens,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    vector: androidx.compose.ui.graphics.vector.ImageVector? = null,
    filled: Boolean = false,
    outlined: Boolean = true,
    tint: Color? = null,
    size: androidx.compose.ui.unit.Dp = Composer.ACTION,
) {
    val content = if (filled) tokens.onAccent else tint ?: tokens.body
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(if (filled) tokens.accent else Color.Transparent)
                .border(1.dp, if (outlined && !filled) tokens.hairline else Color.Transparent, CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Icon(painter = painter, contentDescription = description, tint = content, modifier = Modifier.size(18.dp))
        } else if (vector != null) {
            Icon(imageVector = vector, contentDescription = description, tint = content, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DonePill(tokens: IchthysNotesTokens, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Composer.ACTION / 2)
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier =
            Modifier.height(Composer.ACTION)
                .clip(shape)
                .background(tokens.accent.copy(alpha = tokens.accent.alpha * alpha))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = tokens.onAccent.copy(alpha = alpha),
            modifier = Modifier.size(16.dp),
        )
        BasicText(
            text = stringResource(R.string.ichthys_notes_done),
            style =
                TextStyle(
                    color = tokens.onAccent.copy(alpha = alpha),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = tokens.bodyFont,
                ),
            maxLines = 1,
        )
    }
}

// region checklist editing

/** Makes the line under the cursor an item, or a plain line again. */
private fun TextFieldState.toggleItemOnCurrentLine() {
    edit {
        val text = asCharSequence()
        val cursor = selection.start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        val line = text.subSequence(lineStart, lineEnd).toString()
        val prefixLength = itemPrefixLength(line)
        if (prefixLength > 0) {
            replace(lineStart, lineStart + prefixLength, "")
        } else {
            insert(lineStart, IchthysNoteText.OPEN)
        }
    }
}

/** Length of the `[ ] ` / `[x] ` / `[]` prefix of [line] (with its trailing space), or 0. */
private fun itemPrefixLength(line: String): Int {
    val t = line
    return when {
        t.startsWith("[x] ", ignoreCase = true) || t.startsWith("[ ] ") -> 4
        t.startsWith("[x]", ignoreCase = true) || t.startsWith("[ ]") -> 3
        t.startsWith("[] ") -> 3
        t.startsWith("[]") -> 2
        else -> 0
    }
}

/**
 * Enter after an item continues the list; Enter on an empty item ends it. "- " or "* " at the
 * start of a line becomes an item.
 */
private val ChecklistInput = InputTransformation {
    val changes = changes
    if (changes.changeCount != 1) return@InputTransformation
    val range = changes.getRange(0)
    if (range.length != 1) return@InputTransformation
    val text = asCharSequence()
    val inserted = text[range.start]
    val lineStart = text.lastIndexOf('\n', range.start - 1).let { if (it < 0) 0 else it + 1 }
    when (inserted) {
        '\n' -> {
            val previous = text.subSequence(lineStart, range.start).toString()
            val prefix = itemPrefixLength(previous)
            if (prefix == 0) return@InputTransformation
            if (previous.substring(prefix).isBlank()) {
                // An empty item: Enter removes its box and ends the list, no new line.
                replace(lineStart, range.end, "")
            } else {
                insert(range.end, IchthysNoteText.OPEN)
            }
        }
        ' ' -> {
            val line = text.subSequence(lineStart, range.end).toString()
            if (line == "- " || line == "* ") replace(lineStart, range.end, IchthysNoteText.OPEN)
        }
        else -> Unit
    }
}

/** Shows `[ ] ` as a box and `[x] ` as a ticked box while editing; the stored text keeps the brackets. */
private val ChecklistGlyphs = OutputTransformation {
    val text = asCharSequence().toString()
    // Back to front, so earlier offsets stay valid after each replacement.
    var lineStart = text.length
    while (lineStart > 0) {
        val previousBreak = text.lastIndexOf('\n', lineStart - 1)
        val start = if (previousBreak < 0) 0 else previousBreak + 1
        val line = text.substring(start, lineStart)
        when {
            line.startsWith("[x] ", ignoreCase = true) -> replace(start, start + 4, "☑ ")
            line.startsWith("[ ] ") -> replace(start, start + 4, "☐ ")
        }
        if (previousBreak < 0) break
        lineStart = previousBreak
    }
}

// endregion
