# Localized Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alerts read in the phone's language: producers send a sentence key + arguments (`loc`), OikosPush forwards it, and the Oikos app composes the sentence from its own EN/FR strings at display time, falling back to the server's `title`/`body`.

**Architecture:** Two repos. OikosPush (`/Users/julienhuguel/Documents/SourcesPerso/OikosPush`, Python) validates and carries an optional `loc` dict through `normalize.Alert`, the FCM data map (compact JSON string) and the history buffer; its Frigate bridge adds `loc` with key `frigate.object`. The Oikos app (`/Users/julienhuguel/Documents/SourcesPerso/Oikos`, Kotlin) parses `loc` into `AlertLoc`, stores it (Room v2), and `AlertText.body()` renders it from string resources wherever an alert's body is shown.

**Tech Stack:** Python 3.12 + pytest (OikosPush); Kotlin, Room 2.8 (KSP, exported schemas), kotlinx.serialization, Jetpack Compose, Robolectric (Oikos).

**Spec:** `docs/superpowers/specs/2026-10-09-oikos-localized-alerts-design.md` (in the Oikos repo).

## Global Constraints

- `loc.key`: regex `^[a-z0-9_.]{1,64}$`. `loc.args`: JSON object, at most 8 entries, keys `^[a-z0-9_]{1,32}$`, values strings of at most 200 characters. Anything else → `loc` dropped, alert still delivered.
- `title` stays required, `body` keeps today's meaning; the bridge's English `title`/`body` are unchanged.
- FCM data: `loc` = `json.dumps(loc, ensure_ascii=False, separators=(",", ":"))`, present only when `loc` is not None. History entry: `loc` as a JSON object.
- The 20 labels: `person, car, truck, bus, motorcycle, bicycle, dog, cat, bird, horse, package, face, license_plate, amazon, dhl, dpd, fedex, gls, ups, usps`.
- Unknown key, unknown label or missing `label` → the stored `body`, never a partial sentence.
- Every new app string in `values/strings.xml` **and** `values-fr/strings.xml`.
- Commit identity on every commit in both repos: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`; messages end with
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN
  ```
- Branches: `feat/alert-loc` in OikosPush (from `main`), `feat/localized-alerts` in Oikos (from `master`). Create each at its first task.
- OikosPush tests: `cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush && venv/bin/pytest -q`. Oikos module tests: `./gradlew --no-daemon :core:alerts:testDebugUnitTest` (etc.); full gate `./gradlew --no-daemon check`.

## Review Focus

1. **A producer sends `loc` as a JSON *string* instead of an object** (easy mistake for MQTT scripts). It must be dropped, not crash the service, and the alert still delivered. Pinned by Task 1, `test_loc_wrong_shapes_are_dropped_but_the_alert_is_delivered` (string case).
2. **An oversized alert near the FCM 4 KB limit.** `loc` must be dropped before the fallback `body` is truncated to nothing. Pinned by Task 1, `test_fit_drops_loc_before_emptying_the_body`.
3. **Catch-up from the retained history (phone was offline).** The history entry has `loc` as a JSON object, which `AlertParser.fromHistory` turns into text; it must still parse. Pinned by Task 3, `history entries keep loc`.
4. **A Frigate label in another case (`Car`).** Falls back to the English body, no crash. Pinned by Task 4, `unknown or differently-cased labels fall back to the body`.
5. **Upgrading a phone that already has alerts (Room v1 → v2).** Old rows keep their text and get `loc = null`. Pinned by Task 3, `migration 1 to 2 keeps rows and adds a null loc`.

---

### Task 1: OikosPush — validate and carry `loc`

**Repo:** `/Users/julienhuguel/Documents/SourcesPerso/OikosPush`

**Files:**
- Modify: `normalize.py` (Alert field, `_loc` validator, `_normalize_v1`, `to_fcm_data`, `_fit`)
- Modify: `history.py` (`to_history_entry`)
- Modify: `oikos_push_service.py` (warning when `loc` was dropped)
- Test: `tests/test_normalize.py`, `tests/test_history.py`, `tests/test_service.py`

**Interfaces:**
- Produces: `Alert.loc: dict | None = None` (`{"key": str, "args": dict[str, str]}`); FCM data key `"loc"` (compact JSON string); history entry key `"loc"` (object).

- [ ] **Step 0: Branch**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush && git switch -c feat/alert-loc main
```

- [ ] **Step 1: Failing tests in `tests/test_normalize.py`** (append)

```python
# ---------- loc (localized alerts spec §2-§3) ----------

LOC = {"key": "frigate.object", "args": {"label": "car", "zone": "Driveway"}}


def v1(**over):
    return {"v": 1, "id": "a1", "channel": "camera", "title": "Driveway", "body": "Car in Driveway", **over}


def test_loc_is_kept_and_sent_as_compact_json():
    alert = normalize(TOPIC, v1(loc=LOC), now=NOW)
    assert alert.loc == LOC
    assert to_fcm_data(alert)["loc"] == '{"key":"frigate.object","args":{"label":"car","zone":"Driveway"}}'


def test_loc_args_may_be_missing():
    assert normalize(TOPIC, v1(loc={"key": "x.y"}), now=NOW).loc == {"key": "x.y", "args": {}}


