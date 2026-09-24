# Oikos — direct connection and onboarding (design)

- **Date:** 2026-09-24
- **Author:** Gekkotron
- **Status:** Draft, awaiting review
- **Parent spec:** [Oikos design spec](2026-09-24-oikos-design.md) (§4.5, §4.7, §5.2, §8)

This is the first of two plans that replace the built-in demo house with a real connection. It covers what is needed to use Oikos **at home**: persistent storage for the connection profile and its secrets, a real MQTT transport to the local broker, and onboarding by QR code or manual entry. The gateway (remote) transport and automatic mode selection follow in Plan 2.

---

## 1. Goal and scope

**Goal.** After a fresh install, the user scans the setup QR code (or types the broker settings), sees the connection succeed and devices being discovered, and lands on Home showing the real house, with live state and working commands on the home network.

**In scope**
- `:core:data`: `ProfileRepository`, `SecretStore`, the QR payload parser, `installId`.
- `:core:transport`: `DirectMqttTransport`, `ManagedTransport`, `TransportSpec`, `ActiveTransport`.
- `:feature:onboarding`: Welcome, Scan, Manual, Connect & discover.
- `:app`: wiring the profile to the transport, choosing the start screen, the entity-source session, and a "Set up connection again" action on Home.
- The demo house stays available as a profile ("Try the demo").

**Deferred**

| Item | Goes to |
|---|---|
| `GatewayTransport`, `TransportSelector` (probe, auto mode, make-before-break), per-profile network security config | Plan 2 |
| Entity cache for instant cold start (§4.7) | Plan 2 (it matters most with the slower gateway) |
| Room-assignment onboarding step (§5.2 step 5) | Override plan (shares the `oikos/ui/…` publisher with the override sheet) |
| Theme and language persistence | Settings plan |
| MQTT 5 negotiation | Later; nothing in v1 uses MQTT 5 features |

**Success criteria**
1. Fresh install → scan QR → "Discovering… N devices" → Home shows real devices; toggling a light works.
2. Killing and relaunching the app goes straight to Home with the saved profile.
3. Going to the background closes the MQTT connection after 30 s; returning reconnects and refreshes state.
4. "Try the demo" and "Set up connection again" both work, without restarting the app.
5. No secret is ever written to disk in plaintext or logged.

---

## 2. Modules and wiring

```
:core:transport      + TransportSpec, ManagedTransport, DirectMqttTransport (HiveMQ), ActiveTransport
:core:data           NEW  ProfileRepository, SecretStore, OnboardingPayload parser
:feature:onboarding  NEW  Welcome → Scan | Manual → Connect & discover → Home
:app                 profile → TransportSpec; factory Demo | Direct; start screen; EntitySourceSession
```

- **`TransportSpec`** (sealed, in `:core:transport`): `Demo` or `Direct(host, port, tls, ws, username, password)`. Plan 2 adds `Gateway(...)`.
- **`ActiveTransport`** takes `specs: Flow<TransportSpec?>` and `factory: (TransportSpec) -> ManagedTransport`. It never depends on `:core:data` or `:core:demo`, so `:core:transport` stays pure JVM.
- **`:app`** maps the saved profile to a spec (no profile → `null`) and supplies the factory: `Demo → DemoBroker`, `Direct → DirectMqttTransport`. `DemoBroker` implements `ManagedTransport`; its ticking starts in `start()` and stops in `close()`.
- **`EntitySourceSession`** (`:app`). `MqttDiscoverySource` takes the discovery prefix in its constructor, and the prefix comes from the profile. The session builds the source for the current profile's prefix in a child scope. When the prefix changes (re-onboarding with a different prefix), it cancels the old child scope and builds a new source. `EntitySource` is provided **unscoped** from `session.current()`. Home's ViewModel is cleared whenever Home leaves the back stack, so a new Home always receives the current source.
- **Start screen.** `OikosNavHost` observes `ProfileRepository.state`: `Loading` → nothing (the system splash stays up), `None`/`Locked` → `OnboardingKey`, `Ready` → `HomeKey`. Finishing onboarding replaces the back stack with `HomeKey`.
- **Dependency rule** (parent spec §4.1): `:feature:onboarding` never depends on `:core:transport`. It talks to a `ConnectionTester` interface that it declares and `:app` implements. It depends on `:core:data` for the draft profile and parser.

