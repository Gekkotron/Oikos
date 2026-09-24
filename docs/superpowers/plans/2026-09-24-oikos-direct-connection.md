# Oikos Direct Connection + Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the always-on demo house with a real connection. A fresh install onboards by QR code or manual entry, tests the connection while showing discovered devices, then lands on Home connected directly to the LAN broker. The demo stays available as a profile.

**Architecture:** This plan adds three pieces behind the existing `MqttTransport` interface.
- `:core:transport` gets:
  - `DirectMqttTransport` (HiveMQ, MQTT 3.1.1);
  - a `ManagedTransport` lifecycle contract;
  - `ActiveTransport`, one long-lived transport that runs whatever the saved profile asks for and pauses 30 s after the app goes to the background.
- The new `:core:data` stores the connection profile in a JSON DataStore. The broker password and gateway key are encrypted with an Android Keystore AES key. It also parses the QR payload.
- The new `:feature:onboarding` has four steps: Welcome, Scan (CameraX + ML Kit), Manual, and Connect & discover.
- `:app` maps the profile to a `TransportSpec` and rebuilds the discovery source when the prefix changes. It shows onboarding or Home depending on the profile state.

`MqttDiscoverySource` and the Home UI model do not change.

**Tech Stack:**
- Unchanged: Kotlin 2.4.20, AGP 9.4.1, Compose BOM, Navigation 3, Hilt 2.60.1 with KSP.
- Added:
  - HiveMQ MQTT client 1.4.0;
  - Moquette 0.17 (tests only);
  - DataStore 1.2.1;
  - CameraX 1.6.2 (`camera-camera2`, `camera-lifecycle`, `camera-compose`);
  - ML Kit `barcode-scanning` 17.3.0 (bundled model);
  - `lifecycle-process` (catalog `lifecycle` version);
  - `androidx.test:runner` 1.7.0 (instrumented test).
- Testing: JUnit Jupiter for JVM modules, JUnit 4 + Robolectric for Android modules.

**Spec:** `docs/superpowers/specs/2026-09-24-oikos-direct-connection-design.md`, which refines the parent spec `docs/superpowers/specs/2026-09-24-oikos-design.md`, §4.5, §4.7, §5.2 and §8.

## Global Constraints

- Package root `com.gekkotron.oikos`. Application id `com.gekkotron.oikos`; debug adds `.debug`.
- minSdk 28, targetSdk/compileSdk 36, JVM 17. One Kotlin version for every Kotlin plugin.
- AGP 9 compiles Kotlin itself. Never apply `org.jetbrains.kotlin.android`.
- Robolectric runs at **SDK 34**. Every Android module has `src/test/resources/robolectric.properties` containing `sdk=34`.
- Release unit-test tasks are disabled by the convention plugins. Run Android tests with `testDebugUnitTest` or `check`.
- Module dependency rule (parent spec §4.1, this spec §2):
  - `:feature:onboarding → :core:data, :core:designsystem` only. Never `:core:transport`, `:core:source` or `:core:demo`.
  - `:feature:home → :core:source, :core:designsystem` (unchanged).
  - `:core:transport` stays pure JVM. It never depends on `:core:data` or `:core:demo`.
  - `:core:data` depends on no other Oikos module.
  - Only `:app` wires everything. No module depends on `:app` or on a feature module.
- **Secrets** (the broker password and the gateway key):
  - never logged;
  - never in plaintext on disk;
  - never in a `NavKey` or `SavedStateHandle`.
  - Every type that holds one overrides `toString()` to mask it.
- Nothing house-specific in code (parent spec §1.7). No IPs or hostnames except in tests (`127.0.0.1`, `192.168.1.91` as a sample private address, `*.example`).
- UI strings exist in English (`values/`) and French (`values-fr/`) in the same commit that adds them.
- Colours and fonts come only from `OikosTheme` tokens. There are no hard-coded colours in feature code.
- Accessibility:
  - touch targets are at least 48 dp;
  - screens scroll, so 200 % font scale never clips;
  - errors and status changes are announced (`liveRegion = Polite`).
- Attribution: every byline or author field is `Gekkotron`. Every commit uses exactly this form (do not edit `.git/config`):
  ```bash
  git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
  ```
- Work on the branch `feat/direct-connection`, never directly on `master`.
- Coverage: every new module is added to the root Kover aggregation. Only camera and Keystore glue is excluded (Tasks 4 and 6).

## Review Focus

1. **A URL pasted into Host.** The user pastes `mqtt://192.168.1.91:1883` into the Host field. Expected: a field error ("host only, no mqtt:// or port"), and no connection attempt. Pinned in Task 5 (`host with scheme or port is an error`).
2. **Client-id collision between the connection test and the real transport.** Two clients with the same id make the broker kick one, and automatic reconnect turns that into an endless ping-pong. Expected: the test connection uses its own id, and it is closed before the profile is saved. Pinned in Task 8 (`setup client id differs from the app client id`) and Task 5 (`continue cancels the test before saving`).
3. **An invalid QR code, then a valid one.** The camera keeps seeing the bad code many times a second; then the user shows the right code. Expected: one error message, no flicker, and the valid code connects. Pinned in Task 5 (`a valid code after an invalid one connects`).
4. **"Set up connection again" while connected to a real broker.** Expected: the MQTT socket closes, with no leftover background connection. Pinned in Task 1 (`clearing the spec closes the transport`).
5. **A password with spaces, symbols and non-ASCII characters.** Expected: it survives storage and authenticates against the broker. Pinned in Task 4 (`unicode password round-trips`) and Task 2 (`good credentials connect`, which uses `p@ss wörd:1`).

---

## File Structure

```
gradle/libs.versions.toml                                   — + hivemq, moquette, datastore, test runner, camerax, mlkit, lifecycle-process
settings.gradle.kts                                         — + :core:data, :feature:onboarding
build.gradle.kts                                            — + kover(:core:data, :feature:onboarding)
build-logic/convention/src/main/kotlin/oikos.root.gradle.kts — + Kover excludes for Keystore/camera glue

core/transport/
  build.gradle.kts                                          — + hivemq (impl), moquette + hivemq (testFixtures)
  src/main/kotlin/…/transport/MqttTransport.kt              — + LinkState.Offline reason constants
  src/main/kotlin/…/transport/TransportSpec.kt              — NEW sealed Demo | Direct
  src/main/kotlin/…/transport/ManagedTransport.kt           — NEW start()/close()
  src/main/kotlin/…/transport/ActiveTransport.kt            — NEW profile-driven delegating transport
  src/main/kotlin/…/transport/DirectFailure.kt              — NEW failure kinds + classifyFailure()
  src/main/kotlin/…/transport/DirectMqttTransport.kt        — NEW HiveMQ transport
  src/testFixtures/kotlin/…/transport/testing/FakeMqttTransport.kt    — + retain()
  src/testFixtures/kotlin/…/transport/testing/FakeManagedTransport.kt — NEW
  src/testFixtures/kotlin/…/transport/testing/EmbeddedBroker.kt       — NEW Moquette wrapper + peer client
  src/test/kotlin/…/transport/ActiveTransportTest.kt, DirectFailureTest.kt, DirectMqttTransportTest.kt — NEW

core/demo/src/main/kotlin/…/demo/DemoBroker.kt              — implements ManagedTransport
core/demo/src/test/kotlin/…/demo/DemoBrokerLifecycleTest.kt — NEW

core/data/                                                  — NEW Android library
  build.gradle.kts
  src/main/kotlin/…/data/ConnectionProfile.kt               — LocalBroker, GatewayConfig, ConnectionProfile, ProfileState
  src/main/kotlin/…/data/OnboardingPayload.kt               — QR parser, PayloadError, ParseResult
  src/main/kotlin/…/data/LocalHosts.kt                      — isLikelyLocalHost()
  src/main/kotlin/…/data/SecretStore.kt                     — interface + SecretUnavailable
  src/main/kotlin/…/data/KeystoreSecretStore.kt             — Android Keystore AES-GCM
  src/main/kotlin/…/data/StoredProfile.kt                   — internal serializable model + DataStore serializer
  src/main/kotlin/…/data/ProfileRepository.kt               — state/save/clear/installId
  src/test/…  OnboardingPayloadTest, LocalHostsTest, ProfileRepositoryTest, FakeSecretStore
  src/androidTest/…  KeystoreSecretStoreTest

feature/onboarding/                                         — NEW Android library (Compose, Hilt)
  build.gradle.kts, src/main/AndroidManifest.xml (CAMERA)
  src/main/kotlin/…/feature/onboarding/ConnectionTester.kt  — contract + TestState/TestFailure
  src/main/kotlin/…/feature/onboarding/ManualForm.kt
  src/main/kotlin/…/feature/onboarding/OnboardingViewModel.kt (+ OnboardingUiState, Step)
  src/main/kotlin/…/feature/onboarding/ui/OnboardingRoute.kt, OnboardingLayout.kt, WelcomeScreen.kt,
      ScanScreen.kt, QrCamera.kt, ManualScreen.kt, ConnectScreen.kt, Messages.kt, SecureWindow.kt
  src/main/res/values{,-fr}/strings.xml
  src/test/…  ManualFormTest, OnboardingViewModelTest, OnboardingScreensTest, fakes

feature/home/src/main/kotlin/…/home/ui/LinkPill.kt, LinkSheet.kt (NEW), HomeScreen.kt — tappable pill + "Set up connection again"
feature/home/src/main/res/values{,-fr}/strings.xml

app/
  build.gradle.kts                                          — + data, onboarding, transport, lifecycle-process, packaging excludes
  src/main/AndroidManifest.xml                              — + INTERNET, dataExtractionRules
  src/main/res/xml/data_extraction_rules.xml                — NEW
  src/main/kotlin/…/OikosApplication.kt                     — foreground tracking
  src/main/kotlin/…/AppViewModel.kt                         — NEW profile state + setUpAgain
  src/main/kotlin/…/connection/TransportSpecs.kt            — NEW profile → spec, client ids
  src/main/kotlin/…/connection/EntitySourceSession.kt       — NEW
  src/main/kotlin/…/connection/DirectConnectionTester.kt    — NEW
  src/main/kotlin/…/di/AppModule.kt                         — rewritten
  src/main/kotlin/…/di/DataModule.kt                        — NEW
  src/main/kotlin/…/navigation/OikosNavHost.kt              — profile-driven root
  src/test/…  TransportSpecsTest, EntitySourceSessionTest, DirectConnectionTesterTest, TestDataModule, MainActivityTest (rewritten)
```

`…` stands for `com/gekkotron/oikos`.

---

### Task 1: `TransportSpec`, `ManagedTransport` and `ActiveTransport`

**Files:**
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/MqttTransport.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TransportSpec.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/ManagedTransport.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/ActiveTransport.kt`
- Modify: `core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeMqttTransport.kt`
- Create: `core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeManagedTransport.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/ActiveTransportTest.kt`
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoBroker.kt`
- Test: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerLifecycleTest.kt`

**Interfaces:**
- Consumes: the existing `MqttTransport`, `LinkState`, `MqttMessage`, `PublishFailed` and `FakeMqttTransport`.
- Produces:
  - `sealed interface TransportSpec { data object Demo; data class Direct(host: String, port: Int, tls: Boolean, ws: Boolean, username: String?, password: String?, clientId: String) }`
  - `interface ManagedTransport : MqttTransport { fun start(); suspend fun close() }`
  - `class ActiveTransport(specs: Flow<TransportSpec?>, factory: (TransportSpec) -> ManagedTransport, scope: CoroutineScope, backgroundGrace: Duration = 30.seconds) : MqttTransport`, with `fun setForeground(value: Boolean)`
  - `LinkState.Offline.NOT_CONFIGURED`, `PAUSED`, `REJECTED`, `CLOSED` (String constants)
  - testFixtures: `class FakeManagedTransport(val spec: TransportSpec, val fake: FakeMqttTransport = FakeMqttTransport())`, with `started` and `closed`; `FakeMqttTransport.retain(topic, payload)`
  - `DemoBroker : ManagedTransport`; `startTicking()` now returns `Job`

- [ ] **Step 1: Create the branch**

```bash
git switch -c feat/direct-connection
```

- [ ] **Step 2: Add the reason constants, `TransportSpec` and `ManagedTransport`**

In `MqttTransport.kt`, replace the `Offline` line inside `LinkState` with:

```kotlin
    data class Offline(val reason: String) : LinkState {
        companion object {
            const val NOT_CONFIGURED = "not configured"
            const val PAUSED = "paused"
            const val REJECTED = "broker rejected credentials"
            const val CLOSED = "closed"
        }
    }
```

`TransportSpec.kt`:

```kotlin
package com.gekkotron.oikos.transport

/** What [ActiveTransport] should run (spec §2). Plan 2 adds `Gateway`. */
sealed interface TransportSpec {
    data object Demo : TransportSpec

    data class Direct(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val ws: Boolean,
        val username: String?,
        val password: String?,
        val clientId: String,
    ) : TransportSpec {
        override fun toString(): String =
            "Direct($host:$port, tls=$tls, ws=$ws, user=$username, password=${if (password == null) "none" else "***"}, clientId=$clientId)"
    }
}
```

`ManagedTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport

/** A transport with an explicit lifetime, owned by [ActiveTransport]. */
interface ManagedTransport : MqttTransport {
    /** Starts connecting. Callers subscribe to [messages] first. */
    fun start()

    /** Disconnects for good. Safe to call when not connected. */
    suspend fun close()
}
```

- [ ] **Step 3: Extend the test fixtures**

Add this method to `FakeMqttTransport` (below `setLink`):

```kotlin
    /** Seeds a retained message without delivering it, as if it was published before anyone connected. */
    fun retain(topic: String, payload: String) {
        retainedStore[topic] = payload.encodeToByteArray()
    }
```

`FakeManagedTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec

