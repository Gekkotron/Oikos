# Entity Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cold start draws the last-known house at once, with values dimmed until live data arrives. The cache replays the MQTT messages that built it.

**Architecture:**
- `MqttDiscoverySource` keeps a journal (topic → last payload) of the messages that make up its output. It can `restore()` a journal through its normal `handle()` path, and once connected it prunes restored topics the broker no longer holds.
- `StateStore` marks states built from restored messages as `stale`.
- `:core:data` stores the journal per profile key in `noBackupFilesDir`. `EntitySourceSession` restores it into each new direct source and saves it back, debounced.
- Home dims stale cards and refuses to send commands for them.

**Tech Stack:** Kotlin, coroutines/Flow, AndroidX DataStore (JSON serializer, like `ProfileRepository`), kotlinx.serialization, Hilt, Jetpack Compose, JUnit4 + kotlin.test + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-25-oikos-entity-cache-design.md`

**Branch:** run this on `feat/entity-cache`, created from `master` *after* `fix/review-minors` (the M1–M7 polish) is merged. Task 6 edits `HomeViewModel`, `HomeScreen` and `EntityCard`, which that branch also touches.

## Global Constraints

- Nothing secret in the cache. `profileKey` is `"$host:$port:$prefix"`, with no username, password or gateway key.
- The cache file is `noBackupFilesDir/entity-cache.json`. It is never backed up.
- The demo is never cached. `reset()` ("Set up connection again") clears the cache.
- Prune and settle run on the source's first `LinkState.Connected`, after **2 s** of message quiet, capped at **10 s**. They run once per source.
- Journal saves are debounced by **2 s**.
- A tap on a stale card never calls `send()`. It shows `home_waiting_connection`: EN "Waiting for the connection", FR "En attente de la connexion".
- EN and FR string files keep the same keys.
- Commits use `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, and each message ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `./gradlew check` must pass at the end.

## Review Focus

1. **Restore finishing after live data.** The cache load is async, so live retained messages can arrive first. A restored payload must never overwrite a topic already seen live. Test in Task 2.
2. **Restore finishing after settle.** If the load is slower than the settle window, a late restore would leave stale ghosts that are never pruned. `restore()` after settle must be a no-op. Test in Task 3.
3. **An entity removed while the app was closed.** Its config is restored but never re-sent, so it must disappear at settle, and so must its restored override. Test in Task 3.
4. **A different broker with an old cache on disk.** The key differs, so nothing from the old house may appear. Test in Task 4 (key mismatch) and Task 5 (session).
5. **A sheet command on a stale entity** (cover or climate sheets call `HomeViewModel.send` directly, not `tap`). It must also be refused with the hint. Test in Task 6.

---

## File map

| File | Responsibility |
|---|---|
| `core/model/.../model/EntityState.kt` | + `stale` field |
| `core/state/.../state/StateStore.kt` | restored messages → stale; `settle()` |
| `core/source/.../source/EntitySource.kt` | + `restore()` default no-op |
| `core/source/.../source/MqttDiscoverySource.kt` | journal, `journalVersion`, `restore()`, prune/settle |
| `core/data/.../data/EntityCache.kt` (new) | `EntityCache` interface + `FileEntityCache` (DataStore JSON) |
| `app/.../connection/EntitySourceSession.kt` | restore on build, debounced save, clear |
| `app/.../connection/CacheKey.kt` (new) | `ConnectionProfile.Direct.cacheKey()` |
| `app/.../di/DataModule.kt`, `AppModule.kt` | provide the cache, pass it to the session |
| `app/src/main/res/xml/data_extraction_rules.xml` | comment only |
| `feature/home/.../presentation/HomeModels.kt`, `HomeUiBuilder.kt`, `HomeViewModel.kt` | `CardUi.stale`, `HomeEvent.WaitingForConnection`, send guard |
| `feature/home/.../ui/EntityCard.kt`, `HomeScreen.kt` (hero), `HomeEventsEffect.kt` | dimming, state description, snackbar |
| `feature/home/src/main/res/values*/strings.xml` | `home_waiting_connection`, `card_stale` |

Paths abbreviate `src/main/kotlin/com/gekkotron/oikos/`. Tests live in the matching `src/test/kotlin/...`.

---

