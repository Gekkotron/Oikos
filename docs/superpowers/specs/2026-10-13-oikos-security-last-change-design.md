# Oikos — Security: time since the last change

Status: approved design, 2026-10-13. Parent specs: `2026-10-02-oikos-security-design.md` (the tab) and `2026-10-08-oikos-history-design.md` (OikosHistory, §3.3 contract). This spec adds one endpoint to that contract (Appendix A).

## 1. Goal

Every sensor tile on the Security tab says how long it has been in its current state — "Closed · 2 h", "Clear · 12 min" — so a glance tells how long a door has been closed or when motion was last seen. The time is right from the first frame after a cold start, not only after the next change.

Decisions:
- **All Security sensors**, every section (openings, motion, fire, water, other).
- **The time comes from OikosHistory** (InfluxDB, written by Athena-Core for every MQTT message), through one batch request; live flips seen by the app refine it.
- **No history, no line.** Without OikosHistory (no URL, unreachable, `None` client) a tile looks exactly as today until the app sees a live flip.

Out of scope: sorting by "since", "open for more than N min" alerts, per-sensor history charts on the sheet, "since" on Home tiles or widgets, alarm panels and actions.

## 2. Why `lastUpdate` is not enough

`EntityState.lastUpdate` moves on every confirmed message. Zigbee2MQTT republishes a device's whole state on every battery or link-quality report, so a door closed for three days would read "4 min". A change time must move only when the value flips. The app also keeps no time across a cold start; InfluxDB has every message for at least 30 days.

## 3. OikosHistory

New endpoint `GET /v1/last_change`, specified in Appendix A, added to the history spec's §3.3. Redeploy on the Geekom with `update.sh`; no new environment variables; port, host and gateway allow-list unchanged.

## 4. App

### 4.1 Refs (`:core:history`)

`HistoryRefs.changeRef(entity: Entity): HistoryRef?` — for `Kind.BINARY_SENSOR` only: `state_topic` plus the key read by `value_template` (`jsonKey`, e.g. `{{ value_json.contact }}` → `contact`). Null for other kinds, non-MQTT bindings, or a template `jsonKey` cannot read.

It is a separate function on purpose: `HistoryRefs.of` drives the History chart entry points, and binary sensors must not get a chart of 0/1 means.

Polarity does not matter: only the time of the flip is read, so Zigbee2MQTT's `contact: true` = closed needs no special case.

### 4.2 Client (`:core:history`)

```kotlin
sealed interface LastChange {
    data class At(val at: Instant) : LastChange
    /** Recorded, but no flip within [window]. */
    data class OlderThan(val window: Duration) : LastChange
    data object NotRecorded : LastChange
}

// HistoryClient
suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>>
```

- `HistoryApi.lastChangeUrl(base, refs)` and `HistoryApi.parseLastChange(json, refs)`; items map to refs by position.
- `HttpHistoryClient`: refs are de-duplicated and sorted by `(topic, key)` so the URL, and so the in-memory cache entry, is stable; TTL `SHORT_TTL` (60 s). More than 50 refs: chunks of 50, results merged; any chunk not `Ok` makes the whole result that chunk's failure.
- `HistoryClient.None` answers `Unavailable`; `DemoHistoryClient` answers plausible fixed times so the demo house shows the line.
- An empty ref list answers `Ok(emptyMap())` without a request.

### 4.3 Live flips (`:core:state`)

`EntityState` gains:

```kotlin
/** When a live message last changed the confirmed value; null until the app has seen a flip itself. */
val changedAt: Instant? = null,
```

`StateStore.apply` sets `changedAt = now()` when a `Decoded.State` confirms a value that differs from the previous confirmed value, and only when:
- the previous value was confirmed by a **live** message (its topic is not in `restoredTopics`, and the entity is not `stale`), and
- the previous value is not `StateValue.Unknown`.

So the first message, a value restored from the entity cache, and the first live message after a cache restore never set it: the app did not see those flips happen. `confirmPending` and `revert` leave it unchanged. A repeated identical value leaves it unchanged. It is tracked for every kind (cheap); only Security reads it.

### 4.4 Security view model and builder (`:feature:security`)

`SensorRow` gains `since: Since? = null`:

```kotlin
sealed interface Since {
    data class At(val at: Instant) : Since
    data class Over(val window: Duration) : Since
}
```

`SecurityViewModel` takes a `HistoryClient` (`None` default, like `EnergyViewModel`):
- **Refs:** from the visible binary sensors (same filter as the builder), `changeRef` per entity, as a `Map<uniqueId, HistoryRef>`.
- **Fetch** `lastChanges` when that map changes (`distinctUntilChanged`), when `link` turns `Connected` again, and every 10 min, all only while `state` has subscribers.
- **Result** held as `Map<uniqueId, LastChange>`; a non-`Ok` answer keeps the previous map (an empty one at first). No event, no banner.

`SecurityUiBuilder.build(…, changes: Map<String, LastChange>)` stays pure. Per sensor:

| Server | Local `changedAt` | `since` |
|---|---|---|
| `At(t)` | none | `At(t)` |
| `At(t)` | `c` | `At(max(t, c))` |
| `OlderThan(w)` | none | `Over(w)` |
| `OlderThan(w)` / `NotRecorded` / missing | `c` | `At(c)` |
| `NotRecorded` / missing | none | null |

Unavailable and stale rows keep `since` in the model; the UI hides it (§4.5).

### 4.5 UI

**Tile (`SensorTile`).** The right side of the second row becomes `"<state word> · <duration>"`; the room on the left is still the part that is cut. No duration when `since` is null, the sensor is unavailable, or the row is stale (a cached state is not confirmed yet, so its time may be wrong).

