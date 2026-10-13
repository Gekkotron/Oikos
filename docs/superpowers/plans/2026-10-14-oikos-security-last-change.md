# Security "time since last change" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every sensor tile on the Security tab shows how long it has been in its current state ("Closed · 2 h"), correct from the first frame after a cold start.

**Architecture:** OikosHistory gains a batch `GET /v1/last_change` that finds each series' last flip in InfluxDB (24 h pass, then 30 d pass, then an existence probe). The app fetches it for all visible Security sensors through `HistoryClient.lastChanges`, records live flips itself in `EntityState.changedAt`, and `SecurityUiBuilder` merges both into `SensorRow.since`; the tile and sheet format it against a minute-ticking clock.

**Tech Stack:** OikosHistory: Python 3.12, FastAPI, influxdb-client, pytest. Oikos: Kotlin, Jetpack Compose, Hilt, kotlinx.coroutines/serialization, JUnit 4/5, Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-13-oikos-security-last-change-design.md` (Appendix A = the OikosHistory contract).

## Global Constraints

- Two repos: `~/Documents/SourcesPerso/OikosHistory` (Tasks 1–2) and `~/Documents/SourcesPerso/Oikos` (Tasks 3–9).
- Every commit: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, message ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never edit `.git/config`.
- Oikos work runs on its own branch `feature/security-last-change` in an isolated worktree: another session edits onboarding files on `master` in the main worktree. Stage only the files a task names.
- Endpoint limits (spec A.1, A.4): 1–50 pairs, window 30 days (`2592000` s), at most three Flux queries per request; errors `400 bad_request`, `503 influx_unavailable`; no whole-response 404.
- App: no history (None client, unreachable, NotAllowed, old service 404) → tiles look exactly as today; no banner, no event.
- `HistoryRefs.of` is not changed: binary sensors must not get History chart entries.
- Durations round down: `<1 min`, `N min` (< 1 h), `N h` (< 48 h), `N d` / FR `N j`, `30+ d` / FR `30+ j`.
- No duration when the row is unavailable, stale, or has no state (`active == null`).
- Strings in both `values` and `values-fr`.
- Pushing either repo and redeploying the Geekom are outward actions: ask the user first (Task 9).

## Review Focus

1. **Phone clock ahead of the server** — `changed_at` later than the phone's `now` must read `<1 min`, never a negative duration. Test in Task 7 (`SinceFormatTest`).
2. **Old OikosHistory without the endpoint** — a LAN 404 maps to `NotRecorded`; the Security tab stays plain, no crash, no event. Tests in Task 3 (client) and Task 6 (view model keeps an empty map).
3. **Two sensors sharing one topic+key** (one device exposed twice) — the client de-duplicates the request but both ids must get the time. Tests in Task 2 (service, duplicate pairs) and Task 6 (view model).
4. **Topics/keys with `&`, `+`, spaces, accents, quotes** — URL-encoded by the app, decoded by FastAPI, escaped in Flux. Tests in Task 3 (URL) and Task 1 (Flux escaping inside the `or` filter).
5. **Away from home, the gateway key lacks the host rule** — `NotAllowed` from any chunk makes the whole result non-Ok; tiles stay plain. Test in Task 3 (chunked `NotAllowed`).

---

### Task 1: OikosHistory — Flux builders for last change

**Files:**
- Modify: `~/Documents/SourcesPerso/OikosHistory/flux.py` (append)
- Test: `~/Documents/SourcesPerso/OikosHistory/tests/test_flux.py` (append)

**Interfaces:**
- Consumes: `escape_flux_string(s: str) -> str`, `SeriesKey(measurement, device, sensor_type)` (existing).
- Produces: `build_last_change_flux(bucket: str, keys: list[SeriesKey], range_: str) -> str` and `build_last_point_flux(bucket: str, keys: list[SeriesKey], range_: str) -> str`. Both raise `ValueError` on a NUL byte (via `escape_flux_string`).

- [ ] **Step 1: Write the failing tests** — append to `tests/test_flux.py` (add `build_last_change_flux, build_last_point_flux` to the existing `from flux import (...)`):

```python
def test_last_change_flux_one_series_exact():
    key = SeriesKey("zigbee2mqtt", "entrance/door", "contact")
    assert build_last_change_flux("athena", [key], "24h") == (
        'from(bucket: "athena")\n'
        '  |> range(start: -24h)\n'
        '  |> filter(fn: (r) => r._field == "value")\n'
        '  |> filter(fn: (r) => (r._measurement == "zigbee2mqtt" and r.device == "entrance/door" and r.sensor_type == "contact"))\n'
        '  |> group(columns: ["_measurement", "device", "sensor_type"])\n'
        '  |> sort(columns: ["_time"])\n'
        '  |> difference()\n'
        '  |> filter(fn: (r) => r._value != 0.0)\n'
        '  |> last()\n'
        '  |> yield(name: "change")\n'
    )


def test_last_change_flux_three_series_or_filter():
    keys = [
        SeriesKey("zigbee2mqtt", "a", "contact"),
        SeriesKey("zigbee2mqtt", "b", "occupancy"),
        SeriesKey("jeedom/data", "c", "state"),
    ]
    flux = build_last_change_flux("athena", keys, "30d")
    assert '  |> range(start: -30d)\n' in flux
    assert (
        '(r._measurement == "zigbee2mqtt" and r.device == "a" and r.sensor_type == "contact")'
        ' or (r._measurement == "zigbee2mqtt" and r.device == "b" and r.sensor_type == "occupancy")'
        ' or (r._measurement == "jeedom/data" and r.device == "c" and r.sensor_type == "state")'
    ) in flux


def test_last_change_flux_escapes_inside_or_filter():
    key = SeriesKey("zigbee2mqtt", 'living/"door" ${x}', "con\\tact")
    flux = build_last_change_flux("athena", [key], "24h")
    assert 'r.device == "living/\\"door\\" \\${x}"' in flux
    assert 'r.sensor_type == "con\\\\tact"' in flux


def test_last_change_flux_rejects_nul():
    with pytest.raises(ValueError):
        build_last_change_flux("athena", [SeriesKey("zigbee2mqtt", "a\x00", "contact")], "24h")


def test_last_point_flux_exact():
    key = SeriesKey("zigbee2mqtt", "entrance/door", "contact")
    assert build_last_point_flux("athena", [key], "30d") == (
        'from(bucket: "athena")\n'
        '  |> range(start: -30d)\n'
        '  |> filter(fn: (r) => r._field == "value")\n'
        '  |> filter(fn: (r) => (r._measurement == "zigbee2mqtt" and r.device == "entrance/door" and r.sensor_type == "contact"))\n'
        '  |> group(columns: ["_measurement", "device", "sensor_type"])\n'
        '  |> last()\n'
        '  |> yield(name: "seen")\n'
    )
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/Documents/SourcesPerso/OikosHistory && venv/bin/pytest tests/test_flux.py -q`
Expected: ImportError / FAIL on `build_last_change_flux`.

- [ ] **Step 3: Implement** — append to `flux.py`:

```python
def _series_or(keys) -> str:
    """One `or` filter matching every requested series (last-change spec A.2)."""
    parts = []
    for k in keys:
        m = escape_flux_string(k.measurement)
        d = escape_flux_string(k.device)
        s = escape_flux_string(k.sensor_type)
        parts.append(f'(r._measurement == "{m}" and r.device == "{d}" and r.sensor_type == "{s}")')
    return " or ".join(parts)


def _series_head(bucket: str, keys, range_: str) -> str:
    b = escape_flux_string(bucket)
    return (
        f'from(bucket: "{b}")\n'
        f'  |> range(start: -{range_})\n'
        '  |> filter(fn: (r) => r._field == "value")\n'
        f'  |> filter(fn: (r) => {_series_or(keys)})\n'
        '  |> group(columns: ["_measurement", "device", "sensor_type"])\n'
    )


def build_last_change_flux(bucket: str, keys, range_: str) -> str:
    """Per series, the first point carrying the current value: `difference` reports a flip at its later point."""
    return (
        _series_head(bucket, keys, range_)
        + '  |> sort(columns: ["_time"])\n'
        + '  |> difference()\n'
        + '  |> filter(fn: (r) => r._value != 0.0)\n'
        + '  |> last()\n'
        + '  |> yield(name: "change")\n'
    )


def build_last_point_flux(bucket: str, keys, range_: str) -> str:
    """Existence probe for series without a flip: one row per series that has any point in range."""
    return (
        _series_head(bucket, keys, range_)
        + '  |> last()\n'
        + '  |> yield(name: "seen")\n'
    )
