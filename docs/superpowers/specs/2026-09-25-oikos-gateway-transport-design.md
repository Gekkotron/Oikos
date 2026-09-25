# Oikos — gateway transport and route selection (design)

- **Date:** 2026-09-25
- **Author:** Gekkotron
- **Status:** Draft, awaiting review
- **Parent specs:** [Oikos design](2026-09-24-oikos-design.md) §3.A, §4.5, §4.6, §7, §8; [direct connection](2026-09-24-oikos-direct-connection-design.md) (the Plan 2 rows of its "Deferred" table)

Plan 1 made Oikos work on the home network. This part makes it work **anywhere**. Away from home it reaches the broker through Athena-HttpMqttGateway. It picks the route on its own and switches without losing messages.

---

## 1. Goal and scope

**Success criteria**
1. A profile whose QR code has a `gateway` section works on mobile data. Home shows the live house, the pill says "Gateway", and toggling a light works.
2. Walking out of Wi-Fi switches to the gateway, and walking back in switches to direct. No card goes back to "—" and no message is lost during the switch.
3. A key the gateway does not accept, or a phone clock that is off, shows its own message. Neither causes a retry loop.
4. The link sheet offers **Automatic / Home network / Gateway** when the profile has a gateway.
5. Onboarding away from home still succeeds for a QR code with a gateway: the connection test falls back to it.
6. No secret is logged, and HTTP cleartext is off for the whole app.

**Out of scope:** a Settings screen (the route choice lives in the link sheet until then), editing the gateway URL or key by hand (QR code only), push, and the e2e docker fixture (parent spec §10; it gets a plan of its own).

---

## 2. The gateway's wire contract (server v1.1, `Athena-HttpMqttGateway@39e6dee`)

Sub-project A has landed: multi-topic subscribe, the timestamp window on `/mqtt/subscribe`, a nonce replay cache, and a 15 s keepalive. Server v1.1 also tags the gateway's own errors with `source: "gateway"`.

**Oikos does not speak this protocol itself.** It uses the gateway's Kotlin SDK, `athena-gateway-client` v1.1 (JitPack, `sdk/kotlin` in the gateway repo). The SDK handles the envelope crypto, SSE framing and error decoding. The table below is what the SDK implements; it matters here for the payload encoding and the refusal mapping. **The house gateway must run server v1.1 or later.**

| Aspect | Contract |
|---|---|
| Envelope | `base64(nonce[12] ‖ ciphertext ‖ tag)` over UTF-8 JSON (`EnvelopeCrypto`, already in `:core:crypto`). Every request carries a fresh `timestamp`; the nonce is random, so a retry is never taken for a replay |
| `POST /mqtt/subscribe` | Body `{topics, qos, broker_host?, username?, password?, timestamp}`. **Success:** `200 text/event-stream`. **Refused before the stream starts:** `200 application/octet-stream` whose body is an envelope `{status: 403\|500, body: {error}}`; `401` with an empty body when no key matched; `400` when malformed |
| Stream events | `data: <envelope>` frames of `{type: connected\|message\|error\|disconnected, …}`, and `: keepalive` comments every 15 s. `message` has `topic`, `payload`, `qos`, `retain`, `timestamp`. `connected` has **no** `timestamp`. After `error` or `disconnected` the gateway ends the stream |
| Payloads | The gateway **JSON-decodes** each payload: a JSON payload arrives as a JSON value, anything else as a string, and an empty payload as `""` |
| `POST /mqtt/publish` | Body `{topic, message, qos, retain, broker_host?, username?, password?, timestamp}`. The response is `200` with the envelope `{status, body, timestamp}`, where `body` is a JSON **string** for success and MQTT errors and an **object** for 403 refusals. Otherwise it is `401` with an empty body |
| 403 reasons | `"Request expired"`, `"Request replayed"`, `"port not allowed for this secret"`, `"destination not allowed for this secret"` |

