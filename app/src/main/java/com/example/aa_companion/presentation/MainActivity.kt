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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

data class AnkiCard(val id: Long, val front: String, val back: String)

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val TEST_MESSAGE_PATH = "/wear/test-message"
    private val RESPONSE_PATH = "/wear/deck_buffer"
    private val TAG = "WearTest"

    //Initialize the ViewModel
    private val viewModel: AnkiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Observe the state from the ViewModel
            val cards by viewModel.cards.collectAsState()
            val currentIndex by viewModel.currentIndex.collectAsState()
            val isShowingFront by viewModel.isShowingFront.collectAsState()

            WearTestScreen(
                cards = cards,
                currentIndex = currentIndex,
                isShowingFront = isShowingFront,
                onCardTap = { viewModel.handleCardTap() },
                onFetchClick = { sendTestMessage() }
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

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == RESPONSE_PATH) {

                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val jsonString = dataMap.getString("cards_json") ?: return

                Log.d(TAG, "Payload Received from DataClient! Size: ${jsonString.length} bytes")

                try {
                    val jsonArray = JSONArray(jsonString)
                    val parsedList = mutableListOf<AnkiCard>()

                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        parsedList.add(
                            AnkiCard(
                                id = jsonObject.getLong("id"),
                                front = jsonObject.getString("front"),
                                back = jsonObject.getString("back")
                            )
                        )
                    }

                    Log.d(TAG, "Successfully parsed ${parsedList.size} cards!")
                    // Push the data into the ViewModel instead of a local variable
                    viewModel.updateCards(parsedList)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse incoming JSON payload", e)
                }
            }
        }
    }

    private fun sendTestMessage() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val capabilityInfo = Wearable.getCapabilityClient(this@MainActivity)
                    .getCapability("anki_phone_app", CapabilityClient.FILTER_REACHABLE)
                    .await()

                val targetNodes = capabilityInfo.nodes

                if (targetNodes.isEmpty()) {
                    Log.e(TAG, "No nodes found with 'anki_phone_app' capability!")
                    return@launch
                }

                for (node in targetNodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, TEST_MESSAGE_PATH, "Fetch Cards".toByteArray())
                        .await()
                    Log.d(TAG, "Fetch request sent successfully to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Message failed to send", e)
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
    onFetchClick: () -> Unit
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (cards.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No cards loaded.", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFetchClick) {
                        Text("Fetch Batch")
                    }
                }
            } else if (currentIndex < cards.size) {
                val currentCard = cards[currentIndex]
                val scrollState = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onCardTap() } // UI delegates logic to ViewModel
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isShowingFront) "FRONT (${currentIndex + 1}/${cards.size})" else "BACK",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isShowingFront) currentCard.front else currentCard.back,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Deck Finished!", color = Color.Green, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFetchClick) {
                        Text("Fetch More")
                    }
                }
            }
        }
    }
}