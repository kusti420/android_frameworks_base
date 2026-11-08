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

package com.android.systemui.ichthys.notes

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.panels.domain.interactor.InFirstPageInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.user.utils.UserScopedService
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IchthysOS: what the composer is holding, kept here so a closed shade does not lose it. */
data class IchthysNoteDraft(
    val text: String = "",
    val tint: Int = 0,
    val pinned: Boolean = false,
    /** The note being edited, null for a new one. */
    val editingId: Long? = null,
    val editingCreatedAt: Long = 0L,
    val editingOnLock: Boolean = false,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && editingId == null
}

/**
 * IchthysOS: everything the quick settings notes page does beyond showing notes: the composer's
 * draft and the shade window's keyboard focus, ticking and pinning, removal with undo, the
 * actions behind tappable text, the clipboard suggestion, and the lock screen hand-off.
 */
@SysUISingleton
class IchthysNotesViewModel
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    val repository: IchthysNotesRepository,
    deviceEntryInteractor: DeviceEntryInteractor,
    private val activityStarter: Lazy<ActivityStarter>,
    private val shadeController: Lazy<ShadeController>,
    private val userTracker: UserTracker,
    private val inFirstPageInteractor: InFirstPageInteractor,
    private val windowController: Lazy<NotificationShadeWindowController>,
    private val clipboardProvider: UserScopedService<ClipboardManager>,
) {
    val notes: StateFlow<List<IchthysNote>>
        get() = repository.notes

    val storeReady: StateFlow<Boolean>
        get() = repository.ready

    /** False while the lock screen still has to be dismissed: no note text may be rendered. */
    val isDeviceEntered: StateFlow<Boolean> = deviceEntryInteractor.isDeviceEntered

    private val _draft = MutableStateFlow(IchthysNoteDraft())
    val draft: StateFlow<IchthysNoteDraft> = _draft.asStateFlow()

    private val _pendingRemoval = MutableStateFlow<IchthysNote?>(null)
    /** A note removed in the last few seconds; [undoRemove] brings it back. */
    val pendingRemoval: StateFlow<IchthysNote?> = _pendingRemoval.asStateFlow()
    private var removalJob: Job? = null

    private var openNotesTimeout: Job? = null

    /** A draft written while unlocked, kept out of the composer while the device is locked. */
    private var stashedDraft: IchthysNoteDraft? = null

    init {
        // Nothing typed or removed while unlocked may be read (or committed over) on the lock
        // screen: stash the draft and close the undo strip when the device is locked, give the
        // draft back once it is entered again.
        applicationScope.launch {
            isDeviceEntered.collect { entered ->
                if (!entered) {
                    dismissUndo()
                    val d = _draft.value
                    if (!d.isEmpty) {
                        stashedDraft = d
                        _draft.value = IchthysNoteDraft()
                    }
                } else {
                    val stash = stashedDraft
                    if (stash != null && _draft.value.isEmpty) {
                        _draft.value = stash
                        stashedDraft = null
                    }
                }
            }
        }
    }

    private val _clipSuggestion = MutableStateFlow<String?>(null)
    /** Fresh clipboard text offered as a note, null when there is none or it was dismissed. */
    val clipSuggestion: StateFlow<String?> = _clipSuggestion.asStateFlow()
    private var dismissedClip: String? = null

    private val _openNotesWhenShown = MutableStateFlow(false)
    /** Set by [unlockToOpen]: the next time quick settings is idle, land on the notes page. */
    val openNotesWhenShown: StateFlow<Boolean> = _openNotesWhenShown.asStateFlow()

    private var textInputActive = false

    fun start() = repository.start()

    // region page

    /**
     * Whether the tiles are on screen on their first page. False on the notes page: the shade
     * transition then fades the tiles instead of moving them as shared elements.
     */
    fun setTilesOnFirstPage(onFirstPage: Boolean) {
        inFirstPageInteractor.inFirstPage = onFirstPage
    }

    fun consumeOpenNotes() {
        _openNotesWhenShown.value = false
    }

    /**
     * "Unlock to read": closes the shade, dismisses the keyguard, then expands quick settings on
     * this page. The shade has to be closed first: a bouncer (or the fingerprint alternate
     * bouncer) asked for from expanded quick settings draws over the still-open page.
     */
    fun unlockToOpen() {
        afterShadeClosedIfLocked {
            activityStarter
                .get()
                .dismissKeyguardThenExecute(
                    {
                        _openNotesWhenShown.value = true
                        shadeController.get().animateExpandQs()
                        // Should quick settings not open (disabled shade, lock task), forget
                        // the request instead of landing a later, unrelated open on this page.
                        openNotesTimeout?.cancel()
                        openNotesTimeout =
                            applicationScope.launch {
                                delay(OPEN_NOTES_MILLIS)
                                _openNotesWhenShown.value = false
                            }
                        false
                    },
                    null,
                    true,
                )
        }
    }

    /**
     * Runs [block] at once when the device is entered; while locked, first closes the shade so
     * the keyguard dismissal [block] starts happens over a clean lock screen.
     */
    private fun afterShadeClosedIfLocked(block: () -> Unit) {
        if (isDeviceEntered.value) {
            block()
            return
        }
        setTextInputActive(false)
        val shade = shadeController.get()
        shade.addPostCollapseAction { block() }
        shade.animateCollapseShade()
    }

    /**
     * The composer has (or lost) focus: the shade window must take keyboard focus for the IME to
     * come up over expanded quick settings, as it does for inline replies.
     */
    fun setTextInputActive(active: Boolean) {
        if (textInputActive == active) return
        textInputActive = active
        windowController.get().setIchthysTextInputActive(active)
    }

    // endregion

    // region composer

    fun setDraftText(text: String) {
        if (_draft.value.text != text) _draft.value = _draft.value.copy(text = text)
    }

    fun setDraftTint(tint: Int) {
        _draft.value = _draft.value.copy(tint = tint)
    }

    fun toggleDraftPinned() {
        _draft.value = _draft.value.copy(pinned = !_draft.value.pinned)
    }

    /** Loads [note] into the composer. */
    fun edit(note: IchthysNote) {
        _draft.value =
            IchthysNoteDraft(
                text = note.text,
                tint = note.tint,
                pinned = note.pinned,
                editingId = note.id,
                editingCreatedAt = note.createdAt,
                editingOnLock = note.onLock,
            )
    }

    fun clearDraft() {
        _draft.value = IchthysNoteDraft()
    }

    /** Stores the draft as a new note or over the one being edited; true when it landed. */
    suspend fun commitDraft(): Boolean {
        val d = _draft.value
        val text = d.text.trim()
        if (text.isEmpty()) {
            if (d.editingId != null) repository.remove(d.editingId)
            clearDraft()
            return true
        }
        val now = System.currentTimeMillis()
        val note =
            IchthysNote(
                id = d.editingId ?: now,
                text = text,
                pinned = d.pinned,
                tint = d.tint,
                onLock = d.editingOnLock,
                createdAt = if (d.editingId != null) d.editingCreatedAt else now,
                updatedAt = now,
            )
        val ok = repository.save(note)
        if (ok) clearDraft()
        return ok
    }

    /** Pins [text] straight away (clipboard row, share target). */
    suspend fun pinText(text: String): Boolean {
        val now = System.currentTimeMillis()
        return repository.save(IchthysNote(id = now, text = text.trim(), createdAt = now))
    }

    // endregion

    // region card actions

    fun toggleItem(note: IchthysNote, line: Int, done: Boolean) {
        applicationScope.launch {
            repository.update(note.id) { it.withItemDone(line, done, System.currentTimeMillis()) }
        }
    }

    fun togglePinned(note: IchthysNote) {
        applicationScope.launch {
            repository.update(note.id) { it.copy(pinned = !it.pinned, updatedAt = System.currentTimeMillis()) }
        }
    }

    fun toggleOnLock(note: IchthysNote) {
        applicationScope.launch { repository.update(note.id) { it.copy(onLock = !it.onLock) } }
    }

    /** Removes [note] from the page now and from the store after [UNDO_MILLIS], unless undone. */
    fun remove(note: IchthysNote) {
        removalJob?.cancel()
        val previous = _pendingRemoval.value
        _pendingRemoval.value = note
        applicationScope.launch {
            if (previous != null && previous.id != note.id) repository.remove(previous.id)
            if (_draft.value.editingId == note.id) clearDraft()
            repository.remove(note.id)
        }
        removalJob =
            applicationScope.launch {
                delay(UNDO_MILLIS)
                if (_pendingRemoval.value?.id == note.id) _pendingRemoval.value = null
            }
    }

    fun undoRemove() {
        val note = _pendingRemoval.value ?: return
        removalJob?.cancel()
        _pendingRemoval.value = null
        applicationScope.launch { repository.save(note) }
    }

    fun dismissUndo() {
        removalJob?.cancel()
        _pendingRemoval.value = null
    }

    fun copyNote(note: IchthysNote) = copyText(note.text)

    fun copyText(text: String) {
        applicationScope.launch(backgroundDispatcher) {
            try {
                clipboard().setPrimaryClip(ClipData.newPlainText("note", text))
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy", e)
            }
        }
    }

    fun shareNote(note: IchthysNote) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, note.text)
                note.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
        afterShadeClosedIfLocked {
            activityStarter.get().startActivity(Intent.createChooser(send, null), true)
        }
    }

    /** What a tap on a tappable span does: open, mail, dial, or copy. */
    fun act(entity: IchthysNoteEntity) {
        when (entity) {
            is IchthysNoteEntity.Link -> {
                val url =
                    if (entity.value.contains("://")) entity.value else "https://" + entity.value
                open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            is IchthysNoteEntity.Email ->
                open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + entity.value)))
            is IchthysNoteEntity.Phone ->
                open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + entity.value.replace(" ", ""))))
            is IchthysNoteEntity.Code -> copyText(entity.value)
        }
    }

    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        afterShadeClosedIfLocked { activityStarter.get().startActivity(intent, true) }
    }

    // endregion

    // region clipboard

    /** Re-reads the clipboard; a fresh text clip (under [CLIP_FRESH_MILLIS]) becomes the offer. */
    fun refreshClipSuggestion() {
        applicationScope.launch(backgroundDispatcher) {
            _clipSuggestion.value =
                try {
                    val cm = clipboard()
                    val description = cm.primaryClipDescription
                    val clip = cm.primaryClip
                    val sensitive =
                        description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
                            ?: false
                    val fresh =
                        description != null &&
                            !sensitive &&
                            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                            System.currentTimeMillis() - description.timestamp < CLIP_FRESH_MILLIS
                    val text =
                        if (fresh && clip != null && clip.itemCount > 0)
                            clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
                        else null
                    text?.takeIf { it.isNotEmpty() && it.length <= 2000 && it != dismissedClip }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the clipboard", e)
                    null
                }
        }
    }

    fun dismissClipSuggestion() {
        dismissedClip = _clipSuggestion.value
        _clipSuggestion.value = null
    }

    suspend fun pinClipSuggestion(): Boolean {
        val text = _clipSuggestion.value ?: return false
        val ok = withContext(backgroundDispatcher) { pinText(text) }
        if (ok) {
            dismissedClip = text
            _clipSuggestion.value = null
        }
        return ok
    }

    private fun clipboard(): ClipboardManager =
        clipboardProvider.forUser(UserHandle.of(userTracker.userId))

    // endregion

    companion object {
        private const val TAG = "IchthysNotes"
        const val UNDO_MILLIS = 5_000L
        /** How long "Unlock to read" waits for quick settings to open on the notes page. */
        private const val OPEN_NOTES_MILLIS = 5_000L
        const val CLIP_FRESH_MILLIS = 2 * 60_000L
    }
}