def test_no_loc_means_no_fcm_key():
    alert = normalize(TOPIC, v1(), now=NOW)
    assert alert.loc is None
    assert "loc" not in to_fcm_data(alert)


@pytest.mark.parametrize("loc", [
    '{"key":"frigate.object"}',                       # a JSON string, not an object
    {"args": {"label": "car"}},                       # no key
    {"key": "Frigate.Object"},                        # upper case
    {"key": "x" * 65},                                # key too long
    {"key": "k", "args": ["car"]},                    # args not an object
    {"key": "k", "args": {"label": 3}},               # non-string value
    {"key": "k", "args": {"Label": "car"}},           # bad arg name
    {"key": "k", "args": {"v": "x" * 201}},           # value too long
    {"key": "k", "args": {f"a{i}": "x" for i in range(9)}},  # too many args
])
def test_loc_wrong_shapes_are_dropped_but_the_alert_is_delivered(loc):
    alert = normalize(TOPIC, v1(loc=loc), now=NOW)
    assert isinstance(alert, Alert)
    assert alert.loc is None
    assert alert.body == "Car in Driveway"


def test_legacy_payloads_never_carry_loc():
    alert = normalize("oikos-push/notification/nvr", {"title": "Cam", "body": "x", "loc": LOC}, now=NOW)
    assert alert.loc is None


def test_fit_drops_loc_before_emptying_the_body():
    alert = normalize(TOPIC, v1(body="b" * 3000, loc={"key": "k", "args": {f"a{i}": "x" * 200 for i in range(8)}}), now=NOW)
    data = to_fcm_data(alert)
    assert fcm_size(data) <= FCM_MAX_BYTES
    assert "loc" not in data
    assert data["body"].startswith("b" * 2000)
```

- [ ] **Step 2: Run, expect FAIL**

Run: `venv/bin/pytest -q tests/test_normalize.py -k loc`
Expected: FAIL (`unexpected keyword` / `AttributeError: 'Alert' object has no attribute 'loc'`).

- [ ] **Step 3: Implement in `normalize.py`**

Add `import re` to the imports. After `ELLIPSIS`:

```python
LOC_KEY = re.compile(r"[a-z0-9_.]{1,64}")
LOC_ARG = re.compile(r"[a-z0-9_]{1,32}")
LOC_MAX_ARGS = 8
LOC_MAX_VALUE = 200
```

Add the field to `Alert` (last, after `source`):

```python
    loc: dict | None = None
```

Add, next to the other helpers:

```python
def _loc(raw: Any) -> dict | None:
    """A valid `{"key", "args"}` sentence hint (localized alerts spec §2), else None: a bad hint never costs the alert."""
    if not isinstance(raw, dict):
        return None
    key = raw.get("key")
    if not isinstance(key, str) or not LOC_KEY.fullmatch(key):
        return None
    args = raw.get("args", {})
    if not isinstance(args, dict) or len(args) > LOC_MAX_ARGS:
        return None
    for name, value in args.items():
        if not isinstance(name, str) or not LOC_ARG.fullmatch(name):
            return None
        if not isinstance(value, str) or len(value) > LOC_MAX_VALUE:
            return None
    return {"key": key, "args": dict(args)}
```

In `_normalize_v1`'s final `Alert(...)`, add `loc=_loc(payload.get("loc")),`.

In `to_fcm_data`, before `if alert.cancel:`:

```python
    if alert.loc is not None:
        data["loc"] = json.dumps(alert.loc, ensure_ascii=False, separators=(",", ":"))
```

In `_fit`, drop `loc` first (the app falls back to `body`, so it is the cheapest loss). Replace the body of `_fit` after the first early return with:

```python
    out.pop("loc", None)
    if _size(out) <= FCM_MAX_BYTES:
        return out
    for key in ("body", "title"):
        _truncate_field(out, key)
        if _size(out) <= FCM_MAX_BYTES:
            return out
    out["actions"] = "[]"
    for key in ("entity_id", "video_url", "image_url"):
        if _size(out) <= FCM_MAX_BYTES:
            break
        out.pop(key, None)
    return out
```

and update its docstring's first sentence to: "Drops `loc`, then shrinks `body`, then `title`, …".

- [ ] **Step 4: Run, expect PASS**

Run: `venv/bin/pytest -q tests/test_normalize.py`
Expected: all pass (including the pre-existing tests).

- [ ] **Step 5: History entry — failing test in `tests/test_history.py`** (append; read the file's imports first and reuse them)

```python
def test_history_entry_keeps_loc_as_an_object():
    from normalize import Alert
    from history import to_history_entry
    loc = {"key": "frigate.object", "args": {"label": "car"}}
    assert to_history_entry(Alert(id="a1", title="T", loc=loc))["loc"] == loc
    assert "loc" not in to_history_entry(Alert(id="a2", title="T"))
```

Run: `venv/bin/pytest -q tests/test_history.py -k loc` → FAIL (KeyError).

In `history.py` `to_history_entry`, before `if alert.cancel:`:

```python
    if alert.loc is not None:
        entry["loc"] = alert.loc