```

`range_` is only ever one of the handler's constants (`"24h"`, `"30d"`), never user input.

- [ ] **Step 4: Run to verify they pass**

Run: `venv/bin/pytest tests/test_flux.py -q`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add flux.py tests/test_flux.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(flux): last-change and last-point builders for many series

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: OikosHistory — `GET /v1/last_change`

**Files:**
- Modify: `~/Documents/SourcesPerso/OikosHistory/handlers.py` (imports + new handler at the end)
- Modify: `~/Documents/SourcesPerso/OikosHistory/app.py` (route)
- Modify: `~/Documents/SourcesPerso/OikosHistory/tests/fake_influx.py` (records carry tags)
- Modify: `~/Documents/SourcesPerso/OikosHistory/README.md` (endpoint list)
- Test: `~/Documents/SourcesPerso/OikosHistory/tests/test_handlers.py` (append), `tests/test_integration_influx.py` (append)

**Interfaces:**
- Consumes: `build_last_change_flux`, `build_last_point_flux` (Task 1); `resolve(topic, key, prefixes) -> SeriesKey | None`; `error(status, code, detail)`; `InfluxUnavailable`.
- Produces: `last_change(topics: list[str], keys: list[str], settings: Settings, runner: QueryRunner) -> JSONResponse`; HTTP contract of spec Appendix A.

- [ ] **Step 1: Let fake records carry tags** — in `tests/fake_influx.py`, give `FakeRecord` an `extra` dict:

```python
@dataclass
class FakeRecord:
    time: datetime
    value: float
    result: str = ""  # yield name — groups rows across tables
    extra: dict = field(default_factory=dict)  # tag columns such as device / sensor_type

    def get_time(self) -> datetime:
        return self.time

    def get_value(self) -> float:
        return self.value

    def __getitem__(self, key: str) -> Any:
        if key == "result":
            return self.result
        if key == "_time":
            return self.time
        if key == "_value":
            return self.value
        if key in self.extra:
            return self.extra[key]
        raise KeyError(key)

    @property
    def values(self) -> dict:
        return {"result": self.result, "_time": self.time, "_value": self.value, **self.extra}
```

- [ ] **Step 2: Write the failing handler tests** — append to `tests/test_handlers.py`:

```python
DOOR = ("zigbee2mqtt/entrance/door", "contact")
HALL = ("zigbee2mqtt/hall/motion", "occupancy")
SHED = ("zigbee2mqtt/shed/door", "contact")


def _lc_url(*pairs):
    from urllib.parse import urlencode
    q = []
    for topic, key in pairs:
        q += [("topic", topic), ("key", key)]
    return "/v1/last_change?" + urlencode(q)


def _tag_rec(t, device, sensor_type, result):
    return FakeRecord(_t(t), 1.0, result, {"_measurement": "zigbee2mqtt", "device": device, "sensor_type": sensor_type})


def test_last_change_validation(client):
    assert client.get("/v1/last_change").status_code == 400
    assert client.get("/v1/last_change?topic=a").status_code == 400
    assert client.get("/v1/last_change?topic=a&key=b&topic=c").json()["error"] == "bad_request"
    assert client.get(_lc_url(*[("zigbee2mqtt/d%d" % i, "contact") for i in range(51)])).status_code == 400
    assert client.get(_lc_url(("zigbee2mqtt/a\x00", "contact"))).status_code == 400


def test_last_change_three_states_in_request_order(client, runner):
    def respond(flux):
        if 'yield(name: "change")' in flux and "-24h" in flux:
            return [FakeTable([_tag_rec(1760350920, "entrance/door", "contact", "change")])]
        if 'yield(name: "seen")' in flux:
            return [FakeTable([_tag_rec(1760000000, "hall/motion", "occupancy", "seen")])]
        return []
    runner.respond = respond
    r = client.get(_lc_url(HALL, ("unknown/prefix", "contact"), DOOR, SHED))
    assert r.status_code == 200
    assert r.json() == {"window": 2592000, "items": [
        {"changed_at": None, "recorded": True},
        {"changed_at": None, "recorded": False},
        {"changed_at": 1760350920},
        {"changed_at": None, "recorded": False},
    ]}


def test_last_change_passes_narrow_down(client, runner):
    def respond(flux):
        if 'yield(name: "change")' in flux and "-24h" in flux:
            return [FakeTable([_tag_rec(1760350920, "entrance/door", "contact", "change")])]
        if 'yield(name: "change")' in flux and "-30d" in flux:
            return [FakeTable([_tag_rec(1759000000, "hall/motion", "occupancy", "change")])]
        return []
    runner.respond = respond
    body = client.get(_lc_url(DOOR, HALL, SHED)).json()
    assert [i["changed_at"] for i in body["items"]] == [1760350920, 1759000000, None]
    q24, q30, probe = runner.queries
    assert "entrance/door" in q24 and "hall/motion" in q24 and "shed/door" in q24
    assert "entrance/door" not in q30 and "hall/motion" in q30 and "shed/door" in q30
    assert 'yield(name: "seen")' in probe and "shed/door" in probe and "hall/motion" not in probe


def test_last_change_all_found_in_24h_makes_one_query(client, runner):
    runner.respond = lambda flux: [FakeTable([_tag_rec(1760350920, "entrance/door", "contact", "change")])]
    assert client.get(_lc_url(DOOR)).json()["items"] == [{"changed_at": 1760350920}]
    assert len(runner.queries) == 1


def test_last_change_duplicate_pairs_both_answered(client, runner):
    runner.respond = lambda flux: [FakeTable([_tag_rec(1760350920, "entrance/door", "contact", "change")])]
    body = client.get(_lc_url(DOOR, DOOR)).json()
    assert body["items"] == [{"changed_at": 1760350920}, {"changed_at": 1760350920}]
    assert runner.queries[0].count("entrance/door") == 1


def test_last_change_only_unresolved_makes_no_query(client, runner):
    body = client.get(_lc_url(("unknown/prefix", "contact"))).json()
    assert body["items"] == [{"changed_at": None, "recorded": False}]
    assert runner.queries == []


def test_last_change_influx_down_returns_503(client, runner):
    runner.raise_on_query = InfluxUnavailable("boom")
    r = client.get(_lc_url(DOOR))
    assert r.status_code == 503
    assert r.json() == {"error": "influx_unavailable"}
```

- [ ] **Step 3: Run to verify they fail**

Run: `venv/bin/pytest tests/test_handlers.py -q -k last_change`
Expected: FAIL (404 Not Found from FastAPI: no route).

- [ ] **Step 4: Implement the handler** — in `handlers.py` add `build_last_change_flux, build_last_point_flux` to the `from flux import (...)` list and `SeriesKey` to the `series_lookup` import (`from series_lookup import SeriesKey, resolve`), then append:

```python
LAST_CHANGE_WINDOW_S = 30 * 86_400
LAST_CHANGE_MAX = 50


def _series_of(record) -> SeriesKey:
    return SeriesKey(record["_measurement"], record["device"], record["sensor_type"])


def last_change(topics: list[str], keys: list[str], settings: Settings, runner: QueryRunner) -> JSONResponse:
    """Last-change spec Appendix A: per pair, the time of the last flip within 30 days."""
    if len(topics) != len(keys) or not 1 <= len(topics) <= LAST_CHANGE_MAX:
        return error(400, "bad_request", f"expected 1-{LAST_CHANGE_MAX} topic/key pairs")
    if any("\x00" in s for s in (*topics, *keys)):
        return error(400, "bad_request", "NUL byte")

    resolved = [resolve(t, k, settings.prefixes) for t, k in zip(topics, keys)]
    pending = list(dict.fromkeys(sk for sk in resolved if sk is not None))
    changed: dict[SeriesKey, int] = {}
    seen: set[SeriesKey] = set()
    try:
        # Busy motion sensors have many points: look at the last day first, the month only for the rest.
        for range_ in ("24h", "30d"):
            if not pending:
                break
            for t in runner.query(build_last_change_flux(settings.influx_bucket, pending, range_)):
                for r in t.records:
                    changed[_series_of(r)] = int(r.get_time().timestamp())
            pending = [sk for sk in pending if sk not in changed]
        if pending:
            for t in runner.query(build_last_point_flux(settings.influx_bucket, pending, "30d")):
                for r in t.records:
                    seen.add(_series_of(r))
    except InfluxUnavailable:
        return error(503, "influx_unavailable")

    items = []
    for sk in resolved:
        if sk is not None and sk in changed:
            items.append({"changed_at": changed[sk]})
        else:
            items.append({"changed_at": None, "recorded": sk is not None and sk in seen})
    return JSONResponse(status_code=200, content={"window": LAST_CHANGE_WINDOW_S, "items": items})
```

- [ ] **Step 5: Add the route** — in `app.py`, import `last_change as last_change_handler` in the `from handlers import (...)` list and add after `/v1/today`:

```python
    @app.get("/v1/last_change")
    def _last_change(
        topic: list[str] = Query(...),
        key: list[str] = Query(...),
    ) -> JSONResponse:
        return last_change_handler(topic, key, settings, runner)
