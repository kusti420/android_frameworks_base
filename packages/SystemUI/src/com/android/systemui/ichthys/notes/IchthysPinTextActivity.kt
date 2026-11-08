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
import android.os.Process
import android.os.SystemClock
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
        val caller = callerKey()
        if (caller != null) {
            val verdict = IchthysPinRateLimiter.check(caller, SystemClock.elapsedRealtime())
            if (verdict != IchthysPinRateLimiter.Verdict.ALLOWED) {
                if (verdict == IchthysPinRateLimiter.Verdict.REFUSED) {
                    Toast.makeText(app, R.string.ichthys_notes_pin_limited_toast, Toast.LENGTH_SHORT)
                        .show()
                }
                finish()
                return
            }
        }
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

    /**
     * Who asked, for [IchthysPinRateLimiter]; null = SystemUI itself (not limited). SystemUI is
     * platform-signed, so launchedFromUid names the launching app even without
     * ActivityOptions.setShareIdentityEnabled; the share sheet starts targets as its caller, so a
     * share counts for the app that shared, and PROCESS_TEXT for the app the text is in. The
     * package forms (and the referrer, which the sender can make up) are fallbacks only.
     */
    private fun callerKey(): String? {
        val uid = launchedFromUid
        if (uid == Process.myUid()) return null
        if (uid != Process.INVALID_UID) return "uid:$uid"
        launchedFromPackage?.let { return "pkg:$it" }
        callingPackage?.let { return "pkg:$it" }
        referrer?.takeIf { it.scheme == "android-app" }?.host?.let { return "ref:$it" }
        return "unknown"
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

/**
 * IchthysOS: flood guard of [IchthysPinTextActivity]. That activity is exported (share sheet,
 * text selection "Pin"), so any app can add notes with a plain explicit intent and no user in
 * the loop. Each caller gets a small burst and a small budget per minute, and all callers share
 * a global budget; a person sharing or pinning by hand never comes near either. Refused requests
 * are dropped; the refusal toast is itself limited, so a flood can't turn into a toast flood.
 *
 * Process-wide state (SystemUI is one process, the activity is created per launch). Times are
 * SystemClock.elapsedRealtime() ms.
 */
object IchthysPinRateLimiter {
    const val BURST_WINDOW_MS = 10_000L
    const val BURST_MAX = 3
    const val WINDOW_MS = 60_000L
    const val PER_CALLER_MAX = 6
    const val GLOBAL_MAX = 12
    /** At most one refusal toast per caller per window, and none closer than this together. */
    const val REFUSAL_TOAST_GAP_MS = 5_000L
    private const val MAX_TRACKED_CALLERS = 64

    enum class Verdict {
        ALLOWED,
        /** Refused: drop it and show the refusal toast. */
        REFUSED,
        /** Refused: drop it without a toast (one was shown recently). */
        REFUSED_QUIET,
    }

    private val perCaller = HashMap<String, ArrayDeque<Long>>()
    private val global = ArrayDeque<Long>()
    private val callerToastAt = HashMap<String, Long>()
    private var lastToastAt = Long.MIN_VALUE / 2

    /** Decides one request by [caller] (a stable key: "uid:10123", "pkg:com.example") at [now]. */
    @Synchronized
    fun check(caller: String, now: Long): Verdict {
        prune(now)
        val mine = perCaller[caller]
        val burst = mine?.count { now - it < BURST_WINDOW_MS } ?: 0
        val refused =
            burst >= BURST_MAX || (mine?.size ?: 0) >= PER_CALLER_MAX || global.size >= GLOBAL_MAX
        if (!refused) {
            if (perCaller.size >= MAX_TRACKED_CALLERS && mine == null) {
                // Too many distinct callers in one window (spoofed referrers): the global
                // budget still applies, the newest caller just isn't tracked on its own.
                perCaller.entries.minByOrNull { it.value.lastOrNull() ?: 0L }?.let {
                    perCaller.remove(it.key)
                }
            }
            perCaller.getOrPut(caller) { ArrayDeque() }.addLast(now)
            global.addLast(now)
            return Verdict.ALLOWED
        }
        val last = callerToastAt[caller]
        if ((last != null && now - last < WINDOW_MS) || now - lastToastAt < REFUSAL_TOAST_GAP_MS) {
            return Verdict.REFUSED_QUIET
        }
        if (callerToastAt.size >= MAX_TRACKED_CALLERS) callerToastAt.clear()
        callerToastAt[caller] = now
        lastToastAt = now
        return Verdict.REFUSED
    }

    private fun prune(now: Long) {
        while (global.isNotEmpty() && now - global.first() >= WINDOW_MS) global.removeFirst()
        val it = perCaller.values.iterator()
        while (it.hasNext()) {
            val times = it.next()
            while (times.isNotEmpty() && now - times.first() >= WINDOW_MS) times.removeFirst()
            if (times.isEmpty()) it.remove()
        }
        callerToastAt.values.removeAll { now - it >= WINDOW_MS }
    }

    /** Tests only. */
    @Synchronized
    fun reset() {
        perCaller.clear()
        global.clear()
        callerToastAt.clear()
        lastToastAt = Long.MIN_VALUE / 2
    }
}
