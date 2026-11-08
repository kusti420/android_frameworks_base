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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS: "Notes" in every app's share sheet and "Pin" in every text selection menu. Takes the
 * shared or selected text, stores it as a note, says so in a toast and finishes: nothing opens.
 * A shared link with a subject keeps both: the subject becomes the note's first line.
 */
class IchthysPinTextActivity
@Inject
constructor(
    private val repository: IchthysNotesRepository,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        val text = textOf(intent)
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        val app = applicationContext
        applicationScope.launch {
            val now = System.currentTimeMillis()
            val ok = repository.save(IchthysNote(id = now, text = text.trim(), createdAt = now))
            withContext(mainDispatcher) {
                Toast.makeText(
                        app,
                        if (ok) R.string.ichthys_notes_pinned_toast
                        else R.string.ichthys_notes_pin_failed_toast,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
        finish()
    }

    private fun textOf(intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> {
                val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
                when {
                    body.isNullOrEmpty() -> subject
                    subject.isNullOrEmpty() || body.contains(subject) -> body
                    else -> subject + "\n" + body
                }
            }
            else -> null
        }
    }
}
