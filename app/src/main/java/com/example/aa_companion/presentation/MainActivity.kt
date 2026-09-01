package com.example.aa_companion.presentation

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

data class AnkiCard(
    val id: Long,
    val front: String,
    val back: String,
    val frontHasMedia: Boolean = false,
    val backHasMedia: Boolean = false
)

/** One grade recorded on the watch, waiting to be uploaded to the phone. */
data class QueuedGrade(
    val cardId: Long,
    val ease: Int,
    val timeTakenMs: Long,
    val reviewedAtMs: Long
)

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val FETCH_DECKS_PATH = "/wear/fetch_decks"
    private val FETCH_CARDS_PATH = "/wear/fetch_cards"
    private val RESPONSE_PATH = "/wear/deck_buffer"
    private val GRADES_ACK_PATH = "/wear/grades_ack"
    private val TAG = "WearTest"

    private val viewModel: AnkiViewModel by viewModels()

    // Grades recorded while the phone is away, persisted on disk so they
    // survive reboots and dead batteries.
    private val gradeQueue = mutableListOf<QueuedGrade>()
    private var cardShownAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadCachedCardsOnBoot()
        loadGradeQueueFromDisk()

        setContent {
            val cards by viewModel.cards.collectAsState()
            val currentIndex by viewModel.currentIndex.collectAsState()
            val isShowingFront by viewModel.isShowingFront.collectAsState()
            val pendingCount by viewModel.pendingGrades.collectAsState()

            WearTestScreen(
                cards = cards,
                currentIndex = currentIndex,
                isShowingFront = isShowingFront,
                pendingGrades = pendingCount,
                onCardTap = { viewModel.flipToBack() },
                onFetchClick = { requestFavoriteCards() },
                onGradeClick = { cardId, ease ->
                    recordGrade(cardId, ease)
                    viewModel.nextCard()
                    cardShownAtMs = System.currentTimeMillis()
                },
                onMediaClick = { cardId -> sendMediaRequest(cardId) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        cardShownAtMs = System.currentTimeMillis()
        // If the phone is nearby, flush any queued grades right away.
        CoroutineScope(Dispatchers.IO).launch { uploadGradeQueueIfPossible() }
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    // ---------- Card loading ----------

    private fun loadCachedCardsOnBoot() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = java.io.File(this@MainActivity.filesDir, "anki_cache.json")
                if (file.exists()) {
                    parseAndLoadCards(file.readText())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read from disk", e)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            when (event.dataItem.uri.path) {
                RESPONSE_PATH -> {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val jsonString = dataMap.getString("cards_json") ?: continue

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            java.io.File(this@MainActivity.filesDir, "anki_cache.json")
                                .writeText(jsonString)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to save to disk", e)
                        }
                    }
                    parseAndLoadCards(jsonString)
                }
                GRADES_ACK_PATH -> {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val acked = dataMap.getString("acked_ids") ?: continue
                    clearAckedGrades(acked)
                }
            }
        }
    }

    private fun getSafeBool(json: JSONObject, key: String): Boolean {
        return try {
            val raw = json.get(key)
            if (raw is Boolean) raw else raw.toString().toBoolean()
        } catch (e: Exception) { false }
    }

    private fun parseAndLoadCards(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            val gradedIds = gradeQueue.map { it.cardId }.toSet()
            val parsedList = mutableListOf<AnkiCard>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val card = AnkiCard(
                    id = jsonObject.getLong("id"),
                    front = jsonObject.getString("front"),
                    back = jsonObject.getString("back"),
                    frontHasMedia = getSafeBool(jsonObject, "frontHasMedia"),
                    backHasMedia = getSafeBool(jsonObject, "backHasMedia")
                )
                // "Don't repeat": cards already graded on the watch are hidden
                // until the phone confirms them and sends a fresh batch.
                if (card.id !in gradedIds) parsedList.add(card)
            }
            CoroutineScope(Dispatchers.Main).launch {
                viewModel.updateCards(parsedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
        }
    }

    // ---------- Grade queue (offline-first) ----------

    private fun recordGrade(cardId: Long, ease: Int) {
        val timeTaken = (System.currentTimeMillis() - cardShownAtMs).coerceAtLeast(0)
        val grade = QueuedGrade(cardId, ease, timeTaken, System.currentTimeMillis())
        gradeQueue.add(grade)
        viewModel.setPendingGrades(gradeQueue.size)
        saveGradeQueueToDisk()
        // Try to upload immediately; no-op if the phone is out of range.
        CoroutineScope(Dispatchers.IO).launch { uploadGradeQueueIfPossible() }
    }

    private fun gradeQueueFile() = java.io.File(filesDir, "grade_queue.json")

    private fun saveGradeQueueToDisk() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONArray()
                gradeQueue.forEach { g ->
                    json.put(JSONObject().apply {
                        put("id", g.cardId)
                        put("ease", g.ease)
                        put("timeTaken", g.timeTakenMs)
                        put("reviewedAt", g.reviewedAtMs)
                    })
                }
                gradeQueueFile().writeText(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save grade queue", e)
            }
        }
    }

    private fun loadGradeQueueFromDisk() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = gradeQueueFile()
                if (!file.exists()) return@launch
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    gradeQueue.add(
                        QueuedGrade(
                            cardId = obj.getLong("id"),
                            ease = obj.getInt("ease"),
                            timeTakenMs = obj.optLong("timeTaken", 0),
                            reviewedAtMs = obj.optLong("reviewedAt", 0)
                        )
                    )
                }
                viewModel.setPendingGrades(gradeQueue.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load grade queue", e)
            }
        }
    }

    /** Sends the whole queue to the phone. The queue is only cleared after
     *  the phone acks (see [clearAckedGrades]), so nothing is ever lost. */
    private suspend fun uploadGradeQueueIfPossible() {
        if (gradeQueue.isEmpty()) return
        val json = JSONArray()
        gradeQueue.forEach { g ->
            json.put(JSONObject().apply {
                put("id", g.cardId)
                put("ease", g.ease)
                put("timeTaken", g.timeTakenMs)
                put("reviewedAt", g.reviewedAtMs)
            })
        }
        sendMessageToPhone("/wear/grade_queue", json.toString().toByteArray())
    }

    private fun clearAckedGrades(ackedIdsJson: String) {
        try {
            val array = JSONArray(ackedIdsJson)
            val acked = (0 until array.length()).map { array.getLong(it) }.toSet()
            gradeQueue.removeAll { it.cardId in acked }
            viewModel.setPendingGrades(gradeQueue.size)
            saveGradeQueueToDisk()
            Log.d(TAG, "Cleared ${acked.size} acked grades; ${gradeQueue.size} pending")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear acked grades", e)
        }
    }

    // ---------- Outgoing requests ----------

    private fun requestFavoriteCards() {
        // Empty payload = "use the favorites configured on the phone".
        sendMessageToPhone(FETCH_CARDS_PATH, "{\"deckIds\":[]}".toByteArray())
    }

    private fun sendMediaRequest(cardId: Long) {
        sendMessageToPhone("/wear/view_media", cardId.toString().toByteArray())
    }

    private fun sendMessageToPhone(path: String, payload: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val capabilityInfo = Wearable.getCapabilityClient(this@MainActivity)
                    .getCapability("anki_phone_app", CapabilityClient.FILTER_REACHABLE).await()

                val targetNodes = capabilityInfo.nodes
                for (node in targetNodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, path, payload).await()
                    Log.d(TAG, "Sent $path to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message failed to send to $path", e)
            }
        }
    }
}