/** A [FakeMqttTransport] with a recorded lifetime, created by an `ActiveTransport` factory in tests. */
class FakeManagedTransport(
    val spec: TransportSpec,
    val fake: FakeMqttTransport = FakeMqttTransport(),
) : ManagedTransport, MqttTransport by fake {
    var started = false
        private set
    var closed = false
        private set

    override fun start() {
        started = true
    }

    override suspend fun close() {
        closed = true
    }
}
```

- [ ] **Step 4: Write the failing `ActiveTransport` tests**

`ActiveTransportTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTransportTest {
    private val created = mutableListOf<FakeManagedTransport>()
    private val direct = TransportSpec.Direct("broker.example", 1883, tls = false, ws = false, username = null, password = null, clientId = "oikos-test")

    private fun TestScope.active(
        specs: Flow<TransportSpec?>,
        seed: FakeManagedTransport.() -> Unit = {},
    ) = ActiveTransport(specs, { FakeManagedTransport(it).apply(seed).also(created::add) }, backgroundScope)

    @Test
    fun `no spec is offline and not configured`() = runTest {
        val t = active(flowOf(null))
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.NOT_CONFIGURED), t.link.value)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `a spec starts its transport and mirrors its link`() = runTest {
        val t = active(flowOf(TransportSpec.Demo))
        runCurrent()
        assertEquals(1, created.size)
        assertTrue(created[0].started)
        created[0].fake.setLink(LinkState.Reconnecting(2))
        runCurrent()
        assertEquals(LinkState.Reconnecting(2), t.link.value)
    }

    @Test
    fun `switching spec closes the old transport and replays subscriptions`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val t = active(specs)
        runCurrent()
        t.setSubscriptions(setOf("homeassistant/#"))
        specs.value = direct
        runCurrent()
        assertTrue(created[0].closed)
        assertEquals(direct, created[1].spec)
        assertEquals(setOf("homeassistant/#"), created[1].fake.subscriptions)
    }

    @Test
    fun `clearing the spec closes the transport`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(direct)
        val t = active(specs)
        runCurrent()
        specs.value = null
        runCurrent()
        assertTrue(created.single().closed)
        assertEquals(LinkState.Offline(LinkState.Offline.NOT_CONFIGURED), t.link.value)
    }

    @Test
    fun `messages come only from the current transport`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(TransportSpec.Demo)
        val t = active(specs)
        val received = mutableListOf<String>()
        backgroundScope.launch { t.messages().collect { received += it.topic } }
        t.setSubscriptions(setOf("#"))
        runCurrent()
        val first = created[0]
        specs.value = direct
        runCurrent()
        first.fake.deliver("old/topic", "x")
        created[1].fake.deliver("new/topic", "y")
        runCurrent()
        assertEquals(listOf("new/topic"), received)
    }

    @Test
    fun `retained messages replayed on start reach collectors`() = runTest {
        val specs = MutableStateFlow<TransportSpec?>(null)
        val t = active(specs) { fake.retain("homeassistant/light/a/config", "{}") }
        val received = mutableListOf<MqttMessage>()
        backgroundScope.launch { t.messages().collect { received += it } }
        t.setSubscriptions(setOf("homeassistant/#"))
        specs.value = TransportSpec.Demo
        runCurrent()
        assertEquals(listOf("homeassistant/light/a/config"), received.map { it.topic })
        assertTrue(received.single().retained)
    }

    @Test
    fun `publish without a transport fails`() = runTest {
        val t = active(flowOf(null))
        runCurrent()
        assertFailsWith<PublishFailed> { t.publish("a/b", byteArrayOf(1)) }
    }

    @Test
    fun `publish goes to the current transport`() = runTest {
        val t = active(flowOf(direct))
        runCurrent()
        t.publish("lamp/set", "ON".encodeToByteArray())
        assertEquals(listOf("lamp/set" to "ON"), created.single().fake.publishedStrings())
    }

    @Test
    fun `background closes after the grace period and foreground restarts`() = runTest {
        val t = active(flowOf(TransportSpec.Demo))
        runCurrent()
        t.setForeground(false)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(created[0].closed)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(created[0].closed)
        assertEquals(LinkState.Offline(LinkState.Offline.PAUSED), t.link.value)
        t.setForeground(true)
        runCurrent()
        assertEquals(2, created.size)
        assertTrue(created[1].started)
    }

    @Test
    fun `returning within the grace period keeps the transport`() = runTest {
        active(flowOf(TransportSpec.Demo)).also { runCurrent() }.apply {
            setForeground(false)
            advanceTimeBy(10.seconds)
            setForeground(true)
            advanceTimeBy(60.seconds)
            runCurrent()
        }
        assertFalse(created.single().closed)
    }
}
```

- [ ] **Step 5: Run the tests and watch them fail**

Run: `./gradlew :core:transport:test --tests '*ActiveTransportTest*'`
Expected: compilation FAILS with `Unresolved reference: ActiveTransport`.

- [ ] **Step 6: Implement `ActiveTransport`**

`ActiveTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one [MqttTransport] the rest of the app sees (spec §3.3). It runs the transport for the current
 * [TransportSpec], replays subscriptions onto each new one, and pauses [backgroundGrace] after the app
 * leaves the foreground. Switching is break-before-make; Plan 2's selector adds make-before-break.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTransport(
    specs: Flow<TransportSpec?>,
    private val factory: (TransportSpec) -> ManagedTransport,
    private val scope: CoroutineScope,
    backgroundGrace: Duration = 30.seconds,
) : MqttTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Offline(LinkState.Offline.NOT_CONFIGURED))
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val out = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    private val foreground = MutableStateFlow(true)
    private val mutex = Mutex()
    private var subscriptions: Set<String> = emptySet()
    private var current: Running? = null

    private class Running(val transport: ManagedTransport, val jobs: List<Job>)

    private sealed interface Target {
        data object Paused : Target
        data object NotConfigured : Target
        data class Run(val spec: TransportSpec) : Target
    }

    init {
        val active = foreground.transformLatest { isForeground ->
            if (!isForeground) delay(backgroundGrace)
            emit(isForeground)
        }
        scope.launch {
            combine(specs, active) { spec, isActive ->
                when {
                    !isActive -> Target.Paused
                    spec == null -> Target.NotConfigured
                    else -> Target.Run(spec)
                }
            }.distinctUntilChanged().collect { apply(it) }
        }
    }

    override fun messages(): SharedFlow<MqttMessage> = out.asSharedFlow()

    /** Driven by the process lifecycle. Going to the background pauses after the grace period. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    override suspend fun setSubscriptions(filters: Set<String>) {
        val transport = mutex.withLock {
            subscriptions = filters
            current?.transport
        }
        transport?.setSubscriptions(filters)
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        val transport = mutex.withLock { current?.transport } ?: throw PublishFailed("not connected")
        transport.publish(topic, payload, qos, retain)
    }

    private suspend fun apply(target: Target) {
        val old = mutex.withLock { current.also { current = null } }
        if (old != null) {
            old.jobs.forEach { it.cancel() }
            old.transport.close()
        }
        when (target) {
            Target.Paused -> _link.value = LinkState.Offline(LinkState.Offline.PAUSED)
            Target.NotConfigured -> _link.value = LinkState.Offline(LinkState.Offline.NOT_CONFIGURED)
            is Target.Run -> start(target.spec)
        }
    }

    private suspend fun start(spec: TransportSpec) {
        val transport = factory(spec)
        // Undispatched so both collectors are subscribed before start() or a replay can deliver anything.
        val jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.messages().collect { out.emit(it) } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.link.collect { _link.value = it } },
        )
        val filters = mutex.withLock {
            current = Running(transport, jobs)
            subscriptions
        }
        transport.start()
        if (filters.isNotEmpty()) transport.setSubscriptions(filters)
    }
}
```

- [ ] **Step 7: Run the tests and watch them pass**

Run: `./gradlew :core:transport:test`
Expected: PASS. That includes the existing `FakeMqttTransportTest` and `TopicFilterTest`.

- [ ] **Step 8: Write the failing `DemoBroker` lifecycle test**

`DemoBrokerLifecycleTest.kt`:

```kotlin
package com.gekkotron.oikos.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoBrokerLifecycleTest {
    @Test
    fun `start ticks live sensors and close stops them`() = runTest {
        val broker = DemoBroker(backgroundScope)
        val received = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { broker.messages().collect { received += it.topic } }
        broker.setSubscriptions(setOf("#"))
        runCurrent()
        received.clear() // drop the retained replay; only ticks count from here

        broker.start()
        advanceTimeBy(3.5.seconds)
        runCurrent()
        val afterStart = received.size
        assertTrue(afterStart > 0, "expected sensor ticks after start()")

        broker.close()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(afterStart, received.size)
    }
}
```

Run: `./gradlew :core:demo:test --tests '*DemoBrokerLifecycleTest*'`
Expected: compilation FAILS with `Unresolved reference: start`.

- [ ] **Step 9: Make `DemoBroker` a `ManagedTransport`**

In `DemoBroker.kt`:
- Change the import `com.gekkotron.oikos.transport.MqttTransport` to `com.gekkotron.oikos.transport.ManagedTransport`.
- Add `import kotlinx.coroutines.Job`.
- Change the class header to `) : ManagedTransport {`.
- Add a field after `private var subscriptions`: `private var ticking: Job? = null`.
- Replace `startTicking` with:

```kotlin
    override fun start() {
        if (ticking == null) ticking = startTicking()
    }

    override suspend fun close() {
        ticking?.cancel()
        ticking = null
    }

    /** Publishes live sensor updates every [interval] until cancelled. */
    fun startTicking(interval: Duration = 3.seconds): Job = scope.launch {
        while (true) {
            delay(interval)
            for (m in house.tick()) receive(m.topic, m.payload.encodeToByteArray(), retain = true)
        }
    }
```

`AppModule` still calls `startTicking()` and compiles unchanged; Task 8 replaces it.

- [ ] **Step 10: Run the tests**

Run: `./gradlew :core:transport:test :core:demo:test :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add core/transport core/demo
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(transport): TransportSpec, ManagedTransport and profile-driven ActiveTransport" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `DirectMqttTransport` (HiveMQ), tested against embedded Moquette

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/transport/build.gradle.kts`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/DirectFailure.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/DirectMqttTransport.kt`
- Create: `core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/EmbeddedBroker.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/DirectFailureTest.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/DirectMqttTransportTest.kt`

**Interfaces:**
- Consumes: `TransportSpec.Direct`, `ManagedTransport`, `LinkState.Offline.REJECTED` and `CLOSED` (Task 1).
- Produces:
  - `sealed interface DirectFailure { data object Unreachable; data object Rejected; data class Tls(message); data class Other(message) }`
  - `fun classifyFailure(error: Throwable): DirectFailure`
  - `class DirectMqttTransport(spec: TransportSpec.Direct, scope: CoroutineScope, reconnectInitial: Duration = 1.seconds, reconnectMax: Duration = 30.seconds, publishTimeout: Duration = 10.seconds) : ManagedTransport`, with `val failure: StateFlow<DirectFailure?>`
  - testFixtures:
    - `class EmbeddedBroker(credentials: Pair<String, String>? = null, websocket: Boolean = false) : AutoCloseable`, with `port`, `wsPort`, `start()`, `stop()` and `peer(): Mqtt3BlockingClient`
    - `fun freePort(): Int`

- [ ] **Step 1: Add the dependencies**

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
hivemq = "1.4.0"
moquette = "0.17"
```

Under `[libraries]`:

```toml
hivemq-mqtt-client = { module = "com.hivemq:hivemq-mqtt-client", version.ref = "hivemq" }
moquette-broker = { module = "io.moquette:moquette-broker", version.ref = "moquette" }
```

Replace `core/transport/build.gradle.kts` with:

```kotlin
plugins {
    id("oikos.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.hivemq.mqtt.client)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.hivemq.mqtt.client)
    testFixturesImplementation(libs.moquette.broker)
}
```

**Known risk:** HiveMQ and Moquette both bring Netty 4.1, and Gradle resolves the newer one. If Moquette fails at startup with `NoSuchMethodError` or `ClassNotFoundException` in `io.netty`, run `./gradlew :core:transport:dependencies --configuration testRuntimeClasspath | grep netty`. Then pin the Netty version HiveMQ asks for with a `constraints { testFixturesImplementation("io.netty:netty-handler:<that version>") }` block.

- [ ] **Step 2: Write `EmbeddedBroker`**

`EmbeddedBroker.kt`:

```kotlin
package com.gekkotron.oikos.transport.testing

import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import java.net.ServerSocket
import java.util.Properties
import java.util.UUID

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** An in-process Moquette broker on 127.0.0.1 for transport tests. [stop] then [start] simulates a broker restart. */
class EmbeddedBroker(
    private val credentials: Pair<String, String>? = null,
    private val websocket: Boolean = false,
) : AutoCloseable {
    val port: Int = freePort()
    val wsPort: Int = freePort()
    private var server: Server? = null

    fun start() {
        val props = Properties().apply {
            setProperty("host", "127.0.0.1")
            setProperty("port", port.toString())
            setProperty("websocket_port", if (websocket) wsPort.toString() else "disabled")
            setProperty("allow_anonymous", (credentials == null).toString())
            setProperty("persistence_enabled", "false")
            setProperty("telemetry_enabled", "false")
        }
        val authenticator = credentials?.let { (user, pass) ->
            IAuthenticator { _, username, password -> username == user && password != null && String(password, Charsets.UTF_8) == pass }
        }
        server = Server().apply { startServer(MemoryConfig(props), emptyList(), null, authenticator, null) }
    }

    fun stop() {
        server?.stopServer()
        server = null
    }

    override fun close() = stop()

    /** A plain HiveMQ client acting as another device on the broker. Call `disconnect()` when done. */
    fun peer(): Mqtt3BlockingClient {
        var builder = Mqtt3Client.builder().identifier("peer-${UUID.randomUUID()}").serverHost("127.0.0.1").serverPort(port)
        if (credentials != null) {
            builder = builder.simpleAuth().username(credentials.first).password(credentials.second.encodeToByteArray()).applySimpleAuth()
        }
        return builder.buildBlocking().also { it.connect() }
    }
}
```

If a Moquette property key is rejected at startup, look up the matching constant in `io.moquette.broker.config.IConfig` (0.17) and use its value.

- [ ] **Step 3: Write the failing tests**

`DirectFailureTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals

class DirectFailureTest {
    @Test
    fun `refused and unknown hosts are unreachable`() {
        assertEquals(DirectFailure.Unreachable, classifyFailure(ConnectException("Connection refused")))
        assertEquals(DirectFailure.Unreachable, classifyFailure(RuntimeException("wrapped", UnknownHostException("nas.lan"))))
    }

    @Test
    fun `TLS problems anywhere in the chain are TLS`() {
        val failure = classifyFailure(RuntimeException("connect failed", SSLHandshakeException("PKIX path building failed")))
        assertEquals(DirectFailure.Tls("PKIX path building failed"), failure)
    }

    @Test
    fun `anything else keeps its message`() {
        assertEquals(DirectFailure.Other("boom"), classifyFailure(IllegalStateException("boom")))
    }
}
```

`DirectMqttTransportTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.EmbeddedBroker
import com.gekkotron.oikos.transport.testing.freePort
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DirectMqttTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val brokers = mutableListOf<EmbeddedBroker>()
    private val transports = mutableListOf<DirectMqttTransport>()

    @AfterEach
    fun tearDown() = runBlocking {
        transports.forEach { it.close() }
        brokers.forEach { it.close() }
        scope.cancel()
    }

    private fun broker(credentials: Pair<String, String>? = null, websocket: Boolean = false) =
        EmbeddedBroker(credentials, websocket).also { it.start(); brokers += it }

    private fun transport(port: Int, user: String? = null, pass: String? = null, ws: Boolean = false) = DirectMqttTransport(
        TransportSpec.Direct("127.0.0.1", port, tls = false, ws = ws, username = user, password = pass, clientId = "oikos-test-${transports.size}"),
        scope,
        reconnectInitial = 200.milliseconds,
        reconnectMax = 1.seconds,
    ).also { transports += it }

    private suspend fun MqttTransport.awaitConnected() = withTimeout(10.seconds) { link.first { it is LinkState.Connected } }

    /** Records messages; subscribed before anything can arrive. */
    private fun MqttTransport.record(): MutableList<MqttMessage> {
        val list = Collections.synchronizedList(mutableListOf<MqttMessage>())
        scope.launch(start = CoroutineStart.UNDISPATCHED) { messages().collect { list += it } }
        return list
    }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(10.seconds) { while (!check()) delay(20) }

    @Test
    fun `connects and reports Connected(DIRECT)`() = runBlocking {
        val t = transport(broker().port)
        t.start()
        assertEquals(LinkState.Connected(TransportMode.DIRECT), t.awaitConnected())
    }

    @Test
    fun `good credentials connect`() = runBlocking {
        val b = broker(credentials = "oikos" to "p@ss wörd:1")
        val t = transport(b.port, "oikos", "p@ss wörd:1")
        t.start()
        t.awaitConnected()
    }

    @Test
    fun `bad credentials go Offline without retrying`() = runBlocking {
        val b = broker(credentials = "oikos" to "secret")
        val t = transport(b.port, "oikos", "wrong")
        t.start()
        val offline = withTimeout(10.seconds) {
            t.link.first { it is LinkState.Offline && it.reason == LinkState.Offline.REJECTED }
        }
        assertEquals(DirectFailure.Rejected, t.failure.value)
        delay(1500) // longer than reconnectMax: a retry would have changed the link
        assertEquals(offline, t.link.value)
    }

    @Test
    fun `unreachable broker reports Unreachable and keeps retrying`() = runBlocking {
        val t = transport(freePort())
        t.start()
        eventually { t.failure.value == DirectFailure.Unreachable }
        withTimeout(10.seconds) { t.link.first { it is LinkState.Reconnecting } }
        Unit
    }

    @Test
    fun `receives retained messages on subscribe`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        peer.publishWith().topic("homeassistant/light/a/config").payload("{}".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("homeassistant/#"))
        eventually { got.any { it.topic == "homeassistant/light/a/config" } }
        assertTrue(got.first().retained)
        peer.disconnect()
    }

    @Test
    fun `subscription diff unsubscribes removed filters`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("a/#", "b/#"))
        t.setSubscriptions(setOf("b/#"))
        val peer = b.peer()
        peer.publishWith().topic("a/1").payload("x".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send()
        peer.publishWith().topic("b/1").payload("y".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send()
        eventually { got.any { it.topic == "b/1" } }
        delay(300)
        assertTrue(got.none { it.topic == "a/1" })
        peer.disconnect()
    }

    @Test
    fun `resubscribes after the broker restarts`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("x/#"))
        b.stop()
        withTimeout(10.seconds) { t.link.first { it is LinkState.Reconnecting } }
        b.start()
        t.awaitConnected()
        val peer = b.peer()
        // Retained, so it arrives even if our resubscribe lands after the publish.
        peer.publishWith().topic("x/1").payload("1".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        eventually { got.any { it.topic == "x/1" } }
        peer.disconnect()
    }

    @Test
    fun `QoS 1 publish reaches another client`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        val seen = CompletableDeferred<String>()
        peer.toAsync().subscribeWith().topicFilter("cmd/#").callback { seen.complete(it.payloadAsBytes.decodeToString()) }.send().get()
        val t = transport(b.port)
        t.start()
        t.awaitConnected()
        t.publish("cmd/lamp", "ON".encodeToByteArray(), qos = 1)
        assertEquals("ON", withTimeout(5.seconds) { seen.await() })
        peer.disconnect()
    }

    @Test
    fun `publish while disconnected throws PublishFailed`() = runBlocking {
        val t = transport(broker().port) // never started
        assertFailsWith<PublishFailed> { t.publish("a/b", byteArrayOf(1)) }
        Unit
    }

    @Test
    fun `a burst of 1000 messages arrives complete and in order`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        val got = t.record()
        t.start()
        t.awaitConnected()
        t.setSubscriptions(setOf("burst/#"))
        val peer = b.peer()
        repeat(1000) { peer.publishWith().topic("burst/n").payload("$it".encodeToByteArray()).qos(MqttQos.AT_LEAST_ONCE).send() }
        eventually { got.size >= 1000 }
        assertEquals((0 until 1000).map { "$it" }, got.map { it.payloadString() })
        peer.disconnect()
    }

    @Test
    fun `connects over WebSocket on path mqtt`() = runBlocking {
        val b = broker(websocket = true)
        val t = transport(b.wsPort, ws = true)
        t.start()
        t.awaitConnected()
        Unit
    }

    @Test
    fun `close goes Offline and stops reconnecting`() = runBlocking {
        val b = broker()
        val t = transport(b.port)
        t.start()
        t.awaitConnected()
        t.close()
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), t.link.value)
        b.stop()
        b.start()
        delay(1500)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), t.link.value)
    }
}
```

- [ ] **Step 4: Run the tests and watch them fail**

Run: `./gradlew :core:transport:test --tests '*Direct*'`
Expected: compilation FAILS with `Unresolved reference: DirectMqttTransport` and `classifyFailure`.

- [ ] **Step 5: Implement `DirectFailure`**

`DirectFailure.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.hivemq.client.mqtt.exceptions.ConnectionFailedException
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a direct connection attempt failed; drives the onboarding messages (spec §5, §6). */
sealed interface DirectFailure {
    data object Unreachable : DirectFailure
    data object Rejected : DirectFailure
    data class Tls(val message: String) : DirectFailure
    data class Other(val message: String) : DirectFailure
}

