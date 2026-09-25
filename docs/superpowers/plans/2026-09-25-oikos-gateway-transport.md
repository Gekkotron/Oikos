# Gateway Transport and Route Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Oikos reaches the house from anywhere. Away from home it goes through Athena-HttpMqttGateway, using the gateway's own Kotlin SDK. It picks the route on its own and switches make-before-break without losing messages.

**Architecture:**
- **`:core:transport`**
  - `AthenaGatewayApi` adapts `AthenaGatewayClient` (the `athena-gateway-client` SDK, v1.1) to a small `GatewayApi` seam. The SDK owns the wire format: envelope crypto, SSE, error decoding.
  - `GatewayTransport` runs one `GatewayStream` per topic set, and owns reconnecting and backoff. It calls the SDK's `subscribe` without `reconnect`.
  - `TransportSelector` chooses Direct or Gateway for a `TransportSpec.Routed` (probe, network changes, re-probe, "rejected" fallback).
  - Both hand over between transports with a shared `Handover` (make-before-break, buffered).
  - `ActiveTransport` does not change: it still owns the foreground lifecycle and break-before-make on profile changes.
- **`:core:data`** stores the route choice outside `ConnectionProfile`.
- **`:app`** maps profiles to specs, builds the transports, and feeds default-network changes.
- **Home** gets a route picker in the link sheet.
- **Onboarding's tester** falls back to the gateway.

**Tech Stack:** Kotlin 2.4, coroutines/Flow, `com.github.Gekkotron.Athena-HttpMqttGateway:athena-gateway-client:v1.1` (JitPack; brings OkHttp 4.12), `okhttp3:mockwebserver` 4.12 for tests, `EnvelopeCrypto` (`:core:crypto`, tests only, to play the gateway), Hilt, Jetpack Compose, JUnit Jupiter (JVM modules), JUnit4 + Robolectric (Android modules), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-25-oikos-gateway-transport-design.md` (parents: `2026-09-24-oikos-design.md` §3.A, §4.5, §7, §8; `2026-09-24-oikos-direct-connection-design.md`). SDK docs: `sdk/kotlin/README.md` in `Gekkotron/Athena-HttpMqttGateway`.

**Branch:** `feat/gateway-transport`, created from `master` (at or after `05b95ca`).

**Prerequisite (manual, before the phone check):** the house gateway runs server **v1.1 or later**. The SDK needs the `source: "gateway"` tag (commit `d80e0d3`) to tell a gateway refusal from an upstream error. Update it with `update.sh` on the gateway host.

## Global Constraints

- **SDK use**
  - Build `AthenaGatewayClient(baseUrl, secretKey = key as 64 lowercase hex, okHttpClient)`.
  - Call `subscribe(*topics.sorted(), qos = 1, broker = …)` **without** `reconnect`: `GatewayTransport` owns reconnecting.
  - Call `publish(topic, payload.decodeToString(), qos, retain, broker)`.
  - `Broker(host = brokerHost, username, password)`; never set `port`.
- **Timings**
  - Stream idle timeout: **45 s** (the SDK's). Publish call timeout: **10 s**, set as `callTimeout` on the OkHttp client passed to the SDK; the SDK clears it for streams.
  - Subscription debounce **300 ms**; switch (make-before-break) timeout **15 s**.
  - Probe **1.5 s**; network debounce **1 s**; direct `Reconnecting` debounce **2 s**; re-probe while not on direct **60 s**.
  - Backoff 1 s ×2 up to 30 s, ±20 % jitter. The attempt count resets after a stream stays connected **30 s**.
- **New `LinkState.Offline` constants** (exact strings): `KEY_REJECTED = "gateway key not accepted"`, `CLOCK_SKEW = "phone clock is off"`, `GATEWAY_REFUSED = "gateway refused"`.
- **Secrets**
  - No secret in any `toString`: `TransportSpec.Gateway` masks the key and password, and the SDK's `Broker` masks the password.
  - No secret in logs or diagnostics. Never pass `GatewayException` messages to logs verbatim (SDK README).
  - The route choice is **not** part of `ConnectionProfile`.
- **Strings**
  - EN and FR string files keep the same keys.
  - UI code never shows an `Offline.reason` raw.
- **Cleartext**
  - `AthenaGatewayApi`'s public constructor rejects any URL that does not start with `https://`.
  - `network_security_config.xml` sets `cleartextTrafficPermitted="false"` and uses system trust anchors only.
- **Commits** use `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`. Each message ends with the line `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `./gradlew check` must pass at the end.

## Review Focus

1. **Leaving Wi-Fi mid-session.** The direct transport sits in `Reconnecting`, is not `Connected`, and no network callback arrives. The selector must still move to the gateway about 2 s later, flipping at once because the current transport is not connected. Cards keep their state. Test in Task 6.
2. **A burst of subscription changes through the gateway**, as during discovery. It must open **one** stream with the final topic set, not one per call. Test in Task 5.
3. **An empty retained payload** (a discovery removal) through the gateway arrives as `""`. It must become an empty byte array so the entity disappears, not the two bytes `""`. Test in Task 2 (unit) and Task 3 (through the SDK).
4. **Going to the background while a switch is pending.** `ActiveTransport` closes the selector while `Handover.switchTo` waits for the new transport. Both the current and the pending transport must be closed, and neither may keep a connection open. Test in Task 4 (Handover) and Task 6 (selector).
5. **Changing the route while connected.** The transport is rebuilt, but the profile state stays equal, so the discovery source and the house on screen survive. Test in Task 7 (repository) and Task 8 (spec mapping).

---

## File map

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | + JitPack, limited to the SDK's group |
| `gradle/libs.versions.toml` | + `athena-gateway-client`, `mockwebserver` |
| `core/transport/build.gradle.kts` | + SDK (api), `:core:crypto` and mockwebserver (test) |
| `core/transport/.../transport/TransportSpec.kt` | + `Gateway`, `Routed`, `Route` |
| `core/transport/.../transport/MqttTransport.kt` | + 3 offline reasons |
| `core/transport/.../transport/Backoff.kt` (new) | reconnect delays |
| `core/transport/.../transport/GatewayApi.kt` (new) | `GatewayApi`, `GatewayEvent`, `Refusal`, `GatewayRefused`, payload re-encoding |
| `core/transport/.../transport/AthenaGatewayApi.kt` (new) | `GatewayApi` over the SDK: event and error mapping |
| `core/transport/.../transport/Handover.kt` (new) | make-before-break between two `ManagedTransport`s |
| `core/transport/.../transport/GatewayStream.kt` (new) | one SDK stream as a `ManagedTransport` |
| `core/transport/.../transport/GatewayTransport.kt` (new) | streams, resubscribe, reconnect, publish |
| `core/transport/.../transport/LocalProbe.kt` (new) | `LocalProbe`, `TcpProbe` |
| `core/transport/.../transport/TransportSelector.kt` (new) | route selection |
| `core/transport/.../transport/ActiveTransport.kt` | doc comment only |
| `core/transport/src/testFixtures/.../testing/FakeGatewayApi.kt` (new) | scripted gateway for tests |
| `core/data/.../data/ConnectionProfile.kt`, `StoredProfile.kt`, `ProfileRepository.kt` | `RoutePreference`, `route`, `setRoute` |
| `app/.../connection/TransportSpecs.kt` | profile + route → spec, `routeChoice` |
| `app/.../connection/TransportFactory.kt` (new) | spec → transport |
| `app/.../connection/NetworkChanges.kt` (new) | default-network callback as a `Flow<Unit>` |
| `app/.../di/AppModule.kt`, `AppViewModel.kt`, `navigation/OikosNavHost.kt` | wiring, route state |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml` (new) | permission, cleartext off |
| `feature/home/.../ui/LinkSheet.kt`, `HomeScreen.kt`, `RoomPicker.kt`, `res/values*/strings.xml` | route picker, offline texts |
| `feature/onboarding/.../ConnectionTester.kt`, `ui/Messages.kt`, `ui/ConnectScreen.kt`, `res/values*/strings.xml` | new failures, `viaGateway` |
| `app/.../connection/DirectConnectionTester.kt` | gateway fallback |

Paths abbreviate `src/main/kotlin/com/gekkotron/oikos/`. Tests live in the matching `src/test/kotlin/...`.

---

### Task 1: Spec types, offline reasons, backoff, dependencies

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `core/transport/build.gradle.kts`
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TransportSpec.kt`
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/MqttTransport.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/Backoff.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/BackoffTest.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TransportSpecTest.kt`

**Interfaces:**
- Produces:
  - `TransportSpec.Gateway(url: String, key: ByteArray, brokerHost: String?, username: String?, password: String?)` with `key: ByteArray` (a copy) and content equality.
  - `data class TransportSpec.Routed(direct: TransportSpec.Direct, gateway: TransportSpec.Gateway, route: Route)` and `enum class Route { AUTO, DIRECT, GATEWAY }`.
  - `LinkState.Offline.KEY_REJECTED`, `CLOCK_SKEW` and `GATEWAY_REFUSED`.
  - `class Backoff(initial = 1.seconds, max = 30.seconds, jitter = 0.2, random: Random = Random.Default)` with `fun delayFor(attempt: Int): Duration`. This is Oikos's own class, not the SDK's `Backoff`.

- [ ] **Step 1: Add the dependencies**

In `settings.gradle.kts`, inside `dependencyResolutionManagement { repositories { … } }`, after `google()`, add:

```kotlin
        // The gateway's own Kotlin SDK (athena-gateway-client), built by JitPack from its tags.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Gekkotron.Athena-HttpMqttGateway") }
        }
```

In `gradle/libs.versions.toml`, under `[versions]` after `firebaseAppDistribution`, add:

```toml
athenaGatewayClient = "v1.1"
# Test-only; matches the OkHttp 4.12 that athena-gateway-client brings.
okhttpMockwebserver = "4.12.0"
```

Under `[libraries]`, at the end, add:

```toml
athena-gateway-client = { module = "com.github.Gekkotron.Athena-HttpMqttGateway:athena-gateway-client", version.ref = "athenaGatewayClient" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttpMockwebserver" }
```

Replace the `dependencies` block of `core/transport/build.gradle.kts` with:

```kotlin
dependencies {
    api(libs.kotlinx.coroutines.core)
    // api: :app builds AthenaGatewayApi with its shared OkHttpClient (OkHttp comes with the SDK).
    api(libs.athena.gateway.client)
    implementation(libs.hivemq.mqtt.client)
    testImplementation(project(":core:crypto")) // seals replies as the gateway would
    testImplementation(libs.mockwebserver)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.hivemq.mqtt.client)
    testFixturesImplementation(libs.moquette.broker)
}
```

Run: `./gradlew :core:transport:dependencies --configuration runtimeClasspath | grep -E "athena|okhttp"`
Expected: `athena-gateway-client:v1.1` and `okhttp:4.12.0` resolve, with no `FAILED`.

- [ ] **Step 2: Write the failing tests**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/BackoffTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun `without jitter the delay doubles up to the cap`() {
        val b = Backoff(jitter = 0.0)
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }, (1..7).map(b::delayFor))
    }

    @Test
    fun `jitter stays within plus or minus 20 percent`() {
        val b = Backoff(random = Random(42))
        repeat(200) {
            val first = b.delayFor(1)
            assertTrue(first in 800.milliseconds..1200.milliseconds, "attempt 1: $first")
            val capped = b.delayFor(10)
            assertTrue(capped in 24.seconds..36.seconds, "attempt 10: $capped")
        }
    }

    @Test
    fun `a huge attempt number does not overflow`() {
        assertEquals(30.seconds, Backoff(jitter = 0.0).delayFor(10_000))
    }

    @Test
    fun `attempts start at 1`() {
        assertFailsWith<IllegalArgumentException> { Backoff().delayFor(0) }
    }
}
```

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TransportSpecTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class TransportSpecTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun gateway(k: ByteArray = key) = TransportSpec.Gateway("https://gw.example", k, "192.168.1.91", "oikos", "hunter2")

    @Test
    fun `gateway specs compare by content, including the key`() {
        assertEquals(gateway(), gateway(key.copyOf()))
        assertEquals(gateway().hashCode(), gateway(key.copyOf()).hashCode())
        assertNotEquals(gateway(), gateway(ByteArray(32)))
    }

    @Test
    fun `gateway key is a defensive copy`() {
        val g = gateway()
        g.key[0] = 99
        assertEquals(0, g.key[0])
    }

    @Test
    fun `no secret appears in toString, even inside a routed spec`() {
        val direct = TransportSpec.Direct("h", 1883, tls = false, ws = false, username = "oikos", password = "hunter2", clientId = "c")
        val text = TransportSpec.Routed(direct, gateway(), Route.AUTO).toString()
        assertFalse("hunter2" in text, text)
        assertFalse(key.joinToString("") { "%02x".format(it) } in text, text)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:transport:test --tests '*BackoffTest*' --tests '*TransportSpecTest*'`
Expected: compilation fails with "Unresolved reference: Backoff", "Gateway", "Routed", "Route".

- [ ] **Step 4: Implement**

Replace the whole of `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TransportSpec.kt` with:

