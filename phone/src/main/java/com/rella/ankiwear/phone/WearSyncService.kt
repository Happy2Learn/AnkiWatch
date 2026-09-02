package com.rella.ankiwear.phone

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receives messages from the watch and talks to AnkiDroid on its behalf.
 *
 * Plain-language summary: this service sleeps until the watch sends a message
 * ("give me decks", "give me cards", "here are my grades"), then wakes up,
 * talks to AnkiDroid via [AnkiDroidHelper], and replies over the Wear OS Data
 * Layer.
 *
 * Note on the sync model: syncing is explicit (the user taps "Sync now" on
 * either device while both apps are open). This service is what makes the
 * watch's side of that conversation work; it is not a background auto-sync.
 *
 * Technical: extends [WearableListenerService]; the system delivers
 * MESSAGE_RECEIVED events even when the phone app's UI is closed.
 */
class WearSyncService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var anki: AnkiDroidHelper
    private lateinit var pusher: WearPusher

    companion object {
        private const val TAG = "WearSyncService"

        // Watch -> Phone message paths
        private const val PATH_FETCH_DECKS = "/wear/fetch_decks"
        private const val PATH_FETCH_CARDS = "/wear/fetch_cards"
        private const val PATH_FETCH_LEGACY = "/wear/test-message"
        private const val PATH_ANSWER_CARD = "/wear/answer_card"
        private const val PATH_GRADE_QUEUE = "/wear/grade_queue"
        private const val PATH_VIEW_MEDIA = "/wear/view_media"

        /** Watch asking the phone to run a full sync (grades up, cards down). */
        private const val PATH_SYNC_NOW = "/wear/sync_now"
    }

    override fun onCreate() {
        super.onCreate()
        anki = AnkiDroidHelper(applicationContext)
        pusher = WearPusher(applicationContext, anki)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.d(TAG, "Message received: $path")

        if (!anki.hasPermission()) {
            Log.w(TAG, "AnkiDroid API permission not granted; ignoring $path")
            return
        }

        when (path) {
            PATH_FETCH_DECKS -> scope.launch { pusher.pushDeckList() }

            PATH_FETCH_CARDS -> {
                val requested = parseDeckIds(String(messageEvent.data))
                val deckIds = requested.ifEmpty { favoriteDeckIds() }
                scope.launch { pusher.pushCards(deckIds) }
            }

            PATH_FETCH_LEGACY -> scope.launch { pusher.pushCards(favoriteDeckIds()) }

            PATH_ANSWER_CARD -> {
                val grade = parseSingleGrade(String(messageEvent.data)) ?: return
                scope.launch { applyAndAck(listOf(grade)) }
            }

            // The watch uploaded its queued grades. Apply them, but do NOT
            // push a new card list: restocking mid-review reset the watch to
            // card 1 after every grade. Fresh cards only move on Sync.
            PATH_GRADE_QUEUE -> {
                val grades = parseGradeQueue(String(messageEvent.data))
                scope.launch { applyAndAck(grades) }
            }

            // The watch tapped "Sync now": it will send its queue separately,
            // so all we owe it here is a fresh batch of cards.
            PATH_SYNC_NOW -> scope.launch {
                pusher.pushDeckList()
                pusher.pushCards(favoriteDeckIds())
            }

            PATH_VIEW_MEDIA -> {
                val packed = String(messageEvent.data).toLongOrNull() ?: return
                val (noteId, _) = CardCoding.decode(packed)
                scope.launch { openCardOnPhone(noteId) }
            }
        }
    }

    /**
     * Applies grades to AnkiDroid, then tells the watch which ones landed so it
     * can clear them. The ack carries the packed card ID (not the note ID) so
     * the watch can match it against its own queue entries.
     */
    private suspend fun applyAndAck(grades: List<AnkiDroidHelper.Grade>) {
        val ackedCardIds = mutableListOf<Long>()
        for (grade in orderGradesForReplay(grades)) {
            val result = anki.applyGrade(grade)
            if (result.ok) {
                ackedCardIds.add(CardCoding.encode(grade.noteId, grade.cardOrd))
            } else {
                // Not acked, so the watch keeps it and retries next sync.
                Log.w(TAG, "Grade not applied for noteId=${grade.noteId}: ${result.error}")
            }
        }
        Log.d(TAG, "Applied ${ackedCardIds.size}/${grades.size} grades")
        pusher.pushGradesAck(ackedCardIds)
    }

    // ---------- Parsing helpers ----------

    private fun parseDeckIds(json: String): List<Long> = try {
        val array = JSONObject(json).getJSONArray("deckIds")
        (0 until array.length()).map { array.getLong(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Bad fetch_cards payload", e)
        emptyList()
    }

    private fun parseSingleGrade(json: String): AnkiDroidHelper.Grade? = try {
        gradeFromJson(JSONObject(json))
    } catch (e: Exception) {
        Log.e(TAG, "Bad answer_card payload", e)
        null
    }

    private fun parseGradeQueue(json: String): List<AnkiDroidHelper.Grade> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { gradeFromJson(array.getJSONObject(it)) }
    } catch (e: Exception) {
        Log.e(TAG, "Bad grade_queue payload", e)
        emptyList()
    }

    private fun gradeFromJson(obj: JSONObject): AnkiDroidHelper.Grade {
        val (noteId, cardOrd) = CardCoding.decode(obj.getLong("id"))
        return AnkiDroidHelper.Grade(
            noteId = noteId,
            cardOrd = cardOrd,
            ease = obj.getInt("ease"),
            timeTakenMs = obj.optLong("timeTaken", 0),
            reviewedAtMs = obj.optLong("reviewedAt", System.currentTimeMillis())
        )
    }

    // ---------- Misc ----------

    private fun favoriteDeckIds(): List<Long> =
        FavoriteDecks.read(applicationContext)

    private fun openCardOnPhone(noteId: Long) {
        // Ask AnkiDroid to open its card browser on this note.
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.ichi2.anki")?.apply {
                action = "com.ichi2.anki.intent.action.VIEW_NOTE"
                putExtra("noteId", noteId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent?.let { startActivity(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open note on phone", e)
        }
    }
}