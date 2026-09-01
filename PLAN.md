# Anki Companion — Build Plan

## Goal
Review Anki flashcards on a Samsung Galaxy Watch 7 (Wear OS), **phone-free**,
using the stock, unmodified AnkiDroid app. Grades taken on the watch are queued
locally and replayed into AnkiDroid (which does 100% of scheduling math) when
the phone is back in Bluetooth range.

## Sync model: explicit, not background (decided)

Sync happens when **both apps are open** and the user asks for it. There is no
background syncing.

Why: Wear OS throttles background work aggressively, and background services on
both sides add real complexity for a small convenience gain. An explicit
"Sync now" button is predictable and much easier to reason about.

What this means in practice:
- Reviewing on the watch works entirely offline, any time. Grades pile up in a
  persistent on-watch queue.
- To sync: open the watch app **and** the phone app, then tap "Sync now".
- Nothing is ever lost while unsynced — the queue survives reboots and is only
  cleared after the phone explicitly acknowledges each grade.

## How the devices find each other

There is no discovery logic in our code. Google Play Services maintains the
link established when the watch was paired to the phone. Our apps simply ask
for currently-reachable devices advertising a capability tag:

- Phone declares `anki_phone_app` in `phone/src/main/res/values/wear.xml`
- Watch queries `CapabilityClient.getCapability("anki_phone_app", FILTER_REACHABLE)`
- Both apps must share the same `applicationId` (`com.rella.ankiwear`)

Bluetooth range makes the connection *possible*; something still has to ask.
That "ask" is the sync button.

## Desktop / computer support: not needed

A computer app is **not** part of this project, for two reasons:

1. The Wear OS Data Layer is part of Google Play Services and is Android-only.
   A desktop machine cannot speak it.
2. A watch pairs with exactly one phone.

The computer already reaches the watch *through* the existing chain:

```
Anki Desktop  ──AnkiWeb──>  AnkiDroid  ──middleman──>  Watch
```

Decks created on a computer sync to AnkiWeb, AnkiDroid pulls them, and the
middleman forwards them to the watch. No new software required.

## Architecture

```
┌─────────────┐   AnkiDroid API    ┌──────────────┐   Wear OS Data Layer   ┌───────────┐
│  AnkiDroid  │ ◄────────────────► │  Middleman   │ ◄────────────────────► │   Watch   │
│ (stock app) │  (ContentProvider) │  (phone app) │  (DataClient/Message)  │   (app)   │
└─────────────┘                    └──────────────┘                        └───────────┘
      ▲                                                                     │
      │                              AnkiWeb sync (unchanged)               │ offline
      └─────────────────────────────────────────────────────────────────────┘
```

- **AnkiDroid**: source of truth. Owns decks, cards, scheduling, AnkiWeb sync.
- **Middleman (new phone app)**: reads due cards via the AnkiDroid API, sends
  them to the watch; receives queued grades and replays them into AnkiDroid in
  timestamp order. Runs in the background; no UI needed for daily sync.
- **Watch app** (forked from Abhishek14104/Anki_Companion): renders cards,
  records grades into a persistent on-watch queue, uploads when phone is near.

## Message protocol (watch ↔ phone)

### Watch → Phone (MessageClient)
| Path | Payload | Meaning |
|---|---|---|
| `/wear/test-message` | `"Fetch Cards"` | Request a card batch (legacy, kept) |
| `/wear/fetch_decks` | (empty) | Request the deck list with due counts |
| `/wear/fetch_cards` | `{deckIds: [..]}` | Request due cards for chosen decks |
| `/wear/answer_card` | `{id, ease}` | Single grade (live mode only) |
| `/wear/grade_queue` | `[{id, ease, timeTaken, reviewedAt}, ...]` | Batched offline grades |
| `/wear/view_media` | `<cardId>` | Open card media on phone |

### Phone → Watch (DataClient)
| Path | Key | Payload |
|---|---|---|
| `/wear/deck_buffer` | `cards_json` | `[{id, front, back, frontHasMedia, backHasMedia}]` |
| `/wear/deck_list` | `decks_json` | `[{deckId, name, dueCount}]` |
| `/wear/grades_ack` | `acked_ids` | `[cardId, ...]` — confirmed applied, watch clears queue |

## Watch app changes (from upstream MVP)
- [x] Keep: card UI, flip, DataClient listener, disk cache (`anki_cache.json`)
- [x] Persistent grade queue (`grade_queue.json`) — survives reboots
- [x] "Don't repeat": graded cards filtered out; done-screen shows pending count
- [x] Grade buttons labeled Again/Hard/Good/Easy
- [ ] **Read existing data items on open** (not just live `onDataChanged`), so a
      batch pushed while the app was closed is not silently missed
- [ ] "Sync now" button: upload grade queue + request a fresh card batch
- [ ] Sync status feedback (last synced, pending count, phone reachable?)
- [ ] Deck picker screen
- [ ] Round-screen layout tuning

## Middleman phone app (new)
- [x] AnkiDroid API permission flow (runtime permission)
- [x] Deck list query (name + due counts)
- [x] Due-card query via `schedule` endpoint → JSON batch → DataClient push
- [x] Grade replay: apply queued grades to AnkiDroid in `reviewedAt` order
- [x] Post-sync refresh: push newly-due cards (e.g. "Again" cards)
- [x] Deck favorites setting
- [ ] Deck list refresh button (new decks appear without restarting the app)
- [ ] "Saved automatically" hint next to favorite checkboxes
- [ ] **Test panel on a separate screen** (behind a button, not on the main page)
      to exercise fetch + a single grade write-back by hand

## Milestones
1. **Tethered MVP**: middleman reads cards, watch displays & grades live.
   Includes a minimal write-back test of the AnkiDroid API (riskiest call).
   - [x] AnkiDroid detected, permission granted, real deck names listed
   - [ ] Due-card fetch verified against real AnkiDroid
   - [ ] Grade write-back verified against real AnkiDroid  ← riskiest call
2. **Explicit sync**: "Sync now" on the watch, read-on-open fix, ack/clear.
3. **Polish**: deck picker, round-screen layout, sync status display.

## Safety
- AnkiDroid does all scheduling; watch never computes intervals.
- Grades carry `reviewedAt` timestamps; replayed in order.
- Queue only cleared after explicit ack from phone.
- Card IDs pack `(noteId, cardOrd)` as 48 bits / 16 bits. Note IDs are
  millisecond timestamps and need >32 bits — an earlier 32/32 split truncated
  them and was caught by unit tests. Do not "simplify" this back.

## Known limitation: review timestamps
AnkiDroid's API does not accept a "reviewed at" time; it stamps each review at
the moment it is applied. A card graded at 9am and synced at 1pm is recorded as
a 1pm review. Scheduling intervals are barely affected (FSRS/SM-2 care about
elapsed time between reviews, not wall-clock precision), but daily stats can
shift if a sync crosses midnight.
