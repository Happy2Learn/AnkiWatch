# Anki Companion — Build Plan

## Goal
Review Anki flashcards on a Samsung Galaxy Watch 7 (Wear OS), **phone-free**,
using the stock, unmodified AnkiDroid app. Grades taken on the watch are queued
locally and replayed into AnkiDroid (which does 100% of scheduling math) when
the phone is back in Bluetooth range.

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
- [ ] Persistent grade queue (`grade_queue.json`) — survives reboots
- [ ] "Don't repeat": graded cards filtered out; done-screen shows pending count
- [ ] Deck picker screen
- [ ] Grade buttons labeled Again/Hard/Good/Easy, sized for round screens
- [ ] Auto-upload queue when phone reachable (CapabilityClient)

## Middleman phone app (new)
- [ ] AnkiDroid API permission flow (runtime permission)
- [ ] Deck list query (name + due counts)
- [ ] Due-card query → strip media → JSON batch → DataClient push
- [ ] Grade replay: apply queued grades to AnkiDroid in `reviewedAt` order
- [ ] Post-sync refresh: push newly-due cards (e.g. "Again" cards)
- [ ] Background service + optional deck favorites setting

## Milestones
1. **Tethered MVP**: middleman reads cards, watch displays & grades live.
   Includes a minimal write-back test of the AnkiDroid API (riskiest call).
2. **Offline mode**: watch grade queue + phone replay + ack/clear.
3. **Polish**: deck picker, button labels, round-screen layout, favorites.

## Safety
- AnkiDroid does all scheduling; watch never computes intervals.
- Grades carry `reviewedAt` timestamps; replayed in order.
- Queue only cleared after explicit ack from phone.