fun classifyFailure(error: Throwable): DirectFailure {
    val chain = generateSequence(error) { it.cause }.take(10).toList()
    chain.firstNotNullOfOrNull { it as? Mqtt3ConnAckException }?.let { e ->
        return when (e.mqttMessage.returnCode) {
            Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD, Mqtt3ConnAckReturnCode.NOT_AUTHORIZED -> DirectFailure.Rejected
            else -> DirectFailure.Other("broker refused the connection: ${e.mqttMessage.returnCode}")
        }
    }
    chain.firstOrNull { it is SSLException }?.let { return DirectFailure.Tls(it.message ?: "TLS error") }
    val unreachable = chain.any {
        it is ConnectionFailedException || it is ConnectException || it is UnknownHostException ||
            it is NoRouteToHostException || it is SocketTimeoutException
    }
    return if (unreachable) DirectFailure.Unreachable else DirectFailure.Other(error.message ?: error.javaClass.simpleName)
}
```

- [ ] **Step 6: Implement `DirectMqttTransport`**

`DirectMqttTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * MQTT 3.1.1 to the local broker over TCP, TLS or WebSocket (spec §3.2). Clean session; our own
 * subscription set is re-applied after every reconnect. Messages are never dropped.
 */
class DirectMqttTransport(
    private val spec: TransportSpec.Direct,
    private val scope: CoroutineScope,
    reconnectInitial: Duration = 1.seconds,
    reconnectMax: Duration = 30.seconds,
    private val publishTimeout: Duration = 10.seconds,
) : ManagedTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Offline(NOT_STARTED))
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _failure = MutableStateFlow<DirectFailure?>(null)

    /** Why the last attempt failed; cleared on connect. Read by the onboarding connection test. */
    val failure: StateFlow<DirectFailure?> = _failure.asStateFlow()

    private val inbox = Channel<MqttMessage>(Channel.UNLIMITED)
    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 1024)
    private val mutex = Mutex()
    private var subscriptions: Set<String> = emptySet()
    private val attempts = AtomicInteger()

    @Volatile
    private var closed = false
    private var pump: Job? = null

    private val client: Mqtt3AsyncClient = buildClient(reconnectInitial, reconnectMax)

    override fun messages(): SharedFlow<MqttMessage> = _messages.asSharedFlow()

    override fun start() {
        check(pump == null) { "already started" }
        client.publishes(MqttGlobalPublishFilter.ALL) { p ->
            inbox.trySend(MqttMessage(p.topic.toString(), p.payloadAsBytes, p.isRetain))
        }
        // Wait for the first collector, so nothing is emitted into a SharedFlow nobody listens to.
        pump = scope.launch {
            _messages.subscriptionCount.first { it > 0 }
            for (message in inbox) _messages.emit(message)
        }
        _link.value = LinkState.Connecting
        client.connectWith().cleanSession(true).send() // the outcome arrives through the listeners
    }

    override suspend fun close() {
        closed = true
        try {
            client.disconnect().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not connected: nothing to disconnect; `closed` stops any pending reconnect.
        }
        pump?.cancel()
        inbox.close()
        _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    override suspend fun setSubscriptions(filters: Set<String>) {
        val (added, removed) = mutex.withLock {
            val diff = (filters - subscriptions) to (subscriptions - filters)
            subscriptions = filters
            diff
        }
        if (!client.state.isConnected) return // onConnected applies the whole set
        added.forEach { subscribe(it) }
        removed.forEach { unsubscribe(it) }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (!isValidPublishTopic(topic)) throw PublishFailed("invalid publish topic '$topic'")
        if (!client.state.isConnected) throw PublishFailed("not connected")
        val level = MqttQos.fromCode(qos) ?: throw PublishFailed("invalid QoS $qos")
        try {
            withTimeout(publishTimeout) {
                client.publishWith().topic(topic).payload(payload).qos(level).retain(retain).send().await()
            }
        } catch (e: TimeoutCancellationException) {
            throw PublishFailed("publish timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PublishFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onConnected() {
        if (closed) {
            client.disconnect()
            return
        }
        attempts.set(0)
        _failure.value = null
        _link.value = LinkState.Connected(TransportMode.DIRECT)
        scope.launch {
            // Clean session: the broker forgot our subscriptions, so re-apply all of them.
            val filters = mutex.withLock { subscriptions }
            filters.forEach { subscribe(it) }
        }
    }

    private fun onDisconnected(context: MqttClientDisconnectedContext) {
        val reconnector = context.reconnector
        reconnector.resubscribeIfSessionExpired(false) // onConnected re-applies our own set
        if (closed || context.source == MqttDisconnectSource.USER) {
            reconnector.reconnect(false)
            _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
            return
        }
        val failure = classifyFailure(context.cause)
        _failure.value = failure
        if (failure == DirectFailure.Rejected) {
            reconnector.reconnect(false)
            _link.value = LinkState.Offline(LinkState.Offline.REJECTED)
        } else {
            _link.value = LinkState.Reconnecting(attempts.incrementAndGet())
        }
    }

    private suspend fun subscribe(filter: String) = ignoreFailure {
        client.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send().await()
    }

    private suspend fun unsubscribe(filter: String) = ignoreFailure {
        client.unsubscribeWith().topicFilter(filter).send().await()
    }

    /** A failed (un)subscribe means the connection dropped; onConnected re-applies the set. */
    private suspend fun ignoreFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun buildClient(initial: Duration, max: Duration): Mqtt3AsyncClient {
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(spec.clientId)
            .serverHost(spec.host)
            .serverPort(spec.port)
            .automaticReconnect()
            .initialDelay(initial.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .maxDelay(max.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener { onConnected() }
            .addDisconnectedListener { onDisconnected(it) }
        if (spec.tls) builder = builder.sslWithDefaultConfig()
        if (spec.ws) builder = builder.webSocketConfig().serverPath(WS_PATH).applyWebSocketConfig()
        if (spec.username != null) {
            builder = builder.simpleAuth()
                .username(spec.username)
                .password(spec.password.orEmpty().encodeToByteArray())
                .applySimpleAuth()
        }
        return builder.buildAsync()
    }

    private companion object {
        const val WS_PATH = "mqtt"
        const val NOT_STARTED = "not started"
    }
}
```

- [ ] **Step 7: Run the tests and watch them pass**

Run: `./gradlew :core:transport:test`
Expected: PASS. `DirectMqttTransportTest` takes about 10–20 s because it runs real brokers.

If `bad credentials go Offline without retrying` fails because the link shows `Reconnecting`, the refused CONNACK reached `classifyFailure` wrapped in a type that isn't `Mqtt3ConnAckException`. Log `context.cause` once, then extend the `Rejected` branch to match the actual type. Do not loosen the test.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(transport): DirectMqttTransport over HiveMQ with failure classification, tested on embedded Moquette" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `:core:data` models, the QR payload parser and the local-host check

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/data/build.gradle.kts`
- Create: `core/data/src/test/resources/robolectric.properties`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/OnboardingPayload.kt`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/LocalHosts.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/OnboardingPayloadTest.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/LocalHostsTest.kt`

**Interfaces:**
- Consumes: nothing from other Oikos modules.
- Produces (package `com.gekkotron.oikos.data`):
  - `const val DEFAULT_PREFIX = "homeassistant"`
  - `data class LocalBroker(host: String, port: Int = 1883, tls: Boolean = false, ws: Boolean = false, username: String? = null, password: String? = null)`
  - `class GatewayConfig(url: String, key: ByteArray, brokerHost: String?)`, with `KEY_BYTES = 32`
  - `sealed interface ConnectionProfile { val prefix: String; data object Demo; data class Direct(local: LocalBroker, prefix: String = DEFAULT_PREFIX, gateway: GatewayConfig? = null, pushTokenTopic: String? = null) }`
  - `sealed interface ProfileState { Loading; None; Locked; data class Ready(profile) }`
  - `sealed interface PayloadError { NotOikosCode; data class UnsupportedVersion(version: Int); data class InvalidField(field: String) }`
  - `sealed interface ParseResult { data class Ok(profile: ConnectionProfile.Direct); data class Error(error: PayloadError) }`
  - `object OnboardingPayload { fun parse(text: String): ParseResult }`
  - `fun isLikelyLocalHost(host: String): Boolean`

- [ ] **Step 1: Create the module**

In `settings.gradle.kts`, add after `include(":core:source")`:

```kotlin
include(":core:data")
```

In the root `build.gradle.kts`, add inside `dependencies { … }`:

```kotlin
    kover(project(":core:data"))
```

`core/data/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.data"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
```

`core/data/src/test/resources/robolectric.properties`:

```
sdk=34
```

- [ ] **Step 2: Write the failing tests**

`OnboardingPayloadTest.kt`:

```kotlin
package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class OnboardingPayloadTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private fun ok(text: String) = assertIs<ParseResult.Ok>(OnboardingPayload.parse(text)).profile
    private fun error(text: String) = assertIs<ParseResult.Error>(OnboardingPayload.parse(text)).error

    @Test
    fun `full payload parses every field`() {
        val p = ok(
            """{"v":1,"local":{"host":"192.168.1.91","port":8883,"tls":true,"ws":false,"user":"oikos","pass":"s3cret"},
               "gateway":{"url":"https://gw.example","key":"$key","broker_host":"192.168.1.91"},"prefix":"ha",
               "push":{"token_topic":"fcm-push/tokens/{installId}"}}""",
        )
        assertEquals(LocalBroker("192.168.1.91", 8883, tls = true, ws = false, username = "oikos", password = "s3cret"), p.local)
        assertEquals("ha", p.prefix)
        assertEquals(GatewayConfig("https://gw.example", ByteArray(32) { it.toByte() }, "192.168.1.91"), p.gateway)
        assertEquals("fcm-push/tokens/{installId}", p.pushTokenTopic)
    }

    @Test
    fun `minimal payload uses defaults`() {
        val p = ok("""{"v":1,"local":{"host":"broker.local"}}""")
        assertEquals(LocalBroker("broker.local"), p.local)
        assertEquals(DEFAULT_PREFIX, p.prefix)
        assertNull(p.gateway)
        assertNull(p.pushTokenTopic)
    }

    @Test
    fun `unknown fields are ignored`() {
        ok("""{"v":1,"local":{"host":"h","colour":"blue"},"extra":42}""")
    }

    @Test
    fun `things that are not Oikos codes`() {
        assertEquals(PayloadError.NotOikosCode, error("https://example.com"))
        assertEquals(PayloadError.NotOikosCode, error("[1,2]"))
        assertEquals(PayloadError.NotOikosCode, error("""{"local":{"host":"h"}}"""))
        assertEquals(PayloadError.NotOikosCode, error("""{"v":"1","local":{"host":"h"}}"""))
    }

    @Test
    fun `newer versions are unsupported`() {
        assertEquals(PayloadError.UnsupportedVersion(2), error("""{"v":2,"local":{"host":"h"}}"""))
    }

    @Test
    fun `invalid local fields are named`() {
        assertEquals(PayloadError.InvalidField("local"), error("""{"v":1}"""))
        assertEquals(PayloadError.InvalidField("local.host"), error("""{"v":1,"local":{"host":"  "}}"""))
        assertEquals(PayloadError.InvalidField("local.port"), error("""{"v":1,"local":{"host":"h","port":0}}"""))
        assertEquals(PayloadError.InvalidField("local.port"), error("""{"v":1,"local":{"host":"h","port":70000}}"""))
        assertEquals(PayloadError.InvalidField("local.port"), error("""{"v":1,"local":{"host":"h","port":"1883"}}"""))
        assertEquals(PayloadError.InvalidField("local.tls"), error("""{"v":1,"local":{"host":"h","tls":"yes"}}"""))
    }

    @Test
    fun `invalid gateway fields are named`() {
        val base = """{"v":1,"local":{"host":"h"},"gateway":"""
        assertEquals(PayloadError.InvalidField("gateway.url"), error("""$base{"url":"http://gw.example","key":"$key"}}"""))
        assertEquals(PayloadError.InvalidField("gateway.key"), error("""$base{"url":"https://gw.example","key":"${key.drop(1)}"}}"""))
        assertEquals(PayloadError.InvalidField("gateway.key"), error("""$base{"url":"https://gw.example","key":"${key.dropLast(2)}zz"}}"""))
        assertEquals(PayloadError.InvalidField("gateway.key"), error("""$base{"url":"https://gw.example","key":"${key.dropLast(2)}"}}"""))
    }

    @Test
    fun `blank prefix is invalid`() {
        assertEquals(PayloadError.InvalidField("prefix"), error("""{"v":1,"local":{"host":"h"},"prefix":" "}"""))
    }

    @Test
    fun `toString never shows secrets`() {
        val p = ok("""{"v":1,"local":{"host":"h","user":"u","pass":"s3cret"},"gateway":{"url":"https://gw.example","key":"$key"}}""")
        assertFalse("s3cret" in p.toString())
        assertFalse(key in p.toString())
    }
}
```

`LocalHostsTest.kt`:

```kotlin
package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalHostsTest {
    @Test
    fun `private IPv4 and local names are local`() {
        listOf("192.168.1.91", "10.0.0.2", "172.16.0.1", "172.31.255.255", "broker.local", "NAS.lan", "broker.local.").forEach {
            assertTrue(isLikelyLocalHost(it), it)
        }
    }

    @Test
    fun `public and malformed hosts are not local`() {
        listOf("8.8.8.8", "172.32.0.1", "example.com", "192.168.1", "192.168.1.300", "").forEach {
            assertFalse(isLikelyLocalHost(it), it)
        }
    }
}
```

- [ ] **Step 3: Run the tests and watch them fail**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference: OnboardingPayload`.

- [ ] **Step 4: Implement the models**

`ConnectionProfile.kt`:

```kotlin
package com.gekkotron.oikos.data

const val DEFAULT_PREFIX = "homeassistant"

/** The local broker. [password] is a secret; [toString] masks it. */
data class LocalBroker(
    val host: String,
    val port: Int = 1883,
    val tls: Boolean = false,
    val ws: Boolean = false,
    val username: String? = null,
    val password: String? = null,
) {
    override fun toString(): String =
        "LocalBroker($host:$port, tls=$tls, ws=$ws, user=$username, password=${if (password == null) "none" else "***"})"
}

/** Remote gateway settings from the QR code. Stored now, used by Plan 2. [key] is a secret. */
class GatewayConfig(val url: String, key: ByteArray, val brokerHost: String?) {
    private val keyBytes = key.copyOf()
    val key: ByteArray get() = keyBytes.copyOf()

    init {
        require(keyBytes.size == KEY_BYTES) { "gateway key must be $KEY_BYTES bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is GatewayConfig && url == other.url && brokerHost == other.brokerHost && keyBytes.contentEquals(other.keyBytes)

    override fun hashCode(): Int = (url.hashCode() * 31 + (brokerHost?.hashCode() ?: 0)) * 31 + keyBytes.contentHashCode()

    override fun toString(): String = "GatewayConfig($url, brokerHost=$brokerHost, key=***)"

    companion object {
        const val KEY_BYTES = 32
    }
}

sealed interface ConnectionProfile {
    val prefix: String

    data object Demo : ConnectionProfile {
        override val prefix: String = DEFAULT_PREFIX
    }

    data class Direct(
        val local: LocalBroker,
        override val prefix: String = DEFAULT_PREFIX,
        val gateway: GatewayConfig? = null,
        val pushTokenTopic: String? = null,
    ) : ConnectionProfile
}

sealed interface ProfileState {
    data object Loading : ProfileState
    data object None : ProfileState

    /** A profile exists but its secrets could not be decrypted, for example after a restore to a new phone. */
    data object Locked : ProfileState
    data class Ready(val profile: ConnectionProfile) : ProfileState
}
```

- [ ] **Step 5: Implement the parser and the host check**

`OnboardingPayload.kt`:

```kotlin
package com.gekkotron.oikos.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

sealed interface PayloadError {
    data object NotOikosCode : PayloadError
    data class UnsupportedVersion(val version: Int) : PayloadError
    data class InvalidField(val field: String) : PayloadError
}

sealed interface ParseResult {
    data class Ok(val profile: ConnectionProfile.Direct) : ParseResult
    data class Error(val error: PayloadError) : ParseResult
}

/** The setup QR code, format `v:1` (parent spec §5.2, this spec §4.4). */
object OnboardingPayload {
    fun parse(text: String): ParseResult {
        val root = try {
            Json.parseToJsonElement(text.trim()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return error(PayloadError.NotOikosCode)
        val version = root.int("v") ?: return error(PayloadError.NotOikosCode)
        if (version != 1) return error(PayloadError.UnsupportedVersion(version))

        val local = root["local"] as? JsonObject ?: return invalid("local")
        val host = local.string("host")?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("local.host")
        val port = if ("port" in local) local.int("port")?.takeIf { it in 1..65535 } ?: return invalid("local.port") else 1883
        val broker = LocalBroker(
            host = host,
            port = port,
            tls = local.flag("tls") ?: return invalid("local.tls"),
            ws = local.flag("ws") ?: return invalid("local.ws"),
            username = local.string("user")?.takeIf { it.isNotEmpty() },
            password = local.string("pass")?.takeIf { it.isNotEmpty() },
        )

        val gateway = (root["gateway"] as? JsonObject)?.let { g ->
            val url = g.string("url")?.takeIf { it.startsWith("https://") && it.length > "https://".length }
                ?: return invalid("gateway.url")
            val key = g.string("key")?.let(::hexToBytes)?.takeIf { it.size == GatewayConfig.KEY_BYTES }
                ?: return invalid("gateway.key")
            GatewayConfig(url, key, g.string("broker_host")?.takeIf { it.isNotEmpty() })
        }
        val prefix = if ("prefix" in root) root.string("prefix")?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("prefix") else DEFAULT_PREFIX
        val tokenTopic = (root["push"] as? JsonObject)?.string("token_topic")?.takeIf { it.isNotEmpty() }
        return ParseResult.Ok(ConnectionProfile.Direct(broker, prefix, gateway, tokenTopic))
    }

    private fun error(e: PayloadError) = ParseResult.Error(e)
    private fun invalid(field: String) = ParseResult.Error(PayloadError.InvalidField(field))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    /** Absent means false; present but not a JSON boolean means invalid (null). */
    private fun JsonObject.flag(key: String): Boolean? =
        if (key !in this) false else (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(hex.length / 2) { i -> ((hex[2 * i].digitToInt(16) shl 4) or hex[2 * i + 1].digitToInt(16)).toByte() }
    }
}
```

`LocalHosts.kt`:

```kotlin
package com.gekkotron.oikos.data

/** True when [host] is clearly on the home network: a private IPv4 literal or a `.local`/`.lan` name (spec §4.5). */
fun isLikelyLocalHost(host: String): Boolean {
    val h = host.trim().lowercase().removeSuffix(".")
    if (h.endsWith(".local") || h.endsWith(".lan")) return true
    val parts = h.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val (a, b) = parts
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/data
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(data): connection profile model, QR setup payload parser and local-host check" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Profile storage: DataStore, `SecretStore` and `ProfileRepository`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/data/build.gradle.kts`, `build-logic/convention/src/main/kotlin/oikos.root.gradle.kts`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/SecretStore.kt`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/KeystoreSecretStore.kt`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt`
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ProfileRepository.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/FakeSecretStore.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt`
- Test: `core/data/src/androidTest/kotlin/com/gekkotron/oikos/data/KeystoreSecretStoreTest.kt`

**Interfaces:**
- Consumes: `ConnectionProfile`, `LocalBroker`, `GatewayConfig`, `ProfileState` and `DEFAULT_PREFIX` (Task 3).
- Produces:
  - `interface SecretStore { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(cipher: ByteArray): ByteArray }`
  - `class SecretUnavailable(message: String, cause: Throwable? = null) : Exception`
  - `class KeystoreSecretStore(alias: String = "oikos.secrets.v1") : SecretStore`
  - `class ProfileRepository` (internal constructor), with:
    - `val state: StateFlow<ProfileState>`
    - `suspend fun save(profile: ConnectionProfile)`, `suspend fun clear()`, `suspend fun installId(): String`
    - `ProfileRepository.create(context: Context, secrets: SecretStore, scope: CoroutineScope)`
    - `ProfileRepository.create(file: File, secrets: SecretStore, scope: CoroutineScope)`
    - `const val FILE = "datastore/profile.json"`

- [ ] **Step 1: Add the dependencies**

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
datastore = "1.2.1"
androidxTestRunner = "1.7.0"
```

Under `[libraries]`:

```toml
androidx-datastore = { module = "androidx.datastore:datastore", version.ref = "datastore" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
```

Replace `core/data/build.gradle.kts` with:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
```

In `oikos.root.gradle.kts`, add `"*KeystoreSecretStore*",` to the `classes(…)` list. It is covered by the instrumented test, which CI cannot run.

- [ ] **Step 2: Write the test fake and the failing tests**

`FakeSecretStore.kt` (test source set):

```kotlin
package com.gekkotron.oikos.data

/** Reversible and visibly not plaintext. Flip [failDecrypt] to simulate a lost Keystore key. */
class FakeSecretStore : SecretStore {
    var failDecrypt = false

    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(MARK) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray): ByteArray {
        if (failDecrypt || cipher.firstOrNull() != MARK) throw SecretUnavailable("fake key unavailable")
        return cipher.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        const val MARK: Byte = 0x7F
    }
}
```

`ProfileRepositoryTest.kt`:

```kotlin
package com.gekkotron.oikos.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val secrets = FakeSecretStore()
    private val gatewayKey = ByteArray(32) { it.toByte() }
    private val direct = ConnectionProfile.Direct(
        LocalBroker("192.168.1.91", 1883, username = "oikos", password = "hunter2-secret"),
        prefix = "homeassistant",
        gateway = GatewayConfig("https://gw.example", gatewayKey, "192.168.1.91"),
        pushTokenTopic = "fcm-push/tokens/{installId}",
    )

    private fun file(name: String = "profile.json") = tmp.root.resolve(name)
    private fun TestScope.repo(f: File = file()) = ProfileRepository.create(f, secrets, backgroundScope)
    private suspend fun ProfileRepository.settled() = state.first { it !is ProfileState.Loading }

    @Test
    fun `empty store is None`() = runTest {
        assertEquals(ProfileState.None, repo().settled())
    }

    @Test
    fun `direct profile round-trips`() = runTest {
        val r = repo()
        r.save(direct)
        assertEquals(ProfileState.Ready(direct), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `demo profile round-trips`() = runTest {
        val r = repo()
        r.save(ConnectionProfile.Demo)
        assertEquals(ProfileState.Ready(ConnectionProfile.Demo), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `unicode password round-trips`() = runTest {
        val r = repo()
        val profile = ConnectionProfile.Direct(LocalBroker("broker.local", username = "u", password = "p@ss wörd:1 🔑"))
        r.save(profile)
        assertEquals(ProfileState.Ready(profile), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `secrets never appear in the file`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        r.state.first { it is ProfileState.Ready }
        val text = f.readText()
        assertTrue("192.168.1.91" in text, "sanity: the file holds the profile")
        assertFalse("hunter2-secret" in text)
        assertFalse(Base64.getEncoder().encodeToString(gatewayKey) in text)
        assertFalse(gatewayKey.joinToString("") { "%02x".format(it) } in text)
    }

    @Test
    fun `undecryptable secrets make the profile Locked`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        r.state.first { it is ProfileState.Ready }
        val copy = file("copy.json").also { f.copyTo(it) }
        secrets.failDecrypt = true
        assertEquals(ProfileState.Locked, repo(copy).settled())
    }

    @Test
    fun `a profile without secrets needs no key`() = runTest {
        val f = file()
        val plain = ConnectionProfile.Direct(LocalBroker("broker.local"))
        repo(f).apply { save(plain); state.first { it is ProfileState.Ready } }
        val copy = file("copy.json").also { f.copyTo(it) }
        secrets.failDecrypt = true
        assertEquals(ProfileState.Ready(plain), repo(copy).settled())
    }

    @Test
    fun `clear resets to None and keeps installId`() = runTest {
        val r = repo()
        val id = r.installId()
        r.save(direct)
        r.clear()
        assertEquals(ProfileState.None, r.state.first { it == ProfileState.None })
        assertEquals(id, r.installId())
    }

    @Test
    fun `installId is a stable UUID`() = runTest {
        val r = repo()
        val id = r.installId()
        UUID.fromString(id)
        assertEquals(id, r.installId())
    }

    @Test
    fun `a corrupt file falls back to an empty profile`() = runTest {
        val f = file().apply { writeText("{not json") }
        assertEquals(ProfileState.None, repo(f).settled())
    }
}
```

- [ ] **Step 3: Run the tests and watch them fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*ProfileRepositoryTest*'`
Expected: compilation FAILS with `Unresolved reference: SecretStore` and `ProfileRepository`.

- [ ] **Step 4: Implement `SecretStore` and `KeystoreSecretStore`**

`SecretStore.kt`:

```kotlin
package com.gekkotron.oikos.data

class SecretUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encrypts the few secrets we persist (broker password, gateway key). Implementations never log inputs. */
interface SecretStore {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws SecretUnavailable when the key is gone or [cipher] does not authenticate. */
    fun decrypt(cipher: ByteArray): ByteArray
}
```

`KeystoreSecretStore.kt`:

```kotlin
package com.gekkotron.oikos.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable Android Keystore key. Output: `iv[12] ‖ ciphertext ‖ tag[16]` (spec §4.3). */
class KeystoreSecretStore(private val alias: String = "oikos.secrets.v1") : SecretStore {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey()) // Keystore picks a random IV
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        if (cipher.size < IV_BYTES + TAG_BITS / 8) throw SecretUnavailable("ciphertext too short")
        // Never create a key here: a missing key means the data is unrecoverable (ProfileState.Locked).
        val key = existingKey() ?: throw SecretUnavailable("keystore key missing")
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, cipher, 0, IV_BYTES))
                doFinal(cipher, IV_BYTES, cipher.size - IV_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            throw SecretUnavailable("cannot decrypt", e)
        }
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 5: Implement the stored model and the repository**

`StoredProfile.kt`:

```kotlin
package com.gekkotron.oikos.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
internal enum class StoredMode { DEMO, DIRECT }

@Serializable
internal data class StoredLocal(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val ws: Boolean,
    val username: String? = null,
    val passCipher: String? = null,
)

@Serializable
internal data class StoredGateway(val url: String, val brokerHost: String? = null, val keyCipher: String)

/** `profile.json` (spec §4.1). Secret fields hold base64 [SecretStore] ciphertext. */
@Serializable
internal data class StoredProfile(
    val installId: String = "",
    val mode: StoredMode? = null,
    val local: StoredLocal? = null,
    val gateway: StoredGateway? = null,
    val prefix: String = DEFAULT_PREFIX,
    val pushTokenTopic: String? = null,
)

internal object ProfileSerializer : Serializer<StoredProfile> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredProfile = StoredProfile()

    override suspend fun readFrom(input: InputStream): StoredProfile = try {
        json.decodeFromString(StoredProfile.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable profile", e)
    }

    override suspend fun writeTo(t: StoredProfile, output: OutputStream) {
        output.write(json.encodeToString(StoredProfile.serializer(), t).encodeToByteArray())
    }
}
```

`ProfileRepository.kt`:

```kotlin
package com.gekkotron.oikos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Base64
import java.util.UUID

/** The saved connection profile (spec §4.2). Secrets are decrypted only into memory. */
class ProfileRepository internal constructor(
    private val store: DataStore<StoredProfile>,
    private val secrets: SecretStore,
    scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val state: StateFlow<ProfileState> = store.data.map(::toState).stateIn(scope, SharingStarted.Eagerly, ProfileState.Loading)

    suspend fun save(profile: ConnectionProfile) {
        val id = installId()
        val stored = toStored(profile, id)
        store.updateData { stored }
    }

    /** Forgets the connection; the install id survives (the push plan keys registrations by it). */
    suspend fun clear() {
        val id = installId()
        store.updateData { StoredProfile(installId = id) }
    }

    suspend fun installId(): String =
        store.updateData { if (it.installId.isEmpty()) it.copy(installId = newId()) else it }.installId

    private fun toState(s: StoredProfile): ProfileState = when (s.mode) {
        null -> ProfileState.None
        StoredMode.DEMO -> ProfileState.Ready(ConnectionProfile.Demo)
        StoredMode.DIRECT -> try {
            s.toDirect()?.let { ProfileState.Ready(it) } ?: ProfileState.None
        } catch (e: SecretUnavailable) {
            ProfileState.Locked
        }
    }

    private fun StoredProfile.toDirect(): ConnectionProfile.Direct? {
        val l = local ?: return null
        return ConnectionProfile.Direct(
            local = LocalBroker(l.host, l.port, l.tls, l.ws, l.username, l.passCipher?.let { open(it).decodeToString() }),
            prefix = prefix,
            gateway = gateway?.let { GatewayConfig(it.url, open(it.keyCipher), it.brokerHost) },
            pushTokenTopic = pushTokenTopic,
        )
    }

    private fun toStored(p: ConnectionProfile, id: String): StoredProfile = when (p) {
        ConnectionProfile.Demo -> StoredProfile(installId = id, mode = StoredMode.DEMO)
        is ConnectionProfile.Direct -> StoredProfile(
            installId = id,
            mode = StoredMode.DIRECT,
            local = with(p.local) { StoredLocal(host, port, tls, ws, username, password?.let { seal(it.encodeToByteArray()) }) },
            gateway = p.gateway?.let { StoredGateway(it.url, it.brokerHost, seal(it.key)) },
            prefix = p.prefix,
            pushTokenTopic = p.pushTokenTopic,
        )
    }

    private fun seal(plain: ByteArray): String = Base64.getEncoder().encodeToString(secrets.encrypt(plain))

    private fun open(sealed: String): ByteArray = try {
        secrets.decrypt(Base64.getDecoder().decode(sealed))
    } catch (e: IllegalArgumentException) {
        throw SecretUnavailable("not base64", e)
    }

    companion object {
        const val FILE = "datastore/profile.json"

        fun create(context: Context, secrets: SecretStore, scope: CoroutineScope): ProfileRepository =
            create(context.filesDir.resolve(FILE), secrets, scope)

        fun create(file: File, secrets: SecretStore, scope: CoroutineScope): ProfileRepository {
            val store = DataStoreFactory.create(
                serializer = ProfileSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredProfile() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
            return ProfileRepository(store, secrets, scope)
        }
    }
}
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Write the instrumented Keystore test**

`KeystoreSecretStoreTest.kt` (androidTest):

```kotlin
package com.gekkotron.oikos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val alias = "oikos.test.${UUID.randomUUID()}"
    private val secret = "hunter2-secret".encodeToByteArray()

    @After
    fun tearDown() = deleteKey()

    private fun deleteKey() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

    @Test
    fun roundTrip() {
        val store = KeystoreSecretStore(alias)
        val cipher = store.encrypt(secret)
        assertFalse("hunter2" in cipher.decodeToString(Charsets.ISO_8859_1))
        assertContentEquals(secret, store.decrypt(cipher))
    }

    @Test
    fun twoEncryptionsDiffer() {
        val store = KeystoreSecretStore(alias)
        assertFalse(store.encrypt(secret).contentEquals(store.encrypt(secret)))
    }

    @Test
    fun missingKeyIsUnavailable() {
        val cipher = KeystoreSecretStore(alias).encrypt(secret)
        deleteKey()
        assertFailsWith<SecretUnavailable> { KeystoreSecretStore(alias).decrypt(cipher) }
    }

    @Test
    fun tamperedCipherIsUnavailable() {
        val store = KeystoreSecretStore(alias)
        val cipher = store.encrypt(secret).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<SecretUnavailable> { store.decrypt(cipher) }
    }
}
```

- [ ] **Step 8: Run the instrumented test on a device or emulator**

Run: `./gradlew :core:data:connectedDebugAndroidTest`
Expected: 4 tests PASS.

This needs a connected device (`adb devices`). CI does not run it. If no device is available, say so in the task report instead of skipping silently.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml build-logic core/data
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(data): profile DataStore with Keystore-encrypted secrets and Locked state" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `:feature:onboarding` presentation: tester contract, manual form and ViewModel

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `feature/onboarding/build.gradle.kts`
- Create: `feature/onboarding/src/test/resources/robolectric.properties`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ConnectionTester.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ManualForm.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/OnboardingViewModel.kt`
- Test: `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/ManualFormTest.kt`
- Test: `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/OnboardingViewModelTest.kt`
- Test: `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/Fakes.kt`

**Interfaces:**
- Consumes: `ProfileRepository`, `ProfileState`, `ConnectionProfile`, `LocalBroker`, `OnboardingPayload`, `ParseResult`, `PayloadError`, `isLikelyLocalHost` and `DEFAULT_PREFIX` (Tasks 3–4).
- Produces:
  - `sealed interface TestFailure { Unreachable; Rejected; Tls; data class Other(message: String) }`
  - `sealed interface TestState { Connecting; data class Failed(failure: TestFailure); data class Connected(devices: List<String>) }`
  - `fun interface ConnectionTester { fun test(draft: ConnectionProfile.Direct): Flow<TestState> }`
  - `enum class FormField { HOST, PORT, PREFIX }`
  - `data class ManualForm(host, port: String, tls, ws, username, password, prefix)`, with `errors()`, `plainTextWarning` and `toProfile()`
  - `enum class Step { WELCOME, SCAN, MANUAL, CONNECT }`
  - `data class OnboardingUiState(step, locked, scanError: PayloadError?, form, showFormErrors, test: TestState, saving)`, with `formErrors`
  - `@HiltViewModel class OnboardingViewModel(profiles: ProfileRepository, tester: ConnectionTester)`, with:
    - `state`
    - `openScan()`, `openManual()`, `tryDemo()`
    - `onQrScanned(text)`, `updateForm(form)`, `submitManual()`
    - `retry()`, `finish()`, `back(): Boolean`

- [ ] **Step 1: Create the module**

In `settings.gradle.kts`, add after `include(":feature:home")`:

```kotlin
include(":feature:onboarding")
```

In the root `build.gradle.kts` dependencies, add:

```kotlin
    kover(project(":feature:onboarding"))
```

`feature/onboarding/build.gradle.kts` (the camera dependencies arrive in Task 6):

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.onboarding"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
}
```

`feature/onboarding/src/test/resources/robolectric.properties`:

```
sdk=34
```

- [ ] **Step 2: Write the contract**

`ConnectionTester.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import kotlinx.coroutines.flow.Flow

sealed interface TestFailure {
    data object Unreachable : TestFailure
    data object Rejected : TestFailure
    data object Tls : TestFailure
    data class Other(val message: String) : TestFailure
}

sealed interface TestState {
    data object Connecting : TestState
    data class Failed(val failure: TestFailure) : TestState

    /** Connected; [devices] are the names discovered so far, sorted. */
    data class Connected(val devices: List<String>) : TestState
}

/** Tries a draft profile without saving it (spec §5.1). Cancelling the flow closes the test connection. */
fun interface ConnectionTester {
    fun test(draft: ConnectionProfile.Direct): Flow<TestState>
}
```

- [ ] **Step 3: Write the failing tests**

`Fakes.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.data.SecretUnavailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class FakeConnectionTester : ConnectionTester {
    val drafts = mutableListOf<ConnectionProfile.Direct>()
    val result = MutableStateFlow<TestState>(TestState.Connecting)
    var active = 0
        private set

    override fun test(draft: ConnectionProfile.Direct): Flow<TestState> = flow {
        drafts += draft
        active++
        try {
            emitAll(result)
        } finally {
            active--
        }
    }
}

/** Test-only store; flip [fail] to simulate a lost key. */
class PassThroughSecretStore : SecretStore {
    var fail = false
    override fun encrypt(plain: ByteArray) = byteArrayOf(1) + plain
    override fun decrypt(cipher: ByteArray): ByteArray = if (fail) throw SecretUnavailable("lost") else cipher.copyOfRange(1, cipher.size)
}
```

`ManualFormTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualFormTest {
    private val valid = ManualForm(host = "192.168.1.91")

    @Test
    fun `a valid form has no errors`() = assertEquals(emptySet(), valid.errors())

    @Test
    fun `blank host is an error`() = assertEquals(setOf(FormField.HOST), valid.copy(host = "  ").errors())

    @Test
    fun `host with scheme or port is an error`() {
        listOf("mqtt://192.168.1.91", "192.168.1.91:1883", "broker local").forEach {
            assertEquals(setOf(FormField.HOST), valid.copy(host = it).errors(), it)
        }
    }

    @Test
    fun `port must be 1 to 65535`() {
        listOf("0", "65536", "abc", "").forEach { assertEquals(setOf(FormField.PORT), valid.copy(port = it).errors(), it) }
    }

    @Test
    fun `blank prefix is an error`() = assertEquals(setOf(FormField.PREFIX), valid.copy(prefix = " ").errors())

    @Test
    fun `toProfile trims and turns empty credentials into null`() {
        val p = ManualForm(host = " broker.local ", port = " 8883 ", tls = true, username = " ", password = "", prefix = " ha ").toProfile()
        assertEquals(ConnectionProfile.Direct(LocalBroker("broker.local", 8883, tls = true), prefix = "ha"), p)
    }

    @Test
    fun `plain TCP to a non-local host warns`() {
        assertTrue(valid.copy(host = "mqtt.example.com").plainTextWarning)
        assertFalse(valid.plainTextWarning)
        assertFalse(valid.copy(host = "mqtt.example.com", tls = true).plainTextWarning)
        assertFalse(valid.copy(host = "").plainTextWarning)
    }

    @Test
    fun `toString hides the password`() = assertFalse("s3cret" in valid.copy(password = "s3cret").toString())
}
```

`OnboardingViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = PassThroughSecretStore()
    private val tester = FakeConnectionTester()
    private lateinit var profiles: ProfileRepository
    private lateinit var vm: OnboardingViewModel

    private val qr = """{"v":1,"local":{"host":"192.168.1.91","user":"oikos","pass":"s3cret"}}"""
    private val qrProfile = ConnectionProfile.Direct(LocalBroker("192.168.1.91", username = "oikos", password = "s3cret"))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), secrets, io)
        vm = OnboardingViewModel(profiles, tester)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        io.cancel()
    }

    @Test
    fun `a valid QR code goes to Connect and tests the parsed profile`() {
        vm.openScan()
        vm.onQrScanned(qr)
        assertEquals(Step.CONNECT, vm.state.value.step)
        assertEquals(listOf(qrProfile), tester.drafts)
    }

    @Test
    fun `an invalid QR code shows an error and stays on Scan`() {
        vm.openScan()
        vm.onQrScanned("https://example.com")
        assertEquals(Step.SCAN, vm.state.value.step)
        assertEquals(PayloadError.NotOikosCode, vm.state.value.scanError)
        assertTrue(tester.drafts.isEmpty())
    }

    @Test
    fun `a valid code after an invalid one connects`() {
        vm.openScan()
        repeat(5) { vm.onQrScanned("not a setup code") }
        vm.onQrScanned(qr)
        assertEquals(Step.CONNECT, vm.state.value.step)
        assertEquals(listOf(qrProfile), tester.drafts)
    }

    @Test
    fun `scans outside the Scan step are ignored`() {
        vm.onQrScanned(qr)
        assertEquals(Step.WELCOME, vm.state.value.step)
        assertTrue(tester.drafts.isEmpty())
    }

    @Test
    fun `manual form with errors does not connect and shows errors`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = "mqtt://x"))
        vm.submitManual()
        assertEquals(Step.MANUAL, vm.state.value.step)
        assertEquals(setOf(FormField.HOST), vm.state.value.formErrors)
        assertTrue(tester.drafts.isEmpty())
    }

    @Test
    fun `errors stay hidden until the first submit`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = ""))
        assertEquals(emptySet(), vm.state.value.formErrors)
    }

    @Test
    fun `a valid manual form connects`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = "broker.local"))
        vm.submitManual()
        assertEquals(Step.CONNECT, vm.state.value.step)
        assertEquals(listOf(ConnectionProfile.Direct(LocalBroker("broker.local"))), tester.drafts)
    }

    @Test
    fun `test results reach the state`() {
        vm.openScan()
        vm.onQrScanned(qr)
        tester.result.value = TestState.Failed(TestFailure.Rejected)
        assertEquals(TestState.Failed(TestFailure.Rejected), vm.state.value.test)
    }

    @Test
    fun `retry restarts the test`() {
        vm.openScan()
        vm.onQrScanned(qr)
        vm.retry()
        assertEquals(2, tester.drafts.size)
        assertEquals(1, tester.active)
    }

    @Test
    fun `continue does nothing until connected`() = runTest {
        vm.openScan()
        vm.onQrScanned(qr)
        vm.finish()
        assertEquals(ProfileState.None, profiles.state.first { it !is ProfileState.Loading })
    }

    @Test
    fun `continue cancels the test before saving`() = runTest {
        vm.openScan()
        vm.onQrScanned(qr)
        tester.result.value = TestState.Connected(listOf("Kettle"))
        vm.finish()
        assertEquals(ProfileState.Ready(qrProfile), profiles.state.first { it is ProfileState.Ready })
        assertEquals(0, tester.active)
    }

    @Test
    fun `back from Connect cancels the test and returns to where it came from`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = "broker.local"))
        vm.submitManual()
        assertTrue(vm.back())
        assertEquals(Step.MANUAL, vm.state.value.step)
        assertEquals(0, tester.active)
    }

    @Test
    fun `back on Welcome is not handled`() = assertFalse(vm.back())

    @Test
    fun `try the demo saves the demo profile`() = runTest {
        vm.tryDemo()
        assertEquals(ProfileState.Ready(ConnectionProfile.Demo), profiles.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `a Locked profile shows the unlock notice`() = runTest {
        profiles.save(qrProfile)
        profiles.state.first { it is ProfileState.Ready }
        val copy = tmp.root.resolve("copy.json").also { tmp.root.resolve("profile.json").copyTo(it) }
        secrets.fail = true
        val locked = ProfileRepository.create(copy, secrets, io)
        val lockedVm = OnboardingViewModel(locked, tester)
        locked.state.first { it == ProfileState.Locked }
        assertTrue(lockedVm.state.value.locked)
    }
}
```

- [ ] **Step 4: Run the tests and watch them fail**

Run: `./gradlew :feature:onboarding:testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference: ManualForm` and `OnboardingViewModel`.

- [ ] **Step 5: Implement `ManualForm`**

`ManualForm.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.isLikelyLocalHost

enum class FormField { HOST, PORT, PREFIX }

/** The manual-entry fields as typed (spec §5 step 3). [password] is a secret; [toString] masks it. */
data class ManualForm(
    val host: String = "",
    val port: String = "1883",
    val tls: Boolean = false,
    val ws: Boolean = false,
    val username: String = "",
    val password: String = "",
    val prefix: String = DEFAULT_PREFIX,
) {
    fun errors(): Set<FormField> = buildSet {
        val h = host.trim()
        // Host only: users often paste "mqtt://host:1883". IPv6 literals are out of scope for v1.
        if (h.isEmpty() || h.any { it.isWhitespace() || it == ':' || it == '/' }) add(FormField.HOST)
        if ((port.trim().toIntOrNull() ?: 0) !in 1..65535) add(FormField.PORT)
        if (prefix.isBlank()) add(FormField.PREFIX)
    }

    /** Plain TCP to a host that doesn't look local (spec §4.5): warn, don't block. */
    val plainTextWarning: Boolean get() = !tls && host.isNotBlank() && !isLikelyLocalHost(host)

    /** Only valid when [errors] is empty. */
    fun toProfile(): ConnectionProfile.Direct = ConnectionProfile.Direct(
        LocalBroker(
            host = host.trim(),
            port = port.trim().toInt(),
            tls = tls,
            ws = ws,
            username = username.trim().ifEmpty { null },
            password = password.ifEmpty { null },
        ),
        prefix = prefix.trim(),
    )

    override fun toString(): String = "ManualForm($host:$port, tls=$tls, ws=$ws, user=$username, password=***, prefix=$prefix)"
}
```

- [ ] **Step 6: Implement the ViewModel**

`OnboardingViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.OnboardingPayload
import com.gekkotron.oikos.data.ParseResult
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Step { WELCOME, SCAN, MANUAL, CONNECT }

data class OnboardingUiState(
    val step: Step = Step.WELCOME,
    val locked: Boolean = false,
    val scanError: PayloadError? = null,
    val form: ManualForm = ManualForm(),
    val showFormErrors: Boolean = false,
    val test: TestState = TestState.Connecting,
    val saving: Boolean = false,
) {
    val formErrors: Set<FormField> get() = if (showFormErrors) form.errors() else emptySet()
}

/**
 * Welcome → Scan | Manual → Connect & discover (spec §5). The draft profile, with its secrets, lives
 * only in this ViewModel, never in saved state. Saving a profile makes the app switch to Home.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val tester: ConnectionTester,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = combine(_state, profiles.state) { s, p -> s.copy(locked = p is ProfileState.Locked) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

    private var draft: ConnectionProfile.Direct? = null
    private var origin = Step.WELCOME
    private var testJob: Job? = null
    private var lastRejectedScan: String? = null

    fun openScan() = go(Step.SCAN)

    fun openManual() = go(Step.MANUAL)

    fun tryDemo() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            profiles.save(ConnectionProfile.Demo)
        }
    }

    /** Called for every decoded QR frame; the camera keeps reporting the same code, so repeats are ignored. */
    fun onQrScanned(text: String) {
        if (_state.value.step != Step.SCAN || text == lastRejectedScan) return
        when (val result = OnboardingPayload.parse(text)) {
            is ParseResult.Ok -> {
                lastRejectedScan = null
                connect(result.profile, from = Step.SCAN)
            }
            is ParseResult.Error -> {
                lastRejectedScan = text
                _state.update { it.copy(scanError = result.error) }
            }
        }
    }

    fun updateForm(form: ManualForm) = _state.update { it.copy(form = form) }

    fun submitManual() {
        val form = _state.value.form
        if (form.errors().isNotEmpty()) {
            _state.update { it.copy(showFormErrors = true) }
            return
        }
        connect(form.toProfile(), from = Step.MANUAL)
    }

    fun retry() {
        draft?.let(::startTest)
    }

    fun finish() {
        val profile = draft ?: return
        if (_state.value.test !is TestState.Connected) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            // Close the test connection first: it must not overlap the app's own connection.
            testJob?.cancelAndJoin()
            profiles.save(profile)
        }
    }

    /** @return false when there is nothing to go back to, so the system handles Back. */
    fun back(): Boolean = when (_state.value.step) {
        Step.WELCOME -> false
        Step.CONNECT -> {
            testJob?.cancel()
            testJob = null
            draft = null
            go(origin)
            true
        }
        Step.SCAN, Step.MANUAL -> {
            go(Step.WELCOME)
            true
        }
    }

    private fun go(step: Step) {
        if (step == Step.SCAN) lastRejectedScan = null
        _state.update { it.copy(step = step, scanError = null) }
    }

    private fun connect(profile: ConnectionProfile.Direct, from: Step) {
        origin = from
        draft = profile
        _state.update { it.copy(step = Step.CONNECT, scanError = null) }
        startTest(profile)
    }

    private fun startTest(profile: ConnectionProfile.Direct) {
        testJob?.cancel()
        _state.update { it.copy(test = TestState.Connecting) }
        testJob = viewModelScope.launch {
            tester.test(profile).collect { result -> _state.update { it.copy(test = result) } }
        }
    }
}
```

- [ ] **Step 7: Run the tests and watch them pass**

Run: `./gradlew :feature:onboarding:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts feature/onboarding
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(onboarding): connection tester contract, manual form validation and OnboardingViewModel" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Onboarding UI: Welcome, Scan (CameraX + ML Kit), Manual and Connect

**Files:**
- Modify: `gradle/libs.versions.toml`, `feature/onboarding/build.gradle.kts`, `build-logic/convention/src/main/kotlin/oikos.root.gradle.kts`
- Create: `feature/onboarding/src/main/AndroidManifest.xml`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/OnboardingRoute.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/OnboardingLayout.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/WelcomeScreen.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/ScanScreen.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/QrCamera.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/ManualScreen.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/ConnectScreen.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/Messages.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/SecureWindow.kt`
- Create: `feature/onboarding/src/main/res/values/strings.xml`, `feature/onboarding/src/main/res/values-fr/strings.xml`
- Test: `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/ui/OnboardingScreensTest.kt`

**Interfaces:**
- Consumes: `OnboardingViewModel`, `OnboardingUiState`, `Step`, `ManualForm`, `FormField`, `TestState` and `TestFailure` (Task 5); `PayloadError` (Task 3); `OikosTheme` tokens.
- Produces:
  - `@Composable fun OnboardingRoute(viewModel: OnboardingViewModel = hiltViewModel())`, used by `:app` in Task 8.
  - Internal screen composables, each testable with plain parameters:
    - `WelcomeScreen(locked, busy, onScan, onManual, onDemo)`
    - `ScanContent(permission: CameraPermission, error, onText, onManual, onOpenSettings, onBack, camera)`
    - `ManualScreen(form, errors, onChange, onSubmit, onBack)`
    - `ConnectScreen(test, saving, onRetry, onBack, onContinue)`
    - `SecureWindow()`

- [ ] **Step 1: Add the camera dependencies and the manifest**

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
camerax = "1.6.2"
mlkitBarcode = "17.3.0"
```

Under `[libraries]`:

```toml
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
androidx-camera-compose = { module = "androidx.camera:camera-compose", version.ref = "camerax" }
mlkit-barcode-scanning = { module = "com.google.mlkit:barcode-scanning", version.ref = "mlkitBarcode" }
```

In `feature/onboarding/build.gradle.kts`, append to `dependencies`:

```kotlin
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.mlkit.barcode.scanning)
```

`feature/onboarding/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />
</manifest>
```

In `oikos.root.gradle.kts`, add `"*QrCameraKt*", "*QrAnalyzer*",` to the `classes(…)` excludes. Camera glue can't run on the JVM; the decode-then-parse logic is covered by `OnboardingViewModelTest`.

- [ ] **Step 2: Add the strings (EN and FR)**

`values/strings.xml`:

```xml
<resources>
    <string name="common_back">Back</string>
    <string name="welcome_title">Welcome to Oikos</string>
    <string name="welcome_tagline">Your home, live. Connect to your MQTT broker to get started.</string>
    <string name="welcome_scan">Scan setup code</string>
    <string name="welcome_manual">Enter manually</string>
    <string name="welcome_demo">Try the demo</string>
    <string name="welcome_locked">Your saved connection couldn\'t be unlocked — scan again.</string>
    <string name="scan_title">Scan the setup code</string>
    <string name="scan_waiting">Waiting for camera permission…</string>
    <string name="scan_denied">Oikos needs the camera to read the setup code. Allow it in Settings, or enter the settings manually.</string>
    <string name="scan_open_settings">Open settings</string>
    <string name="scan_error_not_oikos">This QR code isn\'t an Oikos setup code.</string>
    <string name="scan_error_version">This setup code needs a newer version of Oikos (format %1$d).</string>
    <string name="scan_error_field">This setup code has an invalid value: %1$s.</string>
    <string name="manual_title">Broker settings</string>
    <string name="manual_host">Host</string>
    <string name="manual_host_error">Host name or IP address only, without mqtt:// or a port</string>
    <string name="manual_port">Port</string>
    <string name="manual_port_error">Port must be between 1 and 65535</string>
    <string name="manual_tls">TLS</string>
    <string name="manual_ws">WebSocket</string>
    <string name="manual_plain_warning">TLS is off and this host doesn\'t look local. Your password would travel unencrypted.</string>
    <string name="manual_username">Username (optional)</string>
    <string name="manual_password">Password (optional)</string>
    <string name="manual_advanced">Advanced</string>
    <string name="manual_prefix">Discovery prefix</string>
    <string name="manual_prefix_error">Enter a discovery prefix</string>
    <string name="manual_connect">Connect</string>
    <string name="connect_connecting">Connecting…</string>
    <string name="connect_failed">Couldn\'t connect</string>
    <string name="connect_error_unreachable">Can\'t reach the broker. Check the host and port, and that you\'re on the home network.</string>
    <string name="connect_error_rejected">Username or password rejected.</string>
    <string name="connect_error_tls">Secure connection failed. Check the port and the TLS setting.</string>
    <string name="connect_error_other">Connection failed: %1$s</string>
    <string name="connect_retry">Retry</string>
    <plurals name="connect_discovering">
        <item quantity="one">Discovering… %d device</item>
        <item quantity="other">Discovering… %d devices</item>
    </plurals>
    <string name="connect_no_devices">No devices announced yet — check that discovery is enabled.</string>
    <string name="connect_continue">Continue</string>
</resources>
```

`values-fr/strings.xml`:

```xml
<resources>
    <string name="common_back">Retour</string>
    <string name="welcome_title">Bienvenue dans Oikos</string>
    <string name="welcome_tagline">Votre maison, en direct. Connectez-vous à votre broker MQTT pour commencer.</string>
    <string name="welcome_scan">Scanner le code de configuration</string>
    <string name="welcome_manual">Saisir manuellement</string>
    <string name="welcome_demo">Essayer la démo</string>
    <string name="welcome_locked">Votre connexion enregistrée n\'a pas pu être déverrouillée — scannez à nouveau.</string>
    <string name="scan_title">Scannez le code de configuration</string>
    <string name="scan_waiting">En attente de l\'autorisation caméra…</string>
    <string name="scan_denied">Oikos a besoin de la caméra pour lire le code. Autorisez-la dans les paramètres, ou saisissez la configuration manuellement.</string>
    <string name="scan_open_settings">Ouvrir les paramètres</string>
    <string name="scan_error_not_oikos">Ce QR code n\'est pas un code de configuration Oikos.</string>
    <string name="scan_error_version">Ce code nécessite une version plus récente d\'Oikos (format %1$d).</string>
    <string name="scan_error_field">Ce code contient une valeur invalide : %1$s.</string>
    <string name="manual_title">Paramètres du broker</string>
    <string name="manual_host">Hôte</string>
    <string name="manual_host_error">Nom d\'hôte ou adresse IP uniquement, sans mqtt:// ni port</string>
    <string name="manual_port">Port</string>
    <string name="manual_port_error">Le port doit être compris entre 1 et 65535</string>
    <string name="manual_tls">TLS</string>
    <string name="manual_ws">WebSocket</string>
    <string name="manual_plain_warning">TLS est désactivé et cet hôte ne semble pas local. Votre mot de passe circulerait en clair.</string>
    <string name="manual_username">Nom d\'utilisateur (facultatif)</string>
    <string name="manual_password">Mot de passe (facultatif)</string>
    <string name="manual_advanced">Avancé</string>
    <string name="manual_prefix">Préfixe de découverte</string>
    <string name="manual_prefix_error">Saisissez un préfixe de découverte</string>
    <string name="manual_connect">Se connecter</string>
    <string name="connect_connecting">Connexion…</string>
    <string name="connect_failed">Connexion impossible</string>
    <string name="connect_error_unreachable">Broker injoignable. Vérifiez l\'hôte, le port, et que vous êtes sur le réseau de la maison.</string>
    <string name="connect_error_rejected">Nom d\'utilisateur ou mot de passe refusé.</string>
    <string name="connect_error_tls">La connexion sécurisée a échoué. Vérifiez le port et le réglage TLS.</string>
    <string name="connect_error_other">Échec de la connexion : %1$s</string>
    <string name="connect_retry">Réessayer</string>
    <plurals name="connect_discovering">
        <item quantity="one">Découverte… %d appareil</item>
        <item quantity="other">Découverte… %d appareils</item>
    </plurals>
    <string name="connect_no_devices">Aucun appareil annoncé pour l\'instant — vérifiez que la découverte est activée.</string>
    <string name="connect_continue">Continuer</string>
</resources>
```

- [ ] **Step 3: Write the failing UI tests**

`OnboardingScreensTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.FormField
import com.gekkotron.oikos.feature.onboarding.ManualForm
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class OnboardingScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    @Test
    fun `welcome buttons call back`() {
        compose.setContent { OikosTheme { WelcomeScreen(locked = false, busy = false, { calls += "scan" }, { calls += "manual" }, { calls += "demo" }) } }
        compose.onNodeWithText("Scan setup code").performClick()
        compose.onNodeWithText("Enter manually").performClick()
        compose.onNodeWithText("Try the demo").performClick()
        assertEquals(listOf("scan", "manual", "demo"), calls)
        compose.onNodeWithText("couldn't be unlocked", substring = true).assertDoesNotExist()
    }

    @Test
    fun `welcome shows the unlock notice when locked`() {
        compose.setContent { OikosTheme { WelcomeScreen(locked = true, busy = false, {}, {}, {}) } }
        compose.onNodeWithText("couldn't be unlocked", substring = true).assertExists()
    }

    @Test
    fun `manual shows field errors and the plain-text warning`() {
        compose.setContent {
            OikosTheme {
                ManualScreen(ManualForm(host = "mqtt.example.com", port = "0"), setOf(FormField.PORT), {}, { calls += "submit" }, {})
            }
        }
        compose.onNodeWithText("Port must be between 1 and 65535").assertExists()
        compose.onNodeWithText("doesn't look local", substring = true).assertExists()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        assertEquals(listOf("submit"), calls)
    }

    @Test
    fun `manual edits flow back through onChange`() {
        var form by mutableStateOf(ManualForm())
        compose.setContent { OikosTheme { ManualScreen(form, emptySet(), { form = it }, {}, {}) } }
        compose.onNodeWithText("TLS").performClick()
        assertTrue(form.tls)
    }

    @Test
    fun `connect shows progress, then a failure with retry`() {
        var test by mutableStateOf<TestState>(TestState.Connecting)
        compose.setContent { OikosTheme { ConnectScreen(test, saving = false, { calls += "retry" }, {}, {}) } }
        compose.onNodeWithText("Connecting…").assertExists()
        test = TestState.Failed(TestFailure.Rejected)
        compose.onNodeWithText("Username or password rejected.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `connect lists devices and continues`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(listOf("Kettle", "Lamp")), false, {}, {}, { calls += "continue" }) } }
        compose.onNodeWithText("Discovering… 2 devices").assertExists()
        compose.onNodeWithText("Kettle").assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(listOf("continue"), calls)
    }

    @Test
    fun `connect with no devices explains why and still allows continue`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(emptyList()), false, {}, {}, {}) } }
        compose.onNodeWithText("No devices announced yet", substring = true).assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `continue is disabled while saving`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(listOf("Kettle")), saving = true, {}, {}, {}) } }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `scan shows the camera when granted and the error below it`() {
        compose.setContent {
            OikosTheme {
                ScanContent(CameraPermission.GRANTED, PayloadError.NotOikosCode, {}, {}, {}, {}) { modifier, _ ->
                    Box(modifier.testTag("camera"))
                }
            }
        }
        compose.onNodeWithTag("camera").assertExists()
        compose.onNodeWithText("isn't an Oikos setup code", substring = true).assertExists()
    }

    @Test
    fun `scan without permission offers settings and manual entry`() {
        compose.setContent {
            OikosTheme {
                ScanContent(CameraPermission.DENIED, null, {}, { calls += "manual" }, { calls += "settings" }, {}) { modifier, _ ->
                    Box(modifier.testTag("camera"))
                }
            }
        }
        compose.onNodeWithTag("camera").assertDoesNotExist()
        compose.onNodeWithText("Open settings").performClick()
        compose.onNodeWithText("Enter manually").performClick()
        assertEquals(listOf("settings", "manual"), calls)
    }

    @Test
    fun `secure window sets and clears FLAG_SECURE`() {
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SecureWindow() }
        val flags = { compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE }
        compose.waitForIdle()
        assertTrue(flags() != 0)
        shown = false
        compose.waitForIdle()
        assertEquals(0, flags())
    }
}
```

The `Modifier` import stays: the camera slot lambda's `modifier` parameter is a `Modifier`.

- [ ] **Step 4: Run the tests and watch them fail**

Run: `./gradlew :feature:onboarding:testDebugUnitTest --tests '*OnboardingScreensTest*'`
Expected: compilation FAILS with `Unresolved reference: WelcomeScreen`.

- [ ] **Step 5: Implement the shared layout, messages and secure window**

`OnboardingLayout.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme

/** Every onboarding step scrolls, so 200 % font scale never clips (spec §5.3). */
@Composable
internal fun OnboardingColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides OikosTheme.colors.content) {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
```

`Messages.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.feature.onboarding.R
import com.gekkotron.oikos.feature.onboarding.TestFailure

@Composable
internal fun payloadErrorText(error: PayloadError): String = when (error) {
    PayloadError.NotOikosCode -> stringResource(R.string.scan_error_not_oikos)
    is PayloadError.UnsupportedVersion -> stringResource(R.string.scan_error_version, error.version)
    is PayloadError.InvalidField -> stringResource(R.string.scan_error_field, error.field)
}

@Composable
internal fun testFailureText(failure: TestFailure): String = when (failure) {
    TestFailure.Unreachable -> stringResource(R.string.connect_error_unreachable)
    TestFailure.Rejected -> stringResource(R.string.connect_error_rejected)
    TestFailure.Tls -> stringResource(R.string.connect_error_tls)
    is TestFailure.Other -> stringResource(R.string.connect_error_other, failure.message)
}
```

`SecureWindow.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Blocks screenshots and the recents thumbnail while a screen that shows secrets is visible (spec §8). */
@Composable
internal fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
```

- [ ] **Step 6: Implement the screens**

`WelcomeScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R

@Composable
internal fun WelcomeScreen(locked: Boolean, busy: Boolean, onScan: () -> Unit, onManual: () -> Unit, onDemo: () -> Unit) {
    OnboardingColumn {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.welcome_title), style = OikosTheme.type.display)
        Text(stringResource(R.string.welcome_tagline), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
        if (locked) {
            Text(
                stringResource(R.string.welcome_locked),
                style = OikosTheme.type.body,
                color = OikosTheme.colors.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(OikosTheme.colors.warningContainer)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_scan)) }
        OutlinedButton(onClick = onManual, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_manual)) }
        TextButton(onClick = onDemo, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_demo)) }
    }
}
```

`ScanScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R