```

Run again → PASS.

- [ ] **Step 6: Service warning — failing test in `tests/test_service.py`** (append; `svc`, `register`, `notify`, `v1` and the `"test-service"` logger name already exist in that file)

```python
def test_a_dropped_loc_is_logged_and_the_alert_still_sent(svc, caplog):
    register(svc, "inst-a", "tokA")
    with caplog.at_level(logging.WARNING, logger="test-service"):
        notify(svc, v1(loc="frigate.object"))
    assert "Ignoring invalid loc on alert a1" in caplog.text
    data = svc.sender.calls[0]["data"]
    assert data["id"] == "a1" and "loc" not in data


def test_a_valid_loc_reaches_fcm(svc):
    register(svc, "inst-a", "tokA")
    notify(svc, v1(loc={"key": "frigate.object", "args": {"label": "car"}}))
    assert svc.sender.calls[0]["data"]["loc"] == '{"key":"frigate.object","args":{"label":"car"}}'
```

If `v1()` in `test_service.py` does not accept arbitrary keyword overrides, build the dict inline from its body plus `"loc": …`.

Run: `venv/bin/pytest -q tests/test_service.py -k dropped_loc` → FAIL (no log line).

In `oikos_push_service.py`, after the `Rejected` check and before `self.dispatch(alert)`:

```python
        if alert.loc is None and isinstance(parsed, dict) and "loc" in parsed and parsed.get("v") == 1:
            self.logger.warning("Ignoring invalid loc on alert %s", alert.id)
```

Run again → PASS.

- [ ] **Step 7: Whole suite + commit**

Run: `venv/bin/pytest -q` → all pass.

```bash
git add normalize.py history.py oikos_push_service.py tests/test_normalize.py tests/test_history.py tests/test_service.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: carry an optional loc sentence hint to the app

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 2: Frigate bridge sends `loc`

**Repo:** `/Users/julienhuguel/Documents/SourcesPerso/OikosPush`

**Files:**
- Modify: `bridges/frigate/frigate_bridge.py` (`Bridge.alert`)
- Modify: `bridges/frigate/README.md` (one line on `loc`)
- Test: `tests/test_frigate_bridge.py`

**Interfaces:**
- Consumes: `normalize._loc` rules (Task 1).
- Produces: payload key `loc = {"key": "frigate.object", "args": {"label": <raw>, ["zone": <pretty>], ["who": <sub_label>]}}`.

- [ ] **Step 1: Failing tests** — update the exact-dict expectation in `test_new_event_sends_an_audible_alert_with_the_snapshot` to include
  `"loc": {"key": "frigate.object", "args": {"label": "person"}},`
  and append:

```python
def test_loc_names_the_raw_label_the_zone_and_the_person():
    bridge, _ = make()
    a = bridge.handle(event(label="car", entered_zones=["drive_way"], sub_label=["Julien", 0.9]))
    assert a["loc"] == {"key": "frigate.object", "args": {"label": "car", "zone": "Drive Way", "who": "Julien"}}
    assert a["body"] == "Julien (car) in Drive Way"


def test_no_label_means_no_loc():
    bridge, _ = make()
    a = bridge.handle(event(label=None))
    assert "loc" not in a
    assert a["body"] == "Object detected"


def test_loc_passes_oikos_push_normalize():
    from normalize import normalize
    bridge, _ = make()
    a = normalize("oikos-push/notification/frigate", bridge.handle(event(entered_zones=["garden"])))
    assert a.loc == {"key": "frigate.object", "args": {"label": "person", "zone": "Garden"}}
```

Check `test_zone_entered_on_update_triggers_then_body_names_it`, `test_sub_label_names_the_person` and `test_event_qualifying_only_at_end…`: if they compare whole dicts, add the matching `loc`; if they compare single fields, leave them.

- [ ] **Step 2: Run, expect FAIL**

Run: `venv/bin/pytest -q tests/test_frigate_bridge.py` → FAIL (`loc` missing).

- [ ] **Step 3: Implement** in `Bridge.alert`, after `payload` is built and before `if with_clip:`:

```python
        raw_label = event.get("label")
        if isinstance(raw_label, str) and raw_label.strip():
            args = {"label": raw_label.strip()}
            if zones:
                args["zone"] = _pretty(zones[0])
            if who:
                args["who"] = who
            # The app writes the sentence in the phone's language; title/body stay the English fallback.
            payload["loc"] = {"key": "frigate.object", "args": args}
```

Add to `bridges/frigate/README.md`, in the payload/behaviour section: "Each alert also carries `loc` (`frigate.object` with `label`, `zone`, `who`) so the Oikos app can show it in the phone's language; `title`/`body` stay the English fallback."

- [ ] **Step 4: Run, expect PASS**

Run: `venv/bin/pytest -q` → all pass.

- [ ] **Step 5: Commit**