```

- [ ] **Step 6: Run the whole suite**

Run: `venv/bin/pytest -q`
Expected: all pass (integration tests skipped without `INFLUX_URL`).

- [ ] **Step 7: Integration case** — append to `tests/test_integration_influx.py`:

```python
def test_last_change_ignores_repeats(live_influx):
    from datetime import datetime, timedelta, timezone
    from influxdb_client import InfluxDBClient, Point  # type: ignore[import-untyped]
    from influxdb_client.client.write_api import SYNCHRONOUS  # type: ignore[import-untyped]
    from influx import InfluxAdapter
    from series_lookup import SeriesKey
    from flux import build_last_change_flux, build_last_point_flux

    now = datetime.now(timezone.utc).replace(microsecond=0)
    flip_at = now - timedelta(minutes=30)
    with InfluxDBClient(url=live_influx["url"], token=live_influx["token"], org=live_influx["org"]) as c:
        w = c.write_api(write_options=SYNCHRONOUS)
        # closed (1.0) every 5 min, opened (0.0) at flip_at, then repeats of 0.0
        for i in range(12):
            t = now - timedelta(minutes=90 - 5 * i)
            v = 1.0 if t < flip_at else 0.0
            w.write(bucket=live_influx["bucket"], record=Point("zigbee2mqtt").tag("device", "test/door")
                    .tag("sensor_type", "contact").field("value", v).time(t))
        for i in range(6):
            w.write(bucket=live_influx["bucket"], record=Point("zigbee2mqtt").tag("device", "test/still")
                    .tag("sensor_type", "contact").field("value", 1.0).time(now - timedelta(minutes=5 * i)))
    time.sleep(1)

    adapter = InfluxAdapter(url=live_influx["url"], token=live_influx["token"], org=live_influx["org"],
                            bucket=live_influx["bucket"], timeout_s=10)
    try:
        door = SeriesKey("zigbee2mqtt", "test/door", "contact")
        still = SeriesKey("zigbee2mqtt", "test/still", "contact")
        rows = [r for t in adapter.query(build_last_change_flux(live_influx["bucket"], [door, still], "24h")) for r in t.records]
        assert [(r["device"], r.get_time()) for r in rows] == [("test/door", flip_at)]
        seen = [r["device"] for t in adapter.query(build_last_point_flux(live_influx["bucket"], [still], "30d")) for r in t.records]
        assert seen == ["test/still"]
    finally:
        adapter.close()
```

Run: `venv/bin/pytest -q -m influx` only if a test InfluxDB is reachable (env per the file's docstring); otherwise it skips — say so in the report.

- [ ] **Step 8: README** — in `README.md`, after the `/v1/today` line add:

```markdown
- `GET /v1/last_change?topic=&key=[&topic=&key=…]` (1–50 pairs) → `{"window": 2592000, "items": [{"changed_at": t} | {"changed_at": null, "recorded": true|false}, ...]}` — time of each series' last flip within 30 days, in request order
```

- [ ] **Step 9: Commit**

```bash
git add handlers.py app.py tests/fake_influx.py tests/test_handlers.py tests/test_integration_influx.py README.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: GET /v1/last_change — last flip per series for the Security tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `:core:history` — `LastChange`, `changeRef`, API and HTTP client

Work in the Oikos worktree on `feature/security-last-change` from here on.

**Files:**
- Modify: `core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryModels.kt`
- Modify: `core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryRefs.kt`
- Modify: `core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryApi.kt`
- Modify: `core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryClient.kt`
- Test: `core/history/src/test/kotlin/com/gekkotron/oikos/history/HistoryRefsTest.kt`, `HttpHistoryClientTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface LastChange { data class At(val at: Instant); data class OlderThan(val window: Duration); data object NotRecorded }` (`java.time.Instant`, `java.time.Duration`)
  - `HistoryRefs.changeRef(entity: Entity): HistoryRef?`
  - `HistoryClient.lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>>` — interface default returns `HistoryResult.Unavailable("not supported")`, so `None` and test fakes keep compiling.
  - `HistoryApi.lastChangeUrl(base: String, refs: List<HistoryRef>): String`, `HistoryApi.parseLastChange(text: String, refs: List<HistoryRef>): Map<HistoryRef, LastChange>`

- [ ] **Step 1: Failing tests for `changeRef`** — append inside `HistoryRefsTest`:

```kotlin
    @Test
    fun `changeRef reads a binary sensor's topic and key`() {
        val door = entity(Kind.BINARY_SENSOR, null, "state_topic" to "zigbee2mqtt/entrance/door", "value_template" to "{{ value_json.contact }}")
        assertEquals(HistoryRef("zigbee2mqtt/entrance/door", "contact"), HistoryRefs.changeRef(door))
        val bracket = entity(Kind.BINARY_SENSOR, null, "state_topic" to "z/d", "value_template" to "{{ value_json['occupancy'] }}")
        assertEquals(HistoryRef("z/d", "occupancy"), HistoryRefs.changeRef(bracket))
    }

    @Test
    fun `changeRef is null for other kinds, no topic or an unreadable template`() {
        assertNull(HistoryRefs.changeRef(entity(Kind.SENSOR, "°C", "state_topic" to "t", "value_template" to "{{ value_json.temperature }}")))
        assertNull(HistoryRefs.changeRef(entity(Kind.BINARY_SENSOR, null, "value_template" to "{{ value_json.contact }}")))
        assertNull(HistoryRefs.changeRef(entity(Kind.BINARY_SENSOR, null, "state_topic" to "t", "value_template" to "{{ value }}")))
    }

    @Test
    fun `binary sensors still get no chart ref`() =
        assertNull(HistoryRefs.of(entity(Kind.BINARY_SENSOR, null, "state_topic" to "t", "value_template" to "{{ value_json.contact }}")))
```

- [ ] **Step 2: Failing tests for the client** — append inside `HttpHistoryClientTest` (add imports `java.time.Duration`, `java.time.Instant`):

```kotlin
    private val door = HistoryRef("zigbee2mqtt/entrance/door", "contact")
    private val hall = HistoryRef("zigbee2mqtt/hall/motion", "occupancy")
    private val shed = HistoryRef("zigbee2mqtt/shed/door", "contact")

    @Test
    fun `lastChanges sends sorted, de-duplicated pairs and maps the three states`(): Unit = runBlocking {
        answer = ok("""{"window":2592000,"items":[{"changed_at":1760350920},{"changed_at":null,"recorded":true},{"changed_at":null,"recorded":false}]}""")
        val r = assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(client().lastChanges(listOf(shed, door, hall, door))).value
        assertEquals(
            "http://192.168.1.91:8090/v1/last_change?topic=zigbee2mqtt%2Fentrance%2Fdoor&key=contact" +
                "&topic=zigbee2mqtt%2Fhall%2Fmotion&key=occupancy&topic=zigbee2mqtt%2Fshed%2Fdoor&key=contact",
            urls.single(),
        )
        assertEquals(
            mapOf(
                door to LastChange.At(Instant.ofEpochSecond(1760350920)),
                hall to LastChange.OlderThan(Duration.ofDays(30)),
                shed to LastChange.NotRecorded,
            ),
            r,
        )
    }

    @Test
    fun `lastChanges encodes awkward topics and keys`(): Unit = runBlocking {
        answer = ok("""{"window":2592000,"items":[{"changed_at":null,"recorded":false}]}""")
        client().lastChanges(listOf(HistoryRef("z/salle de bain & co+1", "état\"")))
        assertEquals(
            "http://192.168.1.91:8090/v1/last_change?topic=z%2Fsalle%20de%20bain%20%26%20co%2B1&key=%C3%A9tat%22",
            urls.single(),
        )
    }

    @Test
    fun `lastChanges with no refs asks nothing`(): Unit = runBlocking {
        assertEquals(HistoryResult.Ok(emptyMap()), client().lastChanges(emptyList()))
        assertEquals(emptyList(), urls)
    }

    @Test
    fun `lastChanges chunks by 50 and any failed chunk fails the whole`(): Unit = runBlocking {
        val refs = (0 until 60).map { HistoryRef("z/d%02d".format(it), "contact") }
        var calls = 0
        val c = HttpHistoryClient(baseUrl = { "http://h:8090" }, get = { url ->
            urls += url
            calls++
            val n = url.split("&topic=").size
            ok("""{"window":2592000,"items":[${List(n) { """{"changed_at":1760350920}""" }.joinToString(",")}]}""")
        }, clock = { now })
        val all = assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(c.lastChanges(refs)).value
        assertEquals(60, all.size)
        assertEquals(2, calls)
        assertEquals(50, urls[0].split("&topic=").size)

        val gated = HttpHistoryClient(baseUrl = { "http://h:8090" }, get = { url ->
            if (url.contains("z%2Fd55")) HttpGetResult.NotAllowed else ok("""{"window":2592000,"items":[${List(50) { """{"changed_at":1}""" }.joinToString(",")}]}""")
        }, clock = { now })
        assertEquals(HistoryResult.NotAllowed, gated.lastChanges(refs))
    }

    @Test
    fun `lastChanges failures map like the other calls`(): Unit = runBlocking {
        answer = HttpGetResult.Response(404, ByteArray(0), null) // an OikosHistory without the endpoint
        assertEquals(HistoryResult.NotRecorded, client().lastChanges(listOf(door)))
        answer = ok("""{"window":2592000,"items":[]}""") // item count does not match
        assertIs<HistoryResult.Unavailable>(client().lastChanges(listOf(hall)))
        answer = ok("""{"items":[{"changed_at":1}]}""") // no window
        assertIs<HistoryResult.Unavailable>(client().lastChanges(listOf(shed)))
    }

    @Test
    fun `lastChanges answers are cached for a minute`(): Unit = runBlocking {
        answer = ok("""{"window":2592000,"items":[{"changed_at":1760350920}]}""")
        val c = client()
        c.lastChanges(listOf(door))
        now = 59_000
        c.lastChanges(listOf(door))
        assertEquals(1, urls.size)
        now = 61_000
        c.lastChanges(listOf(door))
        assertEquals(2, urls.size)
    }
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew :core:history:test`
Expected: compilation errors (`LastChange`, `changeRef`, `lastChanges` unresolved).

- [ ] **Step 4: Model** — append to `HistoryModels.kt` (add `import java.time.Duration` and `import java.time.Instant` at the top):

```kotlin
/** When a binary series last flipped (last-change spec §4.2). */
sealed interface LastChange {
    data class At(val at: Instant) : LastChange

