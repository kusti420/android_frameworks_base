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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserFileManagerImpl
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

/**
 * IchthysOS: the store of the quick settings notes.
 *
 * One JSON file per user in SystemUI's credential-encrypted files directory (readable by nobody
 * else, unreadable until the user has unlocked once after boot), written atomically. The
 * first version kept the notes in a Settings.Secure string, which any installed app can read
 * without a permission; such a store is imported once and wiped.
 *
 * Format: `[{"id":<long>,"t":"<text>","p":<pinned>,"c":<tint>,"l":<on lock>,"ts":<created>,
 * "u":<updated>}]`. Old entries carry "k" (kind): "list" notes get their plain lines converted to
 * `[ ] ` items on import.
 */
@SysUISingleton
class IchthysNotesRepository
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor,
    private val userFileManager: UserFileManager,
    private val userTracker: UserTracker,
    private val userManager: UserManager,
    private val secureSettings: SecureSettings,
    private val broadcastDispatcher: BroadcastDispatcher,
) {
    private val _notes = MutableStateFlow<List<IchthysNote>>(emptyList())
    /** Pinned first, then most recently changed. */
    val notes: StateFlow<List<IchthysNote>> = _notes.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** False until the store can be read: before the first unlock after boot. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val writeLock = Mutex()
    private var started = false

    /** Starts observing users and unlocks; idempotent, called by the UI when first shown. */
    fun start() {
        if (started) return
        started = true
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) = reload()
            },
            backgroundExecutor,
        )
        applicationScope.launch(backgroundDispatcher) {
            broadcastDispatcher
                .broadcastFlow(IntentFilter(Intent.ACTION_USER_UNLOCKED), UserHandle.ALL)
                .collect { reload() }
        }
        reload()
    }

    fun reload() {
        applicationScope.launch(backgroundDispatcher) {
            writeLock.withLock { _notes.value = read(userTracker.userId) }
        }
    }

    /** Adds or replaces [note]; false when the store refused it (not ready or too many notes). */
    suspend fun save(note: IchthysNote): Boolean =
        edit { current ->
            val clean = note.copy(text = note.text.take(MAX_TEXT_CHARS).trimEnd())
            if (clean.text.isBlank()) return@edit current.filter { it.id != note.id }
            val others = current.filter { it.id != note.id }
            if (others.size >= MAX_NOTES) null else others + clean
        }

    suspend fun update(id: Long, transform: (IchthysNote) -> IchthysNote): Boolean =
        edit { current ->
            val existing = current.firstOrNull { it.id == id } ?: return@edit current
            current.map { if (it.id == id) transform(existing).copy(id = id) else it }
        }

    suspend fun remove(id: Long): Boolean = edit { current -> current.filter { it.id != id } }

    suspend fun find(id: Long): IchthysNote? =
        withContext(backgroundDispatcher) { notes.value.firstOrNull { it.id == id } }

    /** Reads, applies [transform] (null = refused) and writes, serialised against other edits. */
    private suspend fun edit(transform: (List<IchthysNote>) -> List<IchthysNote>?): Boolean =
        withContext(backgroundDispatcher) {
            writeLock.withLock {
                val userId = userTracker.userId
                val current = read(userId)
                if (!_ready.value) return@withLock false
                val next = transform(current)?.let(::sorted) ?: return@withLock false
                val ok = write(userId, next)
                if (ok) _notes.value = next
                ok
            }
        }

    private fun storeAvailable(): Boolean = userManager.isUserUnlocked(UserHandle.myUserId())

    private fun read(userId: Int): List<IchthysNote> {
        if (!storeAvailable()) {
            _ready.value = false
            return emptyList()
        }
        _ready.value = true
        val file = storeFile(userId)
        if (!file.exists()) {
            val imported = importDeviceProtected(userId) ?: importLegacy(userId)
            if (imported.isNotEmpty()) write(userId, imported)
            return imported
        }
        return try {
            decode(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "Unreadable notes store, showing none", e)
            emptyList()
        }
    }

    /**
     * The store, in SystemUI's credential-encrypted files directory. SystemUI defaults to
     * device-protected storage (it runs before the first unlock), so its plain filesDir, and
     * UserFileManager with it, would keep the notes readable before the user ever unlocked.
     */
    private fun storeFile(userId: Int): File {
        val ce = context.createCredentialProtectedStorageContext()
        return File(ce.filesDir, UserFileManagerImpl.createFile(FILE_NAME, userId).path)
    }

    /**
     * Builds from 2026-11-04 kept the store in device-protected storage: move it once, then
     * delete the old copy. Null when there is none.
     */
    private fun importDeviceProtected(userId: Int): List<IchthysNote>? {
        val old = userFileManager.getFile(FILE_NAME, userId)
        if (!old.exists()) return null
        val notes =
            try {
                decode(AtomicFile(old).readFully().toString(Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "Unreadable device-protected notes store", e)
                return null
            }
        if (notes.isEmpty() || write(userId, notes)) {
            AtomicFile(old).delete()
            Log.i(TAG, "Moved ${notes.size} notes to credential-encrypted storage")
        }
        return notes
    }

    private fun write(userId: Int, notes: List<IchthysNote>): Boolean {
        val target = storeFile(userId)
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        var out: FileOutputStream? = null
        return try {
            out = atomic.startWrite()
            out.write(encode(notes).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the notes store", e)
            if (out != null) atomic.failWrite(out)
            false
        }
    }

    /** The first version's Settings.Secure store, imported once and wiped. */
    private fun importLegacy(userId: Int): List<IchthysNote> {
        val raw = secureSettings.getStringForUser(LEGACY_SETTING, userId)
        if (raw.isNullOrEmpty()) return emptyList()
        val notes = decode(raw, legacy = true)
        secureSettings.putStringForUser(LEGACY_SETTING, "", userId)
        Log.i(TAG, "Imported ${notes.size} notes from the settings store")
        return notes
    }

    private fun decode(raw: String, legacy: Boolean = false): List<IchthysNote> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    var text = o.optString("t", "")
                    if (text.isBlank()) continue
                    if (legacy && o.optString("k") == "list") {
                        text =
                            text.split('\n').joinToString("\n") { line ->
                                if (line.isBlank() || IchthysNoteText.isItemLine(line)) line
                                else IchthysNoteText.OPEN + line.trim()
                            }
                    }
                    val created = o.optLong("ts", 0L)
                    add(
                        IchthysNote(
                            id = o.optLong("id", created.takeIf { it != 0L } ?: i.toLong()),
                            text = text,
                            pinned = o.optBoolean("p", false),
                            tint = o.optInt("c", 0).coerceIn(0, TINT_COUNT),
                            onLock = o.optBoolean("l", false),
                            createdAt = created,
                            updatedAt = o.optLong("u", created),
                        )
                    )
                }
            }
            sorted(list)
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
                    .put("p", n.pinned)
                    .put("c", n.tint)
                    .put("l", n.onLock)
                    .put("ts", n.createdAt)
                    .put("u", n.updatedAt)
            )
        }
        return array.toString()
    }

    private fun sorted(notes: List<IchthysNote>): List<IchthysNote> =
        notes.sortedWith(compareByDescending<IchthysNote> { it.pinned }.thenByDescending { it.updatedAt })

    companion object {
        private const val TAG = "IchthysNotes"
        private const val FILE_NAME = "ichthys_notes.json"
        /** The first version's Settings.Secure key. */
        const val LEGACY_SETTING = "ichthys_qs_notes"

        const val MAX_NOTES = 200
        const val MAX_TEXT_CHARS = 4000
        /** Tints 1..[TINT_COUNT]; 0 = none. */
        const val TINT_COUNT = 5
    }
}
