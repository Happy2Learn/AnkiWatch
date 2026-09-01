package com.rella.ankiwear.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The middleman's only screen: permission status + favorite deck picker.
 * Daily sync runs in the background service and never needs this screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var anki: AnkiDroidHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        anki = AnkiDroidHelper(applicationContext)

        setContent {
            MaterialTheme {
                SettingsScreen(anki)
            }
        }
    }
}

@Composable
fun SettingsScreen(anki: AnkiDroidHelper) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember { mutableStateOf(anki.hasPermission()) }
    var ankiInstalled by remember { mutableStateOf(anki.isAnkiDroidInstalled()) }
    var decks by remember { mutableStateOf<List<AnkiDroidHelper.DeckInfo>>(emptyList()) }

    val prefs = remember {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    }
    var favorites by remember {
        mutableStateOf(prefs.getStringSet("favorite_decks", emptySet()) ?: emptySet())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission) {
        if (hasPermission) decks = anki.getDecks()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Anki Wear Companion", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            if (!ankiInstalled) {
                Text(
                    "AnkiDroid is not installed. Install it from the Play Store or F-Droid first.",
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            // Permission card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (hasPermission) "✅ Connected to AnkiDroid"
                        else "Permission needed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!hasPermission) {
                        Spacer(Modifier.height(8.dp))
                        Text("The app needs one-time access to read and review your AnkiDroid cards.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            permissionLauncher.launch(AnkiDroidHelper.PERMISSION)
                        }) {
                            Text("Grant access")
                        }
                    }
                }
            }

            if (hasPermission) {
                Spacer(Modifier.height(24.dp))
                Text("Favorite decks (auto-loaded on your watch):",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                LazyColumn {
                    items(decks) { deck ->
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
                                    prefs.edit()
                                        .putStringSet("favorite_decks", favorites)
                                        .apply()
                                }
                            )
                            Text("${deck.name} (${deck.dueCount} due)")
                        }
                    }
                }
            }
        }
    }
}