@Composable
fun WearTestScreen(
    cards: List<AnkiCard>,
    currentIndex: Int,
    isShowingFront: Boolean,
    pendingGrades: Int,
    onCardTap: () -> Unit,
    onFetchClick: () -> Unit,
    onGradeClick: (Long, Int) -> Unit,
    onMediaClick: (Long) -> Unit
) {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (cards.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    if (pendingGrades > 0) {
                        Text(
                            "All caught up!",
                            color = Color.Green,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$pendingGrades grade${if (pendingGrades == 1) "" else "s"} will sync\nwhen your phone is nearby.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text("No cards loaded.", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFetchClick) { Text("Fetch Batch") }
                }
            } else if (currentIndex < cards.size) {
                val currentCard = cards[currentIndex]
                val scrollState = rememberScrollState()

                val boxModifier = if (isShowingFront) {
                    Modifier.fillMaxSize().clickable { onCardTap() }.padding(16.dp)
                } else {
                    Modifier.fillMaxSize().padding(16.dp)
                }

                Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isShowingFront) "FRONT (${currentIndex + 1}/${cards.size})" else "BACK",
                            color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val cardText = if (isShowingFront) currentCard.front else currentCard.back
                        Text(text = cardText, color = Color.White, textAlign = TextAlign.Center, fontSize = 16.sp)

                        val showMediaButton = if (isShowingFront) currentCard.frontHasMedia else currentCard.backHasMedia

                        if (showMediaButton) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onMediaClick(currentCard.id) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                            ) {
                                Text("View Media on Phone", fontSize = 10.sp, color = Color.White)
                            }
                        }

                        // GRADING BUTTONS — labeled, sized for round screens
                        if (!isShowingFront) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                GradeButton("Again", Color(0xFFD32F2F)) { onGradeClick(currentCard.id, 1) }
                                GradeButton("Hard", Color(0xFFFFA500)) { onGradeClick(currentCard.id, 2) }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                GradeButton("Good", Color(0xFF00AA00)) { onGradeClick(currentCard.id, 3) }
                                GradeButton("Easy", Color(0xFF1976D2)) { onGradeClick(currentCard.id, 4) }
                            }
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Deck Finished!", color = Color.Green, textAlign = TextAlign.Center)
                    if (pendingGrades > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$pendingGrades grade${if (pendingGrades == 1) "" else "s"} waiting to sync.",
                            color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFetchClick) { Text("Fetch More") }
                }
            }
        }
    }
}

@Composable
private fun GradeButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor = color),
        modifier = Modifier.size(width = 72.dp, height = 40.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