Duration (`SinceFormat`, pure, rounded down), for `now - at`:

| Elapsed | EN | FR |
|---|---|---|
| < 1 min (or negative) | `<1 min` | `<1 min` |
| < 1 h | `N min` | `N min` |
| < 48 h | `N h` | `N h` |
| otherwise | `N d` | `N j` |
| `Over(30 d)` | `30+ d` | `30+ j` |

**Sheet (`SensorSheet`).** A line under "room · state": `Since 08:42` (today), `Since Tue 08:42` (the last 6 days), `Since 3 Oct, 08:42` (older), using locale-aware skeletons (`DateFormat.getBestDateTimePattern`), 24 h or 12 h per system setting; `No change in 30 days` for `Over`. Hidden in the same cases as the tile's duration.

**Clock.** `rememberNow()` moves from `feature/alerts/.../ui/Now.kt` to `:core:designsystem` (alerts updated to import it). `SecurityScreen` reads it once and passes `now` to tiles and the sheet.

**Accessibility.** The tile's `stateDescription` uses plural resources: "Closed for 2 hours", "Clear for less than a minute", "Dry for more than 30 days"; the visible " · " text is not read.

Strings go into `values` and `values-fr` of `:feature:security`.

## 5. Testing

- **`:core:state`** `StateStore`: `changedAt` set on a live flip; unchanged on a repeat, on the first message, after a cache restore (and its first live message), from `Unknown`, on `confirmPending` / `revert`.
- **`:core:history`**: `changeRef` (binary sensor with `value_json.contact`, bracket form, non-binary kinds → null); URL building (repeated pairs, sorted, escaped); parsing of all three item states and malformed answers; chunking above 50; empty list makes no request; cache hit within TTL.
- **`:feature:security`**: builder merge table (§4.4); view model fetches once per ref set, again on reconnect, never with `None`, keeps the previous map on failure; `SinceFormat` boundaries (59 s, 60 min, 47 h 59, 48 h, `Over`); Compose tests for a tile with and without `since`, stale and unavailable rows hiding it, the sheet line; Robolectric screenshots of the Openings section in EN and FR (half-width fit).
- **OikosHistory**: Appendix A §A.5.
- **Device check:** open the Security tab after a cold start — every door shows a duration matching the History screen; open a door — its tile flips to `Open · <1 min` without waiting for a refetch.

---

## Appendix A — OikosHistory `GET /v1/last_change`

Added to `2026-10-08-oikos-history-design.md` §3.3. Same conventions: GET only, JSON, unix seconds, the §3.2 series lookup, the existing error envelope.

### A.1 Request

`GET /v1/last_change?topic=<t1>&key=<k1>&topic=<t2>&key=<k2>…`

- `topic` / `key` pairs repeated and matched by position (no separator inside topics).
- 1–50 pairs. Missing, unequal counts, more than 50, or a NUL byte → `400 {"error":"bad_request","detail":…}`.

### A.2 Lookup

Per pair, resolve the series as §3.2. Unresolved pairs are `recorded: false` and take no part in the query.

Two passes, each **one** Flux query over all remaining series:

```flux
from(bucket: "<bucket>")
  |> range(start: -<R>)
  |> filter(fn: (r) => r._field == "value")
  |> filter(fn: (r) => (r._measurement == "<m1>" and r.device == "<d1>" and r.sensor_type == "<s1>") or (…))
  |> group(columns: ["_measurement", "device", "sensor_type"])
  |> sort(columns: ["_time"])
  |> difference()
  |> filter(fn: (r) => r._value != 0.0)
  |> last()
  |> yield(name: "change")
```

- Pass 1: `R = 24h`. Pass 2: `R = 30d`, only for the series pass 1 found no change for.
- A row gives `changed_at` = its `_time` (`difference` reports the change at the later point, i.e. the first point carrying the new value).
- Series still without a change after pass 2: a third query (one Flux script, two yields) over those series reads `first()` in `-30d` and `last()` in `range(start: 0, stop: -30d)`. When both exist and their values differ, the flip is the window's first point (a sensor that reports only on change can hold a single point in the window), so `changed_at` = its time. Otherwise any row means `recorded: true` (has history, no flip in the window), none means `recorded: false`.
- All strings escaped with `escape_flux_string`.

### A.3 Response

`200`, items in request order:

```json
{"window": 2592000, "items": [
  {"changed_at": 1760350920},
  {"changed_at": null, "recorded": true},
  {"changed_at": null, "recorded": false}
]}
```

- `window`: seconds searched (30 d).
- `{"changed_at": t}`: last flip at `t`.
- `{"changed_at": null, "recorded": true}`: recorded, but no flip in the window.
- `{"changed_at": null, "recorded": false}`: no points at all, or no prefix matched. No whole-response `404`.

### A.4 Errors and limits

`400 bad_request` as A.1; `503 influx_unavailable` when any pass fails or times out (`QUERY_TIMEOUT` per query). At most 30 days, 50 series, three queries per request.

### A.5 Tests

- Flux builder: exact strings for 1 and 3 series, escaping inside the `or` filter.
- Validation: unequal counts, 0 and 51 pairs, NUL byte, unresolved prefix → `recorded: false`.
- Pass split with a fake runner: series changed in 24 h skip pass 2; pass 2 gets only the rest; the probe gets only those still without a change.
- Response shaping for the three item states, order preserved; `503` on a failing pass.
- Opt-in `-m influx`: a series of repeated identical values then one flip reports the flip's time; repeats alone report `recorded: true`.
