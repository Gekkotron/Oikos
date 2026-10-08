# Oikos — History and detail (phase 2) design

Status: approved design, 2026-10-08. Parent spec: `2026-09-24-oikos-design.md` §11 (phase 2). It replaces the parent's "gateway `/history` endpoint" with a separate LAN service reached through the gateway's existing raw GET (decision below).

## 1. Goals and scope

**Goals**
1. **Energy over time.** Each Energy node shows "kWh today". Its detail view shows the power curve (24 h / 7 d / 30 d) and, on 7 d / 30 d, kWh-per-day bars.
2. **Sensor trends.** Any recorded numeric entity (temperature, humidity, power…) opens a detail screen with a 24 h / 7 d / 30 d chart.

**Out of scope**
- Card sparklines and the event log (state-change timeline).
- Recording more MQTT namespaces: that is a one-line Athena-Core config change, made when needed.
- Offline history, free zoom/pan, comparing two devices, and calendar ranges other than "today" and per-day totals.

**Decision: a LAN HTTP service, not a gateway endpoint.** The gateway stays a generic encryption gateway. History is a small read-only service on the LAN. At home the app calls it directly; away it uses the gateway's raw GET (gateway v1.3, `"raw": true`), exactly like push media. No gateway change is needed, only a host:port rule on the gateway key.

## 2. Context: what is recorded

Athena-Core (`SourcesPerso/Athena/Athena-Core`) writes MQTT to InfluxDB 2.7 (org `geckostudio`, bucket `athena`), configured with `MQTT_TOPICS=["zigbee2mqtt","jeedom/data"]`:

- `_measurement` = the matching topic prefix, e.g. `zigbee2mqtt` or `jeedom/data`.
- tag `device` = the rest of the topic, e.g. `rooms/living/thermometer`.
- tag `sensor_type` = a top-level JSON key, e.g. `temperature`, `température_int.`.
- `_field` = `value` (float; booleans and `ON`/`TRUE` → 1.0, `OFF`/`FALSE` → 0.0) or `value_str` (other strings).
- **Only JSON-object payloads are recorded.** Plain values and bare JSON numbers are skipped. So an entity has history only when its `value_template` reads a `value_json` key.

## 3. OikosHistory service (new repo `Gekkotron/OikosHistory`)

### 3.1 Shape
- Python 3.12, FastAPI + uvicorn, `influxdb-client`. Docker container next to InfluxDB on the Geekom (`network_mode: host` or the home-automation network).
- Same repo conventions as OikosPush: MIT, author Gekkotron, `.env.example`, `update.sh`, pytest, README.
- Settings (environment):

| Variable | Default | Meaning |
|---|---|---|
| `INFLUX_URL` | `http://localhost:8086` | InfluxDB |
| `INFLUX_TOKEN` | required | **read-only** token for the bucket |
| `INFLUX_ORG` | `geckostudio` | |
| `INFLUX_BUCKET` | `athena` | |
| `HISTORY_PREFIXES` | `zigbee2mqtt,jeedom/data` | topic prefixes = measurements; keep in sync with Athena-Core `MQTT_TOPICS` |
| `HISTORY_TZ` | `Europe/Paris` | where "midnight" falls for `daily` / `today` |
| `PORT` | `8090` | HTTP port |
| `QUERY_TIMEOUT` | `10` | seconds per Influx query |

