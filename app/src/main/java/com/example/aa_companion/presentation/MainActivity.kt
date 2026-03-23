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

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val TEST_MESSAGE_PATH = "/wear/test-message"
    private val RESPONSE_PATH = "/wear/deck_buffer"
    private val TAG = "WearTest"

    private val viewModel: AnkiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadCachedCardsOnBoot()

        setContent {
            val cards by viewModel.cards.collectAsState()
            val currentIndex by viewModel.currentIndex.collectAsState()
            val isShowingFront by viewModel.isShowingFront.collectAsState()

            WearTestScreen(
                cards = cards,
                currentIndex = currentIndex,
                isShowingFront = isShowingFront,
                onCardTap = { viewModel.flipToBack() },
                onFetchClick = { sendTestMessage() },
                onGradeClick = { cardId, ease ->
                    sendGrade(cardId, ease)
                    viewModel.nextCard()
                },
                onMediaClick = { cardId ->
                    sendMediaRequest(cardId)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    private fun loadCachedCardsOnBoot() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = java.io.File(this@MainActivity.filesDir, "anki_cache.json")
                if (file.exists()) {
                    val jsonString = file.readText()
                    parseAndLoadCards(jsonString)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read from disk", e)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == RESPONSE_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val jsonString = dataMap.getString("cards_json") ?: return

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val file = java.io.File(this@MainActivity.filesDir, "anki_cache.json")
                        file.writeText(jsonString)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to save to disk", e)
                    }
                }

                parseAndLoadCards(jsonString)
            }
        }
    }

    // Helper for robust boolean parsing
    private fun getSafeBool(json: JSONObject, key: String): Boolean {
        return try {
            val raw = json.get(key)
            if (raw is Boolean) raw else raw.toString().toBoolean()
        } catch (e: Exception) { false }
    }

    private fun parseAndLoadCards(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            val parsedList = mutableListOf<AnkiCard>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)

                parsedList.add(
                    AnkiCard(
                        id = jsonObject.getLong("id"),
                        front = jsonObject.getString("front"),
                        back = jsonObject.getString("back"),
                        frontHasMedia = getSafeBool(jsonObject, "frontHasMedia"),
                        backHasMedia = getSafeBool(jsonObject, "backHasMedia")
                    )
                )
            }
            CoroutineScope(Dispatchers.Main).launch {
                viewModel.updateCards(parsedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
        }
    }

    private fun sendTestMessage() {
        sendMessageToPhone(TEST_MESSAGE_PATH, "Fetch Cards".toByteArray())
    }

    private fun sendGrade(cardId: Long, ease: Int) {
        try {
            val jsonPayload = org.json.JSONObject().apply {
                put("id", cardId)
                put("ease", ease)
            }.toString().toByteArray()
            sendMessageToPhone("/wear/answer_card", jsonPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build grade payload", e)
        }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No cards loaded.", color = Color.White)
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

                        // GRADING BUTTONS
                        if (!isShowingFront) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { onGradeClick(currentCard.id, 1) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red),
                                    modifier = Modifier.size(36.dp)
                                ) { Text("1", color = Color.White) }

                                Button(
                                    onClick = { onGradeClick(currentCard.id, 2) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFFA500)),
                                    modifier = Modifier.size(36.dp)
                                ) { Text("2", color = Color.White) }

                                Button(
                                    onClick = { onGradeClick(currentCard.id, 3) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00AA00)),
                                    modifier = Modifier.size(36.dp)
                                ) { Text("3", color = Color.White) }

                                Button(
                                    onClick = { onGradeClick(currentCard.id, 4) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Blue),
                                    modifier = Modifier.size(36.dp)
                                ) { Text("4", color = Color.White) }
                            }
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Deck Finished!", color = Color.Green, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFetchClick) { Text("Fetch More") }
                }
            }
        }
    }
}