```kotlin
package com.gekkotron.oikos.transport

/** What [ActiveTransport] should run (spec §2; gateway spec §3.1). */
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

    /** Athena-HttpMqttGateway. [key] (AES-256) and [password] are secrets; [toString] masks them. */
    class Gateway(
        val url: String,
        key: ByteArray,
        val brokerHost: String?,
        val username: String?,
        val password: String?,
    ) : TransportSpec {
        private val keyBytes = key.copyOf()
        val key: ByteArray get() = keyBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Gateway && url == other.url && brokerHost == other.brokerHost && username == other.username &&
                password == other.password && keyBytes.contentEquals(other.keyBytes)

        override fun hashCode(): Int = listOf(url, brokerHost, username, password).hashCode() * 31 + keyBytes.contentHashCode()

        override fun toString(): String =
            "Gateway($url, brokerHost=$brokerHost, user=$username, password=${if (password == null) "none" else "***"}, key=***)"
    }

    /** A profile with both routes; [TransportSelector] picks one (gateway spec §3.5). */
    data class Routed(val direct: Direct, val gateway: Gateway, val route: Route) : TransportSpec
}

/** The user's route choice for a [TransportSpec.Routed] profile. */
enum class Route { AUTO, DIRECT, GATEWAY }
```

In `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/MqttTransport.kt`, inside `LinkState.Offline`'s companion object, after `const val FAILED = "failed to start"`, add:

```kotlin
            /** Gateway HTTP 401: no key on the gateway matched ours. */
            const val KEY_REJECTED = "gateway key not accepted"

            /** Gateway 403 "Request expired": the phone's clock is outside the gateway's window. */
            const val CLOCK_SKEW = "phone clock is off"

            /** Any other gateway 403 (port or destination not allowed for this key). */
            const val GATEWAY_REFUSED = "gateway refused"
```

Create `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/Backoff.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Reconnect delay: [initial] doubled per attempt up to [max], then ±[jitter] (parent spec §4.5 step 6). */
class Backoff(
    private val initial: Duration = 1.seconds,
    private val max: Duration = 30.seconds,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** The delay before reconnect [attempt], counting from 1. */
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempts start at 1" }
        val doubled = initial * (1L shl min(attempt - 1, 20)).toDouble()
        val capped = minOf(doubled, max)
        if (jitter == 0.0) return capped
        return capped * (1 + random.nextDouble(-jitter, jitter))
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:transport:test --tests '*BackoffTest*' --tests '*TransportSpecTest*'`
Expected: PASS.

- [ ] **Step 6: Run the whole module**

Run: `./gradlew :core:transport:test`
Expected: PASS. The `when` over `TransportSpec` in `:app` is not compiled by this module; Task 8 makes it exhaustive.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(transport): gateway and routed specs, gateway offline reasons, jittered backoff; athena-gateway-client dependency" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The gateway seam: events, refusals, payload re-encoding

**Files:**
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayApi.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayApiTest.kt`

**Interfaces:**
- Consumes: from Task 1, the `LinkState.Offline` constants.
- Produces:
  - `interface GatewayApi` with `fun stream(topics: Set<String>): Flow<GatewayEvent>` and `suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean)`.
  - `sealed interface GatewayEvent` with `Connected` and `Message(message: MqttMessage)`.
  - `sealed interface Refusal` (`KeyRejected`, `ClockSkew`, `BrokerRejected`, `Forbidden(reason)`), each with `val offlineReason: String`.
  - `class GatewayRefused(val refusal: Refusal) : Exception`.
  - `internal fun JsonElement?.toPayloadBytes(): ByteArray` and `internal fun errorText(body: JsonElement?): String?`.
  - `stream` completes when the gateway ends the stream, throws `GatewayRefused` for anything that needs the user, and throws `IOException` for anything worth retrying.

- [ ] **Step 1: Write the failing test**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayApiTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GatewayApiTest {
    private fun bytesOf(json: String) = Json.parseToJsonElement(json).toPayloadBytes().decodeToString()

    @Test
    fun `payloads are re-encoded - strings raw, other JSON compact`() {
        assertEquals("ON", bytesOf("\"ON\""))
        assertEquals("""{"state":"ON","brightness":120}""", bytesOf("""{"state": "ON", "brightness": 120}"""))
        assertEquals("21.5", bytesOf("21.5"))
        assertEquals("true", bytesOf("true"))
        assertEquals("[1,2]", bytesOf("[1, 2]"))
        assertEquals("null", JsonNull.toPayloadBytes().decodeToString())
    }

    @Test
    fun `an empty payload becomes zero bytes, so a discovery removal still removes`() {
        assertEquals(0, JsonPrimitive("").toPayloadBytes().size)
        assertEquals(0, null.toPayloadBytes().size)
    }

    @Test
    fun `each refusal names its offline reason`() {
        assertEquals(LinkState.Offline.KEY_REJECTED, Refusal.KeyRejected.offlineReason)
        assertEquals(LinkState.Offline.CLOCK_SKEW, Refusal.ClockSkew.offlineReason)
        assertEquals(LinkState.Offline.REJECTED, Refusal.BrokerRejected.offlineReason)
        assertEquals(LinkState.Offline.GATEWAY_REFUSED, Refusal.Forbidden("destination not allowed for this secret").offlineReason)
    }

    @Test
    fun `error text reads an object or a JSON string holding one`() {
        assertEquals("boom", errorText(Json.parseToJsonElement("""{"error":"boom"}""")))
        assertEquals("boom", errorText(JsonPrimitive("""{"error": "boom"}""")))
        assertEquals("Failed with code 4", errorText(Json.parseToJsonElement("""{"success":false,"message":"Failed with code 4"}""")))
        assertNull(errorText(JsonPrimitive("plain text")))
        assertNull(errorText(null))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:transport:test --tests '*GatewayApiTest*'`
Expected: compilation fails with "Unresolved reference: toPayloadBytes", "Refusal", "errorText".

- [ ] **Step 3: Implement**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayApi.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The gateway as [GatewayTransport] needs it (gateway spec §3.3). [AthenaGatewayApi] implements it over the
 * gateway's own SDK; tests use `FakeGatewayApi`.
 */
interface GatewayApi {
    /**
     * One `/mqtt/subscribe` stream, never reconnected here. Completes when the gateway ends it; throws
     * [GatewayRefused] when only the user can fix it, and an `IOException` for anything worth retrying.
     */
    fun stream(topics: Set<String>): Flow<GatewayEvent>

    /** @throws PublishFailed */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean)
}

sealed interface GatewayEvent {
    /** The gateway connected to the broker and subscribed. */
    data object Connected : GatewayEvent

    data class Message(val message: MqttMessage) : GatewayEvent
}

/** Why the gateway refused us. None of these retry: they need the user (gateway spec §6). */
sealed interface Refusal {
    val offlineReason: String

    data object KeyRejected : Refusal {
        override val offlineReason: String get() = LinkState.Offline.KEY_REJECTED
    }

    data object ClockSkew : Refusal {
        override val offlineReason: String get() = LinkState.Offline.CLOCK_SKEW
    }

    /** The broker refused the gateway's login with our credentials (CONNACK 4 or 5). */
    data object BrokerRejected : Refusal {
        override val offlineReason: String get() = LinkState.Offline.REJECTED
    }

    data class Forbidden(val reason: String) : Refusal {
        override val offlineReason: String get() = LinkState.Offline.GATEWAY_REFUSED
    }
}

class GatewayRefused(val refusal: Refusal) : Exception("gateway refused: $refusal")

/**
 * The gateway JSON-decodes every payload (gateway spec §2): a string comes back as its raw text (so `""`
 * is still an empty payload), anything else as compact JSON that templates can still parse.
 */
internal fun JsonElement?.toPayloadBytes(): ByteArray = when {
    this == null -> ByteArray(0)
    this is JsonPrimitive && isString -> content.encodeToByteArray()
    else -> toString().encodeToByteArray()
}