### 3.2 Series lookup
The app sends `topic` (the entity's MQTT state topic) and `key` (the JSON key). The service picks the **longest** configured prefix `p` such that `topic` starts with `p + "/"`; `measurement = p`, `device = topic[len(p)+1:]` stripped of `/`, `sensor_type = key`. No prefix matches → `404 {"error":"not_recorded"}`. Only `_field == "value"` is queried.

### 3.3 API (GET only: the gateway raw download is a GET)

All responses are JSON. Timestamps are unix seconds (window start). Values are floats rounded to 3 decimals.

**`GET /v1/series?topic=&key=&range=24h|7d|30d`**
- Window = range ÷ 150, rounded: `24h` → 10 min, `7d` → 1 h, `30d` → 5 h.
- Flux: `range(start: -R)` → filter measurement/device/sensor_type/`_field=="value"` → `aggregateWindow(every: W, fn: mean, createEmpty: false)`, and the same with `min` and `max`, joined on `_time` (or one query with three yields).
- `200 {"window": <seconds>, "points": [[t, mean, min, max], …]}`, sorted by `t`. No points in range but series exists → `points: []`. Series never seen → `404 not_recorded`.

**`GET /v1/daily?topic=&key=&days=7|30&kind=counter|power`**
- Days are calendar days in `HISTORY_TZ`, the last one being today (partial).
- `kind=counter` (a kWh meter): per day, the sum of the non-negative increases (`difference(nonNegative: true)` then `sum`), so meter resets do not create negative days.
- `kind=power` (W): per day, `integral(unit: 1h)` / 1000 → kWh.
- Flux uses `import "timezone"` / `option location = timezone.location(name: TZ)` and `aggregateWindow(every: 1d, …)`.
- `200 {"days": [[t_midnight, kwh], …]}`; days without data are omitted.

**`GET /v1/today?topic=&key=&kind=counter|power`**
- kWh since local midnight, computed like one `daily` day. `200 {"kwh": 3.412, "since": t_midnight}`, `kwh: null` when there are no points today.

**`GET /health`** → `200 {"version": "…", "influx": "ok"|"down"}`.

**Errors:** `400 {"error":"bad_request","detail":…}` for a missing or invalid parameter; `404 {"error":"not_recorded"}`; `503 {"error":"influx_unavailable"}` on Influx errors or timeout. Flux strings are built with escaped parameters only; `topic`/`key` are never interpolated unescaped.

### 3.4 Access
Open on the LAN, read-only, no authentication (the same trust level as Frigate's port 5000). Remote access exists only through the gateway key's host:port rule. Queries are bounded (≤ 30 days, ≤ ~150 points per series, ≤ 31 daily values).

### 3.5 Tests
- Pure functions: prefix resolution, window choice, Flux builders (exact strings, escaping), response shaping, counter resets, a day crossing midnight in `Europe/Paris` including a DST change day.
- HTTP layer with a faked Influx query function (FastAPI `TestClient`): 200/400/404/503 paths.
- One opt-in integration test (`-m influx`) against a real `influxdb:2.7` container seeded with points.

## 4. App: `:core:history`

### 4.1 HistoryRef
`HistoryRef(topic: String, key: String)` is derived from an entity's discovery bindings:
- `topic` = `state_topic` (for climate: `current_temperature_topic` for the main reading).
- `key` = the JSON key read from the matching template: `{{ value_json.temperature }}`, `value_json['température_int.']`, `value_json["x"]`, with optional filters after it (`| float`, `| round(1)`). Any other template, or no template → `null`: the entity has no history and no history UI.
- A pure function in `:core:history`, unit-tested against real discovery payloads (Z2M, Jeedom bridge).

### 4.2 HistoryClient
```kotlin
interface HistoryClient {
    suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series>
    suspend fun daily(ref: HistoryRef, days: Int, kind: EnergyKind): HistoryResult<DailyTotals>
    suspend fun today(ref: HistoryRef, kind: EnergyKind): HistoryResult<Double?>
}
enum class HistoryRange { H24, D7, D30 }
enum class EnergyKind { COUNTER, POWER }
sealed interface HistoryResult<out T> { Ok(value) ; NotRecorded ; Unavailable(reason) ; NotAllowed }
```
- Builds `historyUrl + "/v1/…?" + encoded params`, fetches through `LanOrGatewayGet` (4.3), parses with kotlinx.serialization.
- In-memory cache keyed by request: 1 min for `H24` and `today`, 10 min for `D7`/`D30`/`daily`. No disk cache.
- `kind` from the entity's `unit_of_measurement`: `kWh`/`Wh` → `COUNTER`, `W`/`kW` → `POWER`; other units have no daily/today view.

### 4.3 LanOrGatewayGet (shared route)
The LAN-first / gateway-raw-GET logic of `MediaFetcher` (`:core:alerts`) moves to `:core:transport` as `LanOrGatewayGet`: given a URL, try the LAN (when the local probe says the LAN broker is reachable and the host is local; 5 s connect, 15 s read, no redirects), else the gateway raw download (30 s). It returns status + bytes + content type, or `NotAllowed` / `Unavailable`. `MediaFetcher` becomes a thin user of it (size cap, 404/410 → Gone, progress); its existing tests must pass unchanged.

### 4.4 Configuration
- `ConnectionProfile` gains optional `historyUrl: String?` (stored profile + migration default `null`).
- The setup QR gains optional `"history": {"url": "http://192.168.1.91:8090"}` (still `v: 1`). `tools/setup-qr.py` gets a `--history-url` option.
- Settings → connection screen: a "History service URL" field, validated as `http(s)://host[:port]`.
- `historyUrl == null` hides every history entry point and the "today" line.

### 4.5 Demo
`:core:demo` provides `DemoHistoryClient`: a daily sine + noise for sensors, a solar-shaped bell for solar power, plausible kWh/day bars. Previews, screenshots and demo mode use it.

## 5. App: UI

### 5.1 Charts
Vico 2 (Compose, Material 3) in `:core:designsystem` wrappers: `HistoryLineChart(series)` (mean line + min/max band, touch marker with time and value) and `DailyBarsChart(days)`. Colours from the theme tokens (Night Glass / Warm Minimal).

### 5.2 Detail screen (`:feature:history`)
Navigation3 destination `HistoryRoute(entityId)`, full screen.
- Header: name, room, live value + unit.
- Range chips 24 h / 7 d / 30 d (default 24 h).
- Main chart: line + min/max band.
- Stats row for the range: min / avg / max (avg = mean of means weighted equally).
- Energy entities (`kind != null`): on 7 d / 30 d a **Power | kWh per day** toggle; 24 h always shows power.
- States: loading skeleton; `NotRecorded` → "No history recorded for this device"; `Unavailable` → "History unavailable" + Retry; `NotAllowed` → "Add the history host to the gateway key".
- Accessibility: a content description summarising each chart ("Temperature, last 24 hours, min 19.8, max 22.1 °C").

### 5.3 Entry points
- **Home:** tapping a sensor card (today `TapAction.NONE`) opens its detail when it has a `HistoryRef` and `historyUrl` is set. Long-press stays Customize.
- **Control sheet:** a "History" row opening the detail of the entity's main numeric reading (climate: current temperature), shown only when a ref exists.
- **Energy:** tapping a flow node opens the detail of its entity. A **derived house** node has no entity: its series and daily bars are computed client-side by applying the existing derived-house formula point by point to the inputs' series (Influx windows align on the same boundaries for the same window); a missing input point → a gap.

### 5.4 Energy "today"
Each node and matching card shows "**x.x kWh today**" under the live power. Fetched when the screen starts, refreshed every 5 min while visible. The derived house uses its inputs' "today" values with the existing formula. Hidden when unavailable.

### 5.5 Strings
English and French.

## 6. Error handling
- Service: as §3.3. The app maps `404 not_recorded` → `NotRecorded`, `503`/network → `Unavailable`, gateway "destination/port not allowed" → `NotAllowed` (logged once per host).
- History does not depend on the MQTT live link.

## 7. Testing (app)
- Unit: `HistoryRef` extraction, `HistoryClient` URL building and parsing, cache TTLs, unit → kind, derived-house point-wise maths, `LanOrGatewayGet` (LAN, fallback, not allowed), `MediaFetcher` regression.
- ViewModel tests for every detail state and the Energy "today" refresh.
- Robolectric screenshot tests of the detail screen (line, bars, empty, error) in both themes with `DemoHistoryClient`.
- Device checklist: a thermometer on 24 h / 7 d / 30 d; energy bars; "today" vs the Z2M counter; away from home through the gateway.

## 8. Rollout
1. **OikosHistory** (its own plan): repo, tests, deploy on the Geekom, create the read-only Influx token.
2. Add the service's host:port to the gateway key rules.
3. **App** (its own plan): `LanOrGatewayGet`, `:core:history`, profile/QR/Settings field, Vico wrappers, `:feature:history`, entry points, Energy "today".
4. Regenerate the setup QR with `history.url`.

The two plans are independent: the app is built and tested against `DemoHistoryClient` and a faked HTTP layer; the contract between them is §3.3.