internal enum class CameraPermission { WAITING, GRANTED, DENIED }

@Composable
internal fun ScanScreen(error: PayloadError?, onText: (String) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var permission by remember {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        mutableStateOf(if (granted) CameraPermission.GRANTED else CameraPermission.WAITING)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permission = if (ok) CameraPermission.GRANTED else CameraPermission.DENIED
    }
    LaunchedEffect(Unit) {
        if (permission == CameraPermission.WAITING) launcher.launch(Manifest.permission.CAMERA)
    }
    ScanContent(
        permission = permission,
        error = error,
        onText = onText,
        onManual = onManual,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onBack = onBack,
    )
}

@Composable
internal fun ScanContent(
    permission: CameraPermission,
    error: PayloadError?,
    onText: (String) -> Unit,
    onManual: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    camera: @Composable (Modifier, (String) -> Unit) -> Unit = { modifier, callback -> QrCamera(modifier, callback) },
) {
    SecureWindow()
    OnboardingColumn {
        Text(stringResource(R.string.scan_title), style = OikosTheme.type.display)
        when (permission) {
            CameraPermission.GRANTED -> camera(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)), onText)
            CameraPermission.WAITING -> Text(stringResource(R.string.scan_waiting), style = OikosTheme.type.body)
            CameraPermission.DENIED -> {
                Text(stringResource(R.string.scan_denied), style = OikosTheme.type.body)
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_open_settings)) }
            }
        }
        if (error != null) {
            Text(
                payloadErrorText(error),
                style = OikosTheme.type.body,
                color = OikosTheme.colors.warning,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_manual)) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
    }
}
```

`QrCamera.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gekkotron.oikos.designsystem.OikosTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/** Back-camera preview with a QR analyzer. Device-only glue: excluded from coverage. */
@Composable
internal fun QrCamera(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = QrAnalyzer { currentOnText(it) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(executor, analyzer) }
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            analyzer.close()
            executor.shutdown()
        }
    }
    Box(modifier.background(OikosTheme.colors.backgroundBottom)) {
        request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
    }
}

