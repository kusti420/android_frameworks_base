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

package com.android.systemui.qs.notes

import android.content.Context
import android.content.Intent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.panels.domain.interactor.InFirstPageInteractor
import com.android.systemui.qs.ui.composable.IchthysNoteEditorActivity
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IchthysOS: what the quick settings notes page needs beyond the notes themselves: whether the
 * device is entered (locked = frosted placeholders), how to unlock and come back to the page, and
 * the p!n hand-off.
 */
@SysUISingleton
class IchthysQsNotesViewModel
@Inject
constructor(
    @Application private val context: Context,
    val repository: IchthysQsNotesRepository,
    deviceEntryInteractor: DeviceEntryInteractor,
    private val activityStarter: Lazy<ActivityStarter>,
    private val shadeController: Lazy<ShadeController>,
    private val userTracker: UserTracker,
    private val inFirstPageInteractor: InFirstPageInteractor,
) {
    val notes: StateFlow<List<IchthysNote>>
        get() = repository.notes

    /** False while the lock screen still has to be dismissed: no note text may be rendered. */
    val isDeviceEntered: StateFlow<Boolean> = deviceEntryInteractor.isDeviceEntered

    private val _openNotesWhenShown = MutableStateFlow(false)
    /** Set by [unlockToOpen]: the next time quick settings is idle, land on the notes page. */
    val openNotesWhenShown: StateFlow<Boolean> = _openNotesWhenShown.asStateFlow()

    fun start() = repository.start()

    /**
     * IchthysOS: whether the tiles are on screen on their first page. False on the notes page:
     * the shade transition then fades the tiles instead of moving them as shared elements (see
     * IchthysQsNotesPagerHost).
     */
    fun setTilesOnFirstPage(onFirstPage: Boolean) {
        inFirstPageInteractor.inFirstPage = onFirstPage
    }

    fun consumeOpenNotes() {
        _openNotesWhenShown.value = false
    }

    /**
     * "Unlock to open": dismisses the keyguard (bouncer if needed) and, once it is gone, expands
     * quick settings again on the notes page. The shade collapses with the keyguard in between;
     * reopening it is the only way back without a second scene-container entry point.
     */
    fun unlockToOpen() {
        activityStarter
            .get()
            .dismissKeyguardThenExecute(
                {
                    _openNotesWhenShown.value = true
                    shadeController.get().animateExpandQs()
                    false /* if the dismiss should be deferred */
                },
                null /* cancelAction */,
                true, /* afterKeyguardGone */
            )
    }

    /**
     * IchthysOS: opens the note editor for [noteId], or for a new note when null. The editor is
     * [IchthysNoteEditorActivity], not a dialog over the shade: a SystemUI dialog window could not
     * host the text selection toolbar (BadTokenException from RemoteFloatingToolbarPopup) nor sit
     * under the keyboard with expanded quick settings. The shade collapses for the launch, like
     * every other QS launch. Only offered while the device is entered (the page shows "Unlock to
     * open" otherwise); CLEAR_TASK replaces an editor left open behind.
     */
    fun openEditor(noteId: Long?) {
        val intent =
            Intent(context, IchthysNoteEditorActivity::class.java).apply {
                if (noteId != null) putExtra(IchthysNoteEditorActivity.EXTRA_NOTE_ID, noteId)
            }
        activityStarter
            .get()
            .startActivity(
                intent,
                false, /* onlyProvisioned */
                true, /* dismissShade */
                Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
    }
}
