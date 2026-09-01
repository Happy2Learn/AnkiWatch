package com.rella.ankiwear.phone

import org.json.JSONArray

/**
 * Pure, device-free logic for the phone (middleman) app.
 *
 * Everything in this file is plain Kotlin with no Android framework, no
 * ContentResolver, no coroutines — just data in, data out. That's what makes
 * it unit-testable on a regular computer (the JVM), with no phone or watch
 * required.
 *
 * Keeping this logic separate from the Android glue (AnkiDroidHelper,
 * WearSyncService) also makes it easier to reason about and to verify.
 */

/**
 * Packs a (noteId, cardOrd) pair into a single Long for the watch's "id" field.
 *
 * IMPORTANT: AnkiDroid note IDs are millisecond timestamps (~1.77e12 today),
 * which need more than 32 bits. The original 32/32 split silently truncated
 * note IDs, corrupting card identity. We now reserve the low 16 bits for the
 * card ordinal (0..65535, far more than any note type actually uses) and the
 * high 48 bits for the note ID (enough for ~8900 years of millisecond
 * timestamps).
 */
object CardCoding {
    private const val ORD_BITS = 16
    private const val ORD_MASK = (1L shl ORD_BITS) - 1 // 0xFFFF

    fun encode(noteId: Long, cardOrd: Int): Long =
        (noteId shl ORD_BITS) or (cardOrd.toLong() and ORD_MASK)

    fun decode(packed: Long): Pair<Long, Int> =
        (packed shr ORD_BITS) to (packed and ORD_MASK).toInt()
}

/** Parses AnkiDroid's deck due-count field (a JSON array `[learn, review, new]`). */
object DeckCount {
    /**
     * `deck_count` is not a single number — it's a JSON array like
     * `[2, 5, 10]` meaning "2 learning, 5 review, 10 new". We sum the entries
     * to report one "cards due" number to the watch.
     */
    fun parse(json: String?): Int {
        if (json.isNullOrBlank()) return 0
        return try {
            val array = JSONArray(json)
            var sum = 0
            for (i in 0 until array.length()) sum += array.optInt(i, 0)
            sum
        } catch (e: Exception) {
            0
        }
    }
}

/** Detects whether card HTML embeds media (image/audio/video). */
object MediaDetect {
    /**
     * Media itself is never sent to the watch (too heavy). We only flag its
     * presence so the watch can offer "view on phone".
     */
    fun hasMedia(html: String): Boolean =
        html.contains("<img", ignoreCase = true) ||
            html.contains("[sound:", ignoreCase = true) ||
            html.contains("<audio", ignoreCase = true) ||
            html.contains("<video", ignoreCase = true)
}

/**
 * Orders a list of queued grades by when they were recorded, so they are
 * replayed into AnkiDroid in the exact sequence the user actually tapped them.
 *
 * AnkiDroid does all the scheduling; our only job is to preserve temporal order.
 */
fun orderGradesForReplay(grades: List<AnkiDroidHelper.Grade>): List<AnkiDroidHelper.Grade> =
    grades.sortedBy { it.reviewedAtMs }