/** ML Kit's bundled on-device model, QR only; no Play services (spec §5 step 2). Results arrive on the main thread. */
internal class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer, AutoCloseable {
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val media = image.image ?: return image.close()
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onText) }
            .addOnCompleteListener { image.close() }
    }

    override fun close() = scanner.close()
}
```

If the compiler rejects `@ExperimentalGetImage` on an override, put `@androidx.annotation.OptIn(ExperimentalGetImage::class)` on the class instead.

`ManualScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.FormField
import com.gekkotron.oikos.feature.onboarding.ManualForm
import com.gekkotron.oikos.feature.onboarding.R

@Composable
internal fun ManualScreen(form: ManualForm, errors: Set<FormField>, onChange: (ManualForm) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    SecureWindow()
    var advanced by rememberSaveable { mutableStateOf(form.prefix != DEFAULT_PREFIX) }
    OnboardingColumn {
        Text(stringResource(R.string.manual_title), style = OikosTheme.type.display)
        Field(form.host, { onChange(form.copy(host = it)) }, R.string.manual_host, R.string.manual_host_error.takeIf { FormField.HOST in errors }, KeyboardType.Uri)
        Field(form.port, { onChange(form.copy(port = it)) }, R.string.manual_port, R.string.manual_port_error.takeIf { FormField.PORT in errors }, KeyboardType.Number)
        SwitchRow(R.string.manual_tls, form.tls) { onChange(form.copy(tls = it)) }
        SwitchRow(R.string.manual_ws, form.ws) { onChange(form.copy(ws = it)) }
        if (form.plainTextWarning) {
            Text(stringResource(R.string.manual_plain_warning), style = OikosTheme.type.body, color = OikosTheme.colors.warning)
        }
        Field(form.username, { onChange(form.copy(username = it)) }, R.string.manual_username, null, KeyboardType.Text)
        Field(form.password, { onChange(form.copy(password = it)) }, R.string.manual_password, null, KeyboardType.Password, secret = true)
        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.manual_advanced)) }
        if (advanced) {
            Field(form.prefix, { onChange(form.copy(prefix = it)) }, R.string.manual_prefix, R.string.manual_prefix_error.takeIf { FormField.PREFIX in errors }, KeyboardType.Text)
        }
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.manual_connect)) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
    }
}

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    @StringRes label: Int,
    @StringRes error: Int?,
    keyboard: KeyboardType,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