```bash
git add bridges/frigate/frigate_bridge.py bridges/frigate/README.md tests/test_frigate_bridge.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(frigate): send a frigate.object loc for localized alerts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 3: App — `AlertLoc`, parsing and Room v2

**Repo:** `/Users/julienhuguel/Documents/SourcesPerso/Oikos`

**Files:**
- Modify: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/Alert.kt` (AlertLoc, field)
- Modify: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/AlertParser.kt`
- Modify: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/db/AlertEntity.kt`, `db/AlertDatabase.kt`, `db/Mapping.kt`
- Modify: `core/alerts/build.gradle.kts` (room-testing, schemas as test assets)
- Modify: `gradle/libs.versions.toml` (room-testing entry)
- Create (generated by KSP on build): `core/alerts/schemas/com.gekkotron.oikos.alerts.db.AlertDatabase/2.json`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/AlertParserTest.kt`, `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/db/AlertMigrationTest.kt`, `db/AlertDaoTest.kt`

**Interfaces:**
- Produces: `data class AlertLoc(val key: String, val args: Map<String, String>)`; `Alert.loc: AlertLoc? = null` (last constructor parameter); `AlertParser.parseLoc(raw: String?): AlertLoc?` (internal); `AlertEntity.locJson: String?`; `AlertLoc.toJson(): String` (internal, Mapping.kt).

- [ ] **Step 0: Branch**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/Oikos && git switch -c feat/localized-alerts master
```

- [ ] **Step 1: Failing parser tests** (append to `AlertParserTest`, reusing its helpers for a minimal valid data map; if it has none, use the literal map below)

```kotlin
    private val base = mapOf("v" to "1", "id" to "a1", "channel" to "camera", "title" to "Driveway", "body" to "Car in Driveway")

    @Test
    fun `loc is parsed from its compact JSON`() {
        val alert = AlertParser.fromData(base + ("loc" to """{"key":"frigate.object","args":{"label":"car","zone":"Driveway"}}"""))!!
        assertEquals(AlertLoc("frigate.object", mapOf("label" to "car", "zone" to "Driveway")), alert.loc)
    }

    @Test
    fun `a malformed loc is ignored and the alert kept`() {
        for (raw in listOf("not json", "[]", """{"args":{}}""", """{"key":3}""", """{"key":"k","args":{"label":3}}""", """{"key":"k","args":[]}""")) {
            val alert = AlertParser.fromData(base + ("loc" to raw))
            assertEquals("Car in Driveway", alert?.body, raw)
            assertNull(alert?.loc, raw)
        }
        assertNull(AlertParser.fromData(base)!!.loc)
    }

    @Test
    fun `history entries keep loc`() {
        val json = """[{"v":1,"id":"h1","channel":"camera","title":"Driveway","body":"Car","sent_at":1700000000,
            "loc":{"key":"frigate.object","args":{"label":"car"}}}]"""
        assertEquals(AlertLoc("frigate.object", mapOf("label" to "car")), AlertParser.parseHistory(json)!!.single().loc)
    }
```

Run: `./gradlew --no-daemon :core:alerts:testDebugUnitTest --tests '*AlertParserTest'` → FAIL (`AlertLoc` unresolved).

- [ ] **Step 2: Model + parser**

In `Alert.kt`, above `data class Alert`:

```kotlin
/** A sentence the app writes in its own language (localized alerts spec §2): a known [key] and its arguments. */
data class AlertLoc(val key: String, val args: Map<String, String>)
```

Add as the **last** constructor parameter of `Alert` (after `receivedAt`), so positional callers keep compiling:

```kotlin
    /** Localized-sentence hint; null for older producers, unknown shapes and every alert stored before Room v2. */
    val loc: AlertLoc? = null,
```

In `AlertParser.fromData`, add `loc = parseLoc(data["loc"]),` to the `Alert(...)` call, and add:

```kotlin
    /** `{"key": "...", "args": {"name": "value"}}` as text. Anything else means no hint, not a dropped alert. */
    internal fun parseLoc(raw: String?): AlertLoc? {
        if (raw.isNullOrBlank()) return null
        val obj = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val key = (obj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
        val rawArgs = obj["args"] ?: JsonObject(emptyMap())
        val args = (rawArgs as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
            ?: return null
        return AlertLoc(key, args)
    }
```

(`fromHistory` already turns a nested object into its JSON text, so history needs no change.)

Run the parser tests → PASS.

- [ ] **Step 3: Room — failing migration and DAO tests**

`gradle/libs.versions.toml`, next to the other room entries:

```toml
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
```

`core/alerts/build.gradle.kts`: inside `android { … }` add

```kotlin
    // Exported schemas, so MigrationTestHelper can build the v1 database (localized alerts spec §5.1).
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
```

and in `dependencies` add `testImplementation(libs.androidx.room.testing)`.

Create `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/db/AlertMigrationTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AlertDatabase::class.java)

    @Test
    fun `migration 1 to 2 keeps rows and adds a null loc`() {
        helper.createDatabase("migrate.db", 1).use { db ->
            db.execSQL(
                "INSERT INTO alerts (id, channel, severity, title, body, imageUrl, videoUrl, entityId, actionsJson, source, silent, resolved, sentAt, receivedAt) " +
                    "VALUES ('a1', 'camera', 'info', 'Driveway', 'Car in Driveway', NULL, NULL, NULL, '[]', 'frigate', 0, 0, 1000, 1000)",
            )
        }
        helper.runMigrationsAndValidate("migrate.db", 2, true).use { db ->
            db.query("SELECT body, locJson FROM alerts WHERE id = 'a1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Car in Driveway", c.getString(0))
                assertNull(c.getString(1))
            }
        }
    }
}
```

In `AlertDaoTest`, add `locJson = null` to its `entity(...)` helper call of `AlertEntity(...)` and append:

```kotlin
    @Test
    fun `loc survives a round trip through the database`() = runBlocking {
        dao.insert(entity("a").copy(locJson = """{"key":"frigate.object","args":{"label":"car"}}"""))
        assertEquals("""{"key":"frigate.object","args":{"label":"car"}}""", dao.byId("a")?.locJson)
    }
```

(Use the DAO's real single-row getter name — the `@Query("SELECT * FROM alerts WHERE id = :id")` method in `AlertDao.kt`.)

Run: `./gradlew --no-daemon :core:alerts:testDebugUnitTest --tests '*AlertMigrationTest' --tests '*AlertDaoTest'` → FAIL (no `locJson`, no schema 2).

- [ ] **Step 4: Entity, database, mapping**

`AlertEntity`: add the last column

```kotlin
    /** [com.gekkotron.oikos.alerts.AlertLoc] as JSON; added in version 2 (localized alerts spec §5.1). */
    val locJson: String? = null,
```

`AlertDatabase`:

```kotlin
/** Version 2 adds `alerts.locJson`; schemas exported to `core/alerts/schemas` (spec §6.1, localized alerts §5.1). */
@Database(
    entities = [AlertEntity::class, MetaEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
```

(import `androidx.room.AutoMigration`).

`Mapping.kt`: in `toEntity()` add `locJson = loc?.toJson(),`; in `toAlert()` add `loc = AlertParser.parseLoc(locJson),`; and add

```kotlin
internal fun AlertLoc.toJson(): String = buildJsonObject {
    put("key", key)
    put("args", buildJsonObject { args.forEach { (name, value) -> put(name, value) } })
}.toString()
```

(import `com.gekkotron.oikos.alerts.AlertLoc`).

Build once so KSP writes `schemas/…/2.json`: `./gradlew --no-daemon :core:alerts:kspDebugKotlin`. Check the file exists and contains `locJson`.

- [ ] **Step 5: Run, expect PASS**

Run: `./gradlew --no-daemon :core:alerts:testDebugUnitTest` → all pass.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml core/alerts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): parse and store the loc sentence hint (Room v2)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 4: App — `AlertText` and the EN/FR strings

**Files:**
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/text/AlertText.kt`
- Create: `core/alerts/src/main/res/values/strings.xml`, `core/alerts/src/main/res/values-fr/strings.xml`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/text/AlertTextTest.kt`

**Interfaces:**
- Consumes: `Alert.loc`, `AlertLoc` (Task 3).
- Produces: `object AlertText { fun body(alert: Alert, resources: Resources): String }`; `internal val AlertText.FRIGATE_LABELS: Map<String, Pair<Int, Int>>` (noun res, detected res).

- [ ] **Step 1: Strings**

`core/alerts/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Localized alerts spec §5.3: frigate.object. %1$s = object or person, %2$s = zone. -->
    <string name="alert_frigate_in">%1$s in %2$s</string>
    <string name="alert_frigate_who">%1$s (%2$s)</string>

    <string name="alert_frigate_person">Person</string>
    <string name="alert_frigate_person_detected">Person detected</string>
    <string name="alert_frigate_car">Car</string>
    <string name="alert_frigate_car_detected">Car detected</string>
    <string name="alert_frigate_truck">Truck</string>
    <string name="alert_frigate_truck_detected">Truck detected</string>
    <string name="alert_frigate_bus">Bus</string>
    <string name="alert_frigate_bus_detected">Bus detected</string>
    <string name="alert_frigate_motorcycle">Motorcycle</string>
    <string name="alert_frigate_motorcycle_detected">Motorcycle detected</string>
    <string name="alert_frigate_bicycle">Bicycle</string>
    <string name="alert_frigate_bicycle_detected">Bicycle detected</string>
    <string name="alert_frigate_dog">Dog</string>
    <string name="alert_frigate_dog_detected">Dog detected</string>
    <string name="alert_frigate_cat">Cat</string>
    <string name="alert_frigate_cat_detected">Cat detected</string>
    <string name="alert_frigate_bird">Bird</string>
    <string name="alert_frigate_bird_detected">Bird detected</string>
    <string name="alert_frigate_horse">Horse</string>
    <string name="alert_frigate_horse_detected">Horse detected</string>
    <string name="alert_frigate_package">Package</string>
    <string name="alert_frigate_package_detected">Package detected</string>
    <string name="alert_frigate_face">Face</string>
    <string name="alert_frigate_face_detected">Face detected</string>
    <string name="alert_frigate_license_plate">License plate</string>
    <string name="alert_frigate_license_plate_detected">License plate detected</string>
    <string name="alert_frigate_amazon" translatable="false">Amazon</string>
    <string name="alert_frigate_amazon_detected">Amazon detected</string>
    <string name="alert_frigate_dhl" translatable="false">DHL</string>
    <string name="alert_frigate_dhl_detected">DHL detected</string>
    <string name="alert_frigate_dpd" translatable="false">DPD</string>
    <string name="alert_frigate_dpd_detected">DPD detected</string>
    <string name="alert_frigate_fedex" translatable="false">FedEx</string>
    <string name="alert_frigate_fedex_detected">FedEx detected</string>
    <string name="alert_frigate_gls" translatable="false">GLS</string>
    <string name="alert_frigate_gls_detected">GLS detected</string>
    <string name="alert_frigate_ups" translatable="false">UPS</string>
    <string name="alert_frigate_ups_detected">UPS detected</string>
    <string name="alert_frigate_usps" translatable="false">USPS</string>
    <string name="alert_frigate_usps_detected">USPS detected</string>
</resources>
```

