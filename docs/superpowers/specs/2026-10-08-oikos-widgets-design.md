# Oikos — Home-screen widgets (phase 3) design

Status: approved design, 2026-10-08. Parent spec: `2026-09-24-oikos-design.md` §11 (phase 3, "Widgets (Glance)"). Goal: replace the four DomoticApp widgets (shutters, temperature, alarm, Daikin climate) so DomoticApp can be retired.

## 1. Goals and scope

**Goals**

1. **One generic widget, "Oikos devices".** It shows 1–8 chosen entities and acts on them from the home screen. Nothing is house-specific.
2. **Reasonably fresh without opening the app.** A periodic background refresh every 15 min, an explicit ↻ refresh, a refresh after each command, and live updates while the app is open.
3. **Same safety rules as the app.** A command that needs a code (alarm) asks for it in a small keypad activity.

**Out of scope**

- Sliders and fine controls: light brightness and colour, climate setpoint, cover position. These stay in the app.
- Per-kind dedicated widget types, lock-screen widgets, and Wear OS tiles.
- A configurable refresh interval. 15 min is fixed: it is the WorkManager minimum.
- Background MQTT kept open. The parent non-goal still holds: connections are short-lived.

## 2. User-facing behaviour

### 2.1 Placing and configuring

- The widget is resizable from 2×1 to 4×4 cells.
- Placing it opens `WidgetConfigActivity`. It shows a searchable list of the current entities grouped by area, with the override names and icons. The user picks 1–8 entities and can reorder them. For an `ALARM_PANEL` it also asks for the **arm mode** used by the tile (`AlarmMode` among the panel's supported modes; the default is `AWAY` when supported, otherwise the first supported mode).
- On Android 12+, long-press → "Reconfigure" opens the same screen (`widgetFeatures="reconfigurable"`). Before Android 12 the user removes the widget and adds it again.
- The entity list comes from the live source when the app is connected. Otherwise it comes from the entity cache restored into a source. With no profile, the screen offers "Set up Oikos" (onboarding).

### 2.2 Layout

- **Size-responsive** (`SizeMode.Responsive`): a 2×1 shows one large tile (the first entity), and larger sizes show a grid of compact tiles (2 columns at 3–4 cells wide, 1 column below that). Entities that do not fit are not shown. The config screen says how many fit at the current size.
- **Tile:** device icon, name, state text ("21.4 °C", "On", "Open 60 %", "Armed away"), and an action area.
- **Footer:** "Updated 14:02" (or "Offline · 14:02", "Sign-in failed", "Set up Oikos") and a ↻ button.
- **Theme:** `GlanceTheme` with dynamic colour on Android 12+, and Oikos light/dark colours before that. Strings in English and French.

### 2.3 Taps by kind

| Kind | Tap |
|---|---|
| `LIGHT`, `SWITCH` | `Toggle` |
| `COVER` | Open / Stop / Close buttons when the tile is wide enough; otherwise `Open` when closed and `Close` otherwise |
| `CLIMATE` | On/off: `SetHvacMode("off")`, or back on with the last non-off mode in the snapshot (the first non-off supported mode when unknown) |
| `ALARM_PANEL` | Disarmed → `Arm(configured mode)`; any armed/arming/triggered state → `Disarm`. When `codeArmRequired` / `codeDisarmRequired` applies, the tap opens `WidgetCodeActivity` first |
| `BUTTON` | `Press` |
| `SENSOR`, `BINARY_SENSOR`, `NUMBER`, `SELECT` | Read-only: opens the app on the entity's history detail when it has a `HistoryRef` and a history URL, otherwise on Home |

Tapping a tile outside its action area opens the app on Home.

### 2.4 Feedback and staleness

- After a command the tile shows a pending "…" state. It then shows the confirmed state, or after 5 s without confirmation goes back to the previous state with a "!" marker. The marker clears on the next successful refresh.
- Values whose `updatedAt` is older than 30 min are dimmed, and the footer time says how old they are.
- An entity never seen shows "—". An entity missing from a complete live discovery shows "Removed", and tapping it opens reconfigure.

## 3. Architecture

### 3.1 Modules

New module **`:feature:widget`** (Glance `glance-appwidget` + `glance-material3` added to the version catalog; `glance-appwidget-testing` for tests). It depends on `:core:model`, `:core:designsystem` (strings, icons, `CodeKeypadSheet`) and `:core:data` (DataStore helpers). It does **not** depend on `:core:source` or `:core:transport`. All connection work sits behind the `WidgetEntities` interface, implemented in `:app`.

### 3.2 Units

| Unit | Module | Responsibility |
|---|---|---|
| `OikosWidget`, `OikosWidgetReceiver` | widget | Glance UI. Reads `WidgetConfigStore` and `WidgetSnapshotStore` only; never connects. Action callbacks enqueue workers. |
| `WidgetConfigActivity` | widget | The entity picker (§2.1); writes `WidgetConfigStore`, enqueues the periodic refresh, sets the result. |
| `WidgetCodeActivity` | widget | Transparent activity hosting `CodeKeypadSheet` (format from the snapshot). OK enqueues `WidgetCommandWorker` with the code; the code lives only in the activity state and the work input, never on disk. |
| `WidgetConfigStore` | widget | DataStore: `appWidgetId → WidgetConfig(uniqueIds: List<String>, armMode: AlarmMode?)`. |
| `WidgetSnapshotStore` | widget | DataStore in `noBackupFilesDir`: `uniqueId → TileSnapshot(name, icon, kind, stateText, available, alarm/climate extras, codeArmRequired, codeDisarmRequired, codeFormat, historyAvailable, status: OK/PENDING/FAILED/REMOVED, pendingSince, updatedAt)` plus a global `footer: Fresh/Offline/AuthFailed/NotConfigured` with a timestamp. Display data only, no secrets. |
| `TileMapper` | widget | Pure `EntityState + UiOverride → TileSnapshot` (state text, icon, kind extras) for every `Kind`. |
| `WidgetRefreshWorker` | widget | Unique periodic work `oikos-widget-refresh`, 15 min, `NetworkType.CONNECTED`. Enqueued (`KEEP`) when a widget is configured; cancelled from `onDisabled` when the last widget is removed. Also run as a unique one-off (`oikos-widget-refresh-now`, `REPLACE`) by ↻ and right after configuration. No boot receiver: WorkManager persists periodic work across reboots. Refreshes the union of all configured ids. |
| `WidgetCommandWorker` | widget | Expedited one-off per tap: marks the tile `PENDING`, updates the widgets, calls `WidgetEntities.send`, writes the result, updates the widgets. |
| `WidgetEntities` | widget (interface) | `suspend fun refresh(ids: Set<String>): RefreshResult` and `suspend fun send(id: String, command: Command): SendResult`. Results carry `TileSnapshot`s or a failure class (`Offline`, `AuthFailed`, `NotConfigured`). |
| `HeadlessEntities` | app | Implements `WidgetEntities` (§3.3). |
| `WidgetMirror` | app | While the foreground session is connected, collects `states()`, maps the ids present on any widget through `TileMapper`, writes the snapshot (debounced 1 s) and calls `OikosWidget().updateAll()`. |
| (no new transport code) | app | `HeadlessEntities` builds a private `TransportFactory(sessionScope, emptyFlow(), http)` per session. The factory already covers Demo, Direct, Gateway and Routed AUTO (TCP probe, then fallback). `OneShotPublisher` is unchanged. |

### 3.3 `HeadlessEntities`

- **Foreground short-circuit:** when `EntitySourceSession` holds a connected source, `refresh` reads its current `states()` value and `send` calls its `send()` and awaits the tracker confirmation. No second connection.
- **Headless path** (app not connected), under a process-wide `Mutex`:
  1. A private `TransportFactory` builds the transport for the profile's spec, with client id `clientIdFor(installId) + "-widget"`, and the session waits for the link (10 s budget). `Offline(REJECTED | KEY_REJECTED)` → `AuthFailed`; no link in time → `Offline`; no profile → `NotConfigured`. The Demo spec gets the in-process `DemoBroker` the same way.
  2. Build a fresh `MqttDiscoverySource(transport, scope, prefix)` with the profile's prefix; `restore(entityCache.load(key))`; set subscriptions as the foreground session does.
  3. `refresh`: wait until every requested id has a non-cached (live) state, or 8 s, then up to 6 s more for the source to settle (live traffic, then a 2 s quiet window that each newly replayed topic restarts: about 4 s on a small house). Ids still cached are returned with their cached state and their old `updatedAt`. An id absent from discovery is `REMOVED` only when the source settled.
  4. `send`: wait for the id to be discovered, then for its live state (8 s each). Otherwise the retained state replay, which arrives after the subscription debounce, would "confirm" the old value. Then `send(command)` and wait for the `CommandTracker` result (its 5 s timeout). Confirmed means `pending` cleared **and** `lastUpdate` moved: a timeout reverts the store (keeping `lastUpdate`) before it emits the failure. Confirmed → `OK` snapshot; `CommandFailure` or `Rejected` → `FAILED` with the previous state.
  5. Save the source's journal back with `entityCache.save(key, …)`, but only if the source settled (a partial journal would shrink the app's cold-start house) and the foreground session is still not active. Close the transport in `finally` (`NonCancellable`), as `OneShotPublisher` does.
