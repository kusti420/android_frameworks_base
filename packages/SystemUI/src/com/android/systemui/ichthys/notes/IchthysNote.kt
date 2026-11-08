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

import android.util.Patterns

/**
 * IchthysOS: one note of the quick settings notes page.
 *
 * A note is plain text. Lines that start with `[ ] ` or `[x] ` are checklist items; the first
 * line is the title when it is short and followed by more lines. Nothing else is structure.
 */
data class IchthysNote(
    val id: Long,
    val text: String,
    /** Pinned notes sort first. */
    val pinned: Boolean = false,
    /** 0 = no tint (marks use the theme accent), 1..5 = a tint derived from the accent. */
    val tint: Int = 0,
    /** Readable on the lock screen; every other note shows as bars there. */
    val onLock: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    val lines: List<String>
        get() = text.split('\n')

    /** Every checklist item, with the index of its line. */
    val items: List<IchthysNoteItem>
        get() = lines.mapIndexedNotNull { i, line -> IchthysNoteText.parseItem(i, line) }

    val hasItems: Boolean
        get() = items.isNotEmpty()

    val doneCount: Int
        get() = items.count { it.done }

    /** The first line, when the note has more lines, it is short and it is not an item. */
    val title: String?
        get() {
            val l = lines
            if (l.size < 2) return null
            val first = l[0].trim()
            if (first.isEmpty() || first.length > IchthysNoteText.TITLE_MAX_CHARS) return null
            if (IchthysNoteText.parseItem(0, first) != null) return null
            if (l.drop(1).all { it.isBlank() }) return null
            return first
        }

    /** The text lines that are neither the title nor items. */
    val bodyLines: List<String>
        get() {
            val l = lines
            val start = if (title != null) 1 else 0
            return l.drop(start).filter { IchthysNoteText.parseItem(0, it) == null && it.isNotBlank() }
        }

    /** A one-line summary for toasts and the undo strip. */
    val summary: String
        get() = (title ?: lines.firstOrNull { it.isNotBlank() }?.let {
            IchthysNoteText.parseItem(0, it)?.text ?: it.trim()
        } ?: "")

    fun withItemDone(line: Int, done: Boolean, now: Long): IchthysNote {
        val l = lines.toMutableList()
        val item = IchthysNoteText.parseItem(line, l.getOrNull(line) ?: return this) ?: return this
        l[line] = IchthysNoteText.itemLine(item.text, done)
        return copy(text = l.joinToString("\n"), updatedAt = now)
    }
}

data class IchthysNoteItem(val line: Int, val text: String, val done: Boolean)

object IchthysNoteText {
    const val TITLE_MAX_CHARS = 48
    const val OPEN = "[ ] "
    const val DONE = "[x] "

    fun parseItem(line: Int, raw: String): IchthysNoteItem? {
        val t = raw.trimStart()
        return when {
            t.startsWith("[x]", ignoreCase = true) -> IchthysNoteItem(line, t.substring(3).trim(), true)
            t.startsWith("[ ]") -> IchthysNoteItem(line, t.substring(3).trim(), false)
            t.startsWith("[]") -> IchthysNoteItem(line, t.substring(2).trim(), false)
            else -> null
        }
    }

    fun itemLine(text: String, done: Boolean): String = (if (done) DONE else OPEN) + text

    fun isItemLine(raw: String): Boolean = parseItem(0, raw) != null
}

/** IchthysOS: a span of note text that does something when tapped. */
sealed class IchthysNoteEntity(val start: Int, val end: Int, val value: String) {
    class Link(start: Int, end: Int, url: String) : IchthysNoteEntity(start, end, url)
    class Email(start: Int, end: Int, address: String) : IchthysNoteEntity(start, end, address)
    class Phone(start: Int, end: Int, number: String) : IchthysNoteEntity(start, end, number)
    /** Door codes, parcel numbers, passwords after "pw:": a tap copies them. */
    class Code(start: Int, end: Int, code: String) : IchthysNoteEntity(start, end, code)
}

/**
 * IchthysOS: finds the tappable spans of a line of note text. Links and emails first, then phone
 * numbers (7+ digits), then digit codes (4+ digits) and the token after a "pw", "password", "pin",
 * "code" or "key" marker. Spans never overlap; earlier kinds win.
 */
object IchthysNoteEntities {
    private val PHONE = Regex("""(?<![\w.])(\+?\d[\d ()./-]{5,}\d)(?![\w])""")
    private val DIGIT_CODE = Regex("""(?<![\w.-])(\d{4,16}(?:[ -]\d{2,})*)(?![\w.-])""")
    private val GROUPED_CODE = Regex("""\b([A-Z]{1,3} ?\d{3,}(?:[ -]\d{2,})*(?: ?[A-Z]{1,3})?)\b""")
    private val KEYWORD_CODE =
        Regex("""(?i)\b(?:pw|pwd|pass|password|passcode|pin|code|key|otp)\s*[:=]?\s+(\S{3,})""")

    fun detect(line: String): List<IchthysNoteEntity> {
        if (line.isBlank()) return emptyList()
        val found = mutableListOf<IchthysNoteEntity>()
        fun free(s: Int, e: Int) = found.none { s < it.end && e > it.start }

        Patterns.WEB_URL.matcher(line).let { m ->
            while (m.find()) {
                val url = m.group()
                // Patterns.WEB_URL also matches bare words with a TLD-looking end ("make_rom.sh"
                // does not, "example.com" does). Keep it to things with a dot and no spaces.
                if (!url.contains('.') || url.endsWith(".")) continue
                if (free(m.start(), m.end())) found += IchthysNoteEntity.Link(m.start(), m.end(), url)
            }
        }
        Patterns.EMAIL_ADDRESS.matcher(line).let { m ->
            while (m.find()) {
                if (free(m.start(), m.end())) {
                    // A link match may have swallowed the address; prefer the email.
                    found += IchthysNoteEntity.Email(m.start(), m.end(), m.group())
                } else {
                    found.removeAll { it is IchthysNoteEntity.Link && it.start <= m.start() && it.end >= m.end() }
                    found += IchthysNoteEntity.Email(m.start(), m.end(), m.group())
                }
            }
        }
        PHONE.findAll(line).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            val digits = g.value.count { it.isDigit() }
            if (digits in 7..15 && free(g.range.first, g.range.last + 1)) {
                found += IchthysNoteEntity.Phone(g.range.first, g.range.last + 1, g.value)
            }
        }
        DIGIT_CODE.findAll(line).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            if (free(g.range.first, g.range.last + 1)) {
                found += IchthysNoteEntity.Code(g.range.first, g.range.last + 1, g.value)
            }
        }
        GROUPED_CODE.findAll(line).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            if (g.value.count { it.isDigit() } >= 4 && free(g.range.first, g.range.last + 1)) {
                found += IchthysNoteEntity.Code(g.range.first, g.range.last + 1, g.value)
            }
        }
        KEYWORD_CODE.findAll(line).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            val value = g.value.trimEnd(',', '.', ';', ')')
            val end = g.range.first + value.length
            if (free(g.range.first, end)) {
                found += IchthysNoteEntity.Code(g.range.first, end, value)
            }
        }
        return found.sortedBy { it.start }
    }
}