    /** Recorded, but no flip within [window]. */
    data class OlderThan(val window: Duration) : LastChange

    data object NotRecorded : LastChange
}
```

- [ ] **Step 5: `changeRef`** — add to `HistoryRefs` after `of`:

```kotlin
    /**
     * A binary sensor's recorded series, read for the time of its last flip (last-change spec §4.1). Kept apart
     * from [of] on purpose: binary sensors get no chart of 0/1 means.
     */
    fun changeRef(entity: Entity): HistoryRef? {
        if (entity.kind != Kind.BINARY_SENSOR) return null
        val b = entity.bindings as? MqttBindings ?: return null
        val topic = b.str("state_topic") ?: return null
        val key = jsonKey(b.str("value_template")) ?: return null
        return HistoryRef(topic, key)
    }
```

- [ ] **Step 6: API** — in `HistoryApi.kt` add imports `kotlinx.serialization.json.booleanOrNull`, `java.time.Duration`, `java.time.Instant`, then inside the object:

```kotlin
    /** Pairs repeated in order (last-change spec A.1). */
    fun lastChangeUrl(base: String, refs: List<HistoryRef>): String =
        refs.joinToString("&", prefix = "${base.trimEnd('/')}/v1/last_change?") { "topic=${encode(it.topic)}&key=${encode(it.key)}" }

    fun parseLastChange(text: String, refs: List<HistoryRef>): Map<HistoryRef, LastChange> {
        val o = (Json.parseToJsonElement(text) as? JsonObject ?: error("not an object"))
        val window = Duration.ofSeconds(o.getValue("window").jsonPrimitive.longOrNull ?: error("bad window"))
        val items = o.getValue("items").jsonArray
        require(items.size == refs.size) { "expected ${refs.size} items, got ${items.size}" }
        return refs.zip(items).associate { (ref, item) ->
            val i = item as? JsonObject ?: error("bad item")
            val at = i["changed_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
            ref to when {
                at != null -> LastChange.At(Instant.ofEpochSecond(at))
                i["recorded"]?.jsonPrimitive?.booleanOrNull == true -> LastChange.OlderThan(window)
                else -> LastChange.NotRecorded
            }
        }
    }
```

(`o.getValue` throws `NoSuchElementException` and `require` throws `IllegalArgumentException`; `fetch` already turns both into `Unavailable`.)

- [ ] **Step 7: Client** — in `HistoryClient.kt`, add to the interface (after `today`):

```kotlin
    /** Last flip per ref (last-change spec §4.2); refs absent from the map got no answer. */
    suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> =
        HistoryResult.Unavailable("not supported")
```

and to `HttpHistoryClient` (after `today`):

```kotlin
    override suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> {
        // Sorted and de-duplicated: the same set of refs is the same URL, so the same cache entry.
        val sorted = refs.distinct().sortedWith(compareBy({ it.topic }, { it.key }))
        if (sorted.isEmpty()) return HistoryResult.Ok(emptyMap())
        val all = HashMap<HistoryRef, LastChange>()
        for (chunk in sorted.chunked(MAX_REFS)) {
            when (val r = fetch({ HistoryApi.lastChangeUrl(it, chunk) }, SHORT_TTL) { HistoryApi.parseLastChange(it, chunk) }) {
                is HistoryResult.Ok -> all += r.value
                else -> return r
            }
        }
        return HistoryResult.Ok(all)
    }
```

and `const val MAX_REFS = 50` in its `private companion object`.

- [ ] **Step 8: Run to verify they pass**

Run: `./gradlew :core:history:test`
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
git add core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryModels.kt \
        core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryRefs.kt \
        core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryApi.kt \
        core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryClient.kt \
        core/history/src/test/kotlin/com/gekkotron/oikos/history/HistoryRefsTest.kt \
        core/history/src/test/kotlin/com/gekkotron/oikos/history/HttpHistoryClientTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(history): lastChanges over /v1/last_change and binary-sensor change refs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Demo answers and the profile client delegates

**Files:**
- Modify: `core/history/src/main/kotlin/com/gekkotron/oikos/history/DemoHistoryClient.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClient.kt`
- Test: `core/history/src/test/kotlin/com/gekkotron/oikos/history/DemoHistoryClientTest.kt`, `app/src/test/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClientTest.kt`

**Interfaces:**
- Consumes: `HistoryClient.lastChanges`, `LastChange` (Task 3).
- Produces: nothing new; without the delegate, `ProfileHistoryClient` would fall back to the interface default and the real app would never ask.

- [ ] **Step 1: Failing tests** — append inside `DemoHistoryClientTest` (imports `java.time.Instant`, `kotlin.test.assertIs`, `kotlin.test.assertTrue` if missing):

```kotlin
    @Test
    fun `lastChanges answers every ref with a past time, the same twice`(): Unit = runBlocking {
        val now = 1_760_000_000_000L
        val c = DemoHistoryClient(clock = { now })
        val refs = listOf(HistoryRef("demo/door", "contact"), HistoryRef("demo/hall", "occupancy"))
        val a = assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(c.lastChanges(refs)).value
        assertEquals(refs.toSet(), a.keys)
        a.values.forEach {
            val at = assertIs<LastChange.At>(it).at
            assertTrue(at < Instant.ofEpochMilli(now) && at > Instant.ofEpochMilli(now).minusSeconds(3 * 86_400))
        }
        assertEquals(a, assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(c.lastChanges(refs)).value)
    }
```

and inside `ProfileHistoryClientTest`:

```kotlin
    @Test
    fun `lastChanges goes to the service for a direct profile`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct)
        c.lastChanges(listOf(HistoryRef("t", "k")))
        assertEquals("http://192.168.1.91:8090/v1/last_change?topic=t&key=k", urls.single())
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:history:test :app:testDebugUnitTest --tests "*ProfileHistoryClientTest*"`
Expected: Demo test FAIL (`Unavailable` from the default), profile test FAIL (no URL requested).

- [ ] **Step 3: Implement** — in `DemoHistoryClient` (after `today`):

```kotlin
    /** Each sensor flipped between 1 min and 3 days ago, the same for the same ref. */
    override suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> {
        val now = clock() / 1000
        return HistoryResult.Ok(refs.associateWith { LastChange.At(Instant.ofEpochSecond(now - Random(it.hashCode()).nextLong(60, 3 * 86_400L))) })
    }
```

In `ProfileHistoryClient` (after `today`), plus imports `com.gekkotron.oikos.history.LastChange` and `com.gekkotron.oikos.history.HistoryResult` only if the compiler asks:

```kotlin
    override suspend fun lastChanges(refs: List<HistoryRef>) = current().lastChanges(refs)
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :core:history:test :app:testDebugUnitTest --tests "*ProfileHistoryClientTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/history/src/main/kotlin/com/gekkotron/oikos/history/DemoHistoryClient.kt \
        core/history/src/test/kotlin/com/gekkotron/oikos/history/DemoHistoryClientTest.kt \
        app/src/main/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClient.kt \
        app/src/test/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClientTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(history): demo last changes; profile client forwards lastChanges

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `:core:state` — `EntityState.changedAt` from live flips

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/EntityState.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/StateStore.kt` (`apply`, the `Decoded.State` branch)
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt`

**Interfaces:**
- Produces: `EntityState.changedAt: Instant?` (default null), moved only by a live message that changes a live, known confirmed value.

- [ ] **Step 1: Failing tests** — append inside `StateStoreTest`:

```kotlin
    private var time = Instant.parse("2026-10-14T08:00:00Z")
    private val timed = StateStore(now = { time })
    private val door = testEntity("door", Kind.BINARY_SENSOR, "binary_sensor", """{"state_topic":"d"}""")
    private fun changedAt() = timed.states.value.getValue("door").changedAt

    @Test
    fun `a live flip sets changedAt and a repeat keeps it`() {
        timed.setEntities(listOf(door))
        timed.onMessage("d", "OFF")
        assertEquals(null, changedAt()) // the first message: the flip was not seen
        time = time.plusSeconds(60)
        timed.onMessage("d", "ON")
        assertEquals(time, changedAt())
        val flippedAt = time
        time = time.plusSeconds(600)
        timed.onMessage("d", "ON") // a battery report republishes the same state
        assertEquals(flippedAt, changedAt())
        assertEquals(time, timed.states.value.getValue("door").lastUpdate)
    }

    @Test
    fun `cache restores never count as a flip`() {
        timed.setEntities(listOf(door))
        timed.onMessage("d", "OFF", restored = true)
        time = time.plusSeconds(60)
        timed.onMessage("d", "ON") // differs from the cached value, but the change happened while the app was away
        assertEquals(null, changedAt())
        time = time.plusSeconds(60)
        timed.onMessage("d", "OFF")
        assertEquals(time, changedAt())
    }

    @Test
    fun `a value back from unknown after settle is not a flip`() {
        timed.setEntities(listOf(door))
        timed.onMessage("d", "OFF", restored = true)
        timed.settle() // never re-sent: back to Unknown
        timed.onMessage("d", "ON")
        assertEquals(null, changedAt())
    }

    @Test
    fun `confirmPending and revert leave changedAt alone`() {
        timed.setEntities(listOf(lamp))
        timed.onMessage("s", "OFF")
        timed.markPending("l", StateValue.OnOff(true))
        timed.confirmPending("l")
        assertEquals(null, timed.states.value.getValue("l").changedAt)
        timed.markPending("l", StateValue.OnOff(false))
        timed.revert("l")
        assertEquals(null, timed.states.value.getValue("l").changedAt)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:state:test --tests "com.gekkotron.oikos.state.StateStoreTest"`
