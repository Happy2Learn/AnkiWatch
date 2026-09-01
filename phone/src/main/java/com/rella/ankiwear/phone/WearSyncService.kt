package com.rella.ankiwear.phone

import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background postman between the watch and AnkiDroid.
 *
 * Plain-language summary: this service sleeps until the watch sends a
 * message ("give me decks", "give me cards", "here are my grades"), then
 * wakes up, talks to AnkiDroid via [AnkiDroidHelper], and replies over the
 * Wear OS Data Layer. No screen, no taps required — this is what makes the
 * lunch sync automatic.
 *
 * Technical: extends [WearableListenerService]; the system delivers
 * MESSAGE_RECEIVED events even when the app UI is closed.
 */
class WearSyncService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var anki: AnkiDroidHelper

    companion object {
        private const val TAG = "WearSyncService"

        // Watch → Phone message paths
        private const val PATH_FETCH_DECKS = "/wear/fetch_decks"
        private const val PATH_FETCH_CARDS = "/wear/fetch_cards"
        private const val PATH_FETCH_LEGACY = "/wear/test-message"
        private const val PATH_ANSWER_CARD = "/wear/answer_card"
        private const val PATH_GRADE_QUEUE = "/wear/grade_queue"
        private const val PATH_VIEW_MEDIA = "/wear/view_media"

        // Phone → Watch data paths
        private const val PATH_DECK_LIST = "/wear/deck_list"
        private const val PATH_DECK_BUFFER = "/wear/deck_buffer"
        private const val PATH_GRADES_ACK = "/wear/grades_ack"
    }

    override fun onCreate() {
        super.onCreate()
        anki = AnkiDroidHelper(applicationContext)
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
            PATH_FETCH_DECKS -> scope.launch { pushDeckList() }

            PATH_FETCH_CARDS -> {
                val deckIds = parseDeckIds(String(messageEvent.data))
                scope.launch { pushCards(deckIds) }
            }

            PATH_FETCH_LEGACY -> scope.launch { pushCards(favoriteDeckIds()) }

            PATH_ANSWER_CARD -> {
                val grade = parseSingleGrade(String(messageEvent.data)) ?: return
                scope.launch {
                    val applied = anki.applyGrades(listOf(grade))
                    pushGradesAck(applied)
                }
            }

            PATH_GRADE_QUEUE -> {
                val grades = parseGradeQueue(String(messageEvent.data))
                scope.launch {
                    // Replay in timestamp order; AnkiDroid does all scheduling.
                    val applied = anki.applyGrades(grades)
                    pushGradesAck(applied)
                    // Restock: "Again" cards may already be due again.
                    pushCards(favoriteDeckIds())
                }
            }

            PATH_VIEW_MEDIA -> {
                val noteId = String(messageEvent.data).toLongOrNull() ?: return
                scope.launch { openCardOnPhone(noteId) }
            }
        }
    }

    // ---------- Phone → Watch push helpers ----------

    private suspend fun pushDeckList() {
        val decks = anki.getDecks()
        val json = JSONArray()
        decks.forEach { deck ->
            json.put(JSONObject().apply {
                put("deckId", deck.deckId)
                put("name", deck.name)
                put("dueCount", deck.dueCount)
            })
        }
        putData(PATH_DECK_LIST) { it.putString("decks_json", json.toString()) }
        Log.d(TAG, "Pushed ${decks.size} decks to watch")
    }

    private suspend fun pushCards(deckIds: List<Long>) {
        val cards = anki.getDueCards(deckIds)
        val json = JSONArray()
        cards.forEach { card ->
            json.put(JSONObject().apply {
                // The watch's existing schema uses "id"; we encode noteId and
                // cardOrd together so grades can be routed back precisely.
                put("id", encodeCardId(card.noteId, card.cardOrd))
                put("front", card.front)
                put("back", card.back)
                put("frontHasMedia", card.frontHasMedia)
                put("backHasMedia", card.backHasMedia)
            })
        }
        putData(PATH_DECK_BUFFER) {
            it.putString("cards_json", json.toString())
            // Timestamp forces DataClient to treat repeat batches as changes.
            it.putLong("batch_time", System.currentTimeMillis())
        }
        Log.d(TAG, "Pushed ${cards.size} cards to watch")
    }

    private suspend fun pushGradesAck(appliedNoteIds: List<Long>) {
        val json = JSONArray()
        appliedNoteIds.forEach { json.put(it) }
        putData(PATH_GRADES_ACK) {
            it.putString("acked_ids", json.toString())
            it.putLong("ack_time", System.currentTimeMillis())
        }
        Log.d(TAG, "Acked ${appliedNoteIds.size} grades to watch")
    }

    private suspend fun putData(path: String, fill: (DataMap) -> Unit) {
        try {
            val request = PutDataMapRequest.create(path)
            fill(request.dataMap)
            Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent()).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push data to $path", e)
        }
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
        val obj = JSONObject(json)
        val (noteId, cardOrd) = decodeCardId(obj.getLong("id"))
        AnkiDroidHelper.Grade(
            noteId = noteId,
            cardOrd = cardOrd,
            ease = obj.getInt("ease"),
            timeTakenMs = obj.optLong("timeTaken", 0),
            reviewedAtMs = obj.optLong("reviewedAt", System.currentTimeMillis())
        )
    } catch (e: Exception) {
        Log.e(TAG, "Bad answer_card payload", e)
        null
    }

    private fun parseGradeQueue(json: String): List<AnkiDroidHelper.Grade> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            val (noteId, cardOrd) = decodeCardId(obj.getLong("id"))
            AnkiDroidHelper.Grade(
                noteId = noteId,
                cardOrd = cardOrd,
                ease = obj.getInt("ease"),
                timeTakenMs = obj.optLong("timeTaken", 0),
                reviewedAtMs = obj.optLong("reviewedAt", System.currentTimeMillis())
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Bad grade_queue payload", e)
        emptyList()
    }

    // ---------- Misc ----------

    private fun favoriteDeckIds(): List<Long> {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val set = prefs.getStringSet("favorite_decks", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }
    }

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

    /**
     * Packs (noteId, cardOrd) into one Long for the watch's existing "id"
     * field: high 32 bits = noteId, low 32 bits = cardOrd.
     */
    private fun encodeCardId(noteId: Long, cardOrd: Int): Long =
        CardCoding.encode(noteId, cardOrd)

    private fun decodeCardId(packed: Long): Pair<Long, Int> =
        CardCoding.decode(packed)
}
