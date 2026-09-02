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

/**
 * Turns card HTML into plain text suitable for a tiny watch screen.
 *
 * Anki card fields are HTML. Rendered raw, they leak markup like
 * `<hr id=answer>` (AnkiDroid's question/answer separator) onto the screen.
 * The watch has no browser, so we flatten to text on the phone and send only
 * that.
 *
 * Media markers are stripped too — detect media BEFORE calling this, since the
 * markers are what identify it.
 */
object CardText {
    private val STYLE = Regex("(?is)<style.*?</style>")
    private val SCRIPT = Regex("(?is)<script.*?</script>")
    private val SOUND = Regex("(?i)\\[sound:[^]]*]")
    private val LINE_BREAK = Regex("(?i)<(br|hr)[^>]*>")
    private val BLOCK_END = Regex("(?i)</(div|p|li|tr|h[1-6]|blockquote)>")
    private val CLOZE_HINT = Regex("(?i)<span class=\"?cloze\"?>(.*?)</span>")
    private val BLANK_LINES = Regex("\n{2,}")

    fun toPlain(html: String): String {
        if (html.isBlank()) return ""

        val withBreaks = html
            .replace(STYLE, "")
            .replace(SCRIPT, "")
            .replace(SOUND, " ")
            .replace(CLOZE_HINT, "$1")
            .replace(LINE_BREAK, "\n")
            .replace(BLOCK_END, "\n")

        // Jsoup drops remaining tags and decodes entities (&amp;, &nbsp;, ...).
        // wholeText() is used instead of text() because text() collapses the
        // newlines we just inserted.
        val text = org.jsoup.Jsoup.parse(withBreaks).wholeText()

        // Blank lines are dropped rather than preserved: empty Anki fields and
        // wrapper divs generate a lot of them, and vertical space is scarce on a
        // watch screen.
        return text
            .lineSequence()
            .map { it.replace('\u00A0', ' ').trim() }
            .joinToString("\n")
            .replace(BLANK_LINES, "\n")
            .trim()
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

/**
 * The user's chosen decks, stored in SharedPreferences.
 *
 * Kept in one place so the settings screen and the sync service can't drift
 * apart on the preference file name or key.
 */
object FavoriteDecks {
    private const val PREFS = "settings"
    private const val KEY = "favorite_decks"

    fun read(context: android.content.Context): List<Long> =
        rawSet(context).mapNotNull { it.toLongOrNull() }

    fun rawSet(context: android.content.Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()

    fun write(context: android.content.Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY, ids).apply()
    }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
}

/**
 * Phone-side arming switch for watch sync.
 *
 * When OFF, the phone ignores requests from the watch.
 * Defaults to ON. Persisted in SharedPreferences across restarts.
 */
object SyncArm {
    private const val PREFS = "settings"
    private const val KEY = "sync_armed"

    fun isOn(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY, true)

    fun set(context: android.content.Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
}