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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.notes.IchthysNote
import com.android.systemui.qs.notes.IchthysNoteKind
import com.android.systemui.qs.notes.IchthysQsNotesViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.create
import kotlinx.coroutines.launch

/** IchthysOS: what the notes editor opens on. */
sealed interface IchthysNoteEditorTarget {
    data object New : IchthysNoteEditorTarget

    data class Edit(val note: IchthysNote) : IchthysNoteEditorTarget
}

/**
 * IchthysOS: the editor of the quick settings notes page, a SystemUI dialog over the shade (the
 * same host EnableWidgetDialog uses). Shown while [target] is set; it dismisses on device lock.
 */
@Composable
fun IchthysQsNoteEditorDialog(
    target: IchthysNoteEditorTarget?,
    viewModel: IchthysQsNotesViewModel,
    tokens: IchthysNotesTokens,
    onDismiss: () -> Unit,
) {
    var dialog: ComponentSystemUIDialog? by remember { mutableStateOf(null) }
    val context = LocalView.current.context

    DisposableEffect(target) {
        if (target != null) {
            dialog =
                viewModel.dialogFactory.create(context = context) { d ->
                    NoteEditor(
                        initial = (target as? IchthysNoteEditorTarget.Edit)?.note,
                        tokens = tokens,
                        onPin = { note -> viewModel.repository.save(note) },
                        onUnpin =
                            (target as? IchthysNoteEditorTarget.Edit)?.let { edit ->
                                { viewModel.repository.remove(edit.note.id) }
                            },
                        onClose = { d.dismiss() },
                    )
                }
            dialog?.apply {
                setCancelable(true)
                setCanceledOnTouchOutside(true)
                setOnDismissListener { onDismiss() }
                show()
            }
        }
        onDispose {
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            dialog = null
        }
    }
}

private object Editor {
    val RADIUS = 24.dp
    val PADDING = 20.dp
    val CHIP_HEIGHT = 28.dp
    val DOT = 18.dp
    val FIELD_MIN_HEIGHT = 96.dp
}

@Composable
private fun NoteEditor(
    initial: IchthysNote?,
    tokens: IchthysNotesTokens,
    onPin: suspend (IchthysNote) -> Boolean,
    onUnpin: (suspend () -> Boolean)?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(initial?.kind ?: IchthysNoteKind.NOTE) }
    var color by remember { mutableStateOf(initial?.color ?: 0) }
    var value by remember {
        val text = initial?.text ?: ""
        mutableStateOf(TextFieldValue(text, selection = TextRange(text.length)))
    }
    var full by remember { mutableStateOf(false) }
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

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(Editor.RADIUS))
                .background(tokens.card)
                .border(1.dp, tokens.hairline, RoundedCornerShape(Editor.RADIUS))
                .padding(Editor.PADDING),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText(
            text =
                stringResource(
                    if (initial == null) R.string.ichthys_qs_notes_editor_new
                    else R.string.ichthys_qs_notes_editor_edit
                ),
            style =
                TextStyle(
                    color = tokens.text,
                    fontSize = IchthysQsNotes.TITLE_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = tokens.display,
                ),
        )

        // Label chips.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IchthysNoteKind.entries.forEach { k ->
                val selected = k == kind
                val shape = RoundedCornerShape(Editor.CHIP_HEIGHT / 2)
                Box(
                    modifier =
                        Modifier.height(Editor.CHIP_HEIGHT)
                            .clip(shape)
                            .background(if (selected) tokens.accent else Color.Transparent)
                            .border(1.dp, if (selected) tokens.accent else tokens.hairline, shape)
                            .clickable { kind = k }
                            .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(k.labelRes()),
                        style = labelStyle.copy(color = if (selected) tokens.onAccent else tokens.text),
                    )
                }
            }
        }

        // Colour dots.
        val colourLabel = stringResource(R.string.ichthys_qs_notes_editor_colour)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { contentDescription = colourLabel },
        ) {
            IchthysQsNotes.Colors.indices.forEach { i ->
                val selected = i == color
                Box(
                    Modifier.size(Editor.DOT)
                        .clip(CircleShape)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) tokens.text else tokens.hairline,
                            shape = CircleShape,
                        )
                        .padding(3.dp)
                        .background(tokens.noteColor(i), CircleShape)
                        .clickable { color = i }
                )
            }
        }

        // Text.
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                full = false
            },
            textStyle = bodyStyle,
            cursorBrush = SolidColor(tokens.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 3,
            maxLines = 10,
            modifier =
                Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, tokens.hairline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        BasicText(
                            text = stringResource(R.string.ichthys_qs_notes_editor_hint),
                            style = bodyStyle.copy(color = tokens.muted),
                        )
                    }
                    inner()
                }
            },
        )

        if (full) {
            BasicText(
                text = stringResource(R.string.ichthys_qs_notes_editor_full),
                style = labelStyle.copy(color = tokens.accent),
            )
        }

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
                    onClick = {
                        scope.launch {
                            onUnpin()
                            onClose()
                        }
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                text = stringResource(R.string.cancel),
                filled = false,
                tokens = tokens,
                onClick = onClose,
            )
            val text = value.text.trim()
            if (text.isNotEmpty()) {
                PillButton(
                    text = stringResource(R.string.ichthys_qs_notes_editor_pin),
                    filled = true,
                    tokens = tokens,
                    onClick = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val note =
                                IchthysNote(
                                    id = initial?.id ?: now,
                                    text = text,
                                    kind = kind,
                                    color = color,
                                    createdAt = initial?.createdAt ?: now,
                                )
                            if (onPin(note)) onClose() else full = true
                        }
                    },
                )
            }
        }
    }
}
