package com.rella.ankiwear.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The middleman's UI.
 *
 * Two screens:
 *  - Settings (default): permission status, "Sync now", favorite deck picker
 *  - Test panel (behind a button): manual AnkiDroid API checks, including a
 *    single real grade write-back. Kept off the main screen because grading
 *    modifies the user's real collection.
 */
class MainActivity : ComponentActivity() {

    private lateinit var anki: AnkiDroidHelper
    private lateinit var pusher: WearPusher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        anki = AnkiDroidHelper(applicationContext)
        pusher = WearPusher(applicationContext, anki)

        setContent {
            MaterialTheme {
                var showTestPanel by remember { mutableStateOf(false) }

                if (showTestPanel) {
                    TestPanelScreen(
                        anki = anki,
                        pusher = pusher,
                        onBack = { showTestPanel = false }
                    )
                } else {
                    SettingsScreen(
                        anki = anki,
                        pusher = pusher,
                        onOpenTestPanel = { showTestPanel = true }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    anki: AnkiDroidHelper,
    pusher: WearPusher,
    onOpenTestPanel: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(anki.hasPermission()) }
    val ankiInstalled = remember { anki.isAnkiDroidInstalled() }
    var decks by remember { mutableStateOf<List<AnkiDroidHelper.DeckInfo>>(emptyList()) }
    var loadingDecks by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(FavoriteDecks.rawSet(context)) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    suspend fun reloadDecks() {
        loadingDecks = true
        decks = withContext(Dispatchers.IO) { anki.getDecks() }
        loadingDecks = false
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) reloadDecks()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Anki Wear Companion", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            if (!ankiInstalled) {
                Text(
                    "AnkiDroid is not installed. Install it from the Play Store or F-Droid first.",
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            // ---------- Permission ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (hasPermission) "Connected to AnkiDroid"
                        else "Permission needed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!hasPermission) {
                        Spacer(Modifier.height(8.dp))
                        Text("The app needs one-time access to read and review your AnkiDroid cards.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(AnkiDroidHelper.PERMISSION) }) {
                            Text("Grant access")
                        }
                    }
                }
            }

            if (!hasPermission) return@Column

            // ---------- Sync ----------
            Spacer(Modifier.height(24.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sync with watch", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open the Anki app on your watch, then tap Sync now.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !syncing,
                        onClick = {
                            syncing = true
                            syncStatus = "Syncing..."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    if (!pusher.isWatchReachable()) {
                                        "Watch not reachable. Is it nearby with the app open?"
                                    } else {
                                        // Ask the watch for its grades (they live
                                        // there), and send down fresh cards.
                                        pusher.requestGradesFromWatch()
                                        val deckCount = pusher.pushDeckList()
                                        val cardCount = pusher.pushCards(FavoriteDecks.read(context))
                                        if (deckCount == 0) {
                                            "No decks found."
                                        } else if (cardCount == 0) {
                                            "Sent 0 cards. Pick decks below, or nothing is due."
                                        } else {
                                            "Sent $cardCount cards. Grades will arrive shortly."
                                        }
                                    }
                                }
                                syncStatus = result
                                reloadDecks()
                                syncing = false
                            }
                        }
                    ) {
                        Text(if (syncing) "Syncing..." else "Sync now")
                    }
                    syncStatus?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---------- Deck picker ----------
            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Decks to send to your watch",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !loadingDecks,
                    onClick = { scope.launch { reloadDecks() } }
                ) {
                    Text(if (loadingDecks) "..." else "Refresh")
                }
            }
            Text(
                "Changes save automatically. Tap Refresh after adding decks in AnkiDroid.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(8.dp))

            if (decks.isEmpty() && !loadingDecks) {
                Text("No decks found in AnkiDroid.")
            }

            // A plain Column (not LazyColumn) so it scrolls with the page.
            decks.forEach { deck ->
                val id = deck.deckId.toString()
                val checked = favorites.contains(id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            favorites = if (isChecked) favorites + id else favorites - id
                            FavoriteDecks.write(context, favorites)
                        }
                    )
                    Text("${deck.name} (${deck.dueCount} due)")
                }
            }

            // ---------- Test panel entry ----------
            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenTestPanel) {
                Text("Developer test panel")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Manual checks against the real AnkiDroid API.
 *
 * This screen exists to validate the two calls we cannot exercise without a
 * watch: fetching due cards, and writing a grade back. The grade test is
 * deliberately destructive-but-tiny (one card) and clearly labelled.
 */
@Composable
fun TestPanelScreen(
    anki: AnkiDroidHelper,
    pusher: WearPusher,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var log by remember { mutableStateOf("Ready.") }
    var busy by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<AnkiDroidHelper.DueCard>>(emptyList()) }

    fun run(label: String, block: suspend () -> String) {
        busy = true
        log = "$label..."
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "FAILED: ${e::class.simpleName}: ${e.message}"
            }
            log = result
            busy = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            TextButton(onClick = onBack) { Text("< Back to settings") }
            Spacer(Modifier.height(8.dp))
            Text("Test panel", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Manual checks against your real AnkiDroid collection.",
                style = MaterialTheme.typography.bodySmall
            )

            // ---------- Read tests ----------
            Spacer(Modifier.height(24.dp))
            Text("Read tests (safe)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    run("Listing decks") {
                        val decks = anki.getDecks()
                        if (decks.isEmpty()) {
                            "No decks returned. Is AnkiDroid set up?"
                        } else {
                            "Found ${decks.size} decks:\n" +
                                decks.take(10).joinToString("\n") {
                                    "  ${it.name} - ${it.dueCount} due"
                                }
                        }
                    }
                }
            ) { Text("1. List decks") }

            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    run("Fetching due cards") {
                        val deckIds = FavoriteDecks.read(context)
                        if (deckIds.isEmpty()) {
                            "No decks selected. Go back and check at least one deck."
                        } else {
                            val cards = anki.getDueCards(deckIds, limit = 5)
                            fetched = cards
                            if (cards.isEmpty()) {
                                "Fetched 0 cards. Either nothing is due, or the " +
                                    "schedule query needs fixing."
                            } else {
                                val first = cards.first()
                                "Fetched ${cards.size} card(s).\n\n" +
                                    "First card:\n" +
                                    "  noteId=${first.noteId} ord=${first.cardOrd}\n" +
                                    "  front: ${first.front.take(120)}\n" +
                                    "  back: ${first.back.take(120)}"
                            }
                        }
                    }
                }
            ) { Text("2. Fetch due cards") }

            // ---------- Watch test ----------
            Spacer(Modifier.height(24.dp))
            Text("Watch connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    run("Checking watch") {
                        if (pusher.isWatchReachable()) {
                            "Watch is reachable."
                        } else {
                            "No watch found. Make sure it's paired, nearby, and " +
                                "the Anki app is installed on it."
                        }
                    }
                }
            ) { Text("3. Check watch reachable") }

            // ---------- Write test ----------
            Spacer(Modifier.height(24.dp))
            Text("Write test", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "This really reviews a card.",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "It grades the first fetched card as \"Good\" in your real " +
                            "collection and changes its schedule. Use a throwaway " +
                            "deck if you'd rather not touch real cards. AnkiDroid's " +
                            "Undo can reverse it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy && fetched.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                onClick = {
                    run("Grading one card") {
                        val card = fetched.first()
                        val grade = AnkiDroidHelper.Grade(
                            noteId = card.noteId,
                            cardOrd = card.cardOrd,
                            ease = AnkiDroidHelper.EASE_GOOD,
                            timeTakenMs = 3000,
                            reviewedAtMs = System.currentTimeMillis()
                        )
                        val ok = anki.applyGrade(grade)
                        if (ok) {
                            "SUCCESS: AnkiDroid accepted the review for " +
                                "noteId=${card.noteId} ord=${card.cardOrd}.\n\n" +
                                "Check the card's info in AnkiDroid to confirm a " +
                                "new review was logged."
                        } else {
                            "FAILED: AnkiDroid rejected the review. The write-back " +
                                "call or its column names need fixing."
                        }
                    }
                }
            ) {
                Text(
                    if (fetched.isEmpty()) "4. Grade a card (fetch first)"
                    else "4. Grade first card as Good"
                )
            }

            // ---------- Output ----------
            Spacer(Modifier.height(24.dp))
            Text("Result", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    log,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}