---

## 3. Transports (`:core:transport`)

### 3.1 `ManagedTransport`

```kotlin
interface ManagedTransport : MqttTransport {
    fun start()
    suspend fun close()
}
```

The public `MqttTransport` interface is unchanged.

### 3.2 `DirectMqttTransport`

HiveMQ `Mqtt3AsyncClient`, MQTT 3.1.1.

| Aspect | Behaviour |
|---|---|
| Connection types | TCP; TLS with the system trust store; WebSocket or secure WebSocket on path `/mqtt` |
| Session | Clean session; client id `oikos-<first 8 chars of installId>` |
| Credentials | Optional username and password |
| `link` | `Connecting` → `Connected(DIRECT)`. On connection loss: `Reconnecting(attempt)` using HiveMQ automatic reconnect, 1 s initial delay, 30 s maximum |
| Refused CONNACK (bad credentials, not authorized) | `Offline("broker rejected credentials")`; automatic reconnect is stopped |
| `setSubscriptions` | Diff against the current set: subscribe to added filters, unsubscribe removed ones. The set is kept and re-applied after every reconnect (clean session drops subscriptions) |
| `messages()` | HiveMQ callbacks push into an unbounded internal channel that feeds the `SharedFlow`. No message is ever dropped: a lost retained discovery config would hide a device |
| `publish` | Suspends until PUBACK (QoS 1) or until sent (QoS 0), 10 s timeout. Throws `PublishFailed` when not connected, on timeout or on error |
| `close()` | Disconnects, cancels reconnect, completes quietly if already disconnected |

### 3.3 `ActiveTransport`

Implements `MqttTransport` for the rest of the app.

- **Spec changes.** Collects `specs.distinctUntilChanged()`. On each change: `close()` the old transport, create the new one with the factory, `start()` it, replay the current subscription set. This is break-before-make; Plan 2's selector adds make-before-break for automatic switching.
- **`link`.** Mirrors the current transport's link. With no spec: `Offline("not configured")`. While paused in the background: `Offline("paused")`.
- **`messages()`.** One long-lived `SharedFlow` that follows the current transport (`flatMapLatest`). Messages from a closed transport are never emitted.
- **`setSubscriptions`.** Stores the set and forwards it to the current transport, if any.
- **`publish`.** Forwards to the current transport; with none, throws `PublishFailed("not connected")`.
- **Foreground only** (parent spec §4.5.5, pulled forward from the selector). `setActive(Boolean)`, driven by `ProcessLifecycleOwner` in `:app`. Going inactive closes the current transport after **30 s** (cancelled if the app returns first). Becoming active again recreates it from the current spec and replays subscriptions; retained messages refresh the state.

---

## 4. Storage and secrets (`:core:data`)

### 4.1 DataStore

A typed DataStore with a **kotlinx-serialization JSON serializer**. The parent spec names Proto DataStore; JSON keeps the same type safety, reuses the project's serialization setup and avoids the protobuf Gradle plugin and code generation.

One file, `profile.json`:

```kotlin
@Serializable data class StoredProfile(
    val installId: String,                 // random UUID, created once, survives clear()
    val mode: Mode? = null,                // DEMO | DIRECT; null = not configured
    val local: StoredLocal? = null,        // host, port, tls, ws, username, passCipher?
    val gateway: StoredGateway? = null,    // url, brokerHost, keyCipher — kept for Plan 2, unused here
    val prefix: String = "homeassistant",
    val pushTokenTopic: String? = null,    // kept for the push plan
)
```