### Task 1: Stale states in `StateStore`

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/EntityState.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/StateStore.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt`

**Interfaces:**
- Produces:
  - `EntityState.stale: Boolean = false`
  - `StateStore.onMessage(topic: String, payload: String, restored: Boolean = false): Set<String>`
  - `StateStore.settle()`

- [ ] **Step 1: Write the failing tests** (append to `StateStoreTest`)

```kotlin
    private fun stale(id: String) = store.states.value.getValue(id).stale

    @Test
    fun `a restored message makes its entities stale and a live one clears it`() {
        store.setEntities(listOf(temperature, humidity))
        store.onMessage("z/capteur", """{"temperature":21.5,"humidity":48}""", restored = true)
        assertEquals(StateValue.Numeric(21.5), value("t"))
        assertTrue(stale("t"))
        assertTrue(stale("h"))

        store.onMessage("z/capteur", """{"temperature":22.0,"humidity":47}""")
        assertFalse(stale("t"))
        assertFalse(stale("h"))
    }

    @Test
    fun `a live availability message clears stale too`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s", "ON", restored = true)
        assertTrue(stale("l"))
        store.onMessage("s/avail", "online")
        assertFalse(stale("l"))
    }

    @Test
    fun `settle clears every stale state`() {
        store.setEntities(listOf(temperature, lamp))
        store.onMessage("z/capteur", """{"temperature":21.5}""", restored = true)
        store.onMessage("s", "ON", restored = true)
        store.settle()
        assertFalse(stale("t"))
        assertFalse(stale("l"))
    }

    @Test
    fun `an entity added later replays a restored payload as stale`() {
        store.setEntities(listOf(temperature))
        store.onMessage("z/capteur", """{"temperature":21.5,"humidity":48}""", restored = true)
        store.setEntities(listOf(temperature, humidity))
        assertEquals(StateValue.Numeric(48.0), value("h"))
        assertTrue(stale("h"))
    }
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :core:state:test --tests '*StateStoreTest*'`
Expected: compilation FAIL (`stale`, `restored`, `settle` unresolved).

- [ ] **Step 3: Implement**

In `EntityState.kt`, add after `pending`:

```kotlin
    /** True while the value comes from the cache and no live message has confirmed it (spec: entity cache §3.2). */
    val stale: Boolean = false,
```

In `StateStore.kt`:
- Add the field `private val restoredTopics = mutableSetOf<String>()` next to `lastPayload`.
- Change `onMessage`:

```kotlin
    /** Returns the ids whose value was confirmed by this message. A [restored] message comes from the cache. */
    fun onMessage(topic: String, payload: String, restored: Boolean = false): Set<String> = synchronized(lock) {
        val ids = topicIndex[topic] ?: return emptySet()
        lastPayload[topic] = payload
        if (restored) restoredTopics += topic else restoredTopics -= topic
        val next = _states.value.toMutableMap()
        val confirmedIds = ids.filterTo(mutableSetOf()) { apply(it, topic, payload, next) }
        _states.value = next
        confirmedIds
    }

    /** The broker has replayed what it holds: nothing is stale any more. */
    fun settle(): Unit = synchronized(lock) {
        restoredTopics.clear()
        _states.value = _states.value.mapValues { (_, s) -> if (s.stale) s.copy(stale = false) else s }
    }
```

- In `retainPayloads`, also run `restoredTopics.retainAll(subscribed)`.
- In `apply(...)`, just before `states[id] = s`, add:

```kotlin
        s = s.copy(stale = topic in restoredTopics)
```

`setEntities` replays `lastPayload` through `apply`, so a fresh entity inherits stale from a restored payload with no extra code.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:state:test`
Expected: PASS, including all existing tests.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/gekkotron/oikos/model/EntityState.kt core/state/src/main/kotlin/com/gekkotron/oikos/state/StateStore.kt core/state/src/test/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): stale states from restored messages; settle clears them

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Journal and `restore()` in the source

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt`
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`
- Create: `core/source/src/test/kotlin/com/gekkotron/oikos/source/EntityCacheSourceTest.kt`

**Interfaces:**
- Consumes: `StateStore.onMessage(topic, payload, restored)` (Task 1).
- Produces:
  - `EntitySource.restore(messages: Map<String, String>)`: a `suspend` interface method with an empty default body.
  - `MqttDiscoverySource.journal(): Map<String, String>`
  - `MqttDiscoverySource.journalVersion: StateFlow<Long>`, which increments whenever `journal()` may have changed.

- [ ] **Step 1: Write the failing tests**

Create `EntityCacheSourceTest.kt`. It reuses the light config from `MqttDiscoverySourceTest`:

```kotlin
package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.UiOverrides
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EntityCacheSourceTest {
    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightId = "0x01_light_zigbee2mqtt"
    private val lightConfig =
        """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","brightness":true,"unique_id":"$lightId","name":null,"device":{"identifiers":["z_lampe"],"name":"Lampe","suggested_area":"Salon"}}"""
    private val cached = mapOf(
        lightTopic to lightConfig,
        "z2m/lampe" to """{"state":"ON","brightness":254}""",
        "oikos/ui/$lightId" to UiOverrides.encode(UiOverride(name = "Plafonnier")),
    )

    /** Offline by default, like a cold start away from home. */
    private fun TestScope.start(transport: FakeMqttTransport = FakeMqttTransport().apply { setLink(LinkState.Offline("test")) }) =
        transport to MqttDiscoverySource(transport, backgroundScope).also { runCurrent() }

    @Test
    fun `restore builds the house with stale states while offline`() = runTest {
        val (_, source) = start()
        source.restore(cached)
        runCurrent()
        val light = source.entities.value.getValue(lightId)
        assertEquals("Plafonnier", light.name)
        assertEquals("Salon", light.area)
        val state = source.states().value.getValue(lightId)
        assertEquals(true, (state.value as StateValue.Light).on)
        assertTrue(state.stale)
    }

    @Test
    fun `restore replays configs before states whatever the map order`() = runTest {
        val (_, source) = start()
        source.restore(linkedMapOf("z2m/lampe" to """{"state":"ON"}""", lightTopic to lightConfig))
        runCurrent()
        assertTrue(source.states().value.getValue(lightId).stale)
    }

    @Test
    fun `live data wins over a restore that finishes later`() = runTest {
        val transport = FakeMqttTransport().apply { setLink(LinkState.Offline("test")) }
        transport.deliver(lightTopic, lightConfig, retain = true)
        val (_, source) = start(transport)
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
        transport.deliver("z2m/lampe", """{"state":"OFF"}""")
        runCurrent()

        source.restore(cached)
        runCurrent()
        val state = source.states().value.getValue(lightId)
        assertEquals(false, (state.value as StateValue.Light).on)
        assertFalse(state.stale)
    }

    @Test
    fun `the journal holds the topics that build the output, and drops removed ones`() = runTest {
        val (transport, source) = start(FakeMqttTransport())
        transport.deliver(lightTopic, lightConfig, retain = true)
        runCurrent()
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
        transport.deliver("z2m/lampe", """{"state":"ON"}""")
        transport.deliver("unrelated/topic", "x")
        runCurrent()
        assertEquals(setOf(lightTopic, "z2m/lampe"), source.journal().keys)

        val before = source.journalVersion.value
        transport.deliver(lightTopic, "", retain = true)
        runCurrent()
        assertTrue(source.journalVersion.value > before)
        assertEquals(emptySet(), source.journal().keys)
    }
}
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :core:source:test --tests '*EntityCacheSourceTest*'`
Expected: compilation FAIL (`restore`, `journal`, `journalVersion` unresolved).

- [ ] **Step 3: Implement**

In `EntitySource.kt`, add after `setAreas`:

```kotlin
    /** Replays cached messages (topic → payload) as if they had arrived retained; live data wins (entity cache §3.1). */
    suspend fun restore(messages: Map<String, String>) {}
```

In `MqttDiscoverySource.kt`:

1. Add fields under `lock`:

```kotlin
    // Entity cache (spec 2026-09-25): the last payload of every handled topic, the topics received live,
    // and restored config/override topics not yet confirmed live (pruned at settle, Task 3).
    private val payloads = mutableMapOf<String, String>()
    private val liveTopics = mutableSetOf<String>()
    private val restoredOnly = mutableSetOf<String>()
    private val _journalVersion = MutableStateFlow(0L)
    val journalVersion: StateFlow<Long> = _journalVersion.asStateFlow()
```

2. Change `handle` to take the origin, and record the payload before dispatching:

```kotlin
    private fun handle(message: MqttMessage, restored: Boolean = false) {
        val topic = message.topic
        try {
            val payload = message.payloadString()
            synchronized(lock) {
                if (restored) {
                    if (topic in liveTopics) return
                    restoredOnly += topic
                } else {
                    liveTopics += topic
                    restoredOnly -= topic
                }
                if (payload.isEmpty()) payloads.remove(topic) else payloads[topic] = payload
            }
            when {
                topic == AREAS_TOPIC -> onAreas(payload)
                topic.startsWith(UI_PREFIX) -> onOverride(topic.removePrefix(UI_PREFIX), payload)
                else -> when (val r = DiscoveryParser.parse(prefix, topic, payload)) {
                    DiscoveryResult.NotDiscovery -> store.onMessage(topic, payload, restored)
                    // …the other branches are unchanged
                }
            }
            _journalVersion.value++
        } catch (e: Exception) {
            report("unexpected error handling $topic: $e")
        }
    }
```