`broker_port` is never sent: the gateway uses its own `MQTT_BROKER_PORT`, and the phone's local port may be a WebSocket port. `broker_host` comes from the QR code's `gateway.broker_host` and is omitted when absent. The username and password are the local broker's, and they only travel inside the envelope.

**Payload re-encoding.** A string value becomes its UTF-8 bytes. Any other JSON value becomes its compact JSON text, so `{"state": "ON"}` stays JSON that templates can read. Two limits come from the gateway: a number loses its formatting (`21.50` arrives as `21.5`), and a JSON string literal loses its quotes. Neither matters for HA templates.

**Known gateway limit (follow-up for sub-project A).** If a payload is not valid UTF-8, the gateway sends an `error` event and ends the stream. The app simply reconnects with backoff. A retained binary payload on a subscribed topic would therefore make the gateway route unusable. Nothing in the current house publishes one.

---

## 3. Transports (`:core:transport`)

### 3.1 New spec types

```kotlin
class TransportSpec.Gateway(url, key: ByteArray, brokerHost: String?, username: String?, password: String?)  // toString masks key and password
data class TransportSpec.Routed(direct: Direct, gateway: Gateway, route: Route)
enum class Route { AUTO, DIRECT, GATEWAY }
```

`:app` maps a direct profile **without** a gateway to `Direct` (as today), and a direct profile **with** a gateway to `Routed`.

New `LinkState.Offline` reasons:

| Constant | Text | Meaning |
|---|---|---|
| `KEY_REJECTED` | `gateway key not accepted` | HTTP 401 |
| `CLOCK_SKEW` | `phone clock is off` | 403 `Request expired` |
| `GATEWAY_REFUSED` | `gateway refused` | any other 403 |

A gateway `error` event reporting MQTT connect code 4 or 5 maps to the existing `REJECTED`. None of these retry: they need the user to act.

### 3.2 `Handover` (make-before-break, shared)

This is an internal helper that runs one `ManagedTransport` as *current*, mirrors its `link`, and forwards its messages. `switchTo(next)` works like this:

1. Start `next`, with its messages buffered in an unbounded channel from the very first one.
2. If there is no current transport, or the current one is not `Connected`, flip at once. Otherwise wait until `next` is `Connected`, for at most **15 s**.
3. **Flip:**
   1. Stop forwarding the old transport (cancel and join its collector, so no straggler is emitted later).
   2. Make `next` current.
   3. Drain its buffer, then keep forwarding it.
   4. Close the old transport.
4. If `next` does not connect in time, close it and keep the current one; `switchTo` returns `false`.

The new stream is complete and in order from its own start, so after the flip every topic ends at its latest value. Duplicates are harmless because state is idempotent. `setSubscriptions` goes to the current transport and also to a pending one.

### 3.3 `AthenaGatewayApi` (adapter over the SDK)

`GatewayTransport` depends on a small `GatewayApi` seam: `stream(topics): Flow<GatewayEvent>` (`Connected`, `Message`) and `publish(...)`. `AthenaGatewayApi` implements it with `AthenaGatewayClient`:

- **Construction.** `AthenaGatewayClient(url, key as hex, okHttpClient)`, built from the app's shared OkHttp client with a 10 s `callTimeout` for publishes. The SDK clears that timeout for streams and gives them a 45 s read timeout. That read timeout is the dead-stream rule, because keepalives count as bytes. `Broker(host = brokerHost, username, password)`, never `port`. A URL that is not `https://` is rejected.
- **Streams.** `subscribe(*topics.sorted(), qos = 1, broker)` **without** the SDK's `reconnect`: `GatewayTransport` owns reconnecting, so it can hand over make-before-break. `MqttEvent.Connected` and `MqttEvent.Message` map to our events. Payloads are re-encoded as described in §2, and other or newer event types are ignored. The flow completes when the gateway sends `disconnected`.
- **Errors**
  - `Unauthorized` → `GatewayRefused(KeyRejected)`.
  - `Rejected("Request expired")` → `ClockSkew`; any other `Rejected` or a `BadRequest` → `Forbidden(reason)`.
  - A `StreamError` saying `Connection failed with code 4|5` → `BrokerRejected` (`Offline(REJECTED)`).
  - Everything else (`Transport`, `GatewayError` including an undecryptable frame, other `StreamError`s) → `IOException`, which means retry.
