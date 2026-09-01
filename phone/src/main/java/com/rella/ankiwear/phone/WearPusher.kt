package com.rella.ankiwear.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the phone sends to the watch, in one place.
 *
 * Both the background service ([WearSyncService]) and the settings screen
 * ([MainActivity]) need to push data to the watch, so this lives here instead
 * of being duplicated in each.
 */
class WearPusher(
    private val context: Context,
    private val anki: AnkiDroidHelper
) {

    companion object {
        private const val TAG = "WearPusher"

        // Phone -> Watch data paths
        const val PATH_DECK_LIST = "/wear/deck_list"
        const val PATH_DECK_BUFFER = "/wear/deck_buffer"
        const val PATH_GRADES_ACK = "/wear/grades_ack"

        /** Asks the watch to upload its pending grade queue. */
        const val PATH_SYNC_REQUEST = "/wear/sync_request"

        private const val WATCH_CAPABILITY = "anki_watch_app"
    }

    /** Pushes the deck list (names + due counts) to the watch. */
    suspend fun pushDeckList(): Int {
        val decks = anki.getDecks()
        val json = JSONArray()
        decks.forEach { deck ->
            json.put(JSONObject().apply {
                put("deckId", deck.deckId)
                put("name", deck.name)
                put("dueCount", deck.dueCount)
            })
        }
        putData(PATH_DECK_LIST) {
            it.putString("decks_json", json.toString())
            it.putLong("batch_time", System.currentTimeMillis())
        }
        Log.d(TAG, "Pushed ${decks.size} decks to watch")
        return decks.size
    }

    /** Pushes a batch of due cards to the watch. Returns how many were sent. */
    suspend fun pushCards(deckIds: List<Long>): Int {
        val cards = anki.getDueCards(deckIds)
        val json = JSONArray()
        cards.forEach { card ->
            json.put(JSONObject().apply {
                // The watch's schema uses a single "id"; we pack noteId and
                // cardOrd together so grades route back precisely.
                put("id", CardCoding.encode(card.noteId, card.cardOrd))
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
        return cards.size
    }

    /** Confirms to the watch which grades were applied, so it can clear them. */
    suspend fun pushGradesAck(appliedCardIds: List<Long>) {
        val json = JSONArray()
        appliedCardIds.forEach { json.put(it) }
        putData(PATH_GRADES_ACK) {
            it.putString("acked_ids", json.toString())
            it.putLong("ack_time", System.currentTimeMillis())
        }
        Log.d(TAG, "Acked ${appliedCardIds.size} grades to watch")
    }

    /**
     * Asks the watch to upload any grades it has queued. Used by the phone's
     * "Sync now" button — the grade queue lives on the watch, so the phone has
     * to request it rather than reach for it.
     */
    suspend fun requestGradesFromWatch() {
        putData(PATH_SYNC_REQUEST) {
            it.putLong("requested_at", System.currentTimeMillis())
        }
        Log.d(TAG, "Asked watch to upload its grade queue")
    }

    /** True if a watch running our app is currently reachable. */
    suspend fun isWatchReachable(): Boolean = try {
        val info = Wearable.getCapabilityClient(context)
            .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
        info.nodes.isNotEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "Could not check watch reachability", e)
        false
    }

    private suspend fun putData(path: String, fill: (DataMap) -> Unit) {
        try {
            val request = PutDataMapRequest.create(path)
            fill(request.dataMap)
            Wearable.getDataClient(context)
                .putDataItem(request.asPutDataRequest().setUrgent())
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push data to $path", e)
        }
    }
}