Keep the existing `DiscoveryResult` branches exactly as they are. Only the `NotDiscovery` line gains `restored`.

3. Add `restore` and `journal`:

```kotlin
    override suspend fun restore(messages: Map<String, String>) {
        // Configs first so state messages find their entity, then overrides, then states.
        val ordered = messages.entries.sortedBy { (topic, _) ->
            when {
                topic.startsWith("$prefix/") && topic.endsWith("/config") -> 0
                topic.startsWith(UI_PREFIX) -> 1
                else -> 2
            }
        }
        for ((topic, payload) in ordered) handle(MqttMessage(topic, payload.encodeToByteArray(), retained = true), restored = true)
    }

    /** What the cache should hold now: the topics that currently build [entities], [areas] and [states]. */
    fun journal(): Map<String, String> = synchronized(lock) {
        val keep = buildSet {
            addAll(discovered.keys)
            overrides.keys.forEach { add(UI_PREFIX + it) }
            if (areasDocument.areas.isNotEmpty()) add(AREAS_TOPIC)
            addAll(store.subscriptionTopics())
        }
        payloads.filterKeys { it in keep }
    }
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:source:test`
Expected: PASS, including `MqttDiscoverySourceTest` and `ReviewFixesTest`.

- [ ] **Step 5: Commit**

```bash
git add core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt core/source/src/test/kotlin/com/gekkotron/oikos/source/EntityCacheSourceTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(source): message journal and restore; live data wins over cached

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Prune and settle after the first connection

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/EntityCacheSourceTest.kt`

**Interfaces:**
- Consumes: `restoredOnly`, `payloads`, `StateStore.settle()` (Tasks 1–2).
- Produces: `MqttDiscoverySource.SETTLE_QUIET = 2.seconds` and `SETTLE_MAX = 10.seconds` (companion constants).

- [ ] **Step 1: Write the failing tests** (append to `EntityCacheSourceTest`)

```kotlin
    private val quiet = MqttDiscoverySource.SETTLE_QUIET.inWholeMilliseconds

    @Test
    fun `after connecting, restored configs the broker no longer holds are pruned with their override`() = runTest {
        val (transport, source) = start()
        source.restore(cached)
        runCurrent()
        transport.setLink(LinkState.Connected(com.gekkotron.oikos.transport.TransportMode.DIRECT))
        runCurrent()
        assertTrue(lightId in source.entities.value)

        advanceTimeBy(quiet + 1)
        runCurrent()
        assertFalse(lightId in source.entities.value)
        assertEquals(emptySet(), source.journal().keys)
    }

    @Test
    fun `configs re-sent live survive and every state is settled`() = runTest {
        val transport = FakeMqttTransport().apply { setLink(LinkState.Offline("test")) }
        transport.retain(lightTopic, lightConfig)
        val (_, source) = start(transport)
        source.restore(cached)
        runCurrent()
        transport.setLink(LinkState.Connected(com.gekkotron.oikos.transport.TransportMode.DIRECT))
        transport.deliver(lightTopic, lightConfig, retain = true) // the broker's retained replay
        runCurrent()
        advanceTimeBy(quiet + 1)
        runCurrent()
        assertTrue(lightId in source.entities.value)
        assertEquals("Salon", source.entities.value.getValue(lightId).area)
        // The override was only restored, never re-sent: it is gone, so the name falls back.
        assertEquals("Lampe", source.entities.value.getValue(lightId).name)
        assertFalse(source.states().value.getValue(lightId).stale)
    }

    @Test
    fun `a steady stream still settles by the cap`() = runTest {
        val (transport, source) = start()
        source.restore(cached)
        runCurrent()
        transport.setLink(LinkState.Connected(com.gekkotron.oikos.transport.TransportMode.DIRECT))
        runCurrent()
        repeat(12) {
            transport.deliver("homeassistant/noise/$it", "x") // subscribed, so it counts as live traffic
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertFalse(lightId in source.entities.value)
    }

    @Test
    fun `a restore that arrives after settle is ignored`() = runTest {
        val (_, source) = start(FakeMqttTransport())
        advanceTimeBy(quiet + 1)
        runCurrent()
        source.restore(cached)
        runCurrent()
        assertFalse(lightId in source.entities.value)
        assertTrue(source.journal().isEmpty())
    }
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :core:source:test --tests '*EntityCacheSourceTest*'`
Expected: FAIL (`SETTLE_QUIET` unresolved; once that exists, the entity is still present after the quiet time).

