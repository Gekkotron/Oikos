# Oikos — Entity cache for an instant cold start

Status: approved design, 2026-09-25. Parent spec: `2026-09-24-oikos-design.md` §4.7. It replaces the "Entity cache" row that the direct-connection spec deferred to Plan 2. This part does not need the gateway.

## 1. Goal

Today every launch starts empty and Home shows "Discovering devices…" until the broker replays its retained messages. Away from the home Wi-Fi (there is no gateway yet), Home stays on that screen.

After this work:

1. Killing and relaunching the app draws the whole house (rooms, cards, last values) as soon as the profile has loaded. There is no "Discovering…" step.
2. Until live data arrives, cached values are shown **dimmed** (they are stale). When the broker answers, cards refresh in place: nothing re-sorts and the view does not jump.
3. A device removed while the app was closed disappears after reconnecting.
4. Tapping a stale card never sends a command. It shows "Waiting for the connection".
5. Nothing secret is cached. The cache is excluded from backups.

Out of scope: showing the age of a value ("2 h ago"), caching the demo, and keeping more than one profile's cache.

## 2. Approach: replay a message journal

`MqttDiscoverySource` already builds everything (entities, devices, overrides, rooms, states) from raw MQTT messages. The cache therefore stores those messages (topic → last payload) and replays them into a new source through the same `handle()` path. No model type (`Bindings`, `StateValue`, `Kind`) gets a new serialized form, and every future parser change applies to cached data automatically.

The alternatives were rejected. A structured snapshot of `Entity` and `EntityState` needs a second representation to keep in sync. A Room database is too heavy for about 300 entities and roughly 0.5 MB.

## 3. Components

### 3.1 `:core:source` — journal and restore

- `MqttDiscoverySource` keeps a **journal**: the last payload of every topic that currently contributes to its output.
  - Discovery config topics it accepted (`discovered` keys). A `Removed` result or an empty payload drops the topic.
  - `oikos/ui/<id>` overrides and `oikos/ui/_areas`. An empty payload drops the topic.
  - State, availability and `json_attributes` topics that are in `store.subscriptionTopics()` at the time of the change.
- `journal(): Map<String, String>` exposes it, and `journalVersion: StateFlow<Long>` says when it changed. An alarm panel's `code` (its PIN) is removed from the cached config. It is updated inside `publishEntities()` and after state messages, then filtered to the current topics, so topics of removed entities never linger.
- `suspend fun restore(messages: Map<String, String>)` handles each message as if it had arrived retained, marked as *restored*.
  - It skips any topic already received live by this source. A restore that finishes after the first live messages therefore never overwrites fresher data.
  - Config topics are replayed before state topics, so state messages find their entity.
- `EntitySource` gains `restore()` with a default no-op, so the demo source and fakes do not change.

### 3.2 `:core:state` — stale states

- `EntityState` gains `stale: Boolean = false`.
- `StateStore.onMessage(topic, payload, restored = false)`: a restored message sets `stale = true` on the states it updates. A live message on any of an entity's topics sets `stale = false`.
- `StateStore.settle()` sets `stale = false` everywhere (see §3.4).

### 3.3 `:core:data` — `EntityCache`

- Stored as DataStore JSON, like `profile.json`, at `noBackupFilesDir/entity-cache.json`. That location is never backed up. `data_extraction_rules.xml` cannot name it (no rule domain covers it), so it only gets a comment saying so.
- The document is `{ "profileKey": String, "messages": { topic: payload } }`.
- `profileKey` is `"$host:$port:$prefix"`. It holds no username, password or gateway key.
- API:
  - `suspend fun load(key): Map<String, String>` returns empty when the key differs or the file is missing.
  - `suspend fun save(key, messages)`.
  - `suspend fun clear()`.
- Reading starts eagerly when the app starts (the `DataStore.data` flow is shared on the app scope), in parallel with the profile read.
- A corrupt file is replaced with an empty document (`ReplaceFileCorruptionHandler`), so the app falls back to today's behaviour.

### 3.4 Prune and settle (in the source)

Once the source is `Connected` **and** a first live message has arrived (the broker's replay has started), it waits until no *new* topic has arrived for **2 s**, capped at **10 s**. Steady updates on known topics do not count. If the link leaves `Connected` during the wait, it starts over at the next connection. Without any live traffic nothing is settled, because a stalled link must not prune the cached house. Then:

1. Every restored discovery config or override topic that was not received live is removed, as if an empty retained message had arrived. Devices deleted while the app was closed disappear.
2. `store.settle()`: a value still stale was never re-sent by the broker, so it goes back to **Unknown** ("—", as before any cache existed). It is not shown as current.

This runs once per source. Messages are applied under the source lock, so a restored message can never interleave with a live one or with settle.

### 3.5 `:app` — `EntitySourceSession`

- The session gets an `EntityCache`.
- `current(profile)` for a `Direct` profile launches, in the source's child scope, `source.restore(cache.load(key))`. It then, once the source has settled (its journal is complete), saves the journal right away and afterwards at most every **10 s**. It skips empty or unchanged journals. A debounce would never fire on a busy house.
- A `Demo` profile, `null` and `reset()` ("Set up connection again") all call `cache.clear()`.
- A different broker has a different key, so its first save overwrites the old document.

### 3.6 `:feature:home` — dimmed cards

- `HomeUiBuilder` maps `state.stale` to a `stale` flag on the card model. `OikosCard` draws it with the existing muted style: value and icon at reduced emphasis, with the layout unchanged.
- While a card is stale, a tap does not call `send()`. It shows the snackbar `home_waiting_connection` ("Waiting for the connection" / "En attente de la connexion"). Long-press Customize still works: offline it fails and rolls back, as it does today.
- `loading` stays `entities.isEmpty()`. With a warm cache it is false from the first frame.

## 4. Error handling

| Case | Behaviour |
|---|---|
| Cache file missing or corrupt | Empty restore; "Discovering…" as today |
| A cached payload no longer parses | Existing diagnostics; entity skipped, as for a live message |
| Save fails (disk full…) | Diagnostic logged; ignored; retried at the next debounce |
| Restore finishes after live data | Topics already seen live are skipped |
| Broker never connects | Cards stay dimmed; the status pill says Offline/Reconnecting; taps show the hint |

## 5. Testing

- **Source (JVM, fake transport):**
  - Restore builds entities, rooms and states.
  - Live data wins over restored data, in either order.
  - The journal drops removed topics.
  - After Connected plus quiet time, unseen restored configs are pruned and stale is cleared.
  - Restored states are stale; a live message clears it.
- **StateStore:** restored → stale, live → fresh, `settle()` clears.
- **EntityCache (JVM, temp file):** round trip, key mismatch returns empty, corrupt file returns empty, `clear()`.
- **Session:** the demo is not cached; `reset()` clears; a direct profile restores and then saves.
- **HomeUiBuilder / HomeViewModel:** a stale state gives a dimmed card; a tap on a stale card gives the hint and no `send()`.
- **Phone check:** open the app on the real house, kill it, turn Wi-Fi off and relaunch. The house appears dimmed. Turn Wi-Fi on: the cards refresh and the dimming lifts.