`core/alerts/src/main/res/values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="alert_frigate_in">%1$s dans %2$s</string>
    <string name="alert_frigate_who">%1$s (%2$s)</string>

    <string name="alert_frigate_person">Personne</string>
    <string name="alert_frigate_person_detected">Personne détectée</string>
    <string name="alert_frigate_car">Voiture</string>
    <string name="alert_frigate_car_detected">Voiture détectée</string>
    <string name="alert_frigate_truck">Camion</string>
    <string name="alert_frigate_truck_detected">Camion détecté</string>
    <string name="alert_frigate_bus">Bus</string>
    <string name="alert_frigate_bus_detected">Bus détecté</string>
    <string name="alert_frigate_motorcycle">Moto</string>
    <string name="alert_frigate_motorcycle_detected">Moto détectée</string>
    <string name="alert_frigate_bicycle">Vélo</string>
    <string name="alert_frigate_bicycle_detected">Vélo détecté</string>
    <string name="alert_frigate_dog">Chien</string>
    <string name="alert_frigate_dog_detected">Chien détecté</string>
    <string name="alert_frigate_cat">Chat</string>
    <string name="alert_frigate_cat_detected">Chat détecté</string>
    <string name="alert_frigate_bird">Oiseau</string>
    <string name="alert_frigate_bird_detected">Oiseau détecté</string>
    <string name="alert_frigate_horse">Cheval</string>
    <string name="alert_frigate_horse_detected">Cheval détecté</string>
    <string name="alert_frigate_package">Colis</string>
    <string name="alert_frigate_package_detected">Colis détecté</string>
    <string name="alert_frigate_face">Visage</string>
    <string name="alert_frigate_face_detected">Visage détecté</string>
    <string name="alert_frigate_license_plate">Plaque d\'immatriculation</string>
    <string name="alert_frigate_license_plate_detected">Plaque d\'immatriculation détectée</string>
    <string name="alert_frigate_amazon_detected">Amazon détecté</string>
    <string name="alert_frigate_dhl_detected">DHL détecté</string>
    <string name="alert_frigate_dpd_detected">DPD détecté</string>
    <string name="alert_frigate_fedex_detected">FedEx détecté</string>
    <string name="alert_frigate_gls_detected">GLS détecté</string>
    <string name="alert_frigate_ups_detected">UPS détecté</string>
    <string name="alert_frigate_usps_detected">USPS détecté</string>
</resources>
```

- [ ] **Step 2: Failing `AlertTextTest`**

```kotlin
package com.gekkotron.oikos.alerts.text

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertLoc
import com.gekkotron.oikos.alerts.Severity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AlertTextTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun alert(loc: AlertLoc?, body: String = "Server body") = Alert(
        "a1", AlertChannel.CAMERA, Severity.INFO, "Driveway", body, null, null, null, emptyList(), "frigate",
        false, false, Instant.EPOCH, Instant.EPOCH, loc,
    )

    private fun frigate(vararg args: Pair<String, String>) = alert(AlertLoc("frigate.object", mapOf(*args)))

    @Test
    fun `english sentences for every shape`() {
        assertEquals("Car detected", AlertText.body(frigate("label" to "car"), res))
        assertEquals("Car in Driveway", AlertText.body(frigate("label" to "car", "zone" to "Driveway"), res))
        assertEquals("Julien (person)", AlertText.body(frigate("label" to "person", "who" to "Julien"), res))
        assertEquals("Julien (person) in Driveway", AlertText.body(frigate("label" to "person", "who" to "Julien", "zone" to "Driveway"), res))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `french sentences agree with the noun`() {
        assertEquals("Voiture détectée", AlertText.body(frigate("label" to "car"), res))
        assertEquals("Camion détecté", AlertText.body(frigate("label" to "truck"), res))
        assertEquals("Voiture dans Driveway", AlertText.body(frigate("label" to "car", "zone" to "Driveway"), res))
        assertEquals("Julien (personne)", AlertText.body(frigate("label" to "person", "who" to "Julien"), res))
        assertEquals("Julien (personne) dans Driveway", AlertText.body(frigate("label" to "person", "who" to "Julien", "zone" to "Driveway"), res))
    }

    @Test
    fun `unknown or differently-cased labels fall back to the body`() {
        assertEquals("Server body", AlertText.body(frigate("label" to "fire_hydrant"), res))
        assertEquals("Server body", AlertText.body(frigate("label" to "Car"), res))
        assertEquals("Server body", AlertText.body(frigate("zone" to "Driveway"), res))
    }

    @Test
    fun `no loc or an unknown key falls back to the body`() {
        assertEquals("Server body", AlertText.body(alert(null), res))
        assertEquals("Server body", AlertText.body(alert(AlertLoc("jeedom.door", mapOf("label" to "car"))), res))
    }

    @Test
    fun `every label has both strings in both languages`() {
        val labels = listOf(
            "person", "car", "truck", "bus", "motorcycle", "bicycle", "dog", "cat", "bird", "horse",
            "package", "face", "license_plate", "amazon", "dhl", "dpd", "fedex", "gls", "ups", "usps",
        )
        assertEquals(labels.toSet(), AlertText.FRIGATE_LABELS.keys)
        for (label in labels) {
            assert(AlertText.body(frigate("label" to label), res) != "Server body") { label }
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `every label reads in french`() {
        for (label in AlertText.FRIGATE_LABELS.keys) {
            val text = AlertText.body(frigate("label" to label), res)
            assert(text.contains("détecté")) { "$label → $text" }
        }
    }
}
```