- [ ] **Step 3: Implement**

In `MqttDiscoverySource.kt`:
- Add `@Volatile private var settled = false` and `@Volatile private var lastMessageAt = 0L` (the counter of handled messages).
- In `handle`, increment `lastMessageAt++` for live messages, next to `liveTopics += topic`.
- At the top of `restore`, add `if (settled) return`. In `handle`, a `restored` message also returns when `settled`.
- Add to `init`:

```kotlin
        scope.launch {
            link.first { it is LinkState.Connected }
            val deadline = SETTLE_MAX.inWholeMilliseconds / SETTLE_QUIET.inWholeMilliseconds
            var rounds = 0L
            do {
                val seen = lastMessageAt
                delay(SETTLE_QUIET)
                rounds++
            } while (lastMessageAt != seen && rounds < deadline)
            settle()
        }
```

This checks every 2 s: it stops at the first 2 s window with no new message, or after 5 windows (10 s).

- Add `settle()`:

```kotlin
    /** The broker has replayed what it holds: restored configs and overrides it did not re-send are gone. */
    private fun settle() {
        synchronized(lock) {
            settled = true
            for (topic in restoredOnly) {
                payloads.remove(topic)
                when {
                    topic == AREAS_TOPIC -> { areasDocument = AreasDocument(); confirmedAreas = areasDocument }
                    topic.startsWith(UI_PREFIX) -> topic.removePrefix(UI_PREFIX).let { overrides.remove(it); confirmedOverrides.remove(it) }
                    else -> discovered.remove(topic)
                }
            }
            restoredOnly.clear()
        }
        publishEntities()
        store.settle()
        _journalVersion.value++
    }
```

Restored *state* topics also sit in `restoredOnly`. For them, `discovered.remove(topic)` is a no-op and dropping the payload is harmless: `journal()` only keeps payloads that are still present. The value on screen stays; `store.settle()` just un-dims it.

- Add companion constants: `val SETTLE_QUIET: Duration = 2.seconds` and `val SETTLE_MAX: Duration = 10.seconds`.
- Add imports: `kotlinx.coroutines.delay`, `kotlinx.coroutines.flow.first`.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:source:test`
Expected: PASS, all tests. Existing tests start `Connected` with nothing restored, so settle only runs `store.settle()` and changes nothing they assert.

- [ ] **Step 5: Commit**

```bash
git add core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt core/source/src/test/kotlin/com/gekkotron/oikos/source/EntityCacheSourceTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(source): prune restored topics the broker no longer holds and settle stale states after connecting

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `EntityCache` in `:core:data`

**Files:**
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/EntityCache.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/EntityCacheTest.kt`

**Interfaces:**
- Produces:

```kotlin
interface EntityCache {
    suspend fun load(key: String): Map<String, String>
    suspend fun save(key: String, messages: Map<String, String>)
    suspend fun clear()
}
class FileEntityCache private constructor(...) : EntityCache {
    companion object {
        const val FILE = "entity-cache.json"
        fun create(context: Context, scope: CoroutineScope): FileEntityCache
        fun create(file: File, scope: CoroutineScope): FileEntityCache
    }
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gekkotron.oikos.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class EntityCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = tmp.root.resolve("entity-cache.json")
    private fun TestScope.cache() = FileEntityCache.create(file, backgroundScope)
    private val messages = mapOf("homeassistant/light/a/config" to """{"unique_id":"a"}""", "z2m/a" to """{"state":"ON"}""")

    @Test
    fun `missing file loads empty`() = runTest {
        assertEquals(emptyMap(), cache().load("h:1883:homeassistant"))
    }

    @Test
    fun `round trip for the same key, across instances`() = runTest {
        cache().save("h:1883:homeassistant", messages)
        assertEquals(messages, cache().load("h:1883:homeassistant"))
    }

    @Test
    fun `another key loads empty`() = runTest {
        val c = cache()
        c.save("h:1883:homeassistant", messages)
        assertEquals(emptyMap(), c.load("other:1883:homeassistant"))
    }

    @Test
    fun `clear forgets everything`() = runTest {
        val c = cache()
        c.save("k", messages)
        c.clear()
        assertEquals(emptyMap(), c.load("k"))
    }

    @Test
    fun `a corrupt file loads empty`() = runTest {
        file.writeText("{ not json")
        assertEquals(emptyMap(), cache().load("k"))
    }
}
```

The round-trip test uses two instances on the same file. DataStore forbids two active instances on one file in the same process, so create the first one in a separate `TestScope`, or give each test its own file name. If DataStore throws `IllegalStateException: There are multiple DataStores active for the same file`, rewrite that test to save and load through one instance and delete the "across instances" wording.

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*EntityCacheTest*'`
Expected: compilation FAIL (`FileEntityCache` unresolved).

