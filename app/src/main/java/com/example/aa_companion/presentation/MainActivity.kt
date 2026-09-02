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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

    /** Phone asking us to upload our queued grades. */
    private val SYNC_REQUEST_PATH = "/wear/sync_request"

    /** Us asking the phone to run a full sync. */
    private val SYNC_NOW_PATH = "/wear/sync_now"

    private val TAG = "WearTest"

    private val viewModel: AnkiViewModel by viewModels()

    // Grades recorded while the phone is away, persisted on disk so they
    // survive reboots and dead batteries.
    private val gradeQueue = mutableListOf<QueuedGrade>()
    private var cardShownAtMs: Long = 0L

    /**
     * Cards graded within the current batch, kept hidden even after the phone
     * acknowledges them.
     *
     * [gradeQueue] alone is not enough: once a grade is acked it leaves the
     * queue, and the card would reappear in the batch still on screen. This set
     * is cleared only when a genuinely new batch arrives, at which point a card
     * legitimately coming due again (e.g. graded "Again") should be shown.
     */
    private val gradedInBatch = mutableSetOf<Long>()

    /**
     * Timestamp of the batch currently loaded. Only strictly newer batches are
     * applied, so waking the screen cannot reload the batch and throw the user
     * back to card 1.
     */
    private var loadedBatchTime: Long = 0L

    /** Serializes all reads/writes of the review state above. */
    private val stateMutex = Mutex()

    /**
     * True between the user asking for a sync and the resulting card batch
     * being applied. Incoming batches mid-review are ignored unless this is
     * set, so a stray restock cannot reset the user to card 1.
     */
    @Volatile private var expectingSync = false

    /**
     * Completes once the on-disk state has been restored. Anything that filters
     * cards must wait for this, or it may run with an empty grade queue and
     * show already-graded cards again.
     */
    private val restoreComplete = CompletableDeferred<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Strictly ordered: the grade queue and graded-set must be in memory
        // before any card list is filtered, otherwise nothing gets hidden.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                restoreSessionFromDisk()
                restoreGradeQueueFromDisk()
                loadCachedCards()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore saved state", e)
            } finally {
                restoreComplete.complete(Unit)
            }
        }

        setContent {
            val cards by viewModel.cards.collectAsState()
            val currentIndex by viewModel.currentIndex.collectAsState()
            val isShowingFront by viewModel.isShowingFront.collectAsState()
            val pendingCount by viewModel.pendingGrades.collectAsState()

            val syncMessage by viewModel.syncStatus.collectAsState()

            WearTestScreen(
                cards = cards,
                currentIndex = currentIndex,
                isShowingFront = isShowingFront,
                pendingGrades = pendingCount,
                syncStatus = syncMessage,
                onCardTap = { viewModel.flipToBack() },
                onSyncClick = { syncNow() },
                onGradeClick = { card, ease ->
                    recordGrade(card, ease)
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
        CoroutineScope(Dispatchers.IO).launch {
            restoreComplete.await()
            // onDataChanged only fires for *live* changes. Anything the phone
            // pushed while this app was closed is sitting in the data store
            // unread, so pick it up explicitly. Batches already loaded are
            // ignored, so this cannot disturb an in-progress review.
            readStoredDataItems()
        }
    }

    /**
     * Reads card batches and acks that the phone already delivered.
     *
     * Without this, a batch pushed while the watch app was closed would be
     * silently missed: DataClient stores it, but nothing would ever read it.
     */
    private suspend fun readStoredDataItems() {
        try {
            val items = Wearable.getDataClient(this).dataItems.await()
            items.use { buffer ->
                for (item in buffer) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    when (item.uri.path) {
                        RESPONSE_PATH ->
                            dataMap.getString("cards_json")?.let { json ->
                                applyBatch(json, dataMap.getLong("batch_time", 0L))
                            }

                        GRADES_ACK_PATH ->
                            dataMap.getString("acked_ids")?.let { clearAckedGrades(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read stored data items", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    // ---------- Card loading ----------

    /**
     * Restores the last batch from disk so the app works offline after a
     * restart. Cards already graded are filtered out, so review resumes at the
     * first card the user has not answered yet.
     */
    private suspend fun loadCachedCards() {
        try {
            val file = java.io.File(filesDir, "anki_cache.json")
            if (!file.exists()) return
            showCards(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached cards", e)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            when (event.dataItem.uri.path) {
                RESPONSE_PATH -> {
                    val jsonString = dataMap.getString("cards_json") ?: continue
                    val batchTime = dataMap.getLong("batch_time", 0L)
                    CoroutineScope(Dispatchers.IO).launch {
                        restoreComplete.await()
                        applyBatch(jsonString, batchTime)
                    }
                }

                GRADES_ACK_PATH -> {
                    val acked = dataMap.getString("acked_ids") ?: continue
                    CoroutineScope(Dispatchers.IO).launch {
                        restoreComplete.await()
                        clearAckedGrades(acked)
                    }
                }

                // Phone tapped "Sync now": send grades, then accept the card
                // batch that will follow.
                SYNC_REQUEST_PATH -> {
                    Log.d(TAG, "Phone requested our grade queue")
                    expectingSync = true
                    CoroutineScope(Dispatchers.IO).launch {
                        restoreComplete.await()
                        uploadGradeQueueIfPossible()
                    }
                }
            }
        }
    }

    private fun cacheCardsJson(jsonString: String) {
        try {
            java.io.File(filesDir, "anki_cache.json").writeText(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cards to disk", e)
        }
    }

    private fun getSafeBool(json: JSONObject, key: String): Boolean {
        return try {
            val raw = json.get(key)
            if (raw is Boolean) raw else raw.toString().toBoolean()
        } catch (e: Exception) { false }
    }

    /**
     * Applies a batch pushed by the phone, if it is newer than what we have.
     *
     * Older or already-seen batches are ignored. This matters because
     * DataClient retains data items indefinitely, so the same batch is re-read
     * on every resume (including every screen wake); reapplying it would reset
     * the review position and make one card repeat forever.
     */
    private suspend fun applyBatch(jsonString: String, batchTime: Long) {
        val midReview = viewModel.cards.value.isNotEmpty() &&
            viewModel.currentIndex.value < viewModel.cards.value.size
        if (midReview && !expectingSync) {
            Log.d(TAG, "Ignoring batch $batchTime; review in progress")
            return
        }

        val isNew = stateMutex.withLock {
            if (batchTime != 0L && batchTime <= loadedBatchTime) {
                Log.d(TAG, "Ignoring batch $batchTime; already at $loadedBatchTime")
                return@withLock false
            }
            loadedBatchTime = batchTime
            gradedInBatch.clear()
            true
        }
        if (!isNew) return

        expectingSync = false
        cacheCardsJson(jsonString)
        persistSession()
        showCards(jsonString)
    }

    /**
     * Parses a card batch and shows the cards the user has not answered yet.
     *
     * Hidden cards are those graded in this batch ([gradedInBatch]) plus any
     * still queued for upload ([gradeQueue]) — the latter guards against
     * double-grading if the phone re-sends a card before processing the grade.
     */
    private suspend fun showCards(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            // Only Hard/Good/Easy hide a card for the rest of the batch.
            // Again cards are intentionally left visible so they can reappear
            // locally after a short delay.
            val hidden = stateMutex.withLock { gradedInBatch.toSet() }
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
                if (card.id !in hidden) parsedList.add(card)
            }

            val done = jsonArray.length() - parsedList.size
            withContext(Dispatchers.Main) {
                viewModel.updateCards(parsedList)
                viewModel.setSyncStatus(
                    when {
                        parsedList.isEmpty() && done > 0 -> "All $done cards done"
                        done > 0 -> "${parsedList.size} to go ($done done)"
                        else -> "${parsedList.size} cards ready"
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse card JSON", e)
            withContext(Dispatchers.Main) {
                viewModel.setSyncStatus("Couldn't read cards from phone")
            }
        }
    }

    // ---------- Session persistence ----------

    private fun sessionFile() = java.io.File(filesDir, "session.json")

    /** Saves which batch is loaded and which of its cards are already graded. */
    private suspend fun persistSession() {
        try {
            val json = stateMutex.withLock {
                JSONObject().apply {
                    put("batchTime", loadedBatchTime)
                    put("gradedInBatch", JSONArray().apply {
                        gradedInBatch.forEach { put(it) }
                    })
                }
            }
            sessionFile().writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
        }
    }

    private suspend fun restoreSessionFromDisk() {
        try {
            val file = sessionFile()
            if (!file.exists()) return
            val obj = JSONObject(file.readText())
            val batchTime = obj.optLong("batchTime", 0L)
            val graded = obj.optJSONArray("gradedInBatch")
            stateMutex.withLock {
                loadedBatchTime = batchTime
                gradedInBatch.clear()
                if (graded != null) {
                    for (i in 0 until graded.length()) gradedInBatch.add(graded.getLong(i))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore session", e)
        }
    }

    // ---------- Grade queue (offline-first) ----------

    private fun recordGrade(card: AnkiCard, ease: Int) {
        val timeTaken = (System.currentTimeMillis() - cardShownAtMs).coerceAtLeast(0)
        val grade = QueuedGrade(card.id, ease, timeTaken, System.currentTimeMillis())
        val isAgain = ease == 1
        if (isAgain) viewModel.scheduleAgain(card)
        CoroutineScope(Dispatchers.IO).launch {
            val pending = stateMutex.withLock {
                gradeQueue.add(grade)
                // Again is not hidden: it will come back locally in ~10s.
                // Hard/Good/Easy stay hidden for the rest of this batch.
                if (!isAgain) gradedInBatch.add(card.id)
                gradeQueue.size
            }
            withContext(Dispatchers.Main) { viewModel.setPendingGrades(pending) }
            saveGradeQueueToDisk()
            persistSession()
            // Grades stay queued until the user taps Sync. Uploading on every
            // grade made the phone restock the card list and reset to card 1.
        }
    }

    private fun gradeQueueFile() = java.io.File(filesDir, "grade_queue.json")

    private suspend fun saveGradeQueueToDisk() {
        try {
            val json = stateMutex.withLock {
                JSONArray().apply {
                    gradeQueue.forEach { g ->
                        put(JSONObject().apply {
                            put("id", g.cardId)
                            put("ease", g.ease)
                            put("timeTaken", g.timeTakenMs)
                            put("reviewedAt", g.reviewedAtMs)
                        })
                    }
                }
            }
            gradeQueueFile().writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save grade queue", e)
        }
    }

    private suspend fun restoreGradeQueueFromDisk() {
        try {
            val file = gradeQueueFile()
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            val pending = stateMutex.withLock {
                gradeQueue.clear()
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
                gradeQueue.size
            }
            withContext(Dispatchers.Main) { viewModel.setPendingGrades(pending) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load grade queue", e)
        }
    }

    /** Sends the whole queue to the phone. The queue is only cleared after
     *  the phone acks (see [clearAckedGrades]), so nothing is ever lost. */
    private suspend fun uploadGradeQueueIfPossible() {
        val json = stateMutex.withLock {
            if (gradeQueue.isEmpty()) return
            JSONArray().apply {
                gradeQueue.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.cardId)
                        put("ease", g.ease)
                        put("timeTaken", g.timeTakenMs)
                        put("reviewedAt", g.reviewedAtMs)
                    })
                }
            }
        }
        sendMessageToPhone("/wear/grade_queue", json.toString().toByteArray())
    }

    /**
     * Full sync from the watch side: push our grades up, then ask the phone for
     * a fresh batch. Requires the phone app to be open and in range.
     */
    private fun syncNow() {
        expectingSync = true
        viewModel.setSyncStatus("Syncing...")
        CoroutineScope(Dispatchers.IO).launch {
            val reachable = isPhoneReachable()
            if (!reachable) {
                expectingSync = false
                withContext(Dispatchers.Main) {
                    viewModel.setSyncStatus("Phone not found. Open the Anki app on your phone.")
                }
                return@launch
            }
            uploadGradeQueueIfPossible()
            sendMessageToPhone(SYNC_NOW_PATH, ByteArray(0))
        }
    }

    private suspend fun isPhoneReachable(): Boolean = try {
        Wearable.getCapabilityClient(this)
            .getCapability("anki_phone_app", CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes.isNotEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "Could not check phone reachability", e)
        false
    }

    private suspend fun clearAckedGrades(ackedIdsJson: String) {
        try {
            val array = JSONArray(ackedIdsJson)
            val acked = (0 until array.length()).map { array.getLong(it) }.toSet()
            val pending = stateMutex.withLock {
                gradeQueue.removeAll { it.cardId in acked }
                gradeQueue.size
            }
            withContext(Dispatchers.Main) { viewModel.setPendingGrades(pending) }
            saveGradeQueueToDisk()
            Log.d(TAG, "Cleared ${acked.size} acked grades; $pending pending")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear acked grades", e)
        }
    }

    // ---------- Outgoing requests ----------

    private fun requestFavoriteCards() {
        viewModel.setSyncStatus("Asking phone for cards...")
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
                if (targetNodes.isEmpty()) {
                    Log.w(TAG, "No phone reachable for $path")
                    CoroutineScope(Dispatchers.Main).launch {
                        viewModel.setSyncStatus("Phone not found. Open the Anki app on your phone.")
                    }
                    return@launch
                }
                for (node in targetNodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, path, payload).await()
                    Log.d(TAG, "Sent $path to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message failed to send to $path", e)
                CoroutineScope(Dispatchers.Main).launch {
                    viewModel.setSyncStatus("Couldn't reach phone")
                }
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
    syncStatus: String?,
    onCardTap: () -> Unit,
    onSyncClick: () -> Unit,
    onGradeClick: (AnkiCard, Int) -> Unit,
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
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
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
                            "$pendingGrades grade${if (pendingGrades == 1) "" else "s"} waiting.\nOpen Anki on your phone, then Sync.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text("No cards loaded.", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onSyncClick) { Text("Sync", fontSize = 12.sp) }
                    SyncStatusText(syncStatus)
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
                            text = "${currentIndex + 1} of ${cards.size}",
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
                                GradeButton("Again", Color(0xFFD32F2F)) { onGradeClick(currentCard, 1) }
                                GradeButton("Hard", Color(0xFFFFA500)) { onGradeClick(currentCard, 2) }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                GradeButton("Good", Color(0xFF00AA00)) { onGradeClick(currentCard, 3) }
                                GradeButton("Easy", Color(0xFF1976D2)) { onGradeClick(currentCard, 4) }
                            }

                            // Reachable mid-batch: scroll past the grade buttons
                            // to sync without finishing every card first.
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onSyncClick,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                            ) {
                                Text("Sync", fontSize = 10.sp, color = Color.White)
                            }
                            SyncStatusText(syncStatus)
                            Spacer(modifier = Modifier.height(8.dp))
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
                    Button(onClick = onSyncClick) { Text("Sync", fontSize = 12.sp) }
                    SyncStatusText(syncStatus)
                }
            }
        }
    }
}

@Composable
private fun SyncStatusText(status: String?) {
    if (status.isNullOrBlank()) return
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        status,
        color = Color.Gray,
        fontSize = 10.sp,
        textAlign = TextAlign.Center
    )
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
