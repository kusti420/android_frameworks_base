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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.qs.notes.IchthysNote
import com.android.systemui.qs.notes.IchthysNoteKind
import com.android.systemui.qs.notes.IchthysQsNotesRepository
import com.android.systemui.res.R

private object Editor {
    val RADIUS = 28.dp
    val PADDING = 20.dp
    val GAP = 16.dp
    val TITLE_SIZE = 22.sp
    val CHIP_HEIGHT = 30.dp
    val DOT = 22.dp
    val CLOSE = 36.dp
    val FIELD_RADIUS = 16.dp
    val FIELD_PADDING = 14.dp
    val FIELD_MIN_HEIGHT = 120.dp
    const val MAX_CHARS = IchthysQsNotesRepository.MAX_TEXT_CHARS
    /** The count turns accent from here on, so the limit never comes as a surprise. */
    const val COUNT_WARN = MAX_CHARS * 9 / 10
}

/**
 * IchthysOS: what the editor is editing, hoisted so the host can save it on Back or a tap outside
 * the card (Keep-style: closing keeps what was written; the close button discards).
 */
@Stable
internal class IchthysNoteDraft(val initial: IchthysNote?) {
    var kind by mutableStateOf(initial?.kind ?: IchthysNoteKind.NOTE)
    var color by mutableIntStateOf(initial?.color ?: 0)
    var value by
        mutableStateOf(
            (initial?.text ?: "").let { TextFieldValue(it, selection = TextRange(it.length)) }
        )
    /** Set when the store refused the note (too many notes or too much text). */
    var full by mutableStateOf(false)

    val text: String
        get() = value.text.trim()

    /** True when closing should store something: a new non-empty note, or a changed one. */
    val isChanged: Boolean
        get() =
            text.isNotEmpty() &&
                (initial == null ||
                    text != initial.text.trim() ||
                    kind != initial.kind ||
                    color != initial.color)

    fun toNote(now: Long = System.currentTimeMillis()): IchthysNote =
        IchthysNote(
            id = initial?.id ?: now,
            text = text,
            kind = kind,
            color = color,
            createdAt = initial?.createdAt ?: now,
        )
}

/**
 * IchthysOS: the editor card of the quick settings notes page: title and close, label chips,
 * colour dots, the text with its pin time and character count, then Unpin and Pin. Hosted by
 * [IchthysNoteEditorActivity] (an activity window, so the text selection toolbar and the keyboard
 * work; see there), which also runs its enter and exit animation and saves on Back.
 *
 * @param onPin stores the draft and closes, or marks it [IchthysNoteDraft.full].
 * @param onUnpin removes the note being edited and closes; null for a new note.
 * @param onDiscard closes without storing anything.
 */
