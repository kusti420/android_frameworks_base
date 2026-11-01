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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.android.compose.theme.PlatformTheme
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.qs.notes.IchthysNote
import com.android.systemui.qs.notes.IchthysQsNotesRepository
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ShoalQsColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsEnabled
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * IchthysOS: the editor of the quick settings notes page, as a small translucent activity.
 *
 * It used to be a SystemUI dialog over the expanded shade. Such a window has no activity token:
 * the first long-press / selection / cursor-handle tap in its text field crashed SystemUI, because
 * the platform's floating text toolbar (RemoteFloatingToolbarPopup, a PopupWindow) could not be
 * added to it (BadTokenException), and keyboard + expanded quick settings did not get along either.
 * Hiding Compose's LocalTextToolbar did not help: the toolbar also comes from the view's floating
 * action mode and the newer foundation text context menu. An activity window is what every app's
 * text field runs in, so selection toolbar, context menu, IME and paste all just work here.
 *
 * Launched by IchthysQsNotesViewModel.openEditor with the shade collapsing; [EXTRA_NOTE_ID] picks
 * the note to edit, no extra = a new note. Reads and writes through [IchthysQsNotesRepository], so
 * the notes page updates the moment Pin / Unpin lands. The page offers editing only while the
 * device is entered, and the editor finishes as soon as it is not (it is not shown when locked,
 * and an unsaved draft is dropped, as the dialog did).
 */
class IchthysNoteEditorActivity
@Inject
constructor(
    private val repository: IchthysQsNotesRepository,
    private val deviceEntryInteractor: DeviceEntryInteractor,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!deviceEntryInteractor.isDeviceEntered.value) {
            finish()
            return
        }
        enableEdgeToEdge()
        // Lives as long as the activity (not only while started), so an editor left behind via
        // Home is closed on lock too.
        lifecycleScope.launch {
            deviceEntryInteractor.isDeviceEntered.first { !it }
            finish()
        }

        val noteId =
            if (intent.hasExtra(EXTRA_NOTE_ID)) intent.getLongExtra(EXTRA_NOTE_ID, 0L) else null
        setContent {
            PlatformTheme {
                EditorScreen(noteId = noteId, repository = repository, onFinish = ::finish)
            }
        }
    }

    companion object {
        /** Long: id of the note to edit. Absent = a new note. */
        const val EXTRA_NOTE_ID = "com.android.systemui.qs.notes.extra.NOTE_ID"
    }
}

/** What the editor opens on, once known: [note] null = a new note. */
private class EditorStart(val note: IchthysNote?)

private object EditorWindow {
    val MAX_WIDTH = 480.dp
    val GUTTER = 16.dp
    const val SCRIM_ALPHA = 0.6f
}

@Composable
private fun EditorScreen(
    noteId: Long?,
    repository: IchthysQsNotesRepository,
    onFinish: () -> Unit,
) {
    val tokens = rememberIchthysNotesTokens()
    val shoal = rememberShoalQsEnabled()
    // An existing note is read from the store (not passed in the intent: note text stays out of
    // system_server); gone in the meantime = nothing to edit.
    val start by
        produceState(if (noteId == null) EditorStart(null) else null, noteId) {
            if (noteId != null) {
                val note = repository.find(noteId)
                if (note == null) onFinish() else value = EditorStart(note)
            }
        }

    val scrim = if (shoal) ShoalQsColors.Ink else Color.Black
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(scrim.copy(alpha = EditorWindow.SCRIM_ALPHA))
                // Tap outside the card = Cancel (as the dialog's cancel-on-touch-outside); Back too.
                .pointerInput(Unit) { detectTapGestures { onFinish() } }
                .safeDrawingPadding()
                .padding(horizontal = EditorWindow.GUTTER),
        contentAlignment = Alignment.Center,
    ) {
        val s = start
        if (s != null) {
            Column(
                Modifier.widthIn(max = EditorWindow.MAX_WIDTH)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = EditorWindow.GUTTER)
            ) {
                IchthysQsNoteEditor(
                    initial = s.note,
                    tokens = tokens,
                    onPin = { note -> repository.save(note) },
                    onUnpin = s.note?.let { note -> { repository.remove(note.id) } },
                    onClose = onFinish,
                    // Taps on the card's own empty space must not reach the scrim.
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
                )
            }
        }
    }
}