Expected: compilation error (`changedAt` unresolved).

- [ ] **Step 3: Model** — in `EntityState`, after `stale`:

```kotlin
    /** When a live message last changed the confirmed value; null until the app has seen a flip itself (last-change spec §4.3). */
    val changedAt: Instant? = null,
```

- [ ] **Step 4: Store** — replace the `is Decoded.State ->` branch in `StateStore.apply` with:

```kotlin
                is Decoded.State -> {
                    val previous = confirmed[id]
                    confirmed[id] = d.value
                    val confirmsCommand = !s.pending || confirmTopics[id]?.contains(topic) != false
                    if (confirmsCommand) {
                        confirmTopics.remove(id)
                        // Only a flip seen live from a live, known value: not the first message, nor a cache restore or the message after one.
                        val flipped = previous != null && previous != StateValue.Unknown && previous != d.value &&
                            !s.stale && topic !in restoredTopics
                        val at = now()
                        s = s.copy(value = d.value, pending = false, lastUpdate = at, changedAt = if (flipped) at else s.changedAt)
                        confirmedNow = true
                    }
                }
```

- [ ] **Step 5: Run the module**

Run: `./gradlew :core:state:test`
Expected: all pass (existing tests unchanged).

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/com/gekkotron/oikos/model/EntityState.kt \
        core/state/src/main/kotlin/com/gekkotron/oikos/state/StateStore.kt \
        core/state/src/test/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): changedAt moves only on a live flip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Security — fetch last changes and merge them into `SensorRow.since`

**Files:**
- Modify: `feature/security/build.gradle.kts` (dependency)
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityModels.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilder.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityViewModel.kt`
- Create: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/FakeHistoryClient.kt`
- Modify: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/TestEntities.kt`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilderTest.kt`, `SecurityViewModelTest.kt`

**Interfaces:**
- Consumes: `LastChange`, `HistoryRefs.changeRef`, `HistoryClient.lastChanges` (Task 3); `EntityState.changedAt` (Task 5).
- Produces:
  - `sealed interface Since { data class At(val at: Instant); data class Over(val window: Duration) }` in `SecurityModels.kt`
  - `SensorRow.since: Since? = null`
  - `SecurityUiBuilder.build(entities, states, areas, link, changes: Map<String, LastChange> = emptyMap())`
  - `SecurityViewModel` internal constructor `(source, dispatcher, history: HistoryClient = HistoryClient.None, refresh: kotlin.time.Duration = 10.minutes)`; `@Inject constructor(source: EntitySource, history: HistoryClient)`.

- [ ] **Step 1: Dependency** — in `feature/security/build.gradle.kts` dependencies add `implementation(project(":core:history"))`.

- [ ] **Step 2: Test helpers** — append to `TestEntities.kt` (imports `com.gekkotron.oikos.state.mqtt.MqttBindings`, `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.JsonPrimitive`):

```kotlin
/** A sensor discovered over MQTT, so HistoryRefs.changeRef finds its topic and key. */
fun mqttSensor(id: String, deviceClass: String, topic: String = "zigbee2mqtt/$id", key: String = "contact") = Entity(
    id, null, id, Kind.BINARY_SENSOR, deviceClass, null, null, Capabilities(),
    MqttBindings("binary_sensor", JsonObject(mapOf("state_topic" to JsonPrimitive(topic), "value_template" to JsonPrimitive("{{ value_json.$key }}")))),
    UNASSIGNED_AREA,
)
```

Create `FakeHistoryClient.kt`:

```kotlin
package com.gekkotron.oikos.feature.security

import com.gekkotron.oikos.history.DailyTotals
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.history.Series
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHistoryClient : HistoryClient {
    override val available = MutableStateFlow(true)
    val asked = mutableListOf<List<HistoryRef>>()
    var answer: HistoryResult<Map<HistoryRef, LastChange>> = HistoryResult.Ok(emptyMap())

    override suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series> = HistoryResult.Unavailable("unused")
    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals> = HistoryResult.Unavailable("unused")
    override suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?> = HistoryResult.Unavailable("unused")
    override suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> {
        asked += refs
        return answer
    }
}
```

- [ ] **Step 3: Failing builder tests** — append inside `SecurityUiBuilderTest` (imports `com.gekkotron.oikos.history.LastChange`, `java.time.Duration`, `java.time.Instant`):

```kotlin
    private val t0 = Instant.parse("2026-10-14T06:00:00Z")
    private val month = Duration.ofDays(30)

    private fun since(server: LastChange?, local: Instant?): Since? {
        val state = EntityState("door", StateValue.OnOff(false), changedAt = local)
        val changes = server?.let { mapOf("door" to it) } ?: emptyMap()
        return SecurityUiBuilder.build(mapOf("door" to sensor("door", "door")), mapOf("door" to state), areas, LinkState.Connecting, changes)
            .sensors.single().since
    }

    @Test
    fun `since merges the server answer with a live flip`() {
        assertEquals(Since.At(t0), since(LastChange.At(t0), null))
        assertEquals(Since.At(t0.plusSeconds(60)), since(LastChange.At(t0), t0.plusSeconds(60)))
        assertEquals(Since.At(t0), since(LastChange.At(t0), t0.minusSeconds(60)))
        assertEquals(Since.Over(month), since(LastChange.OlderThan(month), null))
        assertEquals(Since.At(t0), since(LastChange.OlderThan(month), t0))
        assertEquals(Since.At(t0), since(LastChange.NotRecorded, t0))
        assertEquals(Since.At(t0), since(null, t0))
        assertNull(since(LastChange.NotRecorded, null))
        assertNull(since(null, null))
    }
```

- [ ] **Step 4: Failing view-model tests** — append inside `SecurityViewModelTest` (imports `com.gekkotron.oikos.feature.security.FakeHistoryClient`, `com.gekkotron.oikos.feature.security.mqttSensor`, `com.gekkotron.oikos.history.HistoryRef`, `com.gekkotron.oikos.history.HistoryResult`, `com.gekkotron.oikos.history.LastChange`, `com.gekkotron.oikos.transport.LinkState`, `com.gekkotron.oikos.transport.TransportMode`, `java.time.Instant`, `kotlin.time.Duration.Companion.minutes`):

```kotlin
    private val history = FakeHistoryClient()
    private val t0 = Instant.parse("2026-10-14T06:00:00Z")
    private val doorRef = HistoryRef("zigbee2mqtt/front", "contact")

    private fun mqttSource() = FakeEntitySource().apply {
        entities.value = listOf(mqttSensor("front", "door"), mqttSensor("hall", "motion", key = "occupancy")).associateBy { it.uniqueId }
        stateMap.value = listOf(on("front", false), on("hall", false)).associateBy { it.uniqueId }
    }

    @Test
    fun `last changes are fetched once per ref set and land on the rows`() = runTest(dispatcher) {
        val src = mqttSource()
        history.answer = HistoryResult.Ok(mapOf(doorRef to LastChange.At(t0)))
        val vm = SecurityViewModel(src, dispatcher, history)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf(setOf(doorRef, HistoryRef("zigbee2mqtt/hall", "occupancy"))), history.asked.map { it.toSet() })
        assertEquals(Since.At(t0), vm.state.value.sensors.first { it.id == "front" }.since)
        src.stateMap.value += "hall" to on("hall", true) // a state change is not a ref change
        assertEquals(1, history.asked.size)
    }

    @Test
    fun `refetch on reconnect and every ten minutes`() = runTest(dispatcher) {
        val src = mqttSource()
        val vm = SecurityViewModel(src, dispatcher, history, refresh = 10.minutes)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(1, history.asked.size)
        testScheduler.advanceTimeBy(10.minutes.inWholeMilliseconds + 1)
        assertEquals(2, history.asked.size)
        src.link.value = LinkState.Connecting
        src.link.value = LinkState.Connected(TransportMode.DEMO)
        assertEquals(3, history.asked.size)
    }

    @Test
    fun `a failed answer keeps the previous times and raises no event`() = runTest(dispatcher) {
        val src = mqttSource()
        history.answer = HistoryResult.Ok(mapOf(doorRef to LastChange.At(t0)))
        val vm = SecurityViewModel(src, dispatcher, history, refresh = 10.minutes)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        history.answer = HistoryResult.NotRecorded // e.g. an OikosHistory without the endpoint
        testScheduler.advanceTimeBy(10.minutes.inWholeMilliseconds + 1)
        assertEquals(2, history.asked.size)
        assertEquals(Since.At(t0), vm.state.value.sensors.first { it.id == "front" }.since)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `two sensors on one topic and key both get the time`() = runTest(dispatcher) {
        val src = FakeEntitySource().apply {
            entities.value = listOf(mqttSensor("a", "door", topic = "zigbee2mqtt/front"), mqttSensor("b", "door", topic = "zigbee2mqtt/front"))
                .associateBy { it.uniqueId }
        }
        history.answer = HistoryResult.Ok(mapOf(doorRef to LastChange.At(t0)))
        val vm = SecurityViewModel(src, dispatcher, history)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf(Since.At(t0), Since.At(t0)), vm.state.value.sensors.map { it.since })
    }

    @Test
    fun `nothing is asked without history`() = runTest(dispatcher) {
        history.available.value = false
        val vm = SecurityViewModel(mqttSource(), dispatcher, history)
        backgroundScope.launch { vm.state.collect {} }
        assertTrue(history.asked.isEmpty())
        assertTrue(vm.state.value.sensors.all { it.since == null })
    }
```