Run: `./gradlew --no-daemon :core:alerts:testDebugUnitTest --tests '*AlertTextTest'` → FAIL (`AlertText` unresolved).

- [ ] **Step 3: `AlertText.kt`**

```kotlin
package com.gekkotron.oikos.alerts.text

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertLoc
import com.gekkotron.oikos.alerts.R
import java.util.Locale

/**
 * The body an alert shows (localized alerts spec §5.2): the sentence its [AlertLoc] names, in [Resources]'
 * language, or the server's English [Alert.body] whenever the hint is missing, unknown or incomplete.
 */
object AlertText {
    /** Raw Frigate label → (noun, "detected" sentence). Exact, lower-case match only. */
    internal val FRIGATE_LABELS: Map<String, Pair<Int, Int>> = mapOf(
        "person" to (R.string.alert_frigate_person to R.string.alert_frigate_person_detected),
        "car" to (R.string.alert_frigate_car to R.string.alert_frigate_car_detected),
        "truck" to (R.string.alert_frigate_truck to R.string.alert_frigate_truck_detected),
        "bus" to (R.string.alert_frigate_bus to R.string.alert_frigate_bus_detected),
        "motorcycle" to (R.string.alert_frigate_motorcycle to R.string.alert_frigate_motorcycle_detected),
        "bicycle" to (R.string.alert_frigate_bicycle to R.string.alert_frigate_bicycle_detected),
        "dog" to (R.string.alert_frigate_dog to R.string.alert_frigate_dog_detected),
        "cat" to (R.string.alert_frigate_cat to R.string.alert_frigate_cat_detected),
        "bird" to (R.string.alert_frigate_bird to R.string.alert_frigate_bird_detected),
        "horse" to (R.string.alert_frigate_horse to R.string.alert_frigate_horse_detected),
        "package" to (R.string.alert_frigate_package to R.string.alert_frigate_package_detected),
        "face" to (R.string.alert_frigate_face to R.string.alert_frigate_face_detected),
        "license_plate" to (R.string.alert_frigate_license_plate to R.string.alert_frigate_license_plate_detected),
        "amazon" to (R.string.alert_frigate_amazon to R.string.alert_frigate_amazon_detected),
        "dhl" to (R.string.alert_frigate_dhl to R.string.alert_frigate_dhl_detected),
        "dpd" to (R.string.alert_frigate_dpd to R.string.alert_frigate_dpd_detected),
        "fedex" to (R.string.alert_frigate_fedex to R.string.alert_frigate_fedex_detected),
        "gls" to (R.string.alert_frigate_gls to R.string.alert_frigate_gls_detected),
        "ups" to (R.string.alert_frigate_ups to R.string.alert_frigate_ups_detected),
        "usps" to (R.string.alert_frigate_usps to R.string.alert_frigate_usps_detected),
    )

    fun body(alert: Alert, resources: Resources): String =
        alert.loc?.let { render(it, resources) } ?: alert.body

    private fun render(loc: AlertLoc, resources: Resources): String? = when (loc.key) {
        "frigate.object" -> frigateObject(loc.args, resources)
        else -> null
    }

    private fun frigateObject(args: Map<String, String>, resources: Resources): String? {
        val (noun, detected) = FRIGATE_LABELS[args["label"]] ?: return null
        val zone = args["zone"]?.takeIf { it.isNotBlank() }
        val who = args["who"]?.takeIf { it.isNotBlank() }
        val subject = when {
            who != null -> resources.getString(R.string.alert_frigate_who, who, resources.getString(noun).lowercase(locale(resources)))
            zone != null -> resources.getString(noun)
            else -> return resources.getString(detected)
        }
        return if (zone != null) resources.getString(R.string.alert_frigate_in, subject, zone) else subject
    }

    private fun locale(resources: Resources): Locale =
        ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
}
```

If `androidx.core` is not on `:core:alerts`' classpath, replace `ConfigurationCompat…` with `resources.configuration.locales[0]` (minSdk 28 ≥ 24).

Note: brand nouns lower-cased in parentheses would read "Julien (amazon)"; that combination (a recognised person *and* a delivery-brand label) does not occur in Frigate, so it is accepted.

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew --no-daemon :core:alerts:testDebugUnitTest :core:alerts:lintDebug` → all pass, lint clean (no MissingTranslation: brand nouns are `translatable="false"`).

- [ ] **Step 5: Commit**

```bash
git add core/alerts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): write frigate.object alerts in the app's language

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 5: App — show `AlertText` everywhere, demo alert