### 4.2 `ProfileRepository`

- `state: StateFlow<ProfileState>` with `Loading`, `None`, `Locked` (secrets could not be decrypted) and `Ready(ConnectionProfile)`. `ConnectionProfile` is the decrypted in-memory model.
- `save(profile)` encrypts the secret fields, then writes.
- `clear()` resets everything except `installId`.
- `installId()` returns the stable id, creating it on first use.

### 4.3 `SecretStore`

```kotlin
interface SecretStore {
    fun encrypt(plain: ByteArray): ByteArray
    /** @throws SecretUnavailable when the key is missing or the ciphertext does not authenticate. */
    fun decrypt(cipher: ByteArray): ByteArray
}
```

- **`KeystoreSecretStore`**: AES-256-GCM key in the Android Keystore, alias `oikos.secrets.v1`, non-exportable, random 12-byte IV prefixed to the ciphertext. Only the broker password and the gateway key are encrypted; the ciphertext is stored base64 in `profile.json`.
- **Key loss.** Keystore keys are not backed up and can be invalidated. If decryption fails, the state is `Locked`: onboarding opens with the notice "Your saved connection couldn't be unlocked — scan again". `installId` is kept.
- **Backups.** `dataExtractionRules` excludes `profile.json` entirely; without the key it is useless.
- The key material and decrypted secrets are never logged.

### 4.4 QR payload parser

`OnboardingPayload.parse(text): ParseResult` (pure Kotlin) for the parent spec's §5.2 format:

```json
{ "v":1, "local":{ "host":"…","port":1883,"tls":false,"ws":false,"user":"…","pass":"…" },
  "gateway":{ "url":"https://…","key":"<hex>","broker_host":"…" }, "prefix":"homeassistant",
  "push":{ "token_topic":"fcm-push/tokens/{installId}" } }
```

| Rule | Error |
|---|---|
| Text is JSON with `v` | `NotOikosCode` |
| `v == 1` | `UnsupportedVersion(v)` |
| `local` present, `host` non-blank, `port` in 1..65535 | `InvalidField("local.host" / "local.port")` |
| If `gateway` present: `url` starts with `https://`, `key` is exactly 64 hex characters | `InvalidField("gateway.url" / "gateway.key")` |
| `prefix` non-blank if present | `InvalidField("prefix")` |

Unknown fields are ignored. Defaults: `port` 1883, `tls`/`ws` false, `prefix` `homeassistant`. Each error maps to an EN/FR message.

### 4.5 Plain TCP

Plain (non-TLS) MQTT is allowed, since a direct profile is the user's LAN broker. The manual form shows a non-blocking warning when TLS is off and the host is neither a private IPv4 literal (10/8, 172.16/12, 192.168/16) nor a `.local`/`.lan` name. The per-profile network security config in the parent spec §8 only concerns HTTP and moves to Plan 2.

---

## 5. Onboarding (`:feature:onboarding`)

All strings in English and French. `FLAG_SECURE` on Scan and Manual.

1. **Welcome.** Title and tagline; **Scan QR code** (primary), **Enter manually**, **Try the demo** (text button). A banner when the state is `Locked`.
2. **Scan.** CameraX preview with an ML Kit bundled barcode analyzer restricted to QR codes. The camera permission is requested when the screen opens. If denied: an explanation with **Open settings** and **Enter manually**. The first decoded code goes through the parser; an invalid one shows an inline error and scanning continues.
3. **Manual.** Host, port (default 1883), TLS and WebSocket toggles, username, password; a collapsed "Advanced" section with the discovery prefix. Local broker only; gateway settings come from the QR code (or Settings, in Plan 2). The plain-TCP warning of §4.5 appears here.
4. **Connect & discover.** Runs the draft through `ConnectionTester`:
   - **Failure** shows a reason with **Back** and **Retry**: unreachable (5 s timeout), credentials rejected, TLS error, other (with the message).
   - **Success** shows a live "Discovering… N devices" count with device names. **Continue** is enabled once connected; with 0 devices it shows "No devices announced yet — check that discovery is enabled".
   - **Continue** saves the profile; `ActiveTransport` switches; the back stack becomes `[HomeKey]`.