- [ ] **Step 5: Run to verify they fail**

Run: `./gradlew :feature:security:testDebugUnitTest --tests "*SecurityUiBuilderTest*" --tests "*SecurityViewModelTest*"`
Expected: compilation errors (`Since`, `changes`, constructor).

- [ ] **Step 6: Models** — in `SecurityModels.kt` add imports `java.time.Duration`, `java.time.Instant`; add the field to `SensorRow` (last, defaulted):

```kotlin
    /** How long the sensor has been in its state (last-change spec §4.4); null: unknown. */
    val since: Since? = null,
```

and after `SensorRow`:

```kotlin
/** [At]: the state began then. [Over]: recorded, but unchanged for longer than [Over.window]. */
sealed interface Since {
    data class At(val at: Instant) : Since
    data class Over(val window: Duration) : Since
}
```

- [ ] **Step 7: Builder** — in `SecurityUiBuilder`: import `com.gekkotron.oikos.history.LastChange` and `java.time.Instant`; change the signature to

```kotlin
    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        link: LinkState,
        changes: Map<String, LastChange> = emptyMap(),
    ): SecurityUi {
```

pass `since = since(changes[e.uniqueId], s?.changedAt)` as the last `SensorRow` argument, and add:

```kotlin
    /** Last-change spec §4.4: the server's time, unless the app saw a later flip itself. */
    private fun since(server: LastChange?, local: Instant?): Since? = when {
        server is LastChange.At -> Since.At(if (local != null && local > server.at) local else server.at)
        local != null -> Since.At(local)
        server is LastChange.OlderThan -> Since.Over(server.window)
        else -> null
    }
```

- [ ] **Step 8: View model** — replace the class header and `state` in `SecurityViewModel.kt` (new imports: `com.gekkotron.oikos.history.HistoryClient`, `com.gekkotron.oikos.history.HistoryRef`, `com.gekkotron.oikos.history.HistoryRefs`, `com.gekkotron.oikos.history.HistoryResult`, `com.gekkotron.oikos.history.LastChange`, `com.gekkotron.oikos.model.Kind`, `com.gekkotron.oikos.model.Placement`, `com.gekkotron.oikos.model.SensorGroup`, `com.gekkotron.oikos.transport.LinkState`, `kotlinx.coroutines.ExperimentalCoroutinesApi`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.flow.distinctUntilChanged`, `kotlinx.coroutines.flow.flatMapLatest`, `kotlinx.coroutines.flow.flow`, `kotlinx.coroutines.flow.flowOf`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.onStart`, `kotlin.time.Duration`, `kotlin.time.Duration.Companion.minutes`):

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SecurityViewModel internal constructor(
    private val source: EntitySource,
    dispatcher: CoroutineDispatcher,
    private val history: HistoryClient = HistoryClient.None,
    refresh: Duration = 10.minutes,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource, history: HistoryClient) : this(source, Dispatchers.Default, history)

    /** The change ref of every sensor the tab lists (same filter as [SecurityUiBuilder]). */
    private val refs: Flow<Map<String, HistoryRef>> = source.entities
        .map { all ->
            all.values
                .filter { it.partOf == null && it.kind == Kind.BINARY_SENSOR && Placement.onSecurity(it.kind, it.deviceClass, it.ui) && SensorGroup.of(it.deviceClass) != null }
                .mapNotNull { e -> HistoryRefs.changeRef(e)?.let { e.uniqueId to it } }
                .toMap()
        }
        .distinctUntilChanged()

    private val connected: Flow<Boolean> = source.link.map { it is LinkState.Connected }.distinctUntilChanged()

    /**
     * Last change by entity id (last-change spec §4.4): asked when the ref set changes, when the link comes back, and
     * every [refresh]; a failed answer emits nothing, so the previous times stay.
     */
    private val changes: Flow<Map<String, LastChange>> =
        combine(refs, connected, history.available, ::Triple)
            .flatMapLatest { (byId, isConnected, available) ->
                if (byId.isEmpty() || !isConnected || !available) {
                    flowOf()
                } else {
                    flow {
                        while (true) {
                            val r = history.lastChanges(byId.values.toList())
                            if (r is HistoryResult.Ok) emit(byId.mapNotNull { (id, ref) -> r.value[ref]?.let { id to it } }.toMap())
                            delay(refresh)
                        }
                    }
                }
            }
            // The tab doesn't wait on the first answer.
            .onStart { emit(emptyMap()) }

    // Like EnergyViewModel: one rebuild per collection turn, built off Main.
    val state: StateFlow<SecurityUi> = combine(source.entities, source.states(), source.areas, source.link, changes, SecurityUiBuilder::build)
        .conflate()
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUi())
```

Note `flowOf()` (empty) on a disconnect: the last emitted map stays in `combine`, so times do not vanish while reconnecting. If `combine(…, SecurityUiBuilder::build)` does not resolve with the defaulted 5th parameter, use a lambda `{ e, s, a, l, c -> SecurityUiBuilder.build(e, s, a, l, c) }`.

- [ ] **Step 9: Run the module**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: all pass (existing view-model tests use the `(source, dispatcher)` form, still valid).

- [ ] **Step 10: Commit**

```bash
git add feature/security/build.gradle.kts \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityModels.kt \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilder.kt \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityViewModel.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/FakeHistoryClient.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/TestEntities.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilderTest.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityViewModelTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(security): fetch last changes and merge them into each sensor row

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Formatting — `SinceFormat`, the sheet's date, strings, shared `rememberNow`

