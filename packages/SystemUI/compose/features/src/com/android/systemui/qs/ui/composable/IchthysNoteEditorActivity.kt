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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.android.compose.animation.Easings
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
 * and an unsaved draft is dropped then). Back or a tap outside the card stores a changed draft; the
 * card's close button discards it.
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
        // IchthysOS: no window animation either way: the scrim fades and the card rises in
        // Compose (see EditorScreen), and the close plays before finish().
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
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
    const val ENTER_MILLIS = 250
    const val EXIT_MILLIS = 180
}

@Composable
private fun EditorScreen(
    noteId: Long?,
    repository: IchthysQsNotesRepository,
    onFinish: () -> Unit,
) {
    val tokens = rememberIchthysNotesTokens()
    val shoal = rememberShoalQsEnabled()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // An existing note is read from the store (not passed in the intent: note text stays out of
    // system_server); gone in the meantime = nothing to edit.
    val start by
        produceState(if (noteId == null) EditorStart(null) else null, noteId) {
            if (noteId != null) {
                val note = repository.find(noteId)
                if (note == null) onFinish() else value = EditorStart(note)
            }
        }

    // IchthysOS: enter = scrim fades in while the card rises a little from below, scaling up from
    // 94 % and fading in (250 ms, decelerating); exit = the reverse, quicker, then finish().
    val shown = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(start) { if (start != null && !closing) shown.targetState = true }
    LaunchedEffect(closing, shown.isIdle) {
        if (closing && shown.isIdle && !shown.currentState) onFinish()
    }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            hideKeyboard(keyboard, focusManager)
            // Nothing on screen yet (an existing note still loading): finish right away.
            if (!shown.currentState && shown.isIdle) onFinish() else shown.targetState = false
        }
    }
    val scrimAlpha by
        animateFloatAsState(
            targetValue = if (shown.targetState) EditorWindow.SCRIM_ALPHA else 0f,
            animationSpec =
                tween(
                    if (shown.targetState) EditorWindow.ENTER_MILLIS else EditorWindow.EXIT_MILLIS
                ),
            label = "noteEditorScrim",
        )

    val s = start
    val draft = remember(s) { s?.let { IchthysNoteDraft(it.note) } }
    // Store the draft and close; on a full store the card stays open with the hint.
    val pin: () -> Unit = {
        if (draft != null && !closing) {
            scope.launch { if (repository.save(draft.toNote())) close() else draft.full = true }
        }
    }
    // Back and a tap outside the card keep what was written (Keep-style); the close button
    // discards. An untouched note just closes.
    val done: () -> Unit = { if (draft != null && draft.isChanged) pin() else close() }
    BackHandler(enabled = !closing) { done() }
    // The scrim's tap detector outlives compositions; it must call the current [done] (an existing
    // note's draft only exists once the note has loaded).
    val currentDone by rememberUpdatedState(done)

    val scrim = if (shoal) ShoalQsColors.Ink else Color.Black
    Box(
        modifier =
            Modifier.fillMaxSize()
                .drawBehind { drawRect(scrim.copy(alpha = scrimAlpha)) }
                .pointerInput(Unit) { detectTapGestures { currentDone() } }
                // Includes the keyboard: with edge-to-edge the IME inset animates frame by frame,
                // so the card rides up above the keyboard with it.
                .safeDrawingPadding()
                .padding(horizontal = EditorWindow.GUTTER),
        contentAlignment = Alignment.Center,
    ) {
        if (s != null && draft != null) {
            AnimatedVisibility(
                visibleState = shown,
                enter =
                    fadeIn(tween(EditorWindow.ENTER_MILLIS, easing = Easings.EmphasizedDecelerate)) +
                        scaleIn(
                            tween(EditorWindow.ENTER_MILLIS, easing = Easings.EmphasizedDecelerate),
                            initialScale = 0.94f,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                        ) +
                        slideInVertically(
                            tween(EditorWindow.ENTER_MILLIS, easing = Easings.EmphasizedDecelerate)
                        ) {
                            it / 10
                        },
                exit =
                    fadeOut(tween(EditorWindow.EXIT_MILLIS, easing = Easings.EmphasizedAccelerate)) +
                        scaleOut(
                            tween(EditorWindow.EXIT_MILLIS, easing = Easings.EmphasizedAccelerate),
                            targetScale = 0.96f,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                        ) +
                        slideOutVertically(
                            tween(EditorWindow.EXIT_MILLIS, easing = Easings.EmphasizedAccelerate)
                        ) {
                            it / 14
                        },
            ) {
                Column(
                    Modifier.widthIn(max = EditorWindow.MAX_WIDTH)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = EditorWindow.GUTTER)
                ) {
                    IchthysQsNoteEditor(
                        draft = draft,
                        tokens = tokens,
                        onPin = pin,
                        onUnpin =
                            s.note?.let { note ->
                                {
                                    if (!closing) {
                                        scope.launch {
                                            repository.remove(note.id)
                                            close()
                                        }
                                    }
                                }
                            },
                        onDiscard = close,
                        // Taps on the card's own empty space must not reach the scrim.
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
                    )
                }
            }
        }
    }
}

private fun hideKeyboard(keyboard: SoftwareKeyboardController?, focusManager: FocusManager) {
    keyboard?.hide()
    focusManager.clearFocus(force = true)
}