@Composable
internal fun IchthysQsNoteEditor(
    draft: IchthysNoteDraft,
    tokens: IchthysNotesTokens,
    onPin: () -> Unit,
    onUnpin: (() -> Unit)?,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val labelStyle =
        TextStyle(color = tokens.muted, fontSize = IchthysQsNotes.LABEL_SIZE, fontFamily = tokens.mono)
    val bodyStyle =
        TextStyle(
            color = tokens.text,
            fontSize = IchthysQsNotes.TEXT_SIZE,
            lineHeight = IchthysQsNotes.TEXT_LINE_HEIGHT,
            fontFamily = tokens.body,
        )
    val cardShape = RoundedCornerShape(Editor.RADIUS)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(tokens.card)
                .border(1.dp, tokens.hairline, cardShape)
                .padding(Editor.PADDING),
        verticalArrangement = Arrangement.spacedBy(Editor.GAP),
    ) {
        // Title (display face) and close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(IchthysQsNotes.COLOR_DOT + 2.dp)
                    .background(tokens.noteColor(draft.color), CircleShape)
            )
            Spacer(Modifier.size(10.dp))
            BasicText(
                text =
                    stringResource(
                        if (draft.initial == null) R.string.ichthys_qs_notes_editor_new
                        else R.string.ichthys_qs_notes_editor_edit
                    ),
                style =
                    TextStyle(
                        color = tokens.text,
                        fontSize = Editor.TITLE_SIZE,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = tokens.display,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier.size(Editor.CLOSE)
                        .clip(CircleShape)
                        .border(1.dp, tokens.hairline, CircleShape)
                        .clickable(onClick = onDiscard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = tokens.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Label chips.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IchthysNoteKind.entries.forEach { k ->
                val selected = k == draft.kind
                val shape = RoundedCornerShape(Editor.CHIP_HEIGHT / 2)
                Box(
                    modifier =
                        Modifier.height(Editor.CHIP_HEIGHT)
                            .clip(shape)
                            .background(if (selected) tokens.accent else Color.Transparent)
                            .border(1.dp, if (selected) tokens.accent else tokens.hairline, shape)
                            .clickable { draft.kind = k }
                            .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(k.labelRes()),
                        style = labelStyle.copy(color = if (selected) tokens.onAccent else tokens.text),
                    )
                }
            }
        }

        // Colour dots: the selected one carries a ring in the text colour.
        val colourLabel = stringResource(R.string.ichthys_qs_notes_editor_colour)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { contentDescription = colourLabel },
        ) {
            IchthysQsNotes.Colors.indices.forEach { i ->
                val selected = i == draft.color
                Box(
                    Modifier.size(Editor.DOT)
                        .clip(CircleShape)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) tokens.text else tokens.hairline,
                            shape = CircleShape,
                        )
                        .clickable { draft.color = i }
                        .padding(4.dp)
                        .background(tokens.noteColor(i), CircleShape)
                )
            }
        }

        // Text, then its pin time (an existing note) and the character count.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fieldShape = RoundedCornerShape(Editor.FIELD_RADIUS)
            BasicTextField(
                value = draft.value,
                onValueChange = {
                    // Capped here, so nothing typed or pasted is cut off silently by the store.
                    draft.value =
                        if (it.text.length <= Editor.MAX_CHARS) it
                        else {
                            val text = it.text.take(Editor.MAX_CHARS)
                            TextFieldValue(text, selection = TextRange(text.length))
                        }
                    draft.full = false
                },
                textStyle = bodyStyle,
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 4,
                maxLines = 10,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = Editor.FIELD_MIN_HEIGHT)
                        .focusRequester(focusRequester)
                        .clip(fieldShape)
                        .border(1.dp, tokens.hairline, fieldShape)
                        .padding(Editor.FIELD_PADDING),
                decorationBox = { inner ->
                    Box {
                        if (draft.value.text.isEmpty()) {
                            BasicText(
                                text = stringResource(R.string.ichthys_qs_notes_editor_hint),
                                style = bodyStyle.copy(color = tokens.muted),
                            )
                        }
                        inner()
                    }
                },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                val initial = draft.initial
                if (initial != null) {
                    BasicText(
                        text = ichthysNoteTime(initial.createdAt),
                        style = labelStyle,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                val count = draft.value.text.length
                BasicText(
                    text = "$count / ${Editor.MAX_CHARS}",
                    style =
                        labelStyle.copy(
                            color = if (count >= Editor.COUNT_WARN) tokens.accent else tokens.muted
                        ),
                    maxLines = 1,
                )
            }
        }

        if (draft.full) {
            BasicText(
                text = stringResource(R.string.ichthys_qs_notes_editor_full),
                style = labelStyle.copy(color = tokens.accent),
            )
        }

        // Unpin (an existing note) on the left, Pin on the right; Pin waits for some text.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IchthysQsNotes.CARD_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onUnpin != null) {
                PillButton(
                    text = stringResource(R.string.ichthys_qs_notes_unpin),
                    filled = false,
                    tokens = tokens,
                    icon = painterResource(R.drawable.ic_keep_off),
                    onClick = onUnpin,
                )
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                text = stringResource(R.string.ichthys_qs_notes_editor_pin),
                filled = true,
                tokens = tokens,
                icon = painterResource(R.drawable.ic_ichthys_pin),
                enabled = draft.text.isNotEmpty(),
                onClick = onPin,
            )
        }
    }
}