**Files:**
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/Now.kt` (moved)
- Delete: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/Now.kt`
- Modify: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertsScreen.kt`, `RecentAlertsSection.kt` (import)
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SinceFormat.kt`
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SinceText.kt`
- Modify: `feature/security/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SinceFormatTest.kt`, `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SinceDateTest.kt`

**Interfaces:**
- Consumes: `Since` (Task 6).
- Produces:
  - `com.gekkotron.oikos.designsystem.rememberNow(): State<Instant>`
  - `sealed interface SinceSpan { UnderAMinute; Minutes(n: Long); Hours(n: Long); Days(n: Long); Over(days: Long) }` and `SinceFormat.span(since: Since, now: Instant): SinceSpan`
  - `@Composable internal fun sinceShort(span: SinceSpan): String`, `@Composable internal fun sinceLong(span: SinceSpan): String`, `internal fun sinceDate(at: Instant, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String`

- [ ] **Step 1: Move `rememberNow`** — `git mv feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/Now.kt core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/Now.kt`, change its package line to `package com.gekkotron.oikos.designsystem`, and in `AlertsScreen.kt` and `RecentAlertsSection.kt` add `import com.gekkotron.oikos.designsystem.rememberNow` (they used it unqualified from the same package). Run `./gradlew :feature:alerts:testDebugUnitTest` — expected PASS.

- [ ] **Step 2: Failing tests** — create `SinceFormatTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SinceFormatTest {
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private fun ago(seconds: Long) = SinceFormat.span(Since.At(now.minusSeconds(seconds)), now)

    @Test
    fun `boundaries round down`() {
        assertEquals(SinceSpan.UnderAMinute, ago(59))
        assertEquals(SinceSpan.Minutes(1), ago(60))
        assertEquals(SinceSpan.Minutes(59), ago(3_599))
        assertEquals(SinceSpan.Hours(1), ago(3_600))
        assertEquals(SinceSpan.Hours(47), ago(48 * 3_600 - 1))
        assertEquals(SinceSpan.Days(2), ago(48 * 3_600))
        assertEquals(SinceSpan.Days(29), ago(29 * 86_400 + 5))
    }

    @Test
    fun `a time ahead of the phone clock reads as just now`() = assertEquals(SinceSpan.UnderAMinute, ago(-120))

    @Test
    fun `over the window`() = assertEquals(SinceSpan.Over(30), SinceFormat.span(Since.Over(Duration.ofDays(30)), now))
}
```

and `SinceDateTest.kt` (Robolectric: `DateFormat.getBestDateTimePattern` is Android):

```kotlin
package com.gekkotron.oikos.feature.security.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SinceDateTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-14T16:00:00Z") // Wednesday 18:00 in Paris

    @Test
    fun `today shows the time only`() =
        assertEquals("08:42", sinceDate(Instant.parse("2026-10-14T06:42:00Z"), now, paris, Locale.UK, is24h = true))

    @Test
    fun `this week shows the weekday`() {
        val s = sinceDate(Instant.parse("2026-10-13T06:42:00Z"), now, paris, Locale.UK, is24h = true)
        assertTrue(s.contains("Tue") && s.contains("08:42"), s)
    }

    @Test
    fun `older shows the date`() {
        val s = sinceDate(Instant.parse("2026-10-03T06:42:00Z"), now, paris, Locale.UK, is24h = true)
        assertTrue(s.contains("3") && s.contains("Oct") && s.contains("08:42"), s)
    }

    @Test
    fun `12-hour clocks get a 12-hour time`() {
        val s = sinceDate(Instant.parse("2026-10-14T12:42:00Z"), now, paris, Locale.US, is24h = false)
        assertTrue(s.contains("2:42") && s.uppercase().contains("PM"), s)
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew :feature:security:testDebugUnitTest --tests "*SinceFormatTest*" --tests "*SinceDateTest*"`
Expected: compilation errors.

- [ ] **Step 4: `SinceFormat.kt`**:

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import java.time.Duration
import java.time.Instant

/** How long a state has lasted, rounded down to one unit (last-change spec §4.5). */
sealed interface SinceSpan {
    data object UnderAMinute : SinceSpan
    data class Minutes(val n: Long) : SinceSpan
    data class Hours(val n: Long) : SinceSpan
    data class Days(val n: Long) : SinceSpan
    data class Over(val days: Long) : SinceSpan
}

object SinceFormat {
    fun span(since: Since, now: Instant): SinceSpan = when (since) {
        is Since.Over -> SinceSpan.Over(since.window.toDays())
        is Since.At -> {
            // A server clock ahead of the phone gives a negative span: read it as "just now".
            val s = Duration.between(since.at, now).seconds
            when {
                s < 60 -> SinceSpan.UnderAMinute
                s < 3_600 -> SinceSpan.Minutes(s / 60)
                s < 48 * 3_600 -> SinceSpan.Hours(s / 3_600)
                else -> SinceSpan.Days(s / 86_400)
            }
        }
    }
}
```

- [ ] **Step 5: Strings** — add to `feature/security/src/main/res/values/strings.xml`:

```xml
    <string name="security_state_since">%1$s · %2$s</string>
    <string name="security_since_short_now">&lt;1 min</string>
    <string name="security_since_short_minutes">%1$d min</string>
    <string name="security_since_short_hours">%1$d h</string>
    <string name="security_since_short_days">%1$d d</string>
    <string name="security_since_short_over">%1$d+ d</string>
    <string name="security_state_for">%1$s for %2$s</string>
    <string name="security_since_long_now">less than a minute</string>
    <plurals name="security_since_long_minutes"><item quantity="one">%1$d minute</item><item quantity="other">%1$d minutes</item></plurals>
    <plurals name="security_since_long_hours"><item quantity="one">%1$d hour</item><item quantity="other">%1$d hours</item></plurals>
    <plurals name="security_since_long_days"><item quantity="one">%1$d day</item><item quantity="other">%1$d days</item></plurals>
    <plurals name="security_since_long_over"><item quantity="one">more than %1$d day</item><item quantity="other">more than %1$d days</item></plurals>
    <string name="security_since_at">Since %1$s</string>
    <plurals name="security_no_change"><item quantity="one">No change in %1$d day</item><item quantity="other">No change in %1$d days</item></plurals>
```

and to `values-fr/strings.xml`:

```xml
    <string name="security_state_since">%1$s · %2$s</string>
    <string name="security_since_short_now">&lt;1 min</string>
    <string name="security_since_short_minutes">%1$d min</string>
    <string name="security_since_short_hours">%1$d h</string>
    <string name="security_since_short_days">%1$d j</string>
    <string name="security_since_short_over">%1$d+ j</string>
    <string name="security_state_for">%1$s depuis %2$s</string>
    <string name="security_since_long_now">moins d’une minute</string>
    <plurals name="security_since_long_minutes"><item quantity="one">%1$d minute</item><item quantity="other">%1$d minutes</item></plurals>
    <plurals name="security_since_long_hours"><item quantity="one">%1$d heure</item><item quantity="other">%1$d heures</item></plurals>
    <plurals name="security_since_long_days"><item quantity="one">%1$d jour</item><item quantity="other">%1$d jours</item></plurals>
    <plurals name="security_since_long_over"><item quantity="one">plus de %1$d jour</item><item quantity="other">plus de %1$d jours</item></plurals>
    <string name="security_since_at">Depuis %1$s</string>
    <plurals name="security_no_change"><item quantity="one">Aucun changement depuis %1$d jour</item><item quantity="other">Aucun changement depuis %1$d jours</item></plurals>
```

- [ ] **Step 6: `SinceText.kt`** (ui package):

```kotlin
package com.gekkotron.oikos.feature.security.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.SinceSpan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "2 h", "<1 min", "30+ d": the tile's compact form. */
@Composable
internal fun sinceShort(span: SinceSpan): String = when (span) {
    SinceSpan.UnderAMinute -> stringResource(R.string.security_since_short_now)
    is SinceSpan.Minutes -> stringResource(R.string.security_since_short_minutes, span.n.toInt())
    is SinceSpan.Hours -> stringResource(R.string.security_since_short_hours, span.n.toInt())
    is SinceSpan.Days -> stringResource(R.string.security_since_short_days, span.n.toInt())
    is SinceSpan.Over -> stringResource(R.string.security_since_short_over, span.days.toInt())
}

/** "2 hours", "more than 30 days": what TalkBack reads. */
@Composable
internal fun sinceLong(span: SinceSpan): String = when (span) {
    SinceSpan.UnderAMinute -> stringResource(R.string.security_since_long_now)
    is SinceSpan.Minutes -> pluralStringResource(R.plurals.security_since_long_minutes, span.n.toInt(), span.n.toInt())
    is SinceSpan.Hours -> pluralStringResource(R.plurals.security_since_long_hours, span.n.toInt(), span.n.toInt())
    is SinceSpan.Days -> pluralStringResource(R.plurals.security_since_long_days, span.n.toInt(), span.n.toInt())
    is SinceSpan.Over -> pluralStringResource(R.plurals.security_since_long_over, span.days.toInt(), span.days.toInt())
}

/** "08:42" today, "Tue 08:42" within six days, "3 Oct, 08:42" before (locale patterns, last-change spec §4.5). */
internal fun sinceDate(at: Instant, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): String {
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val time = if (is24h) "Hm" else "hm"
    val skeleton = when {
        day == today -> time
        !day.isBefore(today.minusDays(6)) -> "EEE$time"
        else -> "dMMM$time"
    }
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(at.atZone(zone))
}
```

- [ ] **Step 7: Run to verify they pass**

Run: `./gradlew :feature:security:testDebugUnitTest --tests "*SinceFormatTest*" --tests "*SinceDateTest*" :feature:alerts:testDebugUnitTest`
Expected: PASS. If an ICU pattern from `getBestDateTimePattern` holds a letter `DateTimeFormatter` rejects (e.g. `B`), the test shows it: map that skeleton to an explicit fallback pattern for that case and add the case to the test.

- [ ] **Step 8: Commit**

```bash
git add core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/Now.kt \
        feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/Now.kt \
        feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertsScreen.kt \
        feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/RecentAlertsSection.kt \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SinceFormat.kt \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SinceText.kt \
        feature/security/src/main/res/values/strings.xml feature/security/src/main/res/values-fr/strings.xml \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SinceFormatTest.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SinceDateTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(security): duration and date formatting for last change; share rememberNow

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: UI — tile line, sheet line, screen clock, accessibility, screenshots

**Files:**
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SensorTile.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SensorSheet.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreen.kt`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreenTest.kt` (append), Create: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SinceScreenshotTest.kt`

**Interfaces:**
- Consumes: `SensorRow.since` (Task 6); `SinceFormat`, `sinceShort`, `sinceLong`, `sinceDate`, `rememberNow` (Task 7).
- Produces: stateless `SecurityScreen(state, …, now: Instant = rememberNow().value)` — tests pass a fixed `now`; `SensorTile(row, now, onClick, modifier)`; `SensorSheet(row, now, onHide, onDismiss, onCustomize)`.

- [ ] **Step 1: Failing Compose tests** — in `SecurityScreenTest`, give `show` a `now` parameter and pass it through:

```kotlin
    private val now = java.time.Instant.parse("2026-10-14T10:00:00Z")
```

change `private fun show(state: SecurityUi) = compose.setContent {` to `private fun show(state: SecurityUi, at: java.time.Instant = now) = compose.setContent {` and add `now = at,` to the `SecurityScreen(` call. Then append (imports `com.gekkotron.oikos.history.LastChange`, `androidx.compose.ui.test.assertContentDescriptionEquals`, `java.time.Duration`):

```kotlin
    private fun withSince(server: LastChange, stale: Boolean = false, available: Boolean = true) = SecurityUiBuilder.build(
        mapOf("door" to sensor("door", "door", name = "Front door")),
        mapOf("door" to on("door", false, available = available, stale = stale)),
        emptyList(), LinkState.Connecting, mapOf("door" to server),
    )

    @Test
    fun `the tile shows how long, and TalkBack reads it in words`() {
        show(withSince(LastChange.At(now.minusSeconds(2 * 3_600 + 300))))
        compose.onNodeWithText("Closed · 2 h", useUnmergedTree = true).assertContentDescriptionEquals("Closed for 2 hours")
    }

    @Test
    fun `over the window reads 30+ d`() {
        show(withSince(LastChange.OlderThan(Duration.ofDays(30))))
        compose.onNodeWithText("Closed · 30+ d", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no duration on a stale or unavailable tile, nor without history`() {
        show(withSince(LastChange.At(now.minusSeconds(600)), stale = true))
        compose.onNodeWithText("Closed", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Closed · 10 min", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an unavailable tile shows no duration`() {
        show(withSince(LastChange.At(now.minusSeconds(600)), available = false))
        compose.onNodeWithText("Closed · 10 min", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a tile without since looks as before`() {
        show(ui(listOf(sensor("door", "door", name = "Front door")), listOf(on("door", false))))
        compose.onNodeWithText("Closed", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the sheet gives the exact time`() {
        show(withSince(LastChange.At(java.time.Instant.parse("2026-10-14T06:42:00Z"))))
        compose.onNodeWithTag("sensor-door").performClick()
        compose.onNode(hasText("Since ", substring = true)).assertExists()
    }

    @Test
    fun `the sheet says no change in 30 days`() {
        show(withSince(LastChange.OlderThan(Duration.ofDays(30))))
        compose.onNodeWithTag("sensor-door").performClick()
        compose.onNodeWithText("No change in 30 days").assertExists()
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :feature:security:testDebugUnitTest --tests "*SecurityScreenTest*"`
Expected: compilation error (`now` parameter) then, once added, FAIL on the new assertions.

- [ ] **Step 3: Tile** — in `SensorTile.kt`: imports `androidx.compose.ui.semantics.contentDescription`, `com.gekkotron.oikos.feature.security.presentation.SinceFormat`, `com.gekkotron.oikos.feature.security.presentation.SinceSpan`, `java.time.Instant`. Change the signature to `internal fun SensorTile(row: SensorRow, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier)` and replace the trailing state `Text(stateWord(row), …)` with:

```kotlin
            val word = stateWord(row)
            val span = sinceSpan(row, now)
            if (span == null) {
                Text(word, style = OikosTheme.type.body, color = accent ?: OikosTheme.colors.content, maxLines = 1)
            } else {
                // TalkBack reads "Closed for 2 hours", not "Closed dot 2 h".
                val spoken = stringResource(R.string.security_state_for, word, sinceLong(span))
                Text(
                    stringResource(R.string.security_state_since, word, sinceShort(span)),
                    style = OikosTheme.type.body,
                    color = accent ?: OikosTheme.colors.content,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }
```

and add below `stateWord`:

```kotlin
/** Null when there is nothing trustworthy to show: no time, unavailable, cached (stale) or no state (last-change spec §4.5). */
internal fun sinceSpan(row: SensorRow, now: Instant): SinceSpan? {
    val since = row.since ?: return null
    if (!row.available || row.stale || row.active == null) return null
    return SinceFormat.span(since, now)
}
```

- [ ] **Step 4: Sheet** — in `SensorSheet.kt`: imports `android.text.format.DateFormat`, `androidx.compose.ui.platform.LocalConfiguration`, `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.res.pluralStringResource`, `com.gekkotron.oikos.feature.security.presentation.Since`, `java.time.Instant`, `java.time.ZoneId`. Signature: `internal fun SensorSheet(row: SensorRow, now: Instant, onHide: () -> Unit, onDismiss: () -> Unit, onCustomize: (() -> Unit)? = null)`. After the "room · state" `Text`, add:

```kotlin
            if (sinceSpan(row, now) != null) {
                val context = LocalContext.current
                val locale = LocalConfiguration.current.locales[0]
                val line = when (val since = row.since) {
                    is Since.At -> stringResource(
                        R.string.security_since_at,
                        sinceDate(since.at, now, ZoneId.systemDefault(), locale, DateFormat.is24HourFormat(context)),
                    )
                    is Since.Over -> since.window.toDays().toInt().let { pluralStringResource(R.plurals.security_no_change, it, it) }
                    null -> null
                }
                if (line != null) Text(line, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            }
```

- [ ] **Step 5: Screen** — in `SecurityScreen.kt`: import `com.gekkotron.oikos.designsystem.rememberNow` and `java.time.Instant`; add the last parameter of the stateless `SecurityScreen`:

```kotlin
    /** One minute-ticking clock for every tile and the sheet; tests pass a fixed one. */
    now: Instant = rememberNow().value,
```

pass it on: `SensorTile(row, now, onClick = { sheetId = row.id }, Modifier.weight(1f).fillMaxHeight())` and `SensorSheet(sheetRow, now, onHide = …, onDismiss = …, onCustomize = …)`. The stateful overload (the one with `viewModel`) does not pass `now`, so it gets the ticking default.

- [ ] **Step 6: Run the module tests**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: all pass.

- [ ] **Step 7: Screenshot check (EN and FR, half-width fit)** — create `SinceScreenshotTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.security.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.presentation.SecurityUiBuilder
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertTrue

/** Last-change spec §5: the Openings section with durations, written to build/screenshots for a look. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SinceScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-14T10:00:00Z")

    private fun render(name: String) {
        val state = SecurityUiBuilder.build(
            listOf(
                sensor("front", "door", name = "Front door", area = "entrance"),
                sensor("garage", "garage_door", name = "Garage door", area = "garage"),
                sensor("bath", "window", name = "Bathroom window", area = "bathroom"),
                sensor("cellar", "door", name = "Cellar door", area = "cellar"),
            ).associateBy { it.uniqueId },
            listOf(on("front", false), on("garage", true), on("bath", false), on("cellar", false)).associateBy { it.uniqueId },
            listOf(Area("entrance", "Entrance"), Area("garage", "Garage"), Area("bathroom", "Bathroom"), Area("cellar", "Cellar")),
            LinkState.Connecting,
            mapOf(
                "front" to LastChange.At(now.minusSeconds(2 * 3_600)),
                "garage" to LastChange.At(now.minusSeconds(40 * 60)),
                "bath" to LastChange.At(now.minusSeconds(5 * 86_400)),
                "cellar" to LastChange.OlderThan(Duration.ofDays(30)),
            ),
        )
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { OikosBackground { SecurityScreen(state, now = now) } } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots/$name.png").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 7) for (y in 0 until bitmap.height step 7) seen += bitmap.getPixel(x, y)
        assertTrue(seen.size > 1, "blank render")
    }

    @Test
    @Config(qualifiers = "en-w411dp-h914dp-xxhdpi")
    fun english() {
        render("security-since-en")
        compose.onNodeWithText("Closed · 30+ d", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h914dp-xxhdpi")
    fun french() {
        render("security-since-fr")
        compose.onNodeWithText("Fermé · 30+ j", useUnmergedTree = true).assertExists()
    }
}
```

If `OikosBackground` has a different signature, wrap in whatever `WaterHeaterScreenshotTest` uses; if the FR closed word is not "Fermé", use the value of `security_closed` in `values-fr/strings.xml`.

Run: `./gradlew :feature:security:testDebugUnitTest --tests "*SinceScreenshotTest*"`
Expected: PASS; then open `feature/security/build/screenshots/security-since-en.png` and `-fr.png` with the Read tool and check that every tile shows the full "state · duration" (only room names may be cut).

- [ ] **Step 8: Commit**

```bash
git add feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SensorTile.kt \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SensorSheet.kt \
        feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreen.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreenTest.kt \
        feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SinceScreenshotTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(security): show time since the last change on tiles and in the sheet

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Contract doc, full check, then (with the user) push, deploy and device check

**Files:**
- Modify: `docs/superpowers/specs/2026-10-08-oikos-history-design.md` (§3.3 pointer)

- [ ] **Step 1: Contract pointer** — in the history spec §3.3, after the `/v1/today` block, add:

```markdown
**`GET /v1/last_change?topic=&key=[&topic=&key=…]`** — added 2026-10-14; specified in `2026-10-13-oikos-security-last-change-design.md` Appendix A (1–50 pairs, last flip per series within 30 days, items in request order).
```

- [ ] **Step 2: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. (`OnboardingViewModelTest` is known to be flaky under a full `check`; if it alone fails, re-run it alone and report both runs.)

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-10-08-oikos-history-design.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "docs(history): point §3.3 at /v1/last_change

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Ask the user**, then, once they say yes:
  1. Push OikosHistory `main` (`git push`, remote `github.com-gekkotron`).
  2. The user (or an approved SSH) runs `cd ~/OikosHistory && ./update.sh` on the Geekom; check `curl "http://192.168.1.91:8090/v1/last_change?topic=zigbee2mqtt/<a real door>&key=contact"` returns `200` with a `changed_at`.
  3. Merge `feature/security-last-change` into `master` per superpowers:finishing-a-development-branch, push, watch CI.

- [ ] **Step 5: Device check (user)** — cold-start the app, open Security: every door/window shows a duration that matches the History screen for that sensor; open a door → its tile reads `Open · <1 min` immediately; away from home (gateway) the durations still appear, or the tiles stay plain without any error.