- **Publish.** `publish(topic, payload.decodeToString(), qos, retain, broker)`. A `GatewayException` becomes `PublishFailed` with short, secret-free text: the offline reason for a rejected key or a clock refusal, and the gateway's own error text otherwise.
- **Accepted differences from the parent spec.** The SDK does not check the timestamps of responses; the gateway's own "Request expired" check covers a skewed clock. A single undecryptable frame ends the stream, and the reconnect backoff then applies. There is no count of 5 bad frames in a row.

### 3.4 `GatewayTransport`

It is a `ManagedTransport`. Each SSE stream is a `GatewayStream`, a small `ManagedTransport` with a fixed topic set, and a `Handover` switches between them.

- **Start:** it waits for a non-empty subscription set (the gateway refuses an empty `topics` list), then opens a stream. `link` is `Connecting` until the `connected` event, then `Connected(GATEWAY)`.
- **Subscription change:** debounced by **300 ms**, then a new stream for the new set is opened make-before-break through the `Handover`.
- **Stream end:** EOF, a `disconnected` event, or any retryable error (a stream error, an undecryptable frame, the 45 s read timeout). The transport then shows `Reconnecting(attempt)` and reopens after the backoff delay. Backoff is 1 s ×2 up to 30 s with ±20 % jitter. `Reconnecting` clears as soon as the new stream reaches `connected`. The attempt count resets only after a stream has stayed connected for **30 s**, so a gateway that connects and then drops at once is not hit every second.
- **Refusal:** it shows `Offline(<reason>)` and stops.
- **Publish:** it goes through `GatewayApi.publish`. When `link` is not `Connected` it throws `PublishFailed("not connected")` at once.

### 3.5 `TransportSelector`

It is a `ManagedTransport` built for a `Routed` spec. `ActiveTransport` does not change: it still owns the foreground lifecycle and break-before-make on profile changes, and a route change is such a change.

- **`Route.DIRECT` / `Route.GATEWAY`:** it runs just that transport.
- **`Route.AUTO`** evaluates at start and whenever one of these happens:
  - the network changes (debounced 1 s);
  - the direct transport shows `Reconnecting` (debounced 2 s);
  - **every 60 s** while on the gateway, because a Wi-Fi join is not always reported as a new default network.

  Each evaluation probes the local broker with a TCP connect to `host:port` that times out after **1.5 s**. The target is Direct if the probe succeeds and Direct has not been rejected, and Gateway otherwise. If the target differs from the current mode, it calls `handover.switchTo(new)`.
- **Direct rejected:** if the direct transport goes `Offline(REJECTED)`, it switches to the gateway (parent spec §7). It does not try direct again until the next network change.
- `link` mirrors the current transport; before the first one exists it is `Connecting`.

Network changes arrive as a `Flow<Unit>` that `:app` supplies from `ConnectivityManager.registerDefaultNetworkCallback` (`onAvailable`, `onLost`). This needs `ACCESS_NETWORK_STATE`, but it reads no SSID and needs no location permission.

---

## 4. Storage (`:core:data`)

- `StoredProfile.route: StoredRoute = AUTO`.
- `ProfileRepository.route: StateFlow<RoutePreference>` and `setRoute(RoutePreference)` change only that field.
- `clear()` resets the route to `AUTO`.
- The route is **not** part of `ConnectionProfile`, so changing it never rebuilds the discovery source.
- The entity cache key (`host:port:prefix`) does not depend on the route: both routes show the same house.

