package com.rella.ankiwear.phone

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Talks to the STOCK AnkiDroid app through its official ContentProvider API.
 *
 * Plain-language summary: AnkiDroid exposes a public "request window"
 * (a ContentProvider) that other apps can use after the user grants the
 * READ_WRITE_DATABASE permission. This class is the only place in the app
 * that knows AnkiDroid's table/column names, so if the API ever changes,
 * this is the only file that needs updating.
 *
 * The names below are taken verbatim from AnkiDroid's own contract file:
 *   api/src/main/java/com/ichi2/anki/FlashCardsContract.kt
 * (see the "AnkiDroid API" section of the AnkiDroid GitHub wiki).
 *
 * Important: due cards are NOT fetched by filtering the `cards` table by a
 * `due` column. AnkiDroid exposes a dedicated `schedule` endpoint
 * (ReviewInfo) that returns the next scheduled (noteId, ord) pairs for a
 * deck. We query that, then read the question/answer text from the `cards`
 * table for each pair.
 */
class AnkiDroidHelper(private val context: Context) {

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        // FlashCardsContract authority and paths (AnkiDroid API)
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val DECKS_URI: Uri = Uri.parse("content://$AUTHORITY/decks")
        private val SELECTED_DECK_URI: Uri = Uri.parse("content://$AUTHORITY/selected_deck")
        private val NOTES_URI: Uri = Uri.parse("content://$AUTHORITY/notes")
        private val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")

        // Deck columns (FlashCardsContract.Deck)
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val DECK_COUNTS = "deck_count"

        // Card columns (FlashCardsContract.Card)
        private const val CARD_QUESTION = "question"
        private const val CARD_ANSWER = "answer"

        // Scheduler-state columns, used only for verifying that a grade landed.
        // Not all AnkiDroid versions expose every one of these, so they are
        // read defensively.
        private const val CARD_REPS = "reps"
        private const val CARD_LAPSES = "lapses"
        private const val CARD_INTERVAL = "interval"
        private const val CARD_RAW_DUE = "due"
        private const val CARD_TYPE = "type"
        private const val CARD_RAW_QUEUE = "queue"

        private const val TAG = "AnkiDroidHelper"

        // ReviewInfo columns (FlashCardsContract.ReviewInfo)
        private const val REVIEW_NOTE_ID = "note_id"
        private const val REVIEW_CARD_ORD = "ord"
        private const val REVIEW_EASE = "answer_ease"
        private const val REVIEW_TIME_TAKEN = "time_taken"

