# AnkiDroid Wear OS Companion
 
A Wear OS companion app for [AnkiDroid](https://github.com/ankidroid/Anki-Android) that lets you review flashcards directly from your wrist — built as an MVP proof-of-concept.
 
> ⚠️ **This is an MVP / proof-of-concept.** It was built to validate the core data pipeline and watch-side rendering architecture. Code structure and practices reflect rapid prototyping, not production standards.
 
---
 
## What It Does
 
- **Fetch flashcard batches** from your phone's AnkiDroid app directly to your watch
- **Review cards on your wrist** — tap to flip from front to back
- **Grade cards** with Anki's standard 4-button ease system (Again / Hard / Good / Easy) — grades sync back to the phone in real time
- **Media handoff** — if a card contains media (image/audio), tap "View Media on Phone" to open it on your phone's screen
- **Offline cache** — cards are saved to disk on the watch so they survive app restarts without needing a re-fetch
- **Watch Face Complication** — shows your daily study streak or card count on any watch face
- **Wear OS Tile** — quick-glance dashboard without opening the app
---
 
## Architecture & Technical Decisions
 
This is a pure **Wear OS rendering surface**. All Anki logic, scheduling, and database access lives on the phone. The watch does zero computation beyond UI rendering and user input.
 
### Data Flow
 
```
AnkiDroid (Phone)
      │
      │  DataClient (/wear/deck_buffer)     ← card batch pushed to watch
      │  MessageClient (/wear/answer_card)  ← grade sent back to phone
      │  MessageClient (/wear/view_media)   ← media handoff request
      ▼
Wear OS Watch
      │
      ├── DataClient.OnDataChangedListener  ← receives card JSON
      ├── disk cache (anki_cache.json)      ← survives restarts
      ├── AnkiViewModel (StateFlow)         ← drives UI state
      └── Jetpack Compose UI                ← renders cards + grading
```
 
### Why DataClient for card delivery, MessageClient for grades
 
`DataClient` guarantees persistent delivery even if the watch is off or unreachable — cards arrive when the watch reconnects. `MessageClient` is fire-and-forget, which is fine for grades and media requests since those are triggered by an active user interaction where the connection is already live.
 
### Why no ViewModel injection / Hilt
 
This is an MVP. Hilt and a proper DI setup are the obvious next step but were intentionally skipped to keep the proof-of-concept fast and readable.
 
---
 
## Tech Stack
 
| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose for Wear OS |
| Watch ↔ Phone | Wear OS Data Layer API (`DataClient`, `MessageClient`, `CapabilityClient`) |
| State Management | `StateFlow` + `ViewModel` |
| Local Cache | File-based JSON (internal storage) |
| Watch Face | Complications API (`ComplicationDataSourceService`) |
| Tile | Wear Tiles (`TileService`) |
| Async | Kotlin Coroutines |
| HTML Parsing | Jsoup (for stripping HTML from card content) |
 
---
 
## Known Limitations (MVP Scope)
 
- No error state UI — failures are logged but not shown to the user
- No loading indicator while fetching cards
- Grading uses `CoroutineScope(Dispatchers.IO)` directly in Activity instead of a repository layer
- Card cache is a flat JSON file — no Room DB yet
- No `BootReceiver` — if the watch reboots, cache is read but sync state may be stale
- HTML in card content is partially handled via Jsoup but complex formatting may not render correctly on watch
---
 
## Relationship to GSoC 2026 Proposal
 
This repo is the working MVP behind a GSoC 2026 proposal to build a full AnkiDroid Wear OS companion. The three proposed user-facing outputs were a watch face complication, a 28-day study heatmap dashboard, and a WorkManager-based smart notification.
 
This MVP validates that the core data pipeline works end-to-end. The full proposal builds on this with proper architecture, WorkManager-based background sync, Room DB on the phone side, and a complete Jetpack Compose Wear OS UI system.
 
---
 
## Author
 
**Abhishek Gupta** — GSoC 2025 @ CCExtractor (Ultimate Alarm Clock Wear OS Companion)
 
[![LinkedIn](https://img.shields.io/badge/LinkedIn-%230A66C2?style=flat&logo=linkedin&logoColor=white)](https://linkedin.com/in/abhishek14104)
[![Portfolio](https://img.shields.io/badge/Portfolio-000000?style=flat&logo=vercel&logoColor=white)](https://abhishek14104.vercel.app/)