```

`ConnectScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R
import com.gekkotron.oikos.feature.onboarding.TestState

@Composable
internal fun ConnectScreen(test: TestState, saving: Boolean, onRetry: () -> Unit, onBack: () -> Unit, onContinue: () -> Unit) {
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    OnboardingColumn {
        when (test) {
            TestState.Connecting -> {
                Text(stringResource(R.string.connect_connecting), style = OikosTheme.type.display, modifier = announce)
                CircularProgressIndicator(color = OikosTheme.colors.content)
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
            is TestState.Failed -> {
                Text(stringResource(R.string.connect_failed), style = OikosTheme.type.display)
                Text(testFailureText(test.failure), style = OikosTheme.type.body, modifier = announce)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_retry)) }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
            is TestState.Connected -> {
                val count = test.devices.size
                Text(pluralStringResource(R.plurals.connect_discovering, count, count), style = OikosTheme.type.display, modifier = announce)
                if (count == 0) {
                    Text(stringResource(R.string.connect_no_devices), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
                }
                test.devices.forEach { Text(it, style = OikosTheme.type.body) }
                Button(onClick = onContinue, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_continue)) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
        }
    }
}
```

`OnboardingRoute.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.onboarding.OnboardingViewModel
import com.gekkotron.oikos.feature.onboarding.Step

