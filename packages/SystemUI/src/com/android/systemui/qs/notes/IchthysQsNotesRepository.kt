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
import android.database.ContentObserver
import android.os.UserHandle
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** IchthysOS: one note of the quick settings notes page. */
data class IchthysNote(
    val id: Long,
    val text: String,
    val kind: IchthysNoteKind,
    /** Index into the page's colour dots, see IchthysQsNotesPage. */
    val color: Int,
    /** Wall-clock millis; newest first on the page. */
    val createdAt: Long,
) {
    /** The lines of a list note: `[x]`/`[X]` lines are done. Plain text has no items. */
    val items: List<IchthysNoteItem>
        get() =
            text.lines().filter { it.isNotBlank() }.map { line ->
                val t = line.trim()
                when {
                    t.startsWith("[x]", ignoreCase = true) ->
                        IchthysNoteItem(t.substring(3).trim(), done = true)
                    t.startsWith("[ ]") -> IchthysNoteItem(t.substring(3).trim(), done = false)
                    t.startsWith("[]") -> IchthysNoteItem(t.substring(2).trim(), done = false)
                    else -> IchthysNoteItem(t, done = false)
                }
            }
}

data class IchthysNoteItem(val text: String, val done: Boolean)

enum class IchthysNoteKind(val key: String) {
    NOTE("note"),
    LIST("list"),
    IDEA("idea");

    companion object {
        fun fromKey(key: String?): IchthysNoteKind = entries.firstOrNull { it.key == key } ?: NOTE
    }
}

/**
 * IchthysOS: the notes shown on the page left of the quick settings tiles.
 *
 * p!n (de.nproth.pin), the notes-as-notifications app the page was designed around, keeps its notes
 * in a ContentProvider that is `exported="false"` and takes no text through intents, so SystemUI
 * cannot read them. The notes live here instead: a JSON array in Settings.Secure, which is per
 * user, survives boot and needs no new system service. (Secure settings have no per-package byte
 * cap; a single value may be 32768 chars, hence [MAX_TOTAL_CHARS].)
 *
 * Format: `[{"id":<long>,"t":"<text>","k":"note|list|idea","c":<colour index>,"ts":<millis>}]`.
 */
@SysUISingleton
class IchthysQsNotesRepository
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
) {
    private val _notes = MutableStateFlow<List<IchthysNote>>(emptyList())
    /** Newest first. */
    val notes: StateFlow<List<IchthysNote>> = _notes.asStateFlow()

    private val writeLock = Mutex()
    private var started = false

    /** Starts observing the store; idempotent, called from the UI the first time it is shown. */
    fun start() {
        if (started) return
        started = true
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    reload()
                }
            }
        secureSettings.registerContentObserverForUserSync(SETTING, observer, UserHandle.USER_ALL)
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) {
                    reload()
                }
            },
            backgroundExecutor,
        )
        reload()
    }

    private fun reload() {
        applicationScope.launch(backgroundDispatcher) { _notes.value = read(userTracker.userId) }
    }

    /**
     * Adds or replaces a note. Returns false when the store is full (nothing is dropped to make
     * room; the user decides what to unpin).
     */
    suspend fun save(note: IchthysNote): Boolean =
        edit { current ->
            val text = note.text.take(MAX_TEXT_CHARS)
            val others = current.filter { it.id != note.id }
            val next = (listOf(note.copy(text = text)) + others).sortedByDescending { it.createdAt }
            if (next.size > MAX_NOTES || next.sumOf { it.text.length } > MAX_TOTAL_CHARS) null
            else next
        }

    suspend fun remove(id: Long): Boolean = edit { current -> current.filter { it.id != id } }

    /** Reads, applies [transform] (null = refused) and writes, serialised against other edits. */
    private suspend fun edit(transform: (List<IchthysNote>) -> List<IchthysNote>?): Boolean =
        withContext(backgroundDispatcher) {
            writeLock.withLock {
                val userId = userTracker.userId
                val next = transform(read(userId)) ?: return@withLock false
                val ok = secureSettings.putStringForUser(SETTING, encode(next), userId)
                if (ok) _notes.value = next
                ok
            }
        }

    private fun read(userId: Int): List<IchthysNote> {
        val raw = secureSettings.getStringForUser(SETTING, userId)
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val text = o.optString("t", "")
                    if (text.isEmpty()) continue
                    add(
                        IchthysNote(
                            id = o.optLong("id", i.toLong()),
                            text = text,
                            kind = IchthysNoteKind.fromKey(o.optString("k")),
                            color = o.optInt("c", 0),
                            createdAt = o.optLong("ts", 0L),
                        )
                    )
                }
            }
                .sortedByDescending { it.createdAt }
        } catch (e: JSONException) {
            Log.w(TAG, "Unreadable notes store, showing none", e)
            emptyList()
        }
    }

    private fun encode(notes: List<IchthysNote>): String {
        val array = JSONArray()
        notes.forEach { n ->
            array.put(
                JSONObject()
                    .put("id", n.id)
                    .put("t", n.text)
                    .put("k", n.kind.key)
                    .put("c", n.color)
                    .put("ts", n.createdAt)
            )
        }
        return array.toString()
    }

    companion object {
        private const val TAG = "IchthysQsNotes"

        /** Settings.Secure key, per user. */
        const val SETTING = "ichthys_qs_notes"

        const val MAX_NOTES = 40
        const val MAX_TEXT_CHARS = 2000
        /** Well under the 32768-char cap of one secure setting, with the JSON framing. */
        const val MAX_TOTAL_CHARS = 24000
    }
}