**Files:**
- Modify: `core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertNotifier.kt` (lines ~102-116)
- Modify: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertRow.kt` (~50-51)
- Modify: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertDetailSheet.kt` (~178)
- Modify: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/demo/DemoAlerts.kt` (demo camera alert gets a `loc`)
- Test: `feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertRowLocaleTest.kt`; `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/demo/DemoAlertsTest.kt`; the existing `AlertNotifier` test in `core/push/src/test` (read it first)

**Interfaces:**
- Consumes: `AlertText.body(alert, resources)` (Task 4), `Alert.loc` (Task 3).

- [ ] **Step 1: Failing UI test** — `AlertRowLocaleTest.kt` (read `AlertRow`'s signature and an existing `feature/alerts` UI test for its setup first; use the same theme wrapper and callbacks):

```kotlin
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fr-w411dp-h914dp")
class AlertRowLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a frigate alert reads in french`() {
        val alert = Alert(
            "a1", AlertChannel.CAMERA, Severity.INFO, "Driveway", "Car in Driveway", null, null, null, emptyList(), "frigate",
            false, false, Instant.EPOCH, Instant.EPOCH, AlertLoc("frigate.object", mapOf("label" to "car", "zone" to "Driveway")),
        )
        compose.setContent { OikosTheme { AlertRow(alert, now = Instant.EPOCH, onClick = {}) } }
        compose.onNodeWithText("Voiture dans Driveway").assertExists()
        compose.onNodeWithText("Car in Driveway").assertDoesNotExist()
    }
}
```

Imports: `androidx.compose.ui.test.junit4.createComposeRule`, `androidx.compose.ui.test.onNodeWithText`, `androidx.compose.ui.test.assertDoesNotExist`, `androidx.test.ext.junit.runners.AndroidJUnit4`, `com.gekkotron.oikos.designsystem.OikosTheme`, the alert classes, `java.time.Instant`, `org.junit.Rule/Test`, `org.junit.runner.RunWith`, `org.robolectric.annotation.Config`.

Run: `./gradlew --no-daemon :feature:alerts:testDebugUnitTest --tests '*AlertRowLocaleTest'` → FAIL (shows "Car in Driveway").

- [ ] **Step 2: Use `AlertText` in the row and the sheet**

`AlertRow.kt`:

```kotlin
                val body = alertBody(alert)
                if (body.isNotBlank()) {
                    Text(body, style = OikosTheme.type.label, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
```

`AlertDetailSheet.kt`:

```kotlin
    val body = alertBody(alert)
    if (body.isNotBlank()) Text(body, style = OikosTheme.type.body)
```

Add to `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertBody.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.text.AlertText

/** The alert's body in the current language; reads LocalConfiguration so a language change recomposes it. */
@Composable
internal fun alertBody(alert: Alert): String {
    LocalConfiguration.current
    return AlertText.body(alert, LocalContext.current.resources)
}
```

Run the UI test → PASS.

- [ ] **Step 3: Notifier** — `testAlert(...)` in `core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertFixtures.kt` builds the alert; give it a `loc: AlertLoc? = null` parameter passed to `Alert(..., loc = loc)`. Append to `AlertNotifierTest`:

```kotlin
    @Test
    fun `the notification text is written in the app's language`(): Unit = runTest {
        assertTrue(notifier().post(testAlert(loc = AlertLoc("frigate.object", mapOf("label" to "car")))))
        assertEquals("Car detected", posted("frigate-1")!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
```

Run: `./gradlew --no-daemon :core:push:testDebugUnitTest --tests '*AlertNotifierTest'` → FAIL ("Person detected"). Then in `AlertNotifier`, compute once `val body = AlertText.body(alert, context.resources)` before the builder and use `body` in `setContentText`, `setSummaryText` and `bigText` (the three `alert.body` uses; import `com.gekkotron.oikos.alerts.text.AlertText`). Run → PASS.

- [ ] **Step 4: Demo alert** — in `DemoAlertsTest`, add an assertion that the `demo-camera` alert has `loc == AlertLoc("frigate.object", mapOf("label" to "person", "zone" to "Garden"))`; run → FAIL. In `DemoAlerts.kt`, give `demo()` an optional `loc: AlertLoc? = null` parameter passed as the `Alert`'s last argument, and pass `loc = AlertLoc("frigate.object", mapOf("label" to "person", "zone" to "Garden"))` for `demo-camera` (its English title/body stay as they are). Run → PASS.

- [ ] **Step 5: Full gate + commit**

Run: `./gradlew --no-daemon check` → BUILD SUCCESSFUL. Known flakes (rerun once): `DemoAlertSeederTest`, `DirectConnectionTesterTest`, the lint crash on `AlertImageLoaderTest.kt`.

```bash
git add core/push feature/alerts core/alerts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): show alerts in the app's language everywhere

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

- [ ] **Step 6: Rollout checklist (user, after merge)**
1. OikosPush: merge `feat/alert-loc`, push, redeploy the service on the Geekom (`update.sh`).
2. App: merge `feat/localized-alerts`, push (App Distribution builds it), install.
3. Frigate bridge: redeploy on the Geekom (same repo, recreate the bridge container).
4. Walk past a camera: a French phone reads "Personne dans …"; Android Settings → Apps → Oikos → Language → English shows "Person in …" in the Alerts screen.
