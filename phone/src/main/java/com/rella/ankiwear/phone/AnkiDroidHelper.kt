package com.rella.ankiwear.phone

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
        private val NOTES_URI: Uri = Uri.parse("content://$AUTHORITY/notes")
        private val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")

        // Deck columns (FlashCardsContract.Deck)
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val DECK_COUNTS = "deck_count"

        // Card columns (FlashCardsContract.Card)
        private const val CARD_QUESTION = "question"
        private const val CARD_ANSWER = "answer"

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
     * @return true if AnkiDroid accepted the review.
     */
    fun applyGrade(grade: Grade): Boolean {
        return try {
            val values = ContentValues().apply {
                put(REVIEW_NOTE_ID, grade.noteId)
                put(REVIEW_CARD_ORD, grade.cardOrd)
                put(REVIEW_EASE, grade.ease)
                put(REVIEW_TIME_TAKEN, grade.timeTakenMs)
            }
            val rows = context.contentResolver.update(SCHEDULE_URI, values, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Replays a batch of grades in chronological order. Returns applied note IDs. */
    fun applyGrades(grades: List<Grade>): List<Long> {
        val applied = mutableListOf<Long>()
        for (grade in orderGradesForReplay(grades)) {
            if (applyGrade(grade)) applied.add(grade.noteId)
        }
        return applied
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

    /** Reads one card's question/answer text via `notes/<noteId>/cards/<ord>`. */
    private fun readCard(noteId: Long, ord: Int): DueCard? {
        val uri = Uri.withAppendedPath(
            Uri.withAppendedPath(NOTES_URI, noteId.toString()),
            "cards/$ord"
        )
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