**Try the demo** saves `mode = DEMO` and goes to Home.

### 5.1 `ConnectionTester`

```kotlin
interface ConnectionTester {
    fun test(draft: ConnectionProfile): Flow<TestState>   // Connecting, Failed(reason), Connected(devices: List<String>)
}
```

`:app` implements it with a temporary `DirectMqttTransport` and a temporary `MqttDiscoverySource` in a child scope, so the count uses the real discovery parser. Cancelling the flow closes both.

### 5.2 "Set up connection again"

Plan 1 has no Settings screen. Tapping Home's link pill opens a small sheet with the link state and **Set up connection again**, which calls `clear()` and returns to Welcome. The Settings plan takes this over.

---

## 6. Error handling

| Situation | Behaviour |
|---|---|
| No profile | Onboarding; transport link `Offline("not configured")` |
| Secrets cannot be decrypted | State `Locked`; onboarding with the unlock notice |
| Broker unreachable during the connection test | "Can't reach the broker" after 5 s; Back / Retry |
| Credentials rejected (test or later) | Test: "Username or password rejected". Later: `Offline("broker rejected credentials")`, no retry loop; the pill sheet offers "Set up connection again" |
| Connection lost after setup | `Reconnecting(attempt)`, 1 s → 30 s; cards behave as in parent spec §7 |
| Publish while disconnected | `PublishFailed` → the existing "Not confirmed — Retry" snackbar |
| Invalid QR code | Inline error on the scan screen; scanning continues |
| Camera permission denied | Explanation, Open settings, Enter manually |

---

## 7. Testing

| Target | Tests |
|---|---|
| `ActiveTransport` | With `FakeMqttTransport`s (wrapped as `ManagedTransport`): spec switch closes old and starts new; subscriptions replayed; messages only from the current transport; `null` spec → `Offline("not configured")`; 30 s background close and cancel-on-return with virtual time; publish without a transport throws |
| `DirectMqttTransport` | JVM integration tests against an embedded **Moquette** broker: connect; bad credentials → `Offline` with no retry; subscription diff; broker restart → resubscribe and messages resume; retained delivery; QoS 1 publish; publish while disconnected throws; a 1,000-message burst arrives complete and in order |
| QR parser | Every valid shape and every error in §4.4 |
| `ProfileRepository` | With an in-memory `SecretStore` and a temp file: round trip; the password and gateway key never appear in the file's bytes; decrypt failure → `Locked`; `clear()` keeps `installId` |
| `EntitySourceSession` | Same prefix → same source; new prefix → old scope cancelled, new source |
| Onboarding | ViewModel tests with fake tester and repository; Robolectric Compose UI tests for Welcome → Manual → connect success / failure, Try the demo, and the Locked banner. The QR analyzer's decode-then-parse step is unit-tested; the camera is not |
| `KeystoreSecretStore` | One instrumented test (run locally; CI has no emulator). Excluded from Kover so the 80 % targets stay honest |

---

## 8. Dependencies added

| Library | Used by |
|---|---|
| `com.hivemq:hivemq-mqtt-client` | `DirectMqttTransport` |
| `io.moquette:moquette-broker` (test) | `DirectMqttTransport` tests |
| `androidx.datastore:datastore` | `:core:data` |
| `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-compose` | Scan screen |
| `com.google.mlkit:barcode-scanning` (bundled model) | Scan screen |
| `androidx.lifecycle:lifecycle-process` | Foreground tracking in `:app` |

Exact versions are pinned in the implementation plan after checking current stable releases.