---

## 5. App and UI

- **Specs:** `toSpec` produces `Routed(direct, Gateway(url, key, brokerHost, local.username, local.password), route)` when the profile has a gateway. The factory builds `TransportSelector`, `GatewayTransport` and `DirectMqttTransport`.
- **Link sheet:** a single-choice row, "Connection": **Automatic** · **Home network** · **Gateway** (FR: **Automatique** · **Réseau local** · **Passerelle**). It is shown only when the profile has a gateway.
- **New offline texts** (EN/FR) for the three new reasons. The clock text suggests automatic date and time.
- **Onboarding:** when the direct test fails as *unreachable* and the draft has a gateway, the tester tries the gateway the same way (a temporary `GatewayTransport` and discovery source). `TestState.Connected` gains `viaGateway`, and the screen then adds "Connected through the gateway". There are new failures: `KeyRejected` and `ClockSkew`.
- **Network security config** (parent spec §8): a static `network_security_config.xml` with `cleartextTrafficPermitted="false"` and system trust anchors only. Android cannot generate it per profile at runtime. It covers HTTP (the gateway, which is also forced to `https://`). The MQTT TCP socket is not subject to it, and plain TCP to the LAN broker stays allowed with the existing warning (direct spec §4.5). *This amends parent spec §8.*

---

## 6. Error handling

| Situation | Behaviour |
|---|---|
| Probe fails, gateway configured | Gateway |
| Probe succeeds but direct is refused (credentials) | Gateway until the next network change |
| Gateway 401 | `Offline(KEY_REJECTED)`, "Scan the setup code again", no retry |
| Gateway 403 expired | `Offline(CLOCK_SKEW)`, "turn on automatic date and time", no retry |
| Gateway other 403 | `Offline(GATEWAY_REFUSED)`, no retry |
| No keepalive or data for 45 s, EOF, `disconnected`, a stream error, an undecryptable frame | `Reconnecting(n)`, backoff 1 s → 30 s ±20 % |
| Publish through the gateway fails or times out (10 s) | `PublishFailed` → the existing "Not confirmed — Retry" |
| New stream or transport does not connect within 15 s during a switch | Stay on the current one; the next trigger tries again |

A route's failure never clears the discovered house: the entity cache and `Handover` keep the cards, and only their values go stale per the existing rules.

---

## 7. Testing

| Target | Tests |
|---|---|
| Payload re-encoding, refusal reasons, error text | Pure unit tests: string, number, object, `null` and `""` payloads; each refusal's offline reason; an error body as an object or a JSON string |
| `AthenaGatewayApi` | The real SDK against MockWebServer, with replies sealed by `EnvelopeCrypto`: the request decrypts to the expected fields; events and payloads; `disconnected` ends the stream; 401; 403 expired / forbidden; broker login refused; other errors, bad frames and 502 are retryable; publish ok and its failure texts; https required |
| `Backoff` | Schedule and jitter bounds, with a seeded random |
| `Handover` | Fakes with virtual time: immediate flip with no current; wait-for-connected flip; no message lost or emitted from the old after the flip; timeout keeps the current and closes the candidate |
| `GatewayTransport` | A fake `GatewayApi`: connected → link; a burst of subscription changes opens one stream; subscription change reopens make-before-break; stream end → `Reconnecting` with backoff; the attempt count survives a stream that drops at once; refusal → `Offline`, no retry; publish |
| `TransportSelector` | Fakes and a fake probe: each route; the auto probe choice; a network change switches; re-probe every 60 s while on the gateway; direct `REJECTED` → gateway |
| `ProfileRepository` | Route round trip; `clear()` → `AUTO`; a route change leaves `state` equal |
| `:app` | `toSpec` for each profile shape; the tester's gateway fallback with a fake gateway |
| Home | Link sheet route row shown only with a gateway; the new offline texts |