- [ ] **Step 3: Implement** `EntityCache.kt`

```kotlin
package com.gekkotron.oikos.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The last MQTT messages that built the house, for an instant cold start (entity cache spec §3.3). No secrets. */
interface EntityCache {
    /** The cached messages for [key], or empty when the cache belongs to another profile. */
    suspend fun load(key: String): Map<String, String>

    suspend fun save(key: String, messages: Map<String, String>)

    suspend fun clear()
}

@Serializable
internal data class StoredCache(val profileKey: String = "", val messages: Map<String, String> = emptyMap())

class FileEntityCache private constructor(private val store: DataStore<StoredCache>, scope: CoroutineScope) : EntityCache {
    // Read eagerly at launch, in parallel with the profile, so Home finds it warm.
    private val current = store.data.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun load(key: String): Map<String, String> {
        val c = current.filterNotNull().first()
        return if (c.profileKey == key) c.messages else emptyMap()
    }

    override suspend fun save(key: String, messages: Map<String, String>) {
        store.updateData { StoredCache(key, messages) }
    }

    override suspend fun clear() {
        store.updateData { StoredCache() }
    }

    companion object {
        const val FILE = "entity-cache.json"

        /** In `noBackupFilesDir`, which is never backed up. */
        fun create(context: Context, scope: CoroutineScope): FileEntityCache = create(context.noBackupFilesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): FileEntityCache {
            val store = DataStoreFactory.create(
                serializer = CacheSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredCache() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
            return FileEntityCache(store, scope)
        }
    }
}

internal object CacheSerializer : Serializer<StoredCache> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: StoredCache = StoredCache()

    override suspend fun readFrom(input: InputStream): StoredCache = try {
        json.decodeFromString(StoredCache.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable entity cache", e)
    }

    override suspend fun writeTo(t: StoredCache, output: OutputStream) {
        output.write(json.encodeToString(StoredCache.serializer(), t).encodeToByteArray())
    }
}
```

(`SerializationException` is an `IllegalArgumentException`, so that one catch covers bad JSON, the same as in `ProfileSerializer`.)

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/gekkotron/oikos/data/EntityCache.kt core/data/src/test/kotlin/com/gekkotron/oikos/data/EntityCacheTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(data): entity cache file keyed by profile, outside backups

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Session wiring and DI

**Files:**
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/CacheKey.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/connection/EntitySourceSession.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/DataModule.kt`, `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml` (comment)
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/EntitySourceSessionTest.kt`

**Interfaces:**
- Consumes:
  - `EntityCache` (Task 4)
  - `EntitySource.restore` (Task 2)
  - `MqttDiscoverySource.journal()` / `journalVersion` (Task 2)
- Produces:
  - `fun ConnectionProfile.Direct.cacheKey(): String = "${local.host}:${local.port}:$prefix"`
  - the `EntitySourceSession(transport, parent, diagnostics, cache: EntityCache? = null, build = …)` constructor

- [ ] **Step 1: Write the failing tests** (append to `EntitySourceSessionTest`)

These use a `FakeEntityCache` in the same test file. The session tests run on `Dispatchers.Unconfined` with `runBlocking`, as the existing ones do. The debounced save is checked with a short `withTimeout` poll.

```kotlin
private class FakeEntityCache(var stored: Pair<String, Map<String, String>>? = null) : EntityCache {
    var clears = 0
    override suspend fun load(key: String) = stored?.takeIf { it.first == key }?.second.orEmpty()
    override suspend fun save(key: String, messages: Map<String, String>) { stored = key to messages }
    override suspend fun clear() { clears++; stored = null }
}
```

Add to the class. The session in these tests is built with `cache = fakeCache` and the default build:

```kotlin
    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightConfig = """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","unique_id":"lamp","name":"Lampe"}"""

    @Test
    fun `a direct source is restored from the cache for its key`() = runBlocking {
        val cache = FakeEntityCache(direct.cacheKey() to mapOf(lightTopic to lightConfig))
        transport.setLink(LinkState.Offline("test"))
        val s = EntitySourceSession(transport, parent, cache = cache).current(direct)
        withTimeout(2_000) { s.entities.first { "lamp" in it } }
        Unit
    }

    @Test
    fun `a cache for another broker is not restored`() = runBlocking {
        val cache = FakeEntityCache("other:1883:homeassistant" to mapOf(lightTopic to lightConfig))
        transport.setLink(LinkState.Offline("test"))
        val s = EntitySourceSession(transport, parent, cache = cache).current(direct)
        delay(200)
        assertTrue(s.entities.value.isEmpty())
    }

    @Test
    fun `the journal is saved under the profile key`() = runBlocking {
        val cache = FakeEntityCache()
        EntitySourceSession(transport, parent, cache = cache).current(direct)
        transport.deliver(lightTopic, lightConfig, retain = true)
        withTimeout(5_000) { while (cache.stored == null) delay(50) }
        assertEquals(direct.cacheKey(), cache.stored!!.first)
        assertTrue(lightTopic in cache.stored!!.second)
    }

    @Test
    fun `the demo and reset clear the cache`() = runBlocking {
        val cache = FakeEntityCache(direct.cacheKey() to mapOf(lightTopic to lightConfig))
        val session = EntitySourceSession(transport, parent, cache = cache)
        session.current(ConnectionProfile.Demo)
        withTimeout(2_000) { while (cache.clears == 0) delay(10) }
        session.current(direct)
        session.reset()
        assertEquals(2, cache.clears)
    }
```

Add the imports: `com.gekkotron.oikos.data.EntityCache`, `com.gekkotron.oikos.transport.LinkState`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.flow.first` and `kotlinx.coroutines.withTimeout`. Check `FakeMqttTransport.setLink` exists; it does (testFixtures).

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*EntitySourceSessionTest*'`
Expected: compilation FAIL (`cache` parameter and `cacheKey` unresolved).

- [ ] **Step 3: Implement**

`CacheKey.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile

/** Which house a cache belongs to; no credentials (entity cache spec §3.3). */
fun ConnectionProfile.Direct.cacheKey(): String = "${local.host}:${local.port}:$prefix"
```

In `EntitySourceSession`:
- Add the constructor parameter `private val cache: EntityCache? = null` before `build`.
- In `current(...)`, after building:

```kotlin
        val built = build(transport, child, profile?.prefix ?: DEFAULT_PREFIX, diagnostics)
        when (profile) {
            is ConnectionProfile.Direct -> cache?.let { attach(built, it, profile.cacheKey(), child) }
            // The demo and "no profile" never show a real house: forget any cached one.
            else -> cache?.let { parent.launch { it.clear() } }
        }
        return built.also { scope = child; source = it; this.profile = profile }
```

- Add:

```kotlin
    @OptIn(FlowPreview::class)
    private fun attach(source: EntitySource, cache: EntityCache, key: String, scope: CoroutineScope) {
        scope.launch {
            source.restore(cache.load(key))
            val journaled = source as? MqttDiscoverySource ?: return@launch
            journaled.journalVersion.debounce(SAVE_DEBOUNCE).collect {
                val messages = journaled.journal()
                if (messages.isNotEmpty()) {
                    try {
                        cache.save(key, messages)
                    } catch (e: IOException) {
                        diagnostics.report(Diagnostic("cache", "save failed: $e"))
                    }
                }
            }
        }
    }
```

- In `reset()`, after `drop()`, add `cache?.clear()`.
- Add `companion object { val SAVE_DEBOUNCE: Duration = 2.seconds }` to the existing companion.
- Add imports: `Diagnostic`, `EntityCache`, `FlowPreview`, `kotlinx.coroutines.flow.debounce`, `kotlinx.coroutines.launch`, `java.io.IOException`, `kotlin.time.Duration`, `kotlin.time.Duration.Companion.seconds`.

In `DataModule.kt`:

```kotlin
    @Provides
    @Singleton
    fun entityCache(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): EntityCache =
        FileEntityCache.create(context, scope)
```

In `AppModule.entitySourceSession`, add the `cache: EntityCache` parameter and pass `cache = cache`.