/** The whole onboarding flow. It ends when a profile is saved; the app then shows Home. */
@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step != Step.WELCOME) { viewModel.back() }
    when (state.step) {
        Step.WELCOME -> WelcomeScreen(state.locked, state.saving, viewModel::openScan, viewModel::openManual, viewModel::tryDemo)
        Step.SCAN -> ScanScreen(state.scanError, viewModel::onQrScanned, viewModel::openManual, onBack = { viewModel.back() })
        Step.MANUAL -> ManualScreen(state.form, state.formErrors, viewModel::updateForm, viewModel::submitManual, onBack = { viewModel.back() })
        Step.CONNECT -> ConnectScreen(state.test, state.saving, viewModel::retry, onBack = { viewModel.back() }, onContinue = viewModel::finish)
    }
}
```

- [ ] **Step 7: Run the tests and watch them pass**

Run: `./gradlew :feature:onboarding:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml build-logic feature/onboarding
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(onboarding): welcome, QR scan (CameraX + ML Kit), manual entry and connect screens (EN/FR)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Home link sheet with "Set up connection again"

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkPill.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheetTest.kt`

**Interfaces:**
- Consumes: `LinkState`, `LinkState.Offline.REJECTED` (Task 1) and the existing `StatusPill`.
- Produces:
  - `HomeRoute(onSetUpAgain: () -> Unit = {}, viewModel: HomeViewModel = hiltViewModel())`
  - `HomeScreen(…, onSetUpAgain: () -> Unit = {})` as a new last parameter
  - `internal fun LinkPill(link: LinkState, onClick: () -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Add the strings**

Append to `feature/home/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="link_details">Connection details</string>
    <string name="link_direct">Connected directly to your broker.</string>
    <string name="link_gateway">Connected through the gateway.</string>
    <string name="link_demo">You\'re exploring the demo house. Nothing leaves this phone.</string>
    <string name="link_connecting">Connecting to your broker…</string>
    <string name="link_reconnecting">Connection lost. Reconnecting (attempt %1$d)…</string>
    <string name="link_rejected">The broker rejected the username or password.</string>
    <string name="link_offline">Offline: %1$s</string>
    <string name="link_set_up_again">Set up connection again</string>
```

Append to `values-fr/strings.xml`:

```xml
    <string name="link_details">Détails de la connexion</string>
    <string name="link_direct">Connecté directement à votre broker.</string>
    <string name="link_gateway">Connecté via la passerelle.</string>
    <string name="link_demo">Vous explorez la maison de démo. Rien ne quitte ce téléphone.</string>
    <string name="link_connecting">Connexion à votre broker…</string>
    <string name="link_reconnecting">Connexion perdue. Reconnexion (tentative %1$d)…</string>
    <string name="link_rejected">Le broker a refusé le nom d\'utilisateur ou le mot de passe.</string>
    <string name="link_offline">Hors ligne : %1$s</string>
    <string name="link_set_up_again">Reconfigurer la connexion</string>
```

- [ ] **Step 2: Write the failing tests**

`LinkSheetTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class LinkSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(link: LinkState, onSetUpAgain: () -> Unit = {}) = compose.setContent {
        OikosTheme { HomeScreen(HomeUiState(loading = false, link = link), {}, {}, { _, _ -> }, onSetUpAgain = onSetUpAgain) }
    }

    @Test
    fun `tapping the pill opens the sheet and set up again calls back`() {
        var called = false
        show(LinkState.Connected(TransportMode.DEMO)) { called = true }
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("exploring the demo house", substring = true).assertExists()
        compose.onNodeWithText("Set up connection again").performClick()
        assertTrue(called)
    }

    @Test
    fun `rejected credentials explain themselves`() {
        show(LinkState.Offline(LinkState.Offline.REJECTED))
        compose.onNodeWithText("Offline").performClick()
        compose.onNodeWithText("The broker rejected the username or password.").assertExists()
    }

    @Test
    fun `reconnecting shows the attempt`() {
        show(LinkState.Reconnecting(3))
        compose.onNodeWithText("Reconnecting…").performClick()
        compose.onNodeWithText("Reconnecting (attempt 3)", substring = true).assertExists()
    }
}
```

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*LinkSheetTest*'`
Expected: compilation FAILS with `No parameter with name 'onSetUpAgain'`.

- [ ] **Step 3: Make the pill tappable and add the sheet**

Replace `LinkPill.kt` with:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.gekkotron.oikos.designsystem.components.PillTone
import com.gekkotron.oikos.designsystem.components.StatusPill
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

@Composable
internal fun LinkPill(link: LinkState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (label, tone) = when (link) {
        LinkState.Connecting -> R.string.pill_connecting to PillTone.WARNING
        is LinkState.Connected -> when (link.mode) {
            TransportMode.DIRECT -> R.string.pill_direct
            TransportMode.GATEWAY -> R.string.pill_gateway
            TransportMode.DEMO -> R.string.pill_demo
        } to PillTone.CONNECTED
        is LinkState.Reconnecting -> R.string.pill_reconnecting to PillTone.WARNING
        is LinkState.Offline -> R.string.pill_offline to PillTone.OFFLINE
    }
    StatusPill(
        stringResource(label),
        tone,
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(onClickLabel = stringResource(R.string.link_details), role = Role.Button, onClick = onClick),
    )
}
```

`LinkSheet.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

/** Link status and the Plan 1 way out of a profile; Settings takes this over later (spec §5.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(link: LinkState, onSetUpAgain: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(linkDescription(link), style = OikosTheme.type.body)
            OutlinedButton(onClick = onSetUpAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_set_up_again)) }
        }
    }
}

@Composable
private fun linkDescription(link: LinkState): String = when (link) {
    LinkState.Connecting -> stringResource(R.string.link_connecting)
    is LinkState.Connected -> stringResource(
        when (link.mode) {
            TransportMode.DIRECT -> R.string.link_direct
            TransportMode.GATEWAY -> R.string.link_gateway
            TransportMode.DEMO -> R.string.link_demo
        },
    )
    is LinkState.Reconnecting -> stringResource(R.string.link_reconnecting, link.attempt)
    is LinkState.Offline ->
        if (link.reason == LinkState.Offline.REJECTED) stringResource(R.string.link_rejected) else stringResource(R.string.link_offline, link.reason)
}
```

- [ ] **Step 4: Wire it into `HomeScreen`**

In `HomeScreen.kt`:
- Change `HomeRoute` to:

```kotlin
@Composable
fun HomeRoute(onSetUpAgain: () -> Unit = {}, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    HomeEventsEffect(viewModel.events, snackbar, onRetry = viewModel::send)
    HomeScreen(state, viewModel::select, viewModel::tap, viewModel::send, snackbar, onSetUpAgain = onSetUpAgain)
}
```

- Add `onSetUpAgain: () -> Unit = {},` as the last parameter of `HomeScreen` (after `windowInsets`).
- Next to `var sheetId …`, add:

```kotlin
    var linkSheet by rememberSaveable { mutableStateOf(false) }
```

- Change the header item to `HomeHeader(state, now, onPillClick = { linkSheet = true })`.
- At the end of `HomeScreen`, after the `ControlSheet` block, add:

```kotlin
    if (linkSheet) {
        LinkSheet(state.link, onSetUpAgain = { linkSheet = false; onSetUpAgain() }, onDismiss = { linkSheet = false })
    }
```

- Change `HomeHeader` to take `onPillClick: () -> Unit` and call `LinkPill(state.link, onClick = onPillClick)`.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS, including the existing `HomeScreenTest`.

If an existing test finds the pill by bounds, the 48 dp touch target adds vertical space around it. Update the expected bounds, not the touch target.

- [ ] **Step 6: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): tappable link pill with a status sheet and Set up connection again (EN/FR)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `:app` wiring, profile-driven navigation, and a check on the real broker

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/TransportSpecs.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/EntitySourceSession.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/DirectConnectionTester.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/di/DataModule.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/TransportSpecsTest.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/EntitySourceSessionTest.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/DirectConnectionTesterTest.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/TestDataModule.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt` (rewritten)

**Interfaces:**
- Consumes: everything above.
  - `ActiveTransport`, `DirectMqttTransport`, `DirectFailure`, `TransportSpec`, `EmbeddedBroker` and `freePort` (Tasks 1–2)
  - `ProfileRepository`, `ProfileState`, `ConnectionProfile`, `LocalBroker`, `KeystoreSecretStore` and `DEFAULT_PREFIX` (Tasks 3–4)
  - `ConnectionTester`, `TestState`, `TestFailure` and `OnboardingRoute` (Tasks 5–6)
  - `HomeRoute(onSetUpAgain)` (Task 7)
- Produces:
  - `fun clientIdFor(installId: String): String` and `fun setupClientIdFor(installId: String): String`
  - `fun ProfileState.toSpec(clientId: String): TransportSpec?` and `fun LocalBroker.toSpec(clientId: String): TransportSpec.Direct`
  - `class EntitySourceSession(transport, parent, diagnostics, build)`, with `fun current(prefix: String): EntitySource`
  - `class DirectConnectionTester(clientId: suspend () -> String, connectTimeout: Duration = 5.seconds) : ConnectionTester`
  - `internal fun deviceNames(devices, entities): List<String>` and `internal fun DirectFailure?.toTestFailure(): TestFailure`
  - `@HiltViewModel class AppViewModel(profiles)`, with `profile: StateFlow<ProfileState>` and `setUpAgain()`

- [ ] **Step 1: Add the dependencies, permissions and backup rules**

In `gradle/libs.versions.toml`, under `[libraries]`:

```toml
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
```

In `app/build.gradle.kts`:
- Add inside `android { … }`:

```kotlin
    packaging {
        resources {
            // HiveMQ pulls in Netty, whose jars carry duplicate metadata files.
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
```

- Add to `dependencies`:

```kotlin
    implementation(project(":core:transport"))
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding"))
    implementation(libs.androidx.lifecycle.process)
    testImplementation(testFixtures(project(":core:transport")))
```

In `app/src/main/AndroidManifest.xml`:
- Add `<uses-permission android:name="android.permission.INTERNET" />` before `<application`.
- Add `android:dataExtractionRules="@xml/data_extraction_rules"` to `<application>`. `allowBackup="false"` already stops cloud backup; these rules also stop device-to-device transfer on Android 12+.

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The profile is useless without its Keystore key, which never leaves the phone (spec §4.3). -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="datastore/profile.json" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="datastore/profile.json" />
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 2: Write the failing connection tests**

`TransportSpecsTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.transport.TransportSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TransportSpecsTest {
    private val id = "3f2a9c1e-0b7d-4e5f-9a8b-1c2d3e4f5a6b"

    @Test
    fun `client ids are short, stable and distinct for the setup test`() {
        assertEquals("oikos-3f2a9c1e", clientIdFor(id))
        assertEquals("oikos-3f2a9c1e-setup", setupClientIdFor(id))
    }

    @Test
    fun `setup client id differs from the app client id`() = assertNotEquals(clientIdFor(id), setupClientIdFor(id))

    @Test
    fun `profile states map to specs`() {
        assertNull(ProfileState.Loading.toSpec("c"))
        assertNull(ProfileState.None.toSpec("c"))
        assertNull(ProfileState.Locked.toSpec("c"))
        assertEquals(TransportSpec.Demo, ProfileState.Ready(ConnectionProfile.Demo).toSpec("c"))
        val local = LocalBroker("broker.local", 8883, tls = true, ws = false, username = "u", password = "p")
        assertEquals(
            TransportSpec.Direct("broker.local", 8883, tls = true, ws = false, username = "u", password = "p", clientId = "c"),
            ProfileState.Ready(ConnectionProfile.Direct(local)).toSpec("c"),
        )
    }
}
```

`EntitySourceSessionTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntitySourceSessionTest {
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val scopes = mutableListOf<CoroutineScope>()
    private val session = EntitySourceSession(FakeMqttTransport(), parent) { transport, scope, prefix, diagnostics ->
        scopes += scope
        EntitySourceSession.defaultBuild(transport, scope, prefix, diagnostics)
    }

    @After
    fun tearDown() = parent.cancel()

    @Test
    fun `same prefix returns the same source`() {
        val first: EntitySource = session.current("homeassistant")
        assertSame(first, session.current("homeassistant"))
        assertTrue(scopes.single().isActive)
    }

    @Test
    fun `a new prefix cancels the old source and builds a new one`() {
        val first = session.current("homeassistant")
        val second = session.current("ha")
        assertNotSame(first, second)
        assertFalse(scopes[0].isActive)
        assertTrue(scopes[1].isActive)
    }
}
```

`DirectConnectionTesterTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import com.gekkotron.oikos.transport.testing.EmbeddedBroker
import com.gekkotron.oikos.transport.testing.freePort
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DirectConnectionTesterTest {
    private val brokers = mutableListOf<EmbeddedBroker>()
    private val tester = DirectConnectionTester(clientId = { "oikos-test-setup" }, connectTimeout = 2.seconds)

    @After
    fun tearDown() = brokers.forEach { it.close() }

    private fun broker(credentials: Pair<String, String>? = null) = EmbeddedBroker(credentials).also { it.start(); brokers += it }

    private fun draft(port: Int, user: String? = null, pass: String? = null) =
        ConnectionProfile.Direct(LocalBroker("127.0.0.1", port, username = user, password = pass))

    @Test
    fun `reachable broker reports discovered devices`() = runBlocking {
        val b = broker()
        val peer = b.peer()
        val config = """{"name":"Kettle","unique_id":"kettle","command_topic":"k/set","state_topic":"k/state",
            "device":{"identifiers":["kettle"],"name":"Kitchen kettle"}}"""
        peer.publishWith().topic("homeassistant/switch/kettle/config").payload(config.encodeToByteArray())
            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        val connected = withTimeout(10.seconds) {
            tester.test(draft(b.port)).first { it is TestState.Connected && it.devices.isNotEmpty() }
        }
        assertEquals(TestState.Connected(listOf("Kitchen kettle")), connected)
        peer.disconnect()
    }

    @Test
    fun `unreachable broker fails with Unreachable after the timeout`() = runBlocking {
        val result = withTimeout(10.seconds) { tester.test(draft(freePort())).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.Unreachable), result)
    }

    @Test
    fun `rejected credentials fail with Rejected`() = runBlocking {
        val b = broker(credentials = "oikos" to "secret")
        val result = withTimeout(10.seconds) { tester.test(draft(b.port, "oikos", "wrong")).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.Rejected), result)
    }

    @Test
    fun `devices without a device block are listed by entity name, sorted`() {
        val names = deviceNames(
            devices = mapOf("d1" to device("d1", "Zeta hub")),
            entities = mapOf("a" to entity("a", "d1", "Ignored"), "b" to entity("b", null, "alpha plug")),
        )
        assertEquals(listOf("alpha plug", "Zeta hub"), names)
    }

    private fun device(id: String, name: String) = com.gekkotron.oikos.model.Device(id, name, null, null, null)

    private fun entity(id: String, deviceId: String?, name: String) =
        com.gekkotron.oikos.model.Entity(
            uniqueId = id, deviceId = deviceId, name = name, kind = com.gekkotron.oikos.model.Kind.SWITCH,
            deviceClass = null, unit = null, icon = null, capabilities = com.gekkotron.oikos.model.Capabilities(),
        )
}
```

`Entity` may have more constructor parameters than the ones shown. If so, copy the `testEntity(...)` helper from `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/TestEntities.kt` into this test file and use it instead.

Run: `./gradlew :app:testDebugUnitTest --tests '*connection*'`
Expected: compilation FAILS with `Unresolved reference: clientIdFor`, `EntitySourceSession` and `DirectConnectionTester`.

- [ ] **Step 3: Implement the connection glue**

`TransportSpecs.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.transport.TransportSpec

fun clientIdFor(installId: String): String = "oikos-" + installId.filter { it != '-' }.take(8)

/** The onboarding test connection has its own id: a duplicate id makes the broker kick the other client. */
fun setupClientIdFor(installId: String): String = clientIdFor(installId) + "-setup"

fun ProfileState.toSpec(clientId: String): TransportSpec? = when (this) {
    is ProfileState.Ready -> when (val p = profile) {
        ConnectionProfile.Demo -> TransportSpec.Demo
        is ConnectionProfile.Direct -> p.local.toSpec(clientId)
    }
    ProfileState.Loading, ProfileState.None, ProfileState.Locked -> null
}

fun LocalBroker.toSpec(clientId: String): TransportSpec.Direct = TransportSpec.Direct(host, port, tls, ws, username, password, clientId)
```

`EntitySourceSession.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the discovery source for the current prefix (spec §2). The prefix is a constructor argument
 * of [MqttDiscoverySource], so a new prefix means a new source, and the old one's scope is cancelled.
 */
class EntitySourceSession(
    private val transport: MqttTransport,
    private val parent: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    private val build: (MqttTransport, CoroutineScope, String, DiagnosticSink) -> EntitySource =
        { transport, scope, prefix, diagnostics -> defaultBuild(transport, scope, prefix, diagnostics) },
) {
    private var prefix: String? = null
    private var scope: CoroutineScope? = null
    private var source: EntitySource? = null

    @Synchronized
    fun current(prefix: String): EntitySource {
        source?.let { if (prefix == this.prefix) return it }
        scope?.cancel()
        val child = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        return build(transport, child, prefix, diagnostics).also {
            scope = child
            source = it
            this.prefix = prefix
        }
    }

    companion object {
        fun defaultBuild(transport: MqttTransport, scope: CoroutineScope, prefix: String, diagnostics: DiagnosticSink): EntitySource =
            MqttDiscoverySource(transport, scope, prefix = prefix, diagnostics = diagnostics)
    }
}
```

`DirectConnectionTester.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.feature.onboarding.ConnectionTester
import com.gekkotron.oikos.feature.onboarding.TestFailure
import com.gekkotron.oikos.feature.onboarding.TestState
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.DirectFailure
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.LinkState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Onboarding's connection test (spec §5.1). It uses a temporary transport and discovery source, so
 * the device count comes from the real parser. Cancelling the flow closes the connection.
 */
class DirectConnectionTester(
    private val clientId: suspend () -> String,
    private val connectTimeout: Duration = 5.seconds,
) : ConnectionTester {
    override fun test(draft: ConnectionProfile.Direct): Flow<TestState> = channelFlow {
        send(TestState.Connecting)
        val transport = DirectMqttTransport(draft.local.toSpec(clientId()), this)
        try {
            transport.start()
            // null = still trying; true = connected; a failure = definitive (no point waiting for the timeout).
            val outcome = withTimeoutOrNull(connectTimeout) {
                combine(transport.link, transport.failure) { link, failure ->
                    when {
                        link is LinkState.Connected -> true
                        failure == DirectFailure.Rejected || failure is DirectFailure.Tls -> failure
                        else -> null
                    }
                }.first { it != null }
            }
            if (outcome != true) {
                send(TestState.Failed((outcome as? DirectFailure ?: transport.failure.value).toTestFailure()))
                return@channelFlow
            }
            val source = MqttDiscoverySource(transport, this, prefix = draft.prefix)
            combine(source.devices, source.entities, ::deviceNames)
                .distinctUntilChanged()
                .collect { send(TestState.Connected(it)) }
        } finally {
            withContext(NonCancellable) { transport.close() }
        }
    }
}

/** Device names, plus entities that belong to no known device; sorted case-insensitively. */
internal fun deviceNames(devices: Map<String, Device>, entities: Map<String, Entity>): List<String> {
    val loose = entities.values.filter { it.deviceId == null || it.deviceId !in devices }.map { it.name }
    return (devices.values.map { it.name } + loose).sortedBy { it.lowercase() }
}

internal fun DirectFailure?.toTestFailure(): TestFailure = when (this) {
    null, DirectFailure.Unreachable -> TestFailure.Unreachable
    DirectFailure.Rejected -> TestFailure.Rejected
    is DirectFailure.Tls -> TestFailure.Tls
    is DirectFailure.Other -> TestFailure.Other(message)
}
```

Run: `./gradlew :app:testDebugUnitTest --tests '*connection*'`
Expected: PASS.

- [ ] **Step 4: Rewrite the Hilt graph**

`DataModule.kt`:

```kotlin
package com.gekkotron.oikos.di

import android.content.Context
import com.gekkotron.oikos.data.KeystoreSecretStore
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun secretStore(): SecretStore = KeystoreSecretStore()

    @Provides
    @Singleton
    fun profiles(@ApplicationContext context: Context, secrets: SecretStore, @ApplicationScope scope: CoroutineScope): ProfileRepository =
        ProfileRepository.create(context, secrets, scope)
}
```

Replace `AppModule.kt` with:

```kotlin
package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.connection.DirectConnectionTester
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.clientIdFor
import com.gekkotron.oikos.connection.setupClientIdFor
import com.gekkotron.oikos.connection.toSpec
import com.gekkotron.oikos.data.DEFAULT_PREFIX
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.feature.onboarding.ConnectionTester
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.ActiveTransport
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun activeTransport(profiles: ProfileRepository, @ApplicationScope scope: CoroutineScope): ActiveTransport = ActiveTransport(
        specs = profiles.state.map { it.toSpec(clientIdFor(profiles.installId())) },
        factory = { spec ->
            when (spec) {
                TransportSpec.Demo -> DemoBroker(scope)
                is TransportSpec.Direct -> DirectMqttTransport(spec, scope)
            }
        },
        scope = scope,
    )

    @Provides
    fun transport(active: ActiveTransport): MqttTransport = active

    @Provides
    @Singleton
    fun entitySourceSession(transport: MqttTransport, @ApplicationScope scope: CoroutineScope): EntitySourceSession =
        EntitySourceSession(transport, scope, diagnostics = { Log.w("Oikos", "${it.source}: ${it.message}") })

    /** Unscoped: each new Home gets the source for the current prefix (spec §2). Home only exists while Ready. */
    @Provides
    fun entitySource(session: EntitySourceSession, profiles: ProfileRepository): EntitySource =
        session.current((profiles.state.value as? ProfileState.Ready)?.profile?.prefix ?: DEFAULT_PREFIX)

    @Provides
    @Singleton
    fun connectionTester(profiles: ProfileRepository): ConnectionTester =
        DirectConnectionTester(clientId = { setupClientIdFor(profiles.installId()) })
}
```

- [ ] **Step 5: Add the app ViewModel, navigation and foreground tracking**

`AppViewModel.kt`:

```kotlin
package com.gekkotron.oikos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    val profile: StateFlow<ProfileState> = profiles.state

    /** Forgets the profile. The transport closes and the app returns to onboarding. */
    fun setUpAgain() {
        viewModelScope.launch { profiles.clear() }
    }
}
```

Replace `OikosNavHost.kt` with:

```kotlin
package com.gekkotron.oikos.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gekkotron.oikos.AppViewModel
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.feature.home.ui.HomeRoute
import com.gekkotron.oikos.feature.onboarding.ui.OnboardingRoute
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingKey : NavKey