        // Card ease values, matching Anki's 4-button system (Ease enum).
        const val EASE_AGAIN = 1
        const val EASE_HARD = 2
        const val EASE_GOOD = 3
        const val EASE_EASY = 4
    }

    data class DeckInfo(val deckId: Long, val name: String, val dueCount: Int)
    data class DueCard(
        val noteId: Long,
        val cardOrd: Int,
        val front: String,
        val back: String,
        val frontHasMedia: Boolean,
        val backHasMedia: Boolean
    )
    data class Grade(
        val noteId: Long,
        val cardOrd: Int,
        val ease: Int,
        val timeTakenMs: Long,
        // Kept for ordering on the watch/phone side. AnkiDroid does NOT accept
        // a "reviewed at" timestamp — it timestamps the review when applied.
        val reviewedAtMs: Long
    )

    /**
     * Result of a write-back attempt. Carries the failure reason instead of
     * collapsing everything to a bare `false`, so problems are diagnosable.
     */
    data class GradeResult(
        val ok: Boolean,
        val rowsUpdated: Int,
        val error: String? = null
    )

    /**
     * A card's scheduler state. Used to verify a grade actually applied:
     * if `reps` goes up, AnkiDroid really recorded a review.
     *
     * Fields are nullable because older AnkiDroid versions do not expose them.
     */
    data class CardState(
        val reps: Int?,
        val lapses: Int?,
        val intervalDays: Int?,
        val rawDue: Long?,
        val type: Int?,
        val queue: Int?
    ) {
        fun describe(): String = buildString {
            append("reps=").append(reps ?: "?")
            append("  lapses=").append(lapses ?: "?")
            append("  interval=").append(intervalDays?.let { "${it}d" } ?: "?")
            append("\ntype=").append(type?.let { typeName(it) } ?: "?")
            append("  queue=").append(queue?.let { queueName(it) } ?: "?")
            append("  due=").append(rawDue ?: "?")
        }

        private fun typeName(t: Int) = when (t) {
            0 -> "new"; 1 -> "learning"; 2 -> "review"; 3 -> "relearning"
            else -> "unknown($t)"
        }

        private fun queueName(q: Int) = when (q) {
            -3 -> "buried(manual)"; -2 -> "buried(sibling)"; -1 -> "suspended"
            0 -> "new"; 1 -> "learning"; 2 -> "review"; 3 -> "day-learn"
            4 -> "preview"
            else -> "unknown($q)"
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun isAnkiDroidInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.ichi2.anki", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * All decks with their names and due counts. The due count is the sum of
     * AnkiDroid's `deck_count` JSON array, which is `[learn, review, new]`.
     */
    fun getDecks(): List<DeckInfo> {
        val decks = mutableListOf<DeckInfo>()
        val cursor = context.contentResolver.query(
            DECKS_URI,
            arrayOf(DECK_ID, DECK_NAME, DECK_COUNTS),
            null, null, null
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                decks.add(
                    DeckInfo(
                        deckId = it.getLong(it.getColumnIndexOrThrow(DECK_ID)),
                        name = it.getString(it.getColumnIndexOrThrow(DECK_NAME)) ?: "Unnamed",
                        dueCount = DeckCount.parse(it.getString(it.getColumnIndexOrThrow(DECK_COUNTS)))
                    )
                )
            }
        }
        return decks
    }

    /**
     * Due cards for the given decks. Media (images/audio) is NOT transferred —
     * we only flag its presence so the watch can offer "view on phone".
     *
     * Flow: query the `schedule` endpoint per deck to get the next due
     * (noteId, ord) pairs, then read each card's question/answer text.
     */
    fun getDueCards(deckIds: List<Long>, limit: Int = 100): List<DueCard> {
        if (deckIds.isEmpty()) return emptyList()
        val cards = mutableListOf<DueCard>()

        for (deckId in deckIds) {
            if (cards.size >= limit) break
            val due = queryScheduled(deckId, limit - cards.size)
            for ((noteId, ord) in due) {
                val card = readCard(noteId, ord) ?: continue
                cards.add(card)
                if (cards.size >= limit) break
            }
        }
        return cards
    }

    /**
     * Replays a single grade into AnkiDroid. AnkiDroid's own scheduler computes
     * the new interval — the watch never does scheduling math.
     *
     * Failures are logged and returned, not swallowed: a silent `false` here
     * previously made write-back problems impossible to diagnose.
     */
    fun applyGrade(grade: Grade): GradeResult {
        return try {
            val values = ContentValues().apply {
                put(REVIEW_NOTE_ID, grade.noteId)
                put(REVIEW_CARD_ORD, grade.cardOrd)
                put(REVIEW_EASE, grade.ease)
                put(REVIEW_TIME_TAKEN, grade.timeTakenMs)
            }
            val rows = context.contentResolver.update(SCHEDULE_URI, values, null, null)
            if (rows > 0) {
                GradeResult(ok = true, rowsUpdated = rows)
            } else {
                val msg = "AnkiDroid reported 0 rows updated. The card may not be " +
                    "the scheduler's current card, or it may no longer be due."
                Log.w(TAG, "applyGrade: $msg (noteId=${grade.noteId} ord=${grade.cardOrd})")
                GradeResult(ok = false, rowsUpdated = 0, error = msg)
            }
        } catch (e: Exception) {
            val msg = "${e::class.simpleName}: ${e.message}"
            Log.e(TAG, "applyGrade threw (noteId=${grade.noteId} ord=${grade.cardOrd})", e)
            GradeResult(ok = false, rowsUpdated = 0, error = msg)
        }
    }

    /** Replays a batch of grades in chronological order. Returns applied note IDs. */
    fun applyGrades(grades: List<Grade>): List<Long> {
        val applied = mutableListOf<Long>()
        for (grade in orderGradesForReplay(grades)) {
            if (applyGrade(grade).ok) applied.add(grade.noteId)
        }
        return applied
    }

    /**
     * Reads a card's scheduler state, for proving whether a grade applied.
     *
     * Requests the extended columns first and falls back to the default
     * projection if this AnkiDroid version rejects them.
     */
    fun readCardState(noteId: Long, ord: Int): CardState? {
        val uri = cardUri(noteId, ord)
        val extended = arrayOf(
            CARD_REPS, CARD_LAPSES, CARD_INTERVAL,
            CARD_RAW_DUE, CARD_TYPE, CARD_RAW_QUEUE
        )

        val cursor = try {
            context.contentResolver.query(uri, extended, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Extended card projection rejected; falling back", e)
            try {
                context.contentResolver.query(uri, null, null, null, null)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not read card state", e2)
                null
            }
        } ?: return null

        return cursor.use {
            if (!it.moveToFirst()) return@use null
            CardState(
                reps = it.optInt(CARD_REPS),
                lapses = it.optInt(CARD_LAPSES),
                intervalDays = it.optInt(CARD_INTERVAL),
                rawDue = it.optLong(CARD_RAW_DUE),
                type = it.optInt(CARD_TYPE),
                queue = it.optInt(CARD_RAW_QUEUE)
            )
        }
    }

    /**
     * Makes a deck the selected one in AnkiDroid.
     *
     * AnkiDroid's scheduler is deck-scoped, so answering a card can require
     * that its deck is selected first.
     */
    fun selectDeck(deckId: Long): Boolean = try {
        val values = ContentValues().apply { put(DECK_ID, deckId) }
        context.contentResolver.update(SELECTED_DECK_URI, values, null, null) > 0
    } catch (e: Exception) {
        Log.e(TAG, "Could not select deck $deckId", e)
        false
    }

    // ---------- Internals ----------

    /**
     * Queries the `schedule` endpoint for the next due cards in one deck.
     * Returns (noteId, ord) pairs. The selection is `limit=?, deckID=?`.
     */
    private fun queryScheduled(deckId: Long, limit: Int): List<Pair<Long, Int>> {
        val result = mutableListOf<Pair<Long, Int>>()
        val cursor = context.contentResolver.query(
            SCHEDULE_URI,
            arrayOf(REVIEW_NOTE_ID, REVIEW_CARD_ORD),
            "limit=?, deckID=?",
            arrayOf(limit.toString(), deckId.toString()),
            null
        ) ?: return result

        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    it.getLong(it.getColumnIndexOrThrow(REVIEW_NOTE_ID)) to
                        it.getInt(it.getColumnIndexOrThrow(REVIEW_CARD_ORD))
                )
            }
        }
        return result
    }

    private fun cardUri(noteId: Long, ord: Int): Uri = Uri.withAppendedPath(
        Uri.withAppendedPath(NOTES_URI, noteId.toString()),
        "cards/$ord"
    )

    /** Reads one card's question/answer text via `notes/<noteId>/cards/<ord>`. */
    private fun readCard(noteId: Long, ord: Int): DueCard? {
        val uri = cardUri(noteId, ord)
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(CARD_QUESTION, CARD_ANSWER),
            null, null, null
        ) ?: return null

        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val question = it.getString(it.getColumnIndexOrThrow(CARD_QUESTION)) ?: ""
            val answer = it.getString(it.getColumnIndexOrThrow(CARD_ANSWER)) ?: ""
            DueCard(
                noteId = noteId,
                cardOrd = ord,
                front = question,
                back = answer,
                frontHasMedia = MediaDetect.hasMedia(question),
                backHasMedia = MediaDetect.hasMedia(answer)
            )
        }
    }
}

/** Reads an Int column if the provider returned it, else null. */
private fun Cursor.optInt(name: String): Int? {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getInt(i) else null
}

/** Reads a Long column if the provider returned it, else null. */
private fun Cursor.optLong(name: String): Long? {
    val i = getColumnIndex(name)
    return if (i >= 0 && !isNull(i)) getLong(i) else null
}