- The `CommandTracker` 5 s timeout and the 8 s waits keep a tap well under the 10-minute WorkManager limit and the expedited-work quota.

### 3.4 Data flow

```
tap ─▶ Glance ActionCallback ─▶ WidgetCommandWorker (expedited)
         │                          ├─ snapshot: PENDING ─▶ updateAll
         │                          ├─ WidgetEntities.send ─▶ HeadlessEntities ─▶ (live source | TransportFactory + MqttDiscoverySource)
         │                          └─ snapshot: OK / FAILED ─▶ updateAll
↻ / 15 min ─▶ WidgetRefreshWorker ─▶ WidgetEntities.refresh ─▶ snapshot + footer ─▶ updateAll
app connected ─▶ WidgetMirror ─▶ snapshot (debounced) ─▶ updateAll
OikosWidget.provideGlance ─▶ reads WidgetConfigStore + WidgetSnapshotStore only
```

## 4. Errors, privacy, demo

- **No profile:** footer "Set up Oikos"; tapping anywhere opens onboarding. The periodic worker returns `success` without connecting.
- **Demo profile:** `HeadlessEntities` gets the in-process `DemoBroker` from the same `TransportFactory`, so a fresh install in demo mode shows a live widget.
- **Unreachable:** the snapshot keeps the last values; footer "Offline · <last fresh time>"; the worker returns `success` (no retry storm; the next period retries). Commands → `FAILED`.
- **Rejected credentials:** footer "Sign-in failed"; tapping the footer opens Settings.
- **Worker killed mid-session:** the transport closes in `finally`; on the next render a `PENDING` older than 30 s is shown as `FAILED`.
- **Profile reset:** clearing the profile also clears `WidgetSnapshotStore` (same hook as `EntityCache.clear()`) and updates the widgets to "Set up Oikos". `WidgetConfigStore` is kept; unknown ids then render "—".
- **Privacy:** the snapshot and config hold no codes or credentials, and live in `noBackupFilesDir`. The alarm code exists only in `WidgetCodeActivity` state and the expedited work's input data (deleted when the work completes). The PIN never reaches the cache (`MqttDiscoverySource.redact`, unchanged).

## 5. Testing

- **Unit**
  - `TileMapper` for every `Kind`, including stale, unavailable, alarm states and climate last mode.
  - `HeadlessEntities` with a fake transport factory: foreground short-circuit; live-ids wait and 8 s budget; cached-only ids; `REMOVED`; send confirmed / not confirmed / rejected; `Offline`, `AuthFailed`, `NotConfigured`; mutex serialises refresh and send; cache saved only when headless; transport closed on cancellation.
  - `WidgetRefreshWorker` and `WidgetCommandWorker` (WorkManager test helpers): snapshot transitions and footer states.
  - `WidgetConfigStore` / `WidgetSnapshotStore` round-trips and corruption fallback.
  - `WidgetMirror`: only configured ids, debounce.
- **Glance:** `glance-appwidget-testing` unit tests of the layouts at 2×1, 3×2, 4×4 in light and dark, with pending, failed, stale, removed and "Set up Oikos".
- **Device checklist:** place a widget and pick entities; toggle a light; open/stop/close a cover; turn the climate off and on; arm, then disarm with a code; force-stop the app and wait for a periodic refresh; ↻ off home Wi-Fi (gateway path); reconfigure; remove the last widget and check that the periodic work is cancelled.