@Serializable
data object HomeKey : NavKey

/** The saved profile picks the root: Ready → Home; none or locked → onboarding (spec §2). */
@Composable
fun OikosNavHost(viewModel: AppViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val root: NavKey = when (profile) {
        ProfileState.Loading -> return // a few milliseconds at launch; the background is already drawn
        is ProfileState.Ready -> HomeKey
        ProfileState.None, ProfileState.Locked -> OnboardingKey
    }
    // A new root (onboarding finished, or "set up again") starts a fresh back stack.
    key(root) {
        val backStack = rememberNavBackStack(root)
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<OnboardingKey> { OnboardingRoute() }
                entry<HomeKey> { HomeRoute(onSetUpAgain = viewModel::setUpAgain) }
            },
        )
    }
}
```

Replace `OikosApplication.kt` with:

```kotlin
package com.gekkotron.oikos

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gekkotron.oikos.transport.ActiveTransport
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OikosApplication : Application() {
    @Inject
    lateinit var transport: ActiveTransport

    override fun onCreate() {
        super.onCreate()
        // Connected only in the foreground; ActiveTransport closes 30 s after onStop (spec §3.3).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = transport.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = transport.setForeground(false)
        })
    }
}
```

- [ ] **Step 6: Replace the data module in tests and rewrite `MainActivityTest`**

`TestDataModule.kt`:

```kotlin
package com.gekkotron.oikos

import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.di.ApplicationScope
import com.gekkotron.oikos.di.DataModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import javax.inject.Singleton

/** Robolectric has no AndroidKeyStore, and each test needs its own file (one DataStore per file per process). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule {
    @Provides
    @Singleton
    fun profiles(@ApplicationScope scope: CoroutineScope): ProfileRepository =
        ProfileRepository.create(Files.createTempDirectory("oikos").resolve("profile.json").toFile(), PassThrough, scope)

    private object PassThrough : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }
}
```

Replace `MainActivityTest.kt` with:

```kotlin
package com.gekkotron.oikos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real graph: ProfileRepository → ActiveTransport → DemoBroker → MqttDiscoverySource → Home. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h914dp")
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openDemo() {
        compose.waitUntilAtLeastOneExists(hasText("Try the demo"), timeoutMillis = 10_000)
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
    }

    @Test
    fun `fresh install opens onboarding and the demo shows the demo house`() {
        openDemo()
        compose.onNodeWithText("Demo").assertExists()
        compose.waitUntilAtLeastOneExists(hasText("Living room"), timeoutMillis = 10_000)
    }

    @Test
    fun `set up connection again returns to welcome`() {
        openDemo()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Set up connection again").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Try the demo"), timeoutMillis = 10_000)
    }
}
```

- [ ] **Step 7: Run the full check**

Run: `./gradlew check koverXmlReport :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with every module's tests passing. `build/reports/kover/report.xml` must include `com/gekkotron/oikos/data` and `com/gekkotron/oikos/feature/onboarding`.

Run: `./gradlew koverLog` (or open `build/reports/kover/html/index.html`).
Expected: overall line coverage stays at or above the 80 % target in `codecov.yml`. If it drops, add tests for the uncovered lines in this plan's code. Never add excludes for them.

- [ ] **Step 8: Install and check on the phone against the real broker**

Run: `./gradlew :app:installDebug` with the phone on USB and on the home Wi-Fi.

Check each item and write down the result:
1. First launch shows Welcome.
2. **Enter manually** with your LAN broker's address shows "Discovering… N devices" with real device names.
3. **Continue** opens Home, with the pill showing "Wi-Fi direct". Toggling a light works; the pending dot clears when the broker confirms.
4. Kill and relaunch the app: it goes straight to Home.
5. Background the app for more than 30 s: the broker log shows the `oikos-…` client disconnecting. Returning reconnects, and cards refresh.
6. Tap the pill, then **Set up connection again**: Welcome appears, and the broker log shows the disconnect.
7. **Try the demo** shows the demo house.
8. On the Scan screen, try a screenshot: it is blocked.

For the QR path, generate a code from a local, git-ignored JSON file, for example `qrencode -t ansiutf8 < setup.json`, and scan it.

Report anything that fails here as a bug with steps to reproduce. Do not mark the task done.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(app): profile-driven transport and navigation, onboarding wiring and foreground-only connection" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes (for the executor)

- **Spec coverage:**

  | Spec section | Task |
  |---|---|
  | §1 success criteria 1–5 | Task 8 step 8, plus the pinned tests |
  | §2 wiring, `EntitySourceSession`, start screen, dependency rule | Task 8 |
  | §3.1–3.3 | Tasks 1–2 |
  | §4.1–4.4 | Tasks 3–4 |
  | §4.5 plain-TCP warning | Tasks 3 and 5–6 |
  | §5 screens | Task 6 |
  | §5.1 tester | Task 8 |
  | §5.2 "Set up connection again" | Tasks 7–8 |
  | §6 error table | Tasks 2, 5, 6, 7 |
  | §7 tests | every task |
  | §8 dependencies | Tasks 2, 4, 6, 8 |

- **Deliberate small deviations from the spec text:**
  - `ActiveTransport` follows transports with explicit undispatched collector jobs rather than `flatMapLatest`. That is the only way to guarantee that retained messages replayed on start are not lost; the spec's intent ("messages from a closed transport are never emitted") is unchanged.
  - During `Loading`, the nav host draws only the background rather than holding the system splash.
  - `allowBackup` was already `false`; the new `dataExtractionRules` also cover device-to-device transfer.
- **Things to verify, not assume:**
  - Moquette property keys (Task 2, step 2);
  - Netty version alignment (Task 2, step 1);
  - how HiveMQ wraps a refused CONNACK (Task 2, step 7);
  - the `Entity` constructor (Task 8, step 2);
  - the `@ExperimentalGetImage` placement (Task 6).

  Each note says what to check and how to fix it without weakening a test.
- **Later plans:**
  - `GatewayTransport` and `TransportSelector` add `TransportSpec.Gateway` and a third factory branch; the `when` in `AppModule` will fail to compile until they do.
  - The entity cache and room assignment are deferred.
  - Settings takes over "Set up connection again" and theme persistence.