In `data_extraction_rules.xml`, add this comment inside `<data-extraction-rules>`:
`<!-- The entity cache lives in noBackupFilesDir, which is never backed up or transferred. -->`

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. The existing session tests use no cache (the parameter defaults to `null`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/gekkotron/oikos/connection/CacheKey.kt app/src/main/kotlin/com/gekkotron/oikos/connection/EntitySourceSession.kt app/src/main/kotlin/com/gekkotron/oikos/di/DataModule.kt app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt app/src/main/res/xml/data_extraction_rules.xml app/src/test/kotlin/com/gekkotron/oikos/connection/EntitySourceSessionTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(app): restore each direct source from the entity cache and save its journal; demo and reset clear it

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Dimmed stale cards and the waiting hint

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`, `HomeUiBuilder.kt`, `HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt`, `HomeEventsEffect.kt`, `HomeScreen.kt` (hero)
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderTest.kt`, `HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `EntityState.stale` (Task 1).
- Produces:
  - `CardUi.stale: Boolean`
  - `HomeEvent.WaitingForConnection`
  - strings `home_waiting_connection`, `card_stale`

- [ ] **Step 1: Write the failing tests**

In `HomeViewModelTest`:

```kotlin
    @Test
    fun `a stale card is dimmed and neither a tap nor a sheet command sends`() = runTest(dispatcher) {
        source.stateMap.value = mapOf(
            "lamp" to EntityState("lamp", StateValue.Light(true, 255), stale = true),
            "shutter" to EntityState("shutter", StateValue.Unknown, stale = true),
        )
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        val lamp = vm.state.value.cards.first { it.id == "lamp" }
        assertTrue(lamp.stale)
        vm.tap(lamp)
        vm.send("shutter", Command.Open)
        assertEquals(emptyList(), source.sent)
        assertEquals(listOf<HomeEvent>(HomeEvent.WaitingForConnection, HomeEvent.WaitingForConnection), events)
    }

    @Test
    fun `a live state is not stale and sends`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val lamp = vm.state.value.cards.first { it.id == "lamp" }
        assertFalse(lamp.stale)
        vm.tap(lamp)
        assertEquals(listOf("lamp" to Command.TurnOff), source.sent)
    }
```

Check the exact name of the cover open command in `core/model/.../Command.kt` (for example `Command.Open`, or `Command.SetPosition(100)`) and use whatever exists. Add `import kotlin.test.assertTrue` if it's missing.

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeViewModelTest*'`
Expected: compilation FAIL (`stale`, `WaitingForConnection` unresolved).

- [ ] **Step 3: Implement**

- `HomeModels.kt`:
  - In `CardUi`, add `val stale: Boolean = false,` after `pending`.
  - In `HomeEvent`, add `data object WaitingForConnection : HomeEvent`.
- `HomeUiBuilder.card(...)`: add `stale = state?.stale ?: false,` after `pending = …`.
- `HomeViewModel`:

```kotlin
    fun tap(card: CardUi) {
        when (card.tap) {
            // Absolute, so "Retry" after a late confirmation repeats the intent instead of flipping it back.
            TapAction.TOGGLE -> send(card.id, if (card.on) Command.TurnOff else Command.TurnOn())
            TapAction.PRESS -> send(card.id, Command.Press)
            TapAction.OPEN_SHEET, TapAction.NONE -> Unit
        }
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            // A cached value may be outdated: never act on it (entity cache §3.6).
            if (state.value.card(id)?.stale == true) return@launch _events.send(HomeEvent.WaitingForConnection)
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(HomeEvent.Rejected(id, result.reason))
        }
    }
```

  Use whatever the ViewModel's UI state property is called (the tests use `vm.state`).
- `HomeEventsEffect.kt`: add the branch `HomeEvent.WaitingForConnection -> snackbar.showSnackbar(resources.getString(R.string.home_waiting_connection))`.
- `EntityCard.kt`:
  - Change `.alpha(if (card.hidden) 0.6f else 1f)` to `.alpha(if (card.hidden || card.stale) 0.6f else 1f)`.
  - In `semantics`, add `if (card.stale) stateDescription = staleText`, placed after the toggle line so it wins, with `val staleText = stringResource(R.string.card_stale)`.
- `HomeScreen.kt`: the hero card takes the same `card.stale` alpha (0.6f) on its root modifier, and the same `stateDescription`.
- Strings, EN (`values/strings.xml`):

```xml
    <string name="home_waiting_connection">Waiting for the connection</string>
    <string name="card_stale">Last known value</string>
```

  FR (`values-fr/strings.xml`):

```xml
    <string name="home_waiting_connection">En attente de la connexion</string>
    <string name="card_stale">Dernière valeur connue</string>
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (unit tests, lint, and kover if configured).

- [ ] **Step 6: Commit**

```bash
git add feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffect.kt feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt feature/home/src/main/res/values/strings.xml feature/home/src/main/res/values-fr/strings.xml feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModelTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): dim stale cards and refuse commands until live data arrives (EN/FR)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Phone check (the user runs it on the real house)**
1. Open the app at home and wait for the house to load. Kill the app.
2. Turn Wi-Fi off and relaunch. The whole house appears at once, dimmed, and the pill says Offline or Reconnecting. Tapping a light shows "Waiting for the connection".
3. Turn Wi-Fi on. The cards refresh in place and the dimming lifts within about 2 s of connecting.