/** The gateway's `body` is an object, or a JSON string holding one (gateway spec §2). */
internal fun errorText(body: JsonElement?): String? {
    val obj = when (body) {
        is JsonObject -> body
        is JsonPrimitive -> if (body.isString) {
            try {
                Json.parseToJsonElement(body.content) as? JsonObject
            } catch (e: SerializationException) {
                null
            }
        } else {
            null
        }
        else -> null
    }
    return listOf("error", "message").firstNotNullOfOrNull { key ->
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:transport:test --tests '*GatewayApiTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(transport): gateway seam - events, refusals, payloads re-encoded from the gateway's JSON" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `AthenaGatewayApi` (adapter over the SDK)

**Files:**
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/AthenaGatewayApi.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/AthenaGatewayApiTest.kt`

**Interfaces:**
- Consumes:
  - From Task 1: `TransportSpec.Gateway`.
  - From Task 2: `GatewayApi`, `GatewayEvent`, `Refusal`, `GatewayRefused`, `toPayloadBytes` and `errorText`.
  - From the SDK: `AthenaGatewayClient`, `Broker`, `MqttEvent` and `GatewayException`.
  - In tests only: `EnvelopeCrypto` (`:core:crypto`).
- Produces:
  - `class AthenaGatewayApi(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient())`, which requires https.
  - Its internal test constructor is `(client: AthenaGatewayClient, broker: Broker?)`.
  - `internal fun Throwable.toStreamFailure(): Throwable` and `internal fun GatewayException.publishText(): String`.

- [ ] **Step 1: Write the failing test**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/AthenaGatewayApiTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.crypto.EnvelopeCrypto
import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.Broker
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The real SDK against MockWebServer playing the gateway (replies sealed with our own EnvelopeCrypto). */
class AthenaGatewayApiTest {
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val crypto = EnvelopeCrypto(key)
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun api(broker: Broker? = Broker(host = "192.168.1.91", username = "oikos", password = "hunter2")) = AthenaGatewayApi(
        AthenaGatewayClient(server.url("/").toString(), key.toHex(), OkHttpClient()),
        broker,
    )

    private fun seal(json: String) = crypto.seal(Json.parseToJsonElement(json).jsonObject)
    private fun event(json: String) = "data: ${seal(json)}\n\n"
    private fun sse(vararg events: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString(""))
    private fun envelope(json: String) =
        MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(seal(json))
    private fun sent(): JsonObject = crypto.open(server.takeRequest().body.readUtf8())

    @Test
    fun `subscribe sends sorted topics, qos 1 and the broker inside the envelope`() = runBlocking {
        server.enqueue(sse(event("""{"type":"connected","topic":"a","topics":["a"]}""")))
        assertEquals(listOf<GatewayEvent>(GatewayEvent.Connected), api().stream(setOf("zigbee2mqtt/#", "homeassistant/#")).toList())
        val request = server.takeRequest()
        assertEquals("/mqtt/subscribe", request.path)
        val raw = request.body.readUtf8()
        assertFalse("hunter2" in raw, "the password travels only inside the envelope")
        val body = crypto.open(raw)
        assertEquals(listOf("homeassistant/#", "zigbee2mqtt/#"), body["topics"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(1), body["qos"])
        assertEquals(JsonPrimitive("192.168.1.91"), body["broker_host"])
        assertEquals(JsonPrimitive("oikos"), body["username"])
        assertEquals(JsonPrimitive("hunter2"), body["password"])
        assertFalse("broker_port" in body)
    }

    @Test
    fun `messages keep topic and retain, and payloads are re-encoded`() = runBlocking {
        server.enqueue(
            sse(
                event("""{"type":"connected"}"""),
                event("""{"type":"message","topic":"zigbee2mqtt/lamp","payload":{"state":"ON"},"qos":1,"retain":true}"""),
                event("""{"type":"message","topic":"homeassistant/light/x/config","payload":"","qos":0,"retain":true}"""),
            ),
        )
        val messages = api().stream(setOf("#")).toList().filterIsInstance<GatewayEvent.Message>().map { it.message }
        assertEquals(MqttMessage("zigbee2mqtt/lamp", """{"state":"ON"}""".encodeToByteArray(), retained = true), messages[0])
        assertEquals(0, messages[1].payload.size, "an empty retained payload still removes the entity")
    }

    @Test
    fun `a disconnected frame ends the stream normally`() = runBlocking {
        server.enqueue(sse(event("""{"type":"connected"}"""), event("""{"type":"disconnected","message":"x"}"""), event("""{"type":"connected"}""")))
        assertEquals(listOf<GatewayEvent>(GatewayEvent.Connected), api().stream(setOf("a")).toList())
    }

    @Test
    fun `401 is KeyRejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Refusal.KeyRejected, assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal)
    }

    @Test
    fun `a gateway 403 expired is ClockSkew, another 403 is Forbidden`() = runBlocking {
        server.enqueue(envelope("""{"status":403,"body":{"error":"Request expired"},"source":"gateway"}"""))
        assertEquals(Refusal.ClockSkew, assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal)
        server.enqueue(envelope("""{"status":403,"body":{"error":"destination not allowed for this secret"},"source":"gateway"}"""))
        assertEquals(
            Refusal.Forbidden("destination not allowed for this secret"),
            assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal,
        )
    }

    @Test
    fun `a refused broker login behind the gateway is BrokerRejected`() = runBlocking {
        server.enqueue(sse(event("""{"type":"error","message":"Connection failed with code 5"}""")))
        assertEquals(Refusal.BrokerRejected, assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal)
    }

    @Test
    fun `other stream errors, bad frames and server errors are retryable`() = runBlocking {
        server.enqueue(sse(event("""{"type":"error","message":"MQTT connection error: [Errno 111] Connection refused"}""")))
        assertFailsWith<IOException> { api().stream(setOf("a")).collect() }
        server.enqueue(sse("data: not-an-envelope\n\n"))
        assertFailsWith<IOException> { api().stream(setOf("a")).collect() }
        server.enqueue(MockResponse().setResponseCode(502))
        assertFailsWith<IOException> { api().stream(setOf("a")).collect() }
        Unit
    }

    @Test
    fun `publish sends the payload as a string and succeeds`() = runBlocking {
        server.enqueue(envelope("""{"status":200,"body":"{\"success\": true, \"topic\": \"zigbee2mqtt/lamp/set\", \"message\": \"Published successfully\"}"}"""))
        api().publish("zigbee2mqtt/lamp/set", "ON".encodeToByteArray(), qos = 1, retain = false)
        val body = sent()
        assertEquals(JsonPrimitive("zigbee2mqtt/lamp/set"), body["topic"])
        assertEquals(JsonPrimitive("ON"), body["message"])
        assertEquals(JsonPrimitive(1), body["qos"])
        assertEquals(JsonPrimitive(false), body["retain"])
    }

    @Test
    fun `publish failures carry a readable message`() = runBlocking {
        server.enqueue(envelope("""{"status":500,"body":"{\"error\": \"MQTT error: boom\"}","source":"gateway"}"""))
        assertEquals("MQTT error: boom", assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(LinkState.Offline.KEY_REJECTED, assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        server.enqueue(envelope("""{"status":403,"body":{"error":"Request expired"},"source":"gateway"}"""))
        assertEquals(LinkState.Offline.CLOCK_SKEW, assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        server.enqueue(envelope("""{"status":200,"body":"{\"success\": false, \"topic\": \"t\", \"message\": \"Failed with code 4\"}"}"""))
        assertEquals("Failed with code 4", assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        Unit
    }

    @Test
    fun `publish to an invalid topic never reaches the gateway`() = runBlocking {
        assertFailsWith<PublishFailed> { api().publish("a/+/b", byteArrayOf(), 1, false) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the public constructor refuses a cleartext URL and builds the broker from the spec`() {
        assertFailsWith<IllegalArgumentException> { AthenaGatewayApi(TransportSpec.Gateway("http://gw.example", key, null, null, null)) }
        assertEquals(null, TransportSpec.Gateway("https://gw.example", key, null, null, null).broker())
        assertEquals(
            Broker(host = "192.168.1.91", username = "oikos", password = "pw"),
            TransportSpec.Gateway("https://gw.example", key, "192.168.1.91", "oikos", "pw").broker(),
        )
    }

    @Test
    fun `the key is sent to the SDK as 64 lowercase hex characters`() {
        val hex = ByteArray(32) { if (it == 0) 0xAB.toByte() else it.toByte() }.toHex()
        assertEquals(64, hex.length)
        assertEquals("ab01", hex.take(4))
    }

    @Test
    fun `failures map to refusal or retry`() {
        assertIs<IOException>(IllegalStateException("x").toStreamFailure())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:transport:test --tests '*AthenaGatewayApiTest*'`
Expected: compilation fails with "Unresolved reference: AthenaGatewayApi", "toHex", "broker".

- [ ] **Step 3: Implement**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/AthenaGatewayApi.kt`:

```kotlin
package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.Broker
import io.github.gekkotron.athena.gateway.client.GatewayException
import io.github.gekkotron.athena.gateway.client.MqttEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [GatewayApi] over the gateway's own Kotlin SDK (`athena-gateway-client`, gateway spec §3.3). The SDK owns
 * the wire format; this maps its events and errors to ours. Streams are opened without the SDK's
 * `reconnect`: [GatewayTransport] reconnects, with make-before-break.
 */
class AthenaGatewayApi internal constructor(
    private val client: AthenaGatewayClient,
    private val broker: Broker?,
) : GatewayApi {
    /** [http] is shared; publishes get a 10 s call timeout (the SDK clears it for streams). */
    constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient()) : this(
        AthenaGatewayClient(
            requireHttps(spec.url),
            spec.key.toHex(),
            http.newBuilder().callTimeout(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
        ),
        spec.broker(),
    )

    override fun stream(topics: Set<String>): Flow<GatewayEvent> =
        client.subscribe(*topics.sorted().toTypedArray(), qos = 1, broker = broker)
            .mapNotNull { event ->
                when (event) {
                    is MqttEvent.Connected -> GatewayEvent.Connected
                    is MqttEvent.Message -> GatewayEvent.Message(MqttMessage(event.topic, event.payload.toPayloadBytes(), event.retain))
                    else -> null // Reconnecting only comes with `reconnect`; newer event types are ignored
                }
            }
            .catch { throw it.toStreamFailure() }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (!isValidPublishTopic(topic)) throw PublishFailed("invalid publish topic '$topic'")
        try {
            client.publish(topic, payload.decodeToString(), qos, retain, broker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            throw PublishFailed(e.publishText())
        }
    }

    private companion object {
        const val PUBLISH_TIMEOUT_SECONDS = 10L

        fun requireHttps(url: String): String = url.also { require(it.startsWith("https://")) { "the gateway URL must use https" } }
    }
}

/** A refusal only the user can fix, or an IOException that [GatewayTransport] retries with backoff. */
internal fun Throwable.toStreamFailure(): Throwable = when (this) {
    is CancellationException -> this
    is GatewayException.Unauthorized -> GatewayRefused(Refusal.KeyRejected)
    is GatewayException.Rejected -> GatewayRefused(if (reason == EXPIRED) Refusal.ClockSkew else Refusal.Forbidden(reason))
    is GatewayException.BadRequest -> GatewayRefused(Refusal.Forbidden("bad request"))
    is GatewayException.StreamError ->
        if (MQTT_REFUSED.containsMatchIn(reason)) GatewayRefused(Refusal.BrokerRejected) else IOException(reason, this)
    // Transport, GatewayError (including an undecryptable frame), anything else: worth another try.
    else -> IOException(message ?: javaClass.simpleName, this)
}

/** Short, secret-free text for "Not confirmed — Retry"; the known cases reuse the offline reasons. */
internal fun GatewayException.publishText(): String = when (this) {
    is GatewayException.Unauthorized -> LinkState.Offline.KEY_REJECTED
    is GatewayException.Rejected -> if (reason == EXPIRED) LinkState.Offline.CLOCK_SKEW else reason
    is GatewayException.GatewayError -> reason
    is GatewayException.Upstream -> errorText(body) ?: "broker error $status"
    else -> message ?: javaClass.simpleName
}

internal fun TransportSpec.Gateway.broker(): Broker? =
    if (brokerHost == null && username == null) null else Broker(host = brokerHost, username = username, password = password)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private const val EXPIRED = "Request expired"

/** CONNACK 4 (bad username or password) and 5 (not authorised), as the gateway's paho callback words them. */
private val MQTT_REFUSED = Regex("""Connection failed with code [45]\b""")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:transport:test --tests '*AthenaGatewayApiTest*'`
Expected: PASS. If a mapping test fails, compare with `ResponseDecoder` in the SDK (`sdk/kotlin/src/main/kotlin/.../internal/ResponseDecoder.kt`, tag `v1.1`). It is the source of truth for which `GatewayException` each reply becomes.

- [ ] **Step 5: Commit**

```bash
git add core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(transport): gateway API over athena-gateway-client - event, refusal and publish error mapping" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `Handover` (make-before-break)

**Files:**
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/Handover.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/HandoverTest.kt`

**Interfaces:**
- Consumes: `ManagedTransport`, `LinkState`, `MqttMessage`, `PublishFailed`; `FakeManagedTransport` / `FakeMqttTransport` from testFixtures.
- Produces:
  - `internal class Handover(scope: CoroutineScope, connectTimeout: Duration = 15.seconds)`.
  - Properties: `val link: StateFlow<LinkState>` (starts `Connecting`), `val messages: SharedFlow<MqttMessage>`, `val current: StateFlow<ManagedTransport?>`.
  - Functions: `suspend fun switchTo(next: ManagedTransport): Boolean`, `suspend fun setSubscriptions(filters: Set<String>)`, `suspend fun publish(topic, payload, qos, retain)`, `suspend fun close()`.

- [ ] **Step 1: Write the failing test**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/HandoverTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HandoverTest {
    private fun connecting() = FakeManagedTransport(TransportSpec.Demo).also { it.fake.setLink(LinkState.Connecting) }
    private fun connected() = FakeManagedTransport(TransportSpec.Demo) // FakeMqttTransport starts Connected(DIRECT)

    private fun TestScope.handover(): Pair<Handover, MutableList<String>> {
        val h = Handover(backgroundScope, connectTimeout = 15.seconds)
        val got = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.messages.collect { got += it.payloadString() } }
        return h to got
    }

    @Test
    fun `with no current transport the first one becomes current at once`() = runTest {
        val (h, _) = handover()
        val first = connecting()
        assertTrue(h.switchTo(first))
        assertTrue(first.started)
        assertSame(first, h.current.value)
        assertEquals(LinkState.Connecting, h.link.value)
        first.fake.setLink(LinkState.Reconnecting(3))
        runCurrent()
        assertEquals(LinkState.Reconnecting(3), h.link.value)
    }

    @Test
    fun `a connected current waits for the next to connect, then nothing from the old is emitted`() = runTest {
        val (h, got) = handover()
        h.setSubscriptions(setOf("#"))
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        runCurrent()
        assertEquals(setOf("#"), next.fake.subscriptions, "subscriptions are applied to the pending transport")
        old.fake.deliver("t", "old-1")
        next.fake.deliver("t", "new-1") // buffered: next is not current yet
        runCurrent()
        assertEquals(listOf("old-1"), got)
        assertSame(old, h.current.value)
        next.fake.setLink(LinkState.Connected(TransportMode.GATEWAY))
        runCurrent()
        assertTrue(switched.await())
        old.fake.deliver("t", "old-2")
        next.fake.deliver("t", "new-2")
        runCurrent()
        assertEquals(listOf("old-1", "new-1", "new-2"), got)
        assertTrue(old.closed)
        assertSame(next, h.current.value)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), h.link.value)
    }

    @Test
    fun `a current that is not connected is replaced at once`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        old.fake.setLink(LinkState.Reconnecting(1))
        val next = connecting()
        assertTrue(h.switchTo(next))
        assertTrue(old.closed)
        assertSame(next, h.current.value)
    }

    @Test
    fun `a next that never connects is closed and the current kept`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        advanceTimeBy(15.seconds + 1.seconds)
        assertFalse(switched.await())
        assertTrue(next.closed)
        assertFalse(old.closed)
        assertSame(old, h.current.value)
    }

    @Test
    fun `a next whose start throws is closed and the current kept`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val broken = FakeManagedTransport(TransportSpec.Demo, failStart = true)
        assertFalse(h.switchTo(broken))
        assertTrue(broken.closed)
        assertSame(old, h.current.value)
    }

    @Test
    fun `subscriptions reach the current transport`() = runTest {
        val (h, _) = handover()
        val t = connected()
        h.switchTo(t)
        h.setSubscriptions(setOf("a/#"))
        assertEquals(setOf("a/#"), t.fake.subscriptions)
    }

    @Test
    fun `publish needs a current transport`() = runTest {
        val (h, _) = handover()
        assertFailsWith<PublishFailed> { h.publish("t", byteArrayOf(1), 1, false) }
        val t = connected()
        h.switchTo(t)
        h.publish("t", "x".encodeToByteArray(), 1, false)
        assertEquals(listOf("t" to "x"), t.fake.publishedStrings())
    }

    @Test
    fun `close during a pending switch closes both transports`() = runTest {
        val (h, _) = handover()
        val old = connected()
        h.switchTo(old)
        val next = connecting()
        val switched = async { h.switchTo(next) }
        runCurrent()
        h.close()
        runCurrent()
        assertFalse(switched.await())
        assertTrue(old.closed)
        assertTrue(next.closed)
        assertNull(h.current.value)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), h.link.value)
        assertFalse(h.switchTo(connected()), "a closed handover accepts nothing")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:transport:test --tests '*HandoverTest*'`
Expected: compilation fails with "Unresolved reference: Handover".

- [ ] **Step 3: Implement**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/Handover.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one [ManagedTransport] as current and hands over to the next make-before-break (gateway spec §3.2).
 * The next transport's messages are buffered from its very first one, so after the flip every topic ends
 * at its latest value, and nothing from the old transport is emitted once the flip has happened.
 */
internal class Handover(
    private val scope: CoroutineScope,
    private val connectTimeout: Duration = 15.seconds,
) {
    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val out = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    val messages: SharedFlow<MqttMessage> = out.asSharedFlow()

    private val _current = MutableStateFlow<ManagedTransport?>(null)
    val current: StateFlow<ManagedTransport?> = _current.asStateFlow()

    private class Slot(val transport: ManagedTransport, val inbox: Channel<MqttMessage>, val intake: Job) {
        var forward: Job? = null
        var mirror: Job? = null
        val disposed = AtomicBoolean(false)
    }

    private val switching = Mutex() // one switch at a time
    private val lock = Mutex() // slots, subscriptions; never held while calling into a transport
    private var active: Slot? = null
    private var pending: Slot? = null
    private var subscriptions: Set<String> = emptySet()
    private var version = 0L
    private val closed = MutableStateFlow(false)

    /** @return true once [next] is current; false if it failed to start, did not connect in time, or [close] won. */
    suspend fun switchTo(next: ManagedTransport): Boolean = switching.withLock {
        val inbox = Channel<MqttMessage>(Channel.UNLIMITED)
        // Undispatched: subscribed before start(), so the first retained message is already buffered.
        val intake = scope.launch(start = CoroutineStart.UNDISPATCHED) { next.messages().collect { inbox.send(it) } }
        val slot = Slot(next, inbox, intake)
        var flipped = false
        try {
            val admitted = lock.withLock {
                if (!closed.value) pending = slot
                !closed.value
            }
            if (!admitted) return false
            next.start()
            applySubscriptions(next)
            val old = lock.withLock { active }
            val ready = old == null || old.transport.link.value !is LinkState.Connected || awaitConnected(next)
            if (ready) flipped = flip(slot, old)
            flipped
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // start() or setSubscriptions() threw: keep what we have
        } finally {
            if (!flipped) {
                withContext(NonCancellable) {
                    lock.withLock { if (pending === slot) pending = null }
                    dispose(slot)
                }
            }
        }
    }

    suspend fun setSubscriptions(filters: Set<String>) {
        val targets = lock.withLock {
            subscriptions = filters
            version++
            listOfNotNull(active?.transport, pending?.transport)
        }
        targets.forEach { it.setSubscriptions(filters) }
    }

    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        val transport = lock.withLock { active?.transport } ?: throw PublishFailed("not connected")
        transport.publish(topic, payload, qos, retain)
    }

    suspend fun close() {
        val slots = lock.withLock {
            closed.value = true
            listOfNotNull(active, pending).also {
                active = null
                pending = null
                _current.value = null
            }
        }
        slots.forEach { dispose(it) }
        _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    private suspend fun awaitConnected(next: ManagedTransport): Boolean =
        withTimeoutOrNull(connectTimeout) {
            combine(next.link, closed) { link, isClosed -> isClosed || link is LinkState.Connected }.first { it }
        } != null

    /** Re-applies when [setSubscriptions] ran meanwhile, so [next] never ends up on an older set. */
    private suspend fun applySubscriptions(next: ManagedTransport) {
        while (true) {
            val (seen, filters) = lock.withLock { version to subscriptions }
            if (filters.isNotEmpty()) next.setSubscriptions(filters)
            if (lock.withLock { version } == seen) return
        }
    }

    private suspend fun flip(slot: Slot, old: Slot?): Boolean {
        lock.withLock {
            if (closed.value) return false
            pending = null
            active = slot
            // Stop the old transport's forwarding before the new one starts: no straggler after the flip.
            old?.forward?.cancelAndJoin()
            old?.mirror?.cancelAndJoin()
            slot.mirror = scope.launch(start = CoroutineStart.UNDISPATCHED) { slot.transport.link.collect { _link.value = it } }
            slot.forward = scope.launch(start = CoroutineStart.UNDISPATCHED) { for (m in slot.inbox) out.emit(m) }
            _current.value = slot.transport
        }
        old?.let { dispose(it) }
        return true
    }

    private suspend fun dispose(slot: Slot) {
        if (!slot.disposed.compareAndSet(false, true)) return
        slot.intake.cancel()
        slot.forward?.cancel()
        slot.mirror?.cancel()
        slot.inbox.close()
        try {
            slot.transport.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // close() is contractually quiet; never let one transport block the next.
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:transport:test --tests '*HandoverTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(transport): make-before-break handover with a buffered next transport" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `GatewayStream`, `GatewayTransport`, `FakeGatewayApi`

**Files:**
- Create: `core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeGatewayApi.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayStream.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayTransport.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayTransportTest.kt`

**Interfaces:**
- Consumes:
  - From Tasks 1–3: `Backoff`, `GatewayApi`, `GatewayEvent`, `GatewayRefused`, `Refusal`.
  - From Task 4: `Handover`.
- Produces:
  - `class GatewayTransport(api: GatewayApi, scope: CoroutineScope, backoff: Backoff = Backoff(), resubscribeDebounce: Duration = 300.milliseconds, stableAfter: Duration = 30.seconds, switchTimeout: Duration = 15.seconds) : ManagedTransport`.
  - `internal class GatewayStream(api, topics: Set<String>, scope) : ManagedTransport` with `val topics` and `val end: Deferred<GatewayStream.End>`.
  - testFixtures `class FakeGatewayApi : GatewayApi` with:
    - `streams: List<FakeGatewayApi.Stream>` and `published: List<MqttMessage>`;
    - `var failPublish: String?` and `var onOpen: (Stream) -> Unit`;
    - on `Stream`: `topics`, `active`, `connect()`, `message(topic, payload, retained)`, `send(event)`, `end(cause)`.

- [ ] **Step 1: Write the test fixture**

`core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeGatewayApi.kt`:

```kotlin
package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.GatewayApi
import com.gekkotron.oikos.transport.GatewayEvent
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.PublishFailed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/** A scripted gateway: each [stream] call opens a [Stream] the test drives. */
class FakeGatewayApi : GatewayApi {
    class Stream(val topics: Set<String>) {
        internal val events = Channel<GatewayEvent>(Channel.UNLIMITED)

        /** True while the transport is still reading this stream. */
        @Volatile
        var active = true
            internal set

        fun send(event: GatewayEvent) {
            events.trySend(event)
        }

        fun connect() = send(GatewayEvent.Connected)

        fun message(topic: String, payload: String, retained: Boolean = false) =
            send(GatewayEvent.Message(MqttMessage(topic, payload.encodeToByteArray(), retained)))

        /** Ends the stream normally, or with [cause] (an IOException, a GatewayRefused…). */
        fun end(cause: Throwable? = null) {
            events.close(cause)
        }
    }

    val streams: MutableList<Stream> = CopyOnWriteArrayList()
    val published: MutableList<MqttMessage> = CopyOnWriteArrayList()
    var failPublish: String? = null

    /** Runs as each stream opens, before any event is read, for example `{ it.connect() }`. */
    var onOpen: (Stream) -> Unit = {}

    override fun stream(topics: Set<String>): Flow<GatewayEvent> = flow {
        val s = Stream(topics)
        streams += s
        onOpen(s)
        try {
            for (e in s.events) emit(e)
        } finally {
            s.active = false
        }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        failPublish?.let { throw PublishFailed(it) }
        published += MqttMessage(topic, payload, retain)
    }
}
```

- [ ] **Step 2: Write the failing test**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayTransportTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeGatewayApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayTransportTest {
    private val api = FakeGatewayApi()
    private val connected = LinkState.Connected(TransportMode.GATEWAY)

    private fun TestScope.started(topics: Set<String> = setOf("homeassistant/#")): Pair<GatewayTransport, MutableList<String>> {
        val t = GatewayTransport(api, backgroundScope, backoff = Backoff(jitter = 0.0))
        val got = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { t.messages().collect { got += "${it.topic}=${it.payloadString()}" } }
        t.start()
        backgroundScope.launch { t.setSubscriptions(topics) }
        advanceTimeBy(301.milliseconds)
        runCurrent()
        return t to got
    }

    @Test
    fun `the first stream opens after the debounce and connected makes the link connected`() = runTest {
        val (t, got) = started()
        assertEquals(1, api.streams.size)
        assertEquals(setOf("homeassistant/#"), api.streams[0].topics)
        assertEquals(LinkState.Connecting, t.link.value)
        api.streams[0].connect()
        api.streams[0].message("homeassistant/light/x/config", "{}", retained = true)
        runCurrent()
        assertEquals(connected, t.link.value)
        assertEquals(listOf("homeassistant/light/x/config={}"), got)
    }

    @Test
    fun `a burst of subscription changes opens a single stream with the final set`() = runTest {
        val t = GatewayTransport(api, backgroundScope, backoff = Backoff(jitter = 0.0))
        t.start()
        t.setSubscriptions(setOf("a"))
        advanceTimeBy(100.milliseconds)
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(100.milliseconds)
        t.setSubscriptions(setOf("a", "b", "c"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(listOf(setOf("a", "b", "c")), api.streams.map { it.topics })
    }

    @Test
    fun `a new topic set reopens make-before-break without losing messages`() = runTest {
        val (t, got) = started(setOf("a"))
        api.streams[0].connect()
        runCurrent()
        t.setSubscriptions(setOf("a", "b"))
        advanceTimeBy(301.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size)
        api.streams[0].message("a", "1") // still current
        api.streams[1].message("b", "2") // buffered until the new stream is connected
        runCurrent()
        assertEquals(listOf("a=1"), got)
        assertTrue(api.streams[0].active)
        assertEquals(connected, t.link.value)
        api.streams[1].connect()
        runCurrent()
        assertEquals(listOf("a=1", "b=2"), got)
        assertFalse(api.streams[0].active, "the old stream is closed after the flip")
        assertEquals(connected, t.link.value)
    }

    @Test
    fun `a dropped stream shows Reconnecting and reopens after the backoff`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        api.streams[0].end(IOException("reset"))
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
        advanceTimeBy(999.milliseconds)
        assertEquals(1, api.streams.size)
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(2, api.streams.size)
        assertEquals(LinkState.Reconnecting(1), t.link.value, "still reconnecting until the new stream connects")
        api.streams[1].connect()
        runCurrent()
        assertEquals(connected, t.link.value)
    }

    @Test
    fun `a stream that drops at once does not reset the attempt count`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        api.streams[0].end()
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        api.streams[1].connect()
        runCurrent()
        api.streams[1].end()
        runCurrent()
        assertEquals(LinkState.Reconnecting(2), t.link.value)
    }

    @Test
    fun `a stream that stayed up resets the attempt count`() = runTest {
        val (t, _) = started()
        api.streams[0].end()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        api.streams[1].connect()
        advanceTimeBy(31.seconds)
        api.streams[1].end()
        runCurrent()
        assertEquals(LinkState.Reconnecting(1), t.link.value)
    }

    @Test
    fun `a refusal goes offline with its reason and never retries`() = runTest {
        api.onOpen = { it.end(GatewayRefused(Refusal.KeyRejected)) }
        val (t, _) = started()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.KEY_REJECTED), t.link.value)
        t.setSubscriptions(setOf("other/#"))
        advanceTimeBy(5.minutes)
        assertEquals(1, api.streams.size)
    }

    @Test
    fun `a clock refusal says so`() = runTest {
        api.onOpen = { it.end(GatewayRefused(Refusal.ClockSkew)) }
        val (t, _) = started()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.CLOCK_SKEW), t.link.value)
    }

    @Test
    fun `refused MQTT credentials behind the gateway are REJECTED`() = runTest {
        api.onOpen = { it.end(GatewayRefused(Refusal.BrokerRejected)) }
        val (t, _) = started()
        runCurrent()
        assertEquals(LinkState.Offline(LinkState.Offline.REJECTED), t.link.value)
        advanceTimeBy(5.minutes)
        assertEquals(1, api.streams.size)
    }

    @Test
    fun `publish goes through the api only while connected`() = runTest {
        val (t, _) = started()
        assertFailsWith<PublishFailed> { t.publish("x/set", "ON".encodeToByteArray(), 1, false) }
        api.streams[0].connect()
        runCurrent()
        t.publish("x/set", "ON".encodeToByteArray(), 1, false)
        assertEquals(listOf("x/set"), api.published.map { it.topic })
        api.failPublish = "MQTT error: boom"
        assertEquals("MQTT error: boom", assertFailsWith<PublishFailed> { t.publish("x/set", byteArrayOf(), 1, false) }.message)
    }

    @Test
    fun `close ends the stream and goes offline`() = runTest {
        val (t, _) = started()
        api.streams[0].connect()
        runCurrent()
        t.close()
        runCurrent()
        assertFalse(api.streams[0].active)
        assertEquals(LinkState.Offline(LinkState.Offline.CLOSED), t.link.value)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :core:transport:test --tests '*GatewayTransportTest*'`
Expected: compilation fails with "Unresolved reference: GatewayTransport".

- [ ] **Step 4: Implement**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayStream.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One `/mqtt/subscribe` stream for a fixed topic set, shaped as a [ManagedTransport] so [Handover] can
 * switch between streams. [GatewayTransport] owns reconnecting and publishing; this only reads.
 */
internal class GatewayStream(
    private val api: GatewayApi,
    val topics: Set<String>,
    private val scope: CoroutineScope,
) : ManagedTransport {
    sealed interface End {
        /** EOF, a disconnect, a retryable error (an IOException from [GatewayApi]), or close(): worth retrying. */
        data object Dropped : End

        /** Needs the user; [reason] is a [LinkState.Offline] constant. */
        data class Refused(val reason: String) : End
    }

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 1024)
    private val _end = CompletableDeferred<End>()
    val end: Deferred<End> get() = _end
    private var job: Job? = null

    override fun messages(): SharedFlow<MqttMessage> = _messages.asSharedFlow()

    override fun start() {
        check(job == null) { "already started" }
        job = scope.launch {
            val outcome = try {
                read()
                End.Dropped
            } catch (e: GatewayRefused) {
                End.Refused(e.refusal.offlineReason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                End.Dropped
            }
            _link.value = LinkState.Offline(if (outcome is End.Refused) outcome.reason else LinkState.Offline.CLOSED)
            _end.complete(outcome)
        }
    }

    private suspend fun read() {
        api.stream(topics).collect { event ->
            when (event) {
                GatewayEvent.Connected -> _link.value = LinkState.Connected(TransportMode.GATEWAY)
                is GatewayEvent.Message -> _messages.emit(event.message)
            }
        }
    }

    override suspend fun close() {
        job?.cancelAndJoin()
        if (_end.complete(End.Dropped)) _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    /** A stream's topics are fixed; [GatewayTransport] opens a new stream for a new set. */
    override suspend fun setSubscriptions(filters: Set<String>) = Unit

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean): Unit =
        throw PublishFailed("streams do not publish")
}
```

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The house through Athena-HttpMqttGateway (gateway spec §3.4). The gateway has no "add topic" call, so a
 * new subscription set opens a new stream make-before-break; a dead stream is reopened with backoff.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GatewayTransport(
    private val api: GatewayApi,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val resubscribeDebounce: Duration = 300.milliseconds,
    private val stableAfter: Duration = 30.seconds,
    switchTimeout: Duration = 15.seconds,
) : ManagedTransport {
    private val handover = Handover(scope, switchTimeout)
    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val wanted = MutableStateFlow<Set<String>>(emptySet())
    private val live = MutableStateFlow<GatewayStream?>(null)

    /** Shown instead of the stream's own link: `Reconnecting(n)` while reopening, `Offline` after a refusal. */
    private val overlay = MutableStateFlow<LinkState?>(null)

    @Volatile
    private var stopped = false
    private val jobs = mutableListOf<Job>()

    override fun messages(): SharedFlow<MqttMessage> = handover.messages

    override fun start() {
        check(jobs.isEmpty()) { "already started" }
        jobs += scope.launch { combine(handover.link, overlay) { link, over -> over ?: link }.collect { _link.value = it } }
        jobs += scope.launch { resubscribe() }
        jobs += scope.launch { reconnect() }
    }

    override suspend fun close() {
        stopped = true
        jobs.forEach { it.cancelAndJoin() }
        handover.close()
        _link.value = LinkState.Offline(LinkState.Offline.CLOSED)
    }

    override suspend fun setSubscriptions(filters: Set<String>) {
        wanted.value = filters
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (_link.value !is LinkState.Connected) throw PublishFailed("not connected")
        api.publish(topic, payload, qos, retain)
    }

    /** The gateway refuses an empty topic list, so nothing opens until the source subscribes. */
    private suspend fun resubscribe() {
        wanted.filter { it.isNotEmpty() }.debounce(resubscribeDebounce).collect { topics ->
            if (!stopped && topics != live.value?.topics) open(topics)
        }
    }

    private suspend fun reconnect() {
        var attempt = 0
        live.filterNotNull().collectLatest { stream ->
            coroutineScope {
                val watch = launch {
                    stream.link.first { it is LinkState.Connected }
                    overlay.value = null
                    delay(stableAfter) // a stream that connects and drops at once must not reset the backoff
                    attempt = 0
                }
                val end = stream.end.await()
                watch.cancel()
                if (handover.current.value !== stream) return@coroutineScope // replaced by a new topic set, not dead
                when (end) {
                    is GatewayStream.End.Refused -> {
                        stopped = true
                        overlay.value = LinkState.Offline(end.reason)
                    }
                    GatewayStream.End.Dropped -> {
                        attempt++
                        overlay.value = LinkState.Reconnecting(attempt)
                        delay(backoff.delayFor(attempt))
                        if (!stopped) open(wanted.first { it.isNotEmpty() })
                    }
                }
            }
        }
    }

    private suspend fun open(topics: Set<String>) {
        val stream = GatewayStream(api, topics, scope)
        if (handover.switchTo(stream)) live.value = stream
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:transport:test --tests '*GatewayTransportTest*'`
Expected: PASS.

- [ ] **Step 6: Run the whole module**

Run: `./gradlew :core:transport:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(transport): gateway transport - one stream per topic set, make-before-break reopen, backoff, refusals" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `LocalProbe` and `TransportSelector`

**Files:**
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/LocalProbe.kt`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TransportSelector.kt`
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/ActiveTransport.kt` (KDoc only)
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TcpProbeTest.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TransportSelectorTest.kt`

**Interfaces:**
- Consumes: from Task 1, `TransportSpec.Routed` and `Route`; from Task 4, `Handover`.
- Produces:
  - `fun interface LocalProbe { suspend fun reachable(): Boolean }` and `class TcpProbe(host: String, port: Int, timeout: Duration = 1500.milliseconds)`.
  - `class TransportSelector(spec: TransportSpec.Routed, direct: (TransportSpec.Direct) -> ManagedTransport, gateway: (TransportSpec.Gateway) -> ManagedTransport, probe: LocalProbe, networkChanges: Flow<Unit>, scope: CoroutineScope, reprobeInterval = 60.seconds, networkDebounce = 1.seconds, reconnectingDebounce = 2.seconds, switchTimeout = 15.seconds) : ManagedTransport`.

- [ ] **Step 1: Write the failing tests**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TcpProbeTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.freePort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TcpProbeTest {
    @Test
    fun `a listening port is reachable`() = runBlocking {
        ServerSocket(0).use { server -> assertTrue(TcpProbe("127.0.0.1", server.localPort).reachable()) }
    }

    @Test
    fun `a closed port is not`() = runBlocking {
        assertFalse(TcpProbe("127.0.0.1", freePort()).reachable())
    }

    @Test
    fun `an unresolvable host is not`() = runBlocking {
        assertFalse(TcpProbe("no-such-host.invalid", 1883).reachable())
    }
}
```

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TransportSelectorTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransportSelectorTest {
    private val directSpec = TransportSpec.Direct("192.168.1.91", 1883, tls = false, ws = false, username = null, password = null, clientId = "c")
    private val gatewaySpec = TransportSpec.Gateway("https://gw.example", ByteArray(32), null, null, null)
    private val directs = mutableListOf<FakeManagedTransport>()
    private val gateways = mutableListOf<FakeManagedTransport>()
    private var reachable = true
    private var probes = 0
    private val network = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Gateways connect at once unless [gatewayConnects] is false. */
    private var gatewayConnects = true

    private fun TestScope.selector(route: Route = Route.AUTO): Pair<TransportSelector, MutableList<String>> {
        val s = TransportSelector(
            TransportSpec.Routed(directSpec, gatewaySpec, route),
            direct = { FakeManagedTransport(it).also(directs::add) },
            gateway = {
                FakeManagedTransport(it).also { g ->
                    g.fake.setLink(if (gatewayConnects) LinkState.Connected(TransportMode.GATEWAY) else LinkState.Connecting)
                    gateways += g
                }
            },
            probe = { probes++; reachable },
            networkChanges = network,
            scope = backgroundScope,
        )
        val got = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { s.messages().collect { got += it.payloadString() } }
        s.start()
        runCurrent()
        return s to got
    }

    @Test
    fun `route DIRECT runs only the direct transport and never probes`() = runTest {
        val (s, _) = selector(Route.DIRECT)
        advanceTimeBy(5.seconds * 60)
        assertEquals(1, directs.size)
        assertTrue(gateways.isEmpty())
        assertEquals(0, probes)
        assertEquals(LinkState.Connected(TransportMode.DIRECT), s.link.value)
    }

    @Test
    fun `route GATEWAY runs only the gateway`() = runTest {
        val (s, _) = selector(Route.GATEWAY)
        assertTrue(directs.isEmpty())
        assertEquals(1, gateways.size)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `auto uses direct when the probe succeeds and the gateway otherwise`() = runTest {
        selector()
        assertEquals(1, directs.size)
        assertTrue(gateways.isEmpty())
    }

    @Test
    fun `auto starts on the gateway away from home`() = runTest {
        reachable = false
        val (s, _) = selector()
        assertTrue(directs.isEmpty())
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `a network change re-probes and switches make-before-break`() = runTest {
        val (s, got) = selector()
        s.setSubscriptions(setOf("#"))
        reachable = false
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertTrue(directs[0].closed)
        assertEquals(setOf("#"), gateways[0].fake.subscriptions)
        gateways[0].fake.deliver("t", "via-gateway")
        runCurrent()
        assertEquals(listOf("via-gateway"), got)
        assertEquals(LinkState.Connected(TransportMode.GATEWAY), s.link.value)
    }

    @Test
    fun `leaving Wi-Fi without a network callback still moves to the gateway`() = runTest {
        val (s, _) = selector()
        reachable = false
        gatewayConnects = false // the gateway is still connecting when the switch happens
        directs[0].fake.setLink(LinkState.Reconnecting(1))
        runCurrent()
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertTrue(directs[0].closed, "a direct transport that is not connected is replaced at once")
        assertEquals(LinkState.Connecting, s.link.value)
    }

    @Test
    fun `on the gateway the selector re-probes every 60 s and returns home`() = runTest {
        reachable = false
        selector()
        reachable = true
        advanceTimeBy(60.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, directs.size)
        assertTrue(gateways[0].closed)
    }

    @Test
    fun `rejected direct credentials fall back to the gateway until the next network change`() = runTest {
        selector()
        directs[0].fake.setLink(LinkState.Offline(LinkState.Offline.REJECTED))
        runCurrent()
        assertEquals(1, gateways.size)
        advanceTimeBy(3 * 60.seconds)
        runCurrent()
        assertEquals(1, directs.size, "no retry of the rejected direct route on re-probe")
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(2, directs.size)
    }

    @Test
    fun `close during a pending switch closes both transports`() = runTest {
        val (s, _) = selector()
        reachable = false
        gatewayConnects = false
        network.emit(Unit)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(1, gateways.size)
        assertFalse(directs[0].closed, "direct is still connected, so the switch waits")
        s.close()
        runCurrent()
        assertTrue(directs[0].closed)
        assertTrue(gateways[0].closed)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:transport:test --tests '*TcpProbeTest*' --tests '*TransportSelectorTest*'`
Expected: compilation fails with "Unresolved reference: TcpProbe", "TransportSelector".

- [ ] **Step 3: Implement**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/LocalProbe.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Is the local broker reachable right now? (parent spec §4.5 step 1). */
fun interface LocalProbe {
    suspend fun reachable(): Boolean
}

/** A bare TCP connect to the broker's port, no MQTT. Reads no SSID, so it needs no location permission. */
class TcpProbe(
    private val host: String,
    private val port: Int,
    private val timeout: Duration = 1500.milliseconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalProbe {
    override suspend fun reachable(): Boolean = withTimeoutOrNull(timeout) {
        runInterruptible(io) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeout.inWholeMilliseconds.toInt()) }
                true
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false // a port outside 0..65535
            }
        }
    } ?: false
}
```

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TransportSelector.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct at home, the gateway elsewhere (gateway spec §3.5). With [Route.AUTO] it re-evaluates on start,
 * on network changes, when the direct link starts reconnecting, and every [reprobeInterval] while not on
 * direct; a switch is make-before-break through [Handover]. Rejected direct credentials stay on the gateway
 * until the next network change. The foreground lifecycle stays with [ActiveTransport].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class TransportSelector(
    private val spec: TransportSpec.Routed,
    private val direct: (TransportSpec.Direct) -> ManagedTransport,
    private val gateway: (TransportSpec.Gateway) -> ManagedTransport,
    private val probe: LocalProbe,
    private val networkChanges: Flow<Unit>,
    private val scope: CoroutineScope,
    private val reprobeInterval: Duration = 60.seconds,
    private val networkDebounce: Duration = 1.seconds,
    private val reconnectingDebounce: Duration = 2.seconds,
    switchTimeout: Duration = 15.seconds,
) : ManagedTransport {
    private val handover = Handover(scope, switchTimeout)
    override val link: StateFlow<LinkState> = handover.link

    @Volatile
    private var directNow: ManagedTransport? = null

    @Volatile
    private var directRejected = false
    private var job: Job? = null

    override fun messages(): SharedFlow<MqttMessage> = handover.messages

    override fun start() {
        check(job == null) { "already started" }
        job = scope.launch {
            when (spec.route) {
                Route.DIRECT -> handover.switchTo(newDirect())
                Route.GATEWAY -> handover.switchTo(gateway(spec.gateway))
                Route.AUTO -> auto()
            }
        }
    }

    override suspend fun close() {
        job?.cancelAndJoin()
        handover.close()
    }

    override suspend fun setSubscriptions(filters: Set<String>) = handover.setSubscriptions(filters)

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) =
        handover.publish(topic, payload, qos, retain)

    private fun newDirect(): ManagedTransport = direct(spec.direct).also { directNow = it }

    private fun onDirect(): Boolean = handover.current.value.let { it != null && it === directNow }

    private suspend fun auto() = coroutineScope {
        val checks = Channel<Unit>(Channel.CONFLATED)
        checks.trySend(Unit)
        launch {
            networkChanges.debounce(networkDebounce).collect {
                directRejected = false
                checks.send(Unit)
            }
        }
        launch {
            handover.current.collectLatest { current ->
                if (current == null || current !== directNow) return@collectLatest
                current.link.collectLatest { link ->
                    when {
                        link is LinkState.Offline && link.reason == LinkState.Offline.REJECTED -> {
                            directRejected = true
                            checks.send(Unit)
                        }
                        link is LinkState.Reconnecting -> {
                            delay(reconnectingDebounce) // a new attempt restarts the wait
                            checks.send(Unit)
                        }
                    }
                }
            }
        }
        launch {
            while (true) {
                delay(reprobeInterval) // a Wi-Fi join is not always a new default network
                if (!onDirect()) checks.send(Unit)
            }
        }
        for (check in checks) {
            val wantDirect = !directRejected && probe.reachable()
            if (handover.current.value == null || wantDirect != onDirect()) {
                handover.switchTo(if (wantDirect) newDirect() else gateway(spec.gateway))
            }
        }
    }
}
```

In `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/ActiveTransport.kt`, replace the class KDoc's last sentence:

```kotlin
 * leaves the foreground. Switching is break-before-make; Plan 2's selector adds make-before-break.
```

with:

```kotlin
 * leaves the foreground. A spec change is break-before-make; moving between the routes of one profile is
 * [TransportSelector]'s job, make-before-break.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:transport:test --tests '*TcpProbeTest*' --tests '*TransportSelectorTest*'`
Expected: PASS.

- [ ] **Step 5: Run the whole module**

Run: `./gradlew :core:transport:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/transport
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(transport): route selector - TCP probe, network changes, re-probe, rejected fallback, make-before-break" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Route preference in `:core:data`

**Files:**
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt`
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt`
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ProfileRepository.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable enum class RoutePreference { AUTO, DIRECT, GATEWAY }`.
  - `ProfileRepository.route: StateFlow<RoutePreference>` and `suspend fun ProfileRepository.setRoute(route: RoutePreference)`.

- [ ] **Step 1: Write the failing tests**

Append these tests inside `ProfileRepositoryTest` (it already defines `direct`, `repo()`, `file()` and `settled()`):

```kotlin
    @Test
    fun `route defaults to AUTO and is saved`() = runTest {
        val r = repo()
        r.save(direct)
        assertEquals(RoutePreference.AUTO, r.route.value)
        r.setRoute(RoutePreference.GATEWAY)
        assertEquals(RoutePreference.GATEWAY, r.route.first { it == RoutePreference.GATEWAY })
        assertTrue("\"GATEWAY\"" in file().readText())
    }

    @Test
    fun `clear resets the route`() = runTest {
        val r = repo()
        r.save(direct)
        r.setRoute(RoutePreference.DIRECT)
        r.route.first { it == RoutePreference.DIRECT }
        r.clear()
        assertEquals(RoutePreference.AUTO, r.route.first { it == RoutePreference.AUTO })
    }

    @Test
    fun `changing the route leaves the profile state equal and keeps roomsPending`() = runTest {
        val r = repo()
        r.save(direct)
        val before = r.state.first { it is ProfileState.Ready }
        r.setRoute(RoutePreference.GATEWAY)
        r.route.first { it == RoutePreference.GATEWAY }
        assertEquals(before, r.state.value)
        assertEquals(ProfileState.Ready(direct, roomsPending = true), r.state.value)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*ProfileRepositoryTest*'`
Expected: compilation fails with "Unresolved reference: RoutePreference", "route", "setRoute".

- [ ] **Step 3: Implement**

In `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt`, add the import `import kotlinx.serialization.Serializable` at the top (below `package`). Change the KDoc of `GatewayConfig` from `Remote gateway settings from the QR code. Stored now, used by Plan 2. [key] is a secret.` to `Remote gateway settings from the QR code. [key] is a secret.`. Append at the end of the file:

```kotlin
/**
 * How to reach a profile that has a gateway (gateway spec §4). Kept out of [ConnectionProfile]: changing
 * it rebuilds the transport but never the discovery source, so the house on screen survives.
 */
@Serializable
enum class RoutePreference { AUTO, DIRECT, GATEWAY }
```

In `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt`, add a last field to `StoredProfile`, after `roomsPending`:

```kotlin
    /** The link sheet's route choice; only used when [gateway] is set. `clear()` and a new `save()` reset it. */
    val route: RoutePreference = RoutePreference.AUTO,
```

In `core/data/src/main/kotlin/com/gekkotron/oikos/data/ProfileRepository.kt`, after the `state` property, add:

```kotlin
    /** The route choice (gateway spec §4); `AUTO` until the user picks one. */
    val route: StateFlow<RoutePreference> =
        store.data.map { it.route }.stateIn(scope, SharingStarted.Eagerly, RoutePreference.AUTO)
```

After `finishRooms()`, add:

```kotlin
    /** Changes only the route; the profile, and so [state], stay equal. */
    suspend fun setRoute(route: RoutePreference) {
        store.updateData { it.copy(route = route) }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(data): route preference stored beside the profile, reset by clear" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: App wiring (specs, factory, network changes, cleartext off)

**Files:**
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/connection/TransportSpecs.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/TransportFactory.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/NetworkChanges.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/TransportSpecsTest.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/TransportFactoryTest.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/NetworkChangesTest.kt`

**Interfaces:**
- Consumes:
  - From Task 1: `TransportSpec.Gateway`, `Routed` and `Route`.
  - From Tasks 3, 5 and 6: `AthenaGatewayApi`, `GatewayTransport`, `TransportSelector` and `TcpProbe`.
  - From Task 7: `RoutePreference`, `ProfileRepository.route` and `setRoute`.
- Produces:
  - `fun ProfileState.toSpec(clientId: String, route: RoutePreference = RoutePreference.AUTO): TransportSpec?` and `fun GatewayConfig.toSpec(local: LocalBroker): TransportSpec.Gateway`.
  - `fun RoutePreference.toRoute(): Route`, `fun Route.toPreference(): RoutePreference` and `fun routeChoice(state: ProfileState, route: RoutePreference): Route?`.
  - `class TransportFactory(scope, networkChanges: Flow<Unit>, http: OkHttpClient)` with `fun create(spec: TransportSpec): ManagedTransport`.
  - `fun Context.networkChanges(): Flow<Unit>`.
  - `AppViewModel.route: StateFlow<Route?>` and `AppViewModel.setRoute(route: Route)`.

- [ ] **Step 1: Write the failing tests**

Append inside `TransportSpecsTest` (imports to add: `com.gekkotron.oikos.data.GatewayConfig`, `com.gekkotron.oikos.data.RoutePreference`, `com.gekkotron.oikos.transport.Route`):

```kotlin
    private val key = ByteArray(32) { it.toByte() }
    private val local = LocalBroker("192.168.1.91", 1883, username = "oikos", password = "pw")
    private val withGateway = ConnectionProfile.Direct(local, gateway = GatewayConfig("https://gw.example", key, "192.168.1.91"))

    @Test
    fun `a profile with a gateway maps to a routed spec carrying the local credentials`() {
        val spec = ProfileState.Ready(withGateway).toSpec("c", RoutePreference.GATEWAY)
        assertEquals(
            TransportSpec.Routed(
                TransportSpec.Direct("192.168.1.91", 1883, tls = false, ws = false, username = "oikos", password = "pw", clientId = "c"),
                TransportSpec.Gateway("https://gw.example", key, "192.168.1.91", "oikos", "pw"),
                Route.GATEWAY,
            ),
            spec,
        )
    }

    @Test
    fun `a profile without a gateway ignores the route`() {
        val plain = ConnectionProfile.Direct(local)
        assertEquals(ProfileState.Ready(plain).toSpec("c"), ProfileState.Ready(plain).toSpec("c", RoutePreference.GATEWAY))
    }

    @Test
    fun `a route change changes the spec but not the profile`() {
        val state = ProfileState.Ready(withGateway)
        assertNotEquals(state.toSpec("c", RoutePreference.AUTO), state.toSpec("c", RoutePreference.DIRECT))
    }

    @Test
    fun `route choice is offered only for a profile with a gateway`() {
        assertEquals(Route.DIRECT, routeChoice(ProfileState.Ready(withGateway), RoutePreference.DIRECT))
        assertNull(routeChoice(ProfileState.Ready(ConnectionProfile.Direct(local)), RoutePreference.DIRECT))
        assertNull(routeChoice(ProfileState.Ready(ConnectionProfile.Demo), RoutePreference.AUTO))
        assertNull(routeChoice(ProfileState.None, RoutePreference.AUTO))
    }

    @Test
    fun `route and preference map both ways`() {
        Route.entries.forEach { assertEquals(it, it.toPreference().toRoute()) }
    }
```

`app/src/test/kotlin/com/gekkotron/oikos/connection/TransportFactoryTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayTransport
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportSelector
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertIs

class TransportFactoryTest {
    private val factory = TransportFactory(TestScope(), emptyFlow(), OkHttpClient())
    private val direct = TransportSpec.Direct("192.168.1.91", 1883, tls = false, ws = false, username = null, password = null, clientId = "c")
    private val gateway = TransportSpec.Gateway("https://gw.example", ByteArray(32), null, null, null)

    @Test
    fun `each spec builds its transport`() {
        assertIs<DemoBroker>(factory.create(TransportSpec.Demo))
        assertIs<DirectMqttTransport>(factory.create(direct))
        assertIs<GatewayTransport>(factory.create(gateway))
        assertIs<TransportSelector>(factory.create(TransportSpec.Routed(direct, gateway, Route.AUTO)))
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/connection/NetworkChangesTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NetworkChangesTest {
    @Test
    fun `default network callbacks emit, and cancelling unregisters`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        var count = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { context.networkChanges().collect { count++ } }
        runCurrent()
        val callback = shadow.networkCallbacks.single()
        val before = count
        callback.onLost(ShadowNetwork.newInstance(7))
        runCurrent()
        callback.onAvailable(ShadowNetwork.newInstance(8))
        runCurrent()
        assertEquals(before + 2, count)
        job.cancel()
        runCurrent()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TransportSpecsTest*' --tests '*TransportFactoryTest*' --tests '*NetworkChangesTest*'`
Expected: compilation fails with "Unresolved reference: routeChoice", "TransportFactory", "networkChanges".

- [ ] **Step 3: Implement**

Replace the whole of `app/src/main/kotlin/com/gekkotron/oikos/connection/TransportSpecs.kt` with:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportSpec

fun clientIdFor(installId: String): String = "oikos-" + installId.filter { it != '-' }.take(8)

/** The onboarding test connection has its own id: a duplicate id makes the broker kick the other client. */
fun setupClientIdFor(installId: String): String = clientIdFor(installId) + "-setup"

/** A profile with a gateway gets both routes and [route] picks between them (gateway spec §5). */
fun ProfileState.toSpec(clientId: String, route: RoutePreference = RoutePreference.AUTO): TransportSpec? = when (this) {
    is ProfileState.Ready -> when (val p = profile) {
        ConnectionProfile.Demo -> TransportSpec.Demo
        is ConnectionProfile.Direct -> {
            val direct = p.local.toSpec(clientId)
            p.gateway?.let { TransportSpec.Routed(direct, it.toSpec(p.local), route.toRoute()) } ?: direct
        }
    }
    ProfileState.Loading, ProfileState.None, ProfileState.Locked -> null
}

fun LocalBroker.toSpec(clientId: String): TransportSpec.Direct = TransportSpec.Direct(host, port, tls, ws, username, password, clientId)

/** The gateway logs in to the broker with the local credentials; they travel only inside the envelope. */
fun GatewayConfig.toSpec(local: LocalBroker): TransportSpec.Gateway =
    TransportSpec.Gateway(url, key, brokerHost, local.username, local.password)

fun RoutePreference.toRoute(): Route = when (this) {
    RoutePreference.AUTO -> Route.AUTO
    RoutePreference.DIRECT -> Route.DIRECT
    RoutePreference.GATEWAY -> Route.GATEWAY
}

fun Route.toPreference(): RoutePreference = when (this) {
    Route.AUTO -> RoutePreference.AUTO
    Route.DIRECT -> RoutePreference.DIRECT
    Route.GATEWAY -> RoutePreference.GATEWAY
}

/** The link sheet's route row: only a profile with a gateway has a choice to make; null hides the row. */
fun routeChoice(state: ProfileState, route: RoutePreference): Route? =
    ((state as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.gateway?.let { route.toRoute() }
```

`app/src/main/kotlin/com/gekkotron/oikos/connection/TransportFactory.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.transport.AthenaGatewayApi
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayTransport
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.TcpProbe
import com.gekkotron.oikos.transport.TransportSelector
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/** Builds `ActiveTransport`'s transport for a spec (spec §2; gateway spec §5). */
class TransportFactory(
    private val scope: CoroutineScope,
    private val networkChanges: Flow<Unit>,
    private val http: OkHttpClient,
) {
    fun create(spec: TransportSpec): ManagedTransport = when (spec) {
        TransportSpec.Demo -> DemoBroker(scope)
        is TransportSpec.Direct -> direct(spec)
        is TransportSpec.Gateway -> gateway(spec)
        is TransportSpec.Routed -> TransportSelector(
            spec,
            direct = ::direct,
            gateway = ::gateway,
            probe = TcpProbe(spec.direct.host, spec.direct.port),
            networkChanges = networkChanges,
            scope = scope,
        )
    }

    private fun direct(spec: TransportSpec.Direct): ManagedTransport = DirectMqttTransport(spec, scope)

    private fun gateway(spec: TransportSpec.Gateway): ManagedTransport = GatewayTransport(AthenaGatewayApi(spec, http), scope)
}
```

`app/src/main/kotlin/com/gekkotron/oikos/connection/NetworkChanges.kt`:

```kotlin
package com.gekkotron.oikos.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * One event per default-network change (parent spec §4.5 step 1). No SSID is read, so no location
 * permission; `ACCESS_NETWORK_STATE` is enough. Each collector registers its own callback.
 */
fun Context.networkChanges(): Flow<Unit> = callbackFlow {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(Unit)
        }

        override fun onLost(network: Network) {
            trySend(Unit)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate()
```

In `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`:

1. Add these imports:
   - `android.content.Context`
   - `com.gekkotron.oikos.connection.TransportFactory`
   - `com.gekkotron.oikos.connection.networkChanges`
   - `dagger.hilt.android.qualifiers.ApplicationContext`
   - `kotlinx.coroutines.flow.combine`
   - `okhttp3.OkHttpClient`

   Remove the now-unused imports:
   - `com.gekkotron.oikos.demo.DemoBroker`
   - `com.gekkotron.oikos.transport.DirectMqttTransport`
   - `com.gekkotron.oikos.transport.TransportSpec`
   - `kotlinx.coroutines.flow.map`
2. Replace the `activeTransport` provider with the following:

```kotlin
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun transportFactory(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        @ApplicationScope scope: CoroutineScope,
    ): TransportFactory = TransportFactory(scope, context.networkChanges(), http)

    @Provides
    @Singleton
    fun activeTransport(
        profiles: ProfileRepository,
        factory: TransportFactory,
        @ApplicationScope scope: CoroutineScope,
    ): ActiveTransport = ActiveTransport(
        // installId() is a suspend DataStore write; resolved once rather than re-run on every emission.
        specs = flow {
            val id = profiles.installId()
            emitAll(combine(profiles.state, profiles.route) { state, route -> state.toSpec(clientIdFor(id), route) })
        },
        factory = factory::create,
        scope = scope,
    )
```

Replace the whole of `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt` with:

```kotlin
package com.gekkotron.oikos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.routeChoice
import com.gekkotron.oikos.connection.toPreference
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.transport.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val sources: EntitySourceSession,
) : ViewModel() {
    val profile: StateFlow<ProfileState> = profiles.state

    /** The link sheet's route row; null hides it (no gateway in the profile). */
    val route: StateFlow<Route?> =
        combine(profiles.state, profiles.route, ::routeChoice).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Rooms step was finished or skipped (a no-op when it was opened from Home). */
    fun finishRooms() {
        viewModelScope.launch { profiles.finishRooms() }
    }

    /** Rebuilds the transport for the new route; the discovery source and the house on screen stay. */
    fun setRoute(route: Route) {
        viewModelScope.launch { profiles.setRoute(route.toPreference()) }
    }

    /**
     * Forgets the profile. The transport closes, the discovery source is dropped (the next profile may
     * be an equal one, such as the demo again, and must still start from an empty house), and the app
     * returns to onboarding.
     */
    fun setUpAgain() {
        viewModelScope.launch {
            profiles.clear()
            sources.reset()
        }
    }
}
```

In `app/src/main/AndroidManifest.xml`, add after the `INTERNET` permission:

```xml
    <!-- Default-network changes for the route selector; no SSID, so no location permission. -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Add `android:networkSecurityConfig="@xml/network_security_config"` to `<application>`, after `android:dataExtractionRules`.

`app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Gateway spec §5 (amends parent spec §8): HTTP cleartext is off for the whole app, and only system CAs are
  trusted. The gateway is HTTPS only. The MQTT TCP socket is not governed by this file; plain TCP to the LAN
  broker stays allowed with the onboarding warning (direct-connection spec §4.5).
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the existing `MainActivityTest` and `EntitySourceSessionTest`.

- [ ] **Step 5: Commit**

```bash
git add app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(app): routed specs for profiles with a gateway, transport factory, network changes, cleartext off" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Route picker and gateway texts in the link sheet

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/RoomPicker.kt` (`RoomRow` → internal `RadioRow`)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheetTest.kt`

**Interfaces:**
- Consumes: `Route` and the new `LinkState.Offline` constants (Task 1); `AppViewModel.route` and `setRoute` (Task 8).
- Produces:
  - `HomeRoute(onSetUpAgain, onAssignRooms, route: Route? = null, onRouteChange: (Route) -> Unit = {}, viewModel)`.
  - `HomeScreen(…, route: Route? = null, onRouteChange: (Route) -> Unit = {})`.
  - `LinkSheet(link, route, onRouteChange, onSetUpAgain, onAssignRooms, onDismiss)`.

- [ ] **Step 1: Write the failing tests**

In `LinkSheetTest`, replace the `show` helper with the version below and append the tests (import `com.gekkotron.oikos.transport.Route`):

```kotlin
    private fun show(
        link: LinkState,
        onSetUpAgain: () -> Unit = {},
        route: Route? = null,
        onRouteChange: (Route) -> Unit = {},
    ) = compose.setContent {
        OikosTheme {
            HomeScreen(
                HomeUiState(loading = false, link = link), {}, {}, { _, _ -> },
                onSetUpAgain = onSetUpAgain, route = route, onRouteChange = onRouteChange,
            )
        }
    }

    private fun openSheet() = compose.onNodeWithText("Offline").performClick()

    @Test
    fun `no route row without a gateway`() {
        show(LinkState.Connected(TransportMode.DIRECT))
        compose.onNodeWithText("Wi-Fi direct").performClick()
        compose.onNodeWithText("Automatic").assertDoesNotExist()
    }

    @Test
    fun `the route row shows the choice and reports a new one`() {
        var picked: Route? = null
        show(LinkState.Connected(TransportMode.GATEWAY), route = Route.AUTO, onRouteChange = { picked = it })
        compose.onNodeWithText("Gateway").performClick() // the pill; the sheet's "Gateway" option is not shown yet
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithText("Home network").assertIsNotSelected()
        compose.onNodeWithText("Home network").performClick()
        assertEquals(Route.DIRECT, picked)
    }

    @Test
    fun `gateway refusals explain themselves`() {
        show(LinkState.Offline(LinkState.Offline.KEY_REJECTED))
        openSheet()
        compose.onNodeWithText("didn't accept this phone's key", substring = true).assertExists()
    }

    @Test
    fun `a clock refusal suggests automatic time`() {
        show(LinkState.Offline(LinkState.Offline.CLOCK_SKEW))
        openSheet()
        compose.onNodeWithText("automatic date and time", substring = true).assertExists()
    }

    @Test
    fun `other gateway refusals have their own text`() {
        show(LinkState.Offline(LinkState.Offline.GATEWAY_REFUSED))
        openSheet()
        compose.onNodeWithText("The gateway refused access to the broker.").assertExists()
    }
```

Add these imports if they are missing: `androidx.compose.ui.test.assertIsSelected` and `androidx.compose.ui.test.assertIsNotSelected`. The pill labels are `pill_offline` "Offline", `pill_direct` "Wi-Fi direct" and `pill_gateway` "Gateway".

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*LinkSheetTest*'`
Expected: compilation fails with "No parameter with name 'route' found".

- [ ] **Step 3: Implement**

In `RoomPicker.kt`, rename `private fun RoomRow(label: String, selected: Boolean, onClick: () -> Unit)` to `internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit)`, and rename its two call sites in `RoomPicker`. The body does not change.

Replace the whole of `LinkSheet.kt` with:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportMode

/**
 * Link status, the route choice when the profile has a gateway, and the Plan 1 way out of a profile.
 * Settings takes this over later (spec §5.2; gateway spec §5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(
    link: LinkState,
    route: Route?,
    onRouteChange: (Route) -> Unit,
    onSetUpAgain: () -> Unit,
    onAssignRooms: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(linkDescription(link), style = OikosTheme.type.body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (route != null) RoutePicker(route, onRouteChange)
            OutlinedButton(onClick = onAssignRooms, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_assign_rooms)) }
            OutlinedButton(onClick = onSetUpAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_set_up_again)) }
        }
    }
}

@Composable
private fun RoutePicker(selected: Route, onSelect: (Route) -> Unit) {
    Column {
        Text(stringResource(R.string.link_route_title), style = OikosTheme.type.label, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.link_route_auto), selected == Route.AUTO) { onSelect(Route.AUTO) }
            RadioRow(stringResource(R.string.link_route_direct), selected == Route.DIRECT) { onSelect(Route.DIRECT) }
            RadioRow(stringResource(R.string.link_route_gateway), selected == Route.GATEWAY) { onSelect(Route.GATEWAY) }
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
    is LinkState.Offline -> stringResource(
        when (link.reason) {
            LinkState.Offline.REJECTED -> R.string.link_rejected
            LinkState.Offline.CLOSED -> R.string.link_offline_closed
            LinkState.Offline.PAUSED -> R.string.link_offline_paused
            LinkState.Offline.NOT_CONFIGURED -> R.string.link_offline_not_configured
            LinkState.Offline.NOT_STARTED -> R.string.link_offline_not_started
            LinkState.Offline.FAILED -> R.string.link_offline_failed
            LinkState.Offline.KEY_REJECTED -> R.string.link_key_rejected
            LinkState.Offline.CLOCK_SKEW -> R.string.link_clock_skew
            LinkState.Offline.GATEWAY_REFUSED -> R.string.link_gateway_refused
            // The reason constants are English; an unknown one is never shown raw.
            else -> R.string.link_offline
        },
    )
}
```

In `HomeScreen.kt`:

1. Add the import `com.gekkotron.oikos.transport.Route`.
2. Change the `HomeRoute` signature to `fun HomeRoute(onSetUpAgain: () -> Unit = {}, onAssignRooms: () -> Unit = {}, route: Route? = null, onRouteChange: (Route) -> Unit = {}, viewModel: HomeViewModel = hiltViewModel())`, and pass `route = route, onRouteChange = onRouteChange,` into its `HomeScreen(...)` call.
3. Add two last parameters to `HomeScreen`: `route: Route? = null,` and `onRouteChange: (Route) -> Unit = {},`.
4. Replace the `LinkSheet(...)` call with:

```kotlin
        LinkSheet(
            state.link,
            route = route,
            onRouteChange = onRouteChange,
            onSetUpAgain = { linkSheet = false; onSetUpAgain() },
            onAssignRooms = { linkSheet = false; onAssignRooms() },
            onDismiss = { linkSheet = false },
        )
```

In `feature/home/src/main/res/values/strings.xml`, after `link_offline_failed`, add:

```xml
    <string name="link_key_rejected">The gateway didn\'t accept this phone\'s key. Set up the connection again with a new setup code.</string>
    <string name="link_clock_skew">The gateway refused the request because this phone\'s clock is off. Turn on automatic date and time.</string>
    <string name="link_gateway_refused">The gateway refused access to the broker.</string>
    <string name="link_route_title">Connection</string>
    <string name="link_route_auto">Automatic</string>
    <string name="link_route_direct">Home network</string>
    <string name="link_route_gateway">Gateway</string>
```

In `feature/home/src/main/res/values-fr/strings.xml`, at the same place, add:

```xml
    <string name="link_key_rejected">La passerelle n\'a pas accepté la clé de ce téléphone. Configurez à nouveau la connexion avec un nouveau code.</string>
    <string name="link_clock_skew">La passerelle a refusé la requête car l\'horloge de ce téléphone est décalée. Activez la date et l\'heure automatiques.</string>
    <string name="link_gateway_refused">La passerelle a refusé l\'accès au broker.</string>
    <string name="link_route_title">Connexion</string>
    <string name="link_route_auto">Automatique</string>
    <string name="link_route_direct">Réseau local</string>
    <string name="link_route_gateway">Passerelle</string>
```

In `OikosNavHost.kt`, after `val profile by …`, add `val route by viewModel.route.collectAsStateWithLifecycle()`. Change the `HomeKey` entry to:

```kotlin
            entry<HomeKey> {
                HomeRoute(
                    onSetUpAgain = viewModel::setUpAgain,
                    onAssignRooms = { if (backStack.lastOrNull() != RoomsKey) backStack.add(RoomsKey) },
                    route = route,
                    onRouteChange = viewModel::setRoute,
                )
            }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/home app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(home): route picker in the link sheet and texts for gateway refusals (EN/FR)" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Onboarding falls back to the gateway

**Files:**
- Modify: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ConnectionTester.kt`
- Modify: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/Messages.kt`
- Modify: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/ConnectScreen.kt`
- Modify: `feature/onboarding/src/main/res/values/strings.xml`, `feature/onboarding/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/connection/DirectConnectionTester.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt` (the tester provider)
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/DirectConnectionTesterTest.kt`
- Test: `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/ui/OnboardingScreensTest.kt`

**Interfaces:**
- Consumes:
  - From Task 5: `GatewayTransport`, `GatewayApi` and the testFixtures `FakeGatewayApi`.
  - From Task 2: `GatewayRefused` and `Refusal`; from Task 3: `AthenaGatewayApi`.
  - From Task 8: `GatewayConfig.toSpec(local)`.
- Produces:
  - `TestFailure.KeyRejected` and `TestFailure.ClockSkew`.
  - `TestState.Connected(devices: List<String>, viaGateway: Boolean = false)`.
  - `DirectConnectionTester(clientId, connectTimeout = 5.seconds, gatewayTimeout = 10.seconds, gatewayApi: (TransportSpec.Gateway) -> GatewayApi = { AthenaGatewayApi(it) }, dispatcher = Dispatchers.Default)`.
  - `internal fun gatewayFailure(link: LinkState?): TestFailure`.

- [ ] **Step 1: Write the failing tests**

Append inside `DirectConnectionTesterTest` (imports: `com.gekkotron.oikos.data.GatewayConfig`, `com.gekkotron.oikos.transport.GatewayRefused`, `com.gekkotron.oikos.transport.Refusal`, `com.gekkotron.oikos.transport.testing.FakeGatewayApi`):

```kotlin
    private val kettle = """{"name":"Kettle","unique_id":"kettle","command_topic":"k/set","state_topic":"k/state",
        "device":{"identifiers":["kettle"],"name":"Kitchen kettle"}}"""

    private fun awayDraft() = ConnectionProfile.Direct(
        LocalBroker("127.0.0.1", freePort()),
        gateway = GatewayConfig("https://gw.example", ByteArray(32), "192.168.1.91"),
    )

    private fun gatewayTester(api: FakeGatewayApi) =
        DirectConnectionTester(clientId = { "oikos-test-setup" }, connectTimeout = 1.seconds, gatewayTimeout = 3.seconds, gatewayApi = { api })

    @Test
    fun `an unreachable broker falls back to the gateway when the draft has one`() = runBlocking {
        val api = FakeGatewayApi().apply {
            onOpen = { it.connect(); it.message("homeassistant/switch/kettle/config", kettle, retained = true) }
        }
        val connected = withTimeout(10.seconds) {
            gatewayTester(api).test(awayDraft()).first { it is TestState.Connected && it.devices.isNotEmpty() }
        }
        assertEquals(TestState.Connected(listOf("Kitchen kettle"), viaGateway = true), connected)
    }

    @Test
    fun `a gateway that rejects the key fails with KeyRejected`() = runBlocking {
        val api = FakeGatewayApi().apply { onOpen = { it.end(GatewayRefused(Refusal.KeyRejected)) } }
        val failed = withTimeout(10.seconds) { gatewayTester(api).test(awayDraft()).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.KeyRejected), failed)
    }

    @Test
    fun `a gateway that never connects fails as unreachable`() = runBlocking {
        val failed = withTimeout(10.seconds) { gatewayTester(FakeGatewayApi()).test(awayDraft()).first { it is TestState.Failed } }
        assertEquals(TestState.Failed(TestFailure.Unreachable), failed)
    }

    @Test
    fun `gateway links map to test failures`() {
        assertEquals(TestFailure.KeyRejected, gatewayFailure(LinkState.Offline(LinkState.Offline.KEY_REJECTED)))
        assertEquals(TestFailure.ClockSkew, gatewayFailure(LinkState.Offline(LinkState.Offline.CLOCK_SKEW)))
        assertEquals(TestFailure.Rejected, gatewayFailure(LinkState.Offline(LinkState.Offline.REJECTED)))
        assertEquals(TestFailure.Other("gateway refused"), gatewayFailure(LinkState.Offline(LinkState.Offline.GATEWAY_REFUSED)))
        assertEquals(TestFailure.Unreachable, gatewayFailure(null))
        assertEquals(TestFailure.Unreachable, gatewayFailure(LinkState.Reconnecting(2)))
    }
```

Add the import `com.gekkotron.oikos.transport.LinkState`.

Append inside `OnboardingScreensTest`:

```kotlin
    @Test
    fun `connect says when it went through the gateway`() {
        compose.setContent { OikosTheme { ConnectScreen(TestState.Connected(listOf("Kettle"), viaGateway = true), false, {}, {}, {}) } }
        compose.onNodeWithText("Connected through the gateway", substring = true).assertExists()
    }

    @Test
    fun `gateway failures have their own texts`() {
        var test by mutableStateOf<TestState>(TestState.Failed(TestFailure.KeyRejected))
        compose.setContent { OikosTheme { ConnectScreen(test, false, {}, {}, {}) } }
        compose.onNodeWithText("didn't accept the key", substring = true).assertExists()
        test = TestState.Failed(TestFailure.ClockSkew)
        compose.onNodeWithText("automatic date and time", substring = true).assertExists()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:onboarding:testDebugUnitTest --tests '*OnboardingScreensTest*' :app:testDebugUnitTest --tests '*DirectConnectionTesterTest*'`
Expected: compilation fails with "Unresolved reference: KeyRejected", "viaGateway", "gatewayApi", "gatewayFailure".

- [ ] **Step 3: Implement**

In `ConnectionTester.kt`, change `TestFailure` and `TestState.Connected` as follows:

```kotlin
sealed interface TestFailure {
    data object Unreachable : TestFailure
    data object Rejected : TestFailure
    data object Tls : TestFailure

    /** The gateway did not accept the key from the setup code. */
    data object KeyRejected : TestFailure

    /** The gateway refused the request as expired: the phone's clock is off. */
    data object ClockSkew : TestFailure
    data class Other(val message: String) : TestFailure
}
```

```kotlin
    /** Connected; [devices] are the names discovered so far, sorted. [viaGateway]: the broker was out of reach. */
    data class Connected(val devices: List<String>, val viaGateway: Boolean = false) : TestState
```

In `ui/Messages.kt`, add two branches to `testFailureText`:

```kotlin
    TestFailure.KeyRejected -> stringResource(R.string.connect_error_key_rejected)
    TestFailure.ClockSkew -> stringResource(R.string.connect_error_clock)
```

In `ui/ConnectScreen.kt`, in the `is TestState.Connected ->` branch, right after the `Text(pluralStringResource(R.plurals.connect_discovering, …))` line, add:

```kotlin
                if (test.viaGateway) {
                    Text(stringResource(R.string.connect_via_gateway), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
                }
```

In `feature/onboarding/src/main/res/values/strings.xml`, after `connect_error_other`, add:

```xml
    <string name="connect_error_key_rejected">The gateway didn\'t accept the key in this code. Ask for a new setup code.</string>
    <string name="connect_error_clock">The gateway refused the request because this phone\'s clock is off. Turn on automatic date and time, then retry.</string>
    <string name="connect_via_gateway">Connected through the gateway: the broker isn\'t reachable from this network.</string>
```

In `values-fr/strings.xml`, at the same place, add:

```xml
    <string name="connect_error_key_rejected">La passerelle n\'a pas accepté la clé de ce code. Demandez un nouveau code de configuration.</string>
    <string name="connect_error_clock">La passerelle a refusé la requête car l\'horloge de ce téléphone est décalée. Activez la date et l\'heure automatiques, puis réessayez.</string>
    <string name="connect_via_gateway">Connecté via la passerelle : le broker n\'est pas joignable depuis ce réseau.</string>
```

Replace the whole of `app/src/main/kotlin/com/gekkotron/oikos/connection/DirectConnectionTester.kt` with:

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
import com.gekkotron.oikos.transport.AthenaGatewayApi
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayApi
import com.gekkotron.oikos.transport.GatewayTransport
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Onboarding's connection test (spec §5.1). It uses a temporary transport and discovery source, so the
 * device count comes from the real parser. When the broker is out of reach and the code has a gateway,
 * it tries the gateway the same way (gateway spec §5). Cancelling the flow closes the connection.
 */
class DirectConnectionTester(
    private val clientId: suspend () -> String,
    private val connectTimeout: Duration = 5.seconds,
    private val gatewayTimeout: Duration = 10.seconds,
    private val gatewayApi: (TransportSpec.Gateway) -> GatewayApi = { AthenaGatewayApi(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConnectionTester {
    override fun test(draft: ConnectionProfile.Direct): Flow<TestState> = channelFlow {
        send(TestState.Connecting)
        val failure = testDirect(draft) ?: return@channelFlow // null: connected, discovered until cancelled
        val gateway = draft.gateway
        if (failure != TestFailure.Unreachable || gateway == null) {
            send(TestState.Failed(failure))
            return@channelFlow
        }
        // A child scope: a failed attempt must not leave the source's collectors keeping this flow open.
        val attempt = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val transport = GatewayTransport(gatewayApi(gateway.toSpec(draft.local)), attempt)
        try {
            transport.start()
            // The source's subscriptions open the stream: the gateway refuses an empty topic list.
            val source = MqttDiscoverySource(transport, attempt, prefix = draft.prefix)
            val link = withTimeoutOrNull(gatewayTimeout) {
                transport.link.first { it is LinkState.Connected || it is LinkState.Offline }
            }
            if (link !is LinkState.Connected) {
                send(TestState.Failed(gatewayFailure(link)))
                return@channelFlow
            }
            discover(source, viaGateway = true)
        } finally {
            withContext(NonCancellable) { transport.close() }
            attempt.cancel()
        }
    }.flowOn(dispatcher)

    /** Returns the failure, or runs discovery until the collector cancels (and so never returns). */
    private suspend fun ProducerScope<TestState>.testDirect(draft: ConnectionProfile.Direct): TestFailure? {
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
            if (outcome != true) return (outcome as? DirectFailure ?: transport.failure.value).toTestFailure()
            discover(MqttDiscoverySource(transport, this, prefix = draft.prefix), viaGateway = false)
            return null
        } finally {
            withContext(NonCancellable) { transport.close() }
        }
    }

    /**
     * conflate() keeps only the latest pending list while this collector is delaying, so a burst of retained
     * configs never grows the UI update rate past CONNECTED_THROTTLE, and the delay after send (rather than
     * a fixed-interval sample) guarantees the final list is always the one delivered.
     */
    private suspend fun ProducerScope<TestState>.discover(source: MqttDiscoverySource, viaGateway: Boolean) {
        combine(source.devices, source.entities, ::deviceNames)
            .distinctUntilChanged()
            .conflate()
            .collect { names ->
                send(TestState.Connected(names, viaGateway))
                delay(CONNECTED_THROTTLE)
            }
    }

    private companion object {
        val CONNECTED_THROTTLE = 250.milliseconds
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

/** A gateway attempt that did not connect: its refusal, or "unreachable" when it simply never connected. */
internal fun gatewayFailure(link: LinkState?): TestFailure = when ((link as? LinkState.Offline)?.reason) {
    LinkState.Offline.KEY_REJECTED -> TestFailure.KeyRejected
    LinkState.Offline.CLOCK_SKEW -> TestFailure.ClockSkew
    LinkState.Offline.REJECTED -> TestFailure.Rejected
    LinkState.Offline.GATEWAY_REFUSED -> TestFailure.Other("gateway refused")
    else -> TestFailure.Unreachable
}
```

`AppModule.connectionTester` shares the app's OkHttp client:

```kotlin
    @Provides
    @Singleton
    fun connectionTester(profiles: ProfileRepository, http: OkHttpClient): ConnectionTester =
        DirectConnectionTester(clientId = { setupClientIdFor(profiles.installId()) }, gatewayApi = { AthenaGatewayApi(it, http) })
```

Add the import `com.gekkotron.oikos.transport.AthenaGatewayApi` to `AppModule.kt`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:onboarding:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, including the existing direct-path tests (`reachable broker reports discovered devices`, `unreachable broker fails with Unreachable after the timeout`, `rejected credentials fail with Rejected`, and the burst test).

- [ ] **Step 5: Run the full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Kover must stay at or above its thresholds. `NetworkChanges.kt` is covered by its Robolectric test; nothing new needs a Kover exclusion.

- [ ] **Step 6: Commit**

```bash
git add feature/onboarding app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit \
  -m "feat(onboarding): the connection test falls back to the gateway away from home; key and clock failures (EN/FR)" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Phone check on the real house** (manual; report the results)

1. On Wi-Fi: relaunch the app. The pill shows "Wi-Fi direct", and toggling a light works.
2. Turn Wi-Fi off (mobile data on). Within about 5 s the pill shows "Gateway". Cards keep their values without going to "—", and toggling a light works.
3. Turn Wi-Fi back on. Within about 60 s (sooner if Android reports a new default network) the pill goes back to "Wi-Fi direct".
4. In the link sheet, choose **Gateway** while on Wi-Fi: the pill shows "Gateway". Choose **Automatic**: it goes back to "Wi-Fi direct".
5. Set the phone clock 10 min off with automatic time off, then choose **Gateway**. The sheet shows the clock message, and nothing retries in a loop. Restore automatic time.
