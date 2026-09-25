# Oikos — design spec

- **Date:** 2026-09-24
- **Author:** Gekkotron
- **Status:** Draft, awaiting review
- **License (app + bridge):** Apache-2.0

Oikos is a new Android home-automation app (Kotlin + Jetpack Compose). It replaces DomoticApp. It talks MQTT directly instead of polling the Jeedom HTTP API, and it discovers devices by itself using the Home Assistant MQTT Discovery convention. It starts as a private repo and should eventually become public and usable by Home Assistant users.

---

## 1. Goals and non-goals

### Goals (v1, the "showcase")

1. **Beautiful by default.**
   - Two themes built from one token system:
     - **Night Glass** (dark, the default)
     - **Warm Minimal** (light, the alternative)
   - Theme selection follows the system unless the user overrides it.
2. **Live, not polled.** All state arrives over MQTT subscriptions. Commands are MQTT publishes.
3. **Self-discovering.** Devices appear because they announce themselves:
   - Discovery messages are read from `homeassistant/…/config`.
   - A thin override layer (`oikos/ui/…`) lets the user rename, hide, re-room, reorder and restyle devices without touching the publishers.
4. **Works everywhere.**
   - **At home:** direct MQTT to the local broker.
   - **Away:** MQTT through the encrypted Athena-HttpMqttGateway (SSE + publish).
   - Selection is automatic, with a visible status pill and a manual override.
5. **Three screens:** Home/rooms, Energy (live power flow), Security (alarm, sensors, camera alerts via push).
6. **Push notifications** (personal build). FCM tokens are registered over MQTT, so the app needs neither Firestore nor Google sign-in.
7. **Nothing house-specific in the app.**
   - No hard-coded IPs, keys or device lists.
   - Setup is by QR code or manual entry.
   - UI strings are in English and French from day one.

### Non-goals (v1), planned for later phases

- History and charts (InfluxDB): see §11, phase 2.
- A device-detail screen, widgets, geofencing/presence, the voice assistant, Jeedom scenarios.
- Home Assistant WebSocket as an entity source. The interface exists in v1, but the implementation does not.
- UnifiedPush/ntfy or a public push relay; the public release itself.
- Background MQTT connection. The app is connected only while in the foreground; push covers the background.

---

## 2. Context: the system today

| Piece | Today |
|---|---|
| Broker | Mosquitto, LAN `192.168.1.91:1883`, plain TCP |
| Zigbee | zigbee2mqtt → `zigbee2mqtt/rooms/<room>/<device>` (one JSON per device) |
| Jeedom | publishes `jeedom/data/<object>/<eq>` and accepts commands on `…/set` |
| Custom services | philipsMqtt (`philips/…`), RoborockMqtt (`roborock/…`), Suez2Mqtt (`water/…`), WaterHeaterController (`water-heater/…`), Mqtt2FCMPush (`fcm-push/…`) |
| History | Telegraf `mqtt_consumer` → InfluxDB 2 (Flux). `_measurement` = topic namespace, tag `device` = topic suffix, tag `sensor_type` = JSON key, `_field` = `value` \| `value_str` (documented in `Athena/Athena-Hestia/config/zones.yaml`) |
| Remote access | [Athena-HttpMqttGateway](https://github.com/Gekkotron/Athena-HttpMqttGateway): AES-256-GCM envelope, multiple keys each scoped by port and destination, 60 s timestamp window, `/gateway`, `/mqtt/publish`, `/mqtt/subscribe` (SSE), exposed via Tailscale Funnel |
| Current app | DomoticApp: Jeedom JSON-RPC polling, device rendering keyed on Jeedom tags and command names (fragile), mixed Views and Compose, secrets in source |

---

## 3. Sub-projects

| # | Sub-project | Repo | Owner | Oikos v1 depends on it? |
|---|---|---|---|---|
| A | Gateway fixes | `Gekkotron/Athena-HttpMqttGateway` | user (in progress) | **yes**, for remote mode |
| B | `oikos-bridge` discovery publisher | **new repo** `Gekkotron/oikos-bridge` | this plan | **yes**, for Jeedom and custom devices |
| C | Mqtt2FCMPush accepts token registration over MQTT (its own `fcm-push/tokens/…` contract, client-agnostic) | `Mqtt2FCMPush` | this plan | yes, for push |
| D | zigbee2mqtt HA discovery | configuration change only | user | yes, for Zigbee devices |
| E | Oikos app | **new private repo** `Gekkotron/Oikos` | this plan | — |

Oikos can be developed against the local broker (direct mode) and the e2e fixture (§10) before A lands.

### 3.A Gateway: requirements Oikos relies on

These are not designed here; they are the contract the app expects.

1. `/mqtt/subscribe` works on master. Today `server/app.py:93` calls `secret.allows(...)`, which no longer exists; it should call `allows_port`.
2. `/mqtt/subscribe` rejects requests whose `timestamp` falls outside `MAX_AGE_SECONDS`, like the other endpoints.
3. **Multi-topic subscribe.** The request accepts `topics: [string]`; the legacy `topic` is still accepted. Every message event carries its actual `topic`.
4. (Recommended) A nonce cache for the `MAX_AGE_SECONDS` window, so a captured request cannot be replayed inside the window.
5. **Stream behaviour.** A keepalive comment at least every 15 s. Retained messages are delivered on (re)subscribe, which is already the behaviour.

Oikos uses **one key per phone**:
- Created with `add_secret.py`.
- Scoped to the MQTT port and the broker's IP.
- Revoked by deleting its line.

### 3.B `oikos-bridge` (new repo, Python 3.11, Docker)

**Purpose.** Publish HA-discovery configs for everything that does not announce itself: Jeedom devices and the custom services. The app then never needs device-specific knowledge.

**Configuration.** A single `devices.yaml`, for example:

```yaml
discovery_prefix: homeassistant
bridge_id: oikos-bridge
devices:
  - id: daikin_salon
    name: Clim salon
    area: Salon
    manufacturer: Daikin
    entities:
      - component: climate
        object_id: daikin_salon
        current_temperature_topic: jeedom/data/rooms/living/climatisation
        current_temperature_template: "{{ value_json['température_int.'] }}"
        temperature_state_topic: jeedom/data/rooms/living/climatisation
        temperature_state_template: "{{ value_json.consigne }}"
        temperature_command_topic: jeedom/data/rooms/living/climatisation/set
        modes: [off, cool, heat, auto, fan_only]
  - id: water_heater
    name: Chauffe-eau
    area: Garage
    entities:
      - component: sensor
        object_id: water_heater_temp_0
        device_class: temperature
        unit_of_measurement: "°C"
        state_topic: water-heater/data
        value_template: "{{ value_json.temp_0 }}"
      - component: sensor
        object_id: water_heater_power
        device_class: power
        unit_of_measurement: W
        state_topic: water-heater/data
        value_template: "{{ value_json.power }}"
```

**Behaviour.**
1. **Publishing configs.**
   - Each entity is published as a retained message to `<prefix>/<component>/<bridge_id>/<object_id>/config`.
   - Topic: HA per-entity discovery.
   - The payload uses full HA key names, plus:
     - `unique_id = <device.id>_<object_id>`
     - a `device` block with `identifiers`, `name`, `manufacturer`, `model` and `suggested_area`
     - `availability_topic = oikos-bridge/status`, only when the entity has no availability of its own
2. **Availability.** The bridge publishes `oikos-bridge/status`: retained `online`, with Last Will `offline`.
3. **Removing entities.** On start, the bridge reads its own previously published configs (by subscribing to `<prefix>/+/<bridge_id>/+/config`). Any config no longer in the YAML gets an empty retained payload.
4. **Republishing.** All configs are republished on broker reconnect and on `homeassistant/status` = `online`.
5. **Validation.**
   - On start, the YAML is validated against a schema: unknown components or missing topics are fatal and reported.
   - `--dry-run` prints the payloads without publishing.

**Out of scope for the bridge.** It does not translate payloads. Values are extracted by templates on the consumer side, which is the standard HA model. The exact `jeedom/data/…` payload shapes are captured with `mosquitto_sub` while writing `devices.yaml`.

### 3.C Mqtt2FCMPush: token registration over MQTT

This is a **Mqtt2FCMPush-owned contract**. Mqtt2FCMPush knows nothing about Oikos: any client (Oikos, a future app, a script) registers the same way. It sits next to the service's existing topics (`fcm-push/notification/#`, `fcm-push/heartbeat`, `fcm-push/notification/responses`).

**Topic.**
- `fcm-push/tokens/<clientId>`, **retained**.
- Configured by env `TOKEN_TOPIC`, default `fcm-push/tokens/+`, in `config.py` like `MQTT_TOPIC` and `HEARTBEAT_TOPIC`.
- `<clientId>` is chosen by the client and must be stable per install; a random UUID is recommended.

**Payload:**
```json
{ "token": "<fcm token>", "device_name": "Pixel 9", "lang": "fr", "updated_at": 1790241732 }
```
- Only `token` is required.
- Unknown fields are ignored, so clients may add their own.
- An empty retained payload means unregister.

**Mqtt2FCMPush behaviour.**
1. It subscribes to `TOKEN_TOPIC` and keeps an in-memory `clientId → token` map, which is rebuilt on connect from the retained messages. Invalid JSON or a missing `token` is logged and ignored.
2. When a notification has no explicit `tokens`, the targets are the Firestore tokens (legacy, unchanged, still gated by `ADMIN_ONLY`) **plus** all MQTT-registered tokens, de-duplicated.
3. If FCM reports a token invalid (`UNREGISTERED` / `INVALID_ARGUMENT`), it publishes an empty retained payload to `fcm-push/tokens/<clientId>` to clear it.
4. The notification payload and action replies are unchanged.
5. The README documents the contract.

Mosquitto ACLs are optional. When the broker has auth, only trusted clients should be able to write `fcm-push/tokens/#`.

A small inconsistency to fix while there: the `mqtt_handler.py` fallback subscribes to `fcm-push/notifications/#` (plural), while `config.py` defaults to `fcm-push/notification/#`.

**Oikos side** (see §4.8).
- The registration topic template is a user setting, default `fcm-push/tokens/{installId}`, and can be set in the QR payload (`push.token_topic`).
- Oikos is therefore not hard-wired to Mqtt2FCMPush's namespace either; it only speaks the documented contract.

### 3.D zigbee2mqtt

- Set `homeassistant: { enabled: true }`, keeping the default discovery prefix `homeassistant`.
- `friendly_name` values stay `rooms/<room>/<device>`. Any device name with three or more `/` segments gives a suggested room (the second-to-last segment) and a display name (the last segment). This applies only when the device sends no `suggested_area`, so HA's own field still wins. Shorter names are left as they are.

---

## 4. Oikos app: architecture

### 4.1 Modules

```
:app                    — entry, navigation (Navigation 3), Hilt graph, flavors
:core:designsystem      — tokens (color/type/shape/motion) for Night Glass + Warm Minimal, reusable cards, pills, sheets
:core:model             — domain: Device, Entity, EntityState, Command, Area, UiOverride, EnergyRole
:core:crypto            — AES-256-GCM envelope (nonce‖ct‖tag, base64, JSON + timestamp)
:core:transport         — MqttTransport interface, DirectMqttTransport, GatewayTransport, TransportSelector
:core:source            — EntitySource interface; MqttDiscoverySource (v1). HomeAssistantSource later.
:core:state             — StateStore, template evaluator, optimistic command tracker
:core:data              — DataStore settings, Keystore-backed secret store, entity cache
:core:push              — PushProvider interface; FcmPushProvider (flavor `personal`); NoopPushProvider (flavor `public`)
:feature:onboarding
:feature:home
:feature:energy
:feature:security
:feature:settings
```

**Dependency rule.**
- `feature → core:model, core:designsystem, core:source/state` only, never `core:transport` directly.
- `core:source → core:transport`.
- No module depends on a feature module.

### 4.2 Domain model (`:core:model`)

```kotlin
data class Device(val id: String, val name: String, val manufacturer: String?, val model: String?, val suggestedArea: String?)

enum class Kind { LIGHT, SWITCH, COVER, CLIMATE, SENSOR, BINARY_SENSOR, ALARM_PANEL, NUMBER, SELECT, BUTTON }

data class Entity(
    val uniqueId: String,
    val deviceId: String?,
    val name: String,
    val kind: Kind,
    val deviceClass: String?,
    val unit: String?,
    val icon: String?,               // "mdi:…" string, mapped to our icon set with a per-kind fallback
    val capabilities: Capabilities,  // kind-specific: brightness, color, position, modes, min/max/step, options…
    val bindings: Bindings,          // source-specific; opaque to features (topics + templates for MQTT)
)

data class EntityState(val uniqueId: String, val value: StateValue, val attributes: Map<String, Any?>, val available: Boolean, val lastUpdate: Instant, val pending: Boolean)

sealed interface Command { /* TurnOn(brightness?, color?), TurnOff, Toggle, SetPosition(0..100), Open, Close, Stop,
                             SetTemperature(t), SetHvacMode(m), SetNumber(v), SelectOption(o), Press, Arm(mode, code?), Disarm(code?) */ }

data class UiOverride(val name: String?, val area: String?, val hidden: Boolean = false, val order: Int? = null,
                      val cardStyle: String? = null, val favorite: Boolean = false, val energyRole: EnergyRole? = null)

enum class EnergyRole { SOLAR, GRID, HOUSE, WATER_HEATER, WATER }
```

`Bindings` is the only source-specific part. A future `HomeAssistantSource` produces the same `Entity` and `Command` types with its own bindings.

### 4.3 `EntitySource` (`:core:source`)

```kotlin
interface EntitySource {
    val entities: StateFlow<Map<String, Entity>>        // after overrides are applied
    val devices: StateFlow<Map<String, Device>>
    val areas: StateFlow<List<Area>>
    fun states(): Flow<Map<String, EntityState>>
    suspend fun send(uniqueId: String, command: Command): CommandResult
    suspend fun setOverride(uniqueId: String, override: UiOverride)
}
```

**`MqttDiscoverySource` (v1)**

1. **Subscribes** to `<prefix>/#` (default prefix `homeassistant`, configurable) and `oikos/ui/#`.
2. **Accepted discovery topics:**
   - Per-entity: `<prefix>/<component>/[<node_id>/]<object_id>/config`
   - Device-based: `<prefix>/device/<object_id>/config` with a `cmps` map
3. **Parsing rules:**
   - Expand HA **abbreviations** (`stat_t`, `cmd_t`, `dev`, `avty_t`, …), using the table from the HA docs, embedded as a resource.
   - Expand the `~` base topic.
   - An empty payload removes the entity.
   - Unsupported components or invalid payloads: skip the entity, log it with its topic, and leave the rest unaffected.
4. **Overrides:**
   - `oikos/ui/<unique_id>` holds a retained JSON `UiOverride`, and `oikos/ui/_areas` holds a retained `{ "areas": [{ "id", "name", "icon", "order" }] }`.
   - Oikos is the writer, so overrides sync between phones.
   - The effective area is `override.area ?: device.suggestedArea ?: device.pathArea ?: "Unassigned"`, where `pathArea` comes from a hierarchical device name (§3.D). A declared area matches a raw area by id, or by name ignoring case.
5. **State subscription.** Computes the distinct set of state, availability and attribute topics of all entities and asks the transport for exactly that set. The set is recomputed (debounced 500 ms) when entities change.

### 4.4 State and templates (`:core:state`)

- **`StateStore`** maps each incoming `(topic, payload)` to every entity bound to that topic. Many entities share one zigbee2mqtt JSON topic.
- **Template evaluator.** A deliberately small subset of Jinja:
  - `{{ value }}`
  - `{{ value_json.a.b }}`, `{{ value_json['key with spaces'] }}` and mixed forms
  - Filters `| int`, `| float`, `| round(n)`, `| lower`, `| upper`, `| default(x)`, `| default(x, true)`, `| string`, `| truncate(n, killwords, end, leeway)`
  - `{% if <expr> %}…{% elif <expr> %}…{% else %}…{% endif %}`, nestable, using Python truthiness (zigbee2mqtt boolean switches)
  - Conditions may also use `<string literal> in <expr>` (JSON key membership), `and` (short-circuit), and `==` (string/number equality)

  Anything else counts as *unsupported*. The entity's state then shows as `—`, its card is marked "template unsupported" in the log screen, and nothing crashes.
- **Payload semantics** follow HA per component. Examples: light and switch use `payload_on`/`payload_off` and `state_value_template`; cover uses `position_topic`, `position_open`/`position_closed` and `set_position_topic`; binary sensor uses `payload_on`/`payload_off`; the alarm panel uses the standard states. The per-kind mapping lives in one file per kind, `kinds/*.kt`.
- **Optimistic commands.**
  - `send()` publishes, then marks the entity state `pending = true` with the predicted value.
  - If a state message confirming it arrives within **5 s**, `pending` is cleared.
  - Otherwise the entity reverts to the last confirmed value and a non-blocking error is emitted, shown as a snackbar with "Retry".

### 4.5 Transport (`:core:transport`)

```kotlin
interface MqttTransport {
    val link: StateFlow<LinkState>                       // Connecting, Connected(mode), Reconnecting(attempt), Offline(reason)
    fun messages(): SharedFlow<MqttMessage>              // topic, payload bytes, retained
    suspend fun setSubscriptions(filters: Set<String>)   // declarative; transport diffs and (re)subscribes
    suspend fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false)
}
```

**`DirectMqttTransport`**
- HiveMQ MQTT client (MQTT 3.1.1; 5 when the broker supports it).
- Connects over TCP, TLS or WebSocket (WS or WSS), with optional username and password.
- Clean session, and resubscribe on reconnect.

**`GatewayTransport`**
- **Subscribe:** OkHttp SSE with a `POST {gatewayUrl}/mqtt/subscribe`. The body is the encrypted `{topics, qos, timestamp, broker_host?, broker_port?, username?, password?}`. Each `data:` line is decrypted into an event.
- **Changing subscriptions:** the stream is re-opened (make-before-break). The gateway has no "add topic" call.
- **Dead stream:** declared when no bytes, including keepalives, arrive for **40 s**.
- **Publish:** `POST {gatewayUrl}/mqtt/publish` with an encrypted body. A non-2xx status or an encrypted error body becomes `PublishFailed`.

**`TransportSelector`** (implements `MqttTransport` for everyone else)

1. **Probe the local broker.** A `ConnectivityManager.NetworkCallback` watches the network. On any network change, the selector tries a TCP connect to the configured local broker, with a **1.5 s** timeout. No SSID is read, so no location permission is needed.
2. **Choose the mode.** If the probe succeeds, use Direct; otherwise use Gateway (if configured); otherwise go Offline.
3. **Switch make-before-break.** Connect the new transport and apply the same subscriptions. Once it is `Connected`, switch the message source and then close the old transport. Retained messages refresh state immediately.
4. **Manual override.** Settings offers Auto / Direct only / Gateway only.
5. **Lifecycle.** Connect while the process is in the foreground (`ProcessLifecycleOwner`); disconnect **30 s** after going to the background.
6. **Reconnect.** Exponential backoff from 1 s to 30 s, with ±20 % jitter.

### 4.6 Crypto (`:core:crypto`)

- `AES/GCM/NoPadding` with a 12-byte random nonce and a 128-bit tag.
- Output is `base64(nonce ‖ ciphertext ‖ tag)`; the plaintext is UTF-8 JSON that always contains `timestamp` (epoch seconds).
- Responses and events are decrypted the same way. For responses, a `timestamp` outside ±`maxAge` (default 60 s, configurable) is rejected.
- The key comes from `SecretStore` and is never logged.

### 4.7 Persistence (`:core:data`)

- **DataStore (Proto):**
  - connection profile: local broker, gateway URL, discovery prefix, mode override
  - theme and language
  - the last known entities, devices and overrides, for an instant cold start (UI shows cached structure with states marked stale until live data arrives)
- **`SecretStore`:**
  - holds the gateway AES key and the broker password
  - these are encrypted with an AES key held in the Android Keystore (non-exportable)
  - the ciphertext is stored in DataStore

### 4.8 Push (`:core:push`)

- `PushProvider { suspend fun register(): PushRegistration?; val incoming: Flow<PushMessage> }`.
- **`FcmPushProvider`** (flavor `personal`, which includes `google-services.json` and is not committed):
  - Registers by publishing `{token, device_name, lang, updated_at}` (retained) to the configured token topic, default `fcm-push/tokens/{installId}` (§3.C). It republishes on token refresh or when the retained payload differs, and publishes an empty retained payload on reset from Settings.
  - `installId` is a random UUID stored in DataStore.
- **`FirebaseMessagingService`** handles notifications:
  - Handles the data-only messages from Mqtt2FCMPush: title, body, `channelId`, `image_url`, `video_url`, actions.
  - Creates channels: security, alarm, cameras, energy, misc.
  - Stores the last 200 notifications in Room for the Security screen.
  - Action buttons publish to `fcm-push/notification/responses` through `WorkManager` with network-constrained retries (a pattern ported from DomoticApp's `SendCommandWorker`). While the app is in the background, a one-shot transport is used.
- Flavor **`public`**: `NoopPushProvider` for now; UnifiedPush comes in phase 4.

---

## 5. UI

### 5.1 Design system (`:core:designsystem`)

- **Night Glass (dark, default):**
  - Background: a radial gradient from `#1D2B55` to `#0B1020`.
  - Cards: translucent (white at 6 % opacity with a 9 % border). On-state cards get an amber gradient.
  - Accents: mint `#6FF0BF` (connected), amber `#FFC457` (solar/on), cyan `#58E1FF` (water), violet `#8F7BFF` (grid), rose `#FF7A9A` (heat).
- **Warm Minimal (light):**
  - Paper background `#F5F1EA`, with cards in `#FFFDF9` and soft warm shadows.
  - The on-state card is inverted (`#1F1B16` background with paper-coloured text).
  - Titles use a serif display face (for example Fraunces) and body text a sans (for example Inter), both bundled.
- **Shared tokens:** radius 20 dp for cards and 24 dp for the nav bar, spacing on a 4 dp grid, motion 200–350 ms with emphasized easing, 48 dp minimum touch targets.
- **Components:** `EntityCard`, one variant per kind; `StatusPill` (Wi-Fi direct / Passerelle / Reconnexion… / Hors ligne); `RoomChips`; `ControlSheet`; `GlassNavBar`; `PendingDot`.
- **Mockups:** the approved ones are saved in `docs/design/mockups/` (`visual-style.html` option A/B, `energy-layout.html` option A). They are reproduced as Compose `@Preview`s and screenshot tests.

### 5.2 Screens

- **Onboarding**
  1. Welcome.
  2. Scan the QR code, or enter the settings manually. The QR payload is JSON:
     ```json
     { "v":1, "local":{ "host":"…","port":1883,"tls":false,"ws":false,"user":"…","pass":"…" },
       "gateway":{ "url":"https://…","key":"<hex>","broker_host":"…" }, "prefix":"homeassistant",
       "push":{ "token_topic":"fcm-push/tokens/{installId}" } }
     ```
  3. Connection test.
  4. A "Discovering…" live list with a device count.
  5. Room assignment, pre-filled from `suggested_area`, which can be skipped.
- **Home**
  - Greeting and the outdoor temperature (an entity with the override `cardStyle: "hero_weather"`), plus the `StatusPill`.
  - `RoomChips` (All, Favourites, then areas), then a 2-column grid of cards. Wide cards are used for power and energy sensors.
  - **Tap:** toggle (light, switch) or open the `ControlSheet` (brightness and colour, cover position with open/stop/close, climate setpoint and modes, number, select).
  - **Long-press:** override sheet with rename, area, hide, favourite, order, card style and energy role.
- **Energy.** An animated flow diagram built from the entities with an `energyRole`: solar at the top, house in the centre, grid on the left, water heater on the right, water at the bottom left.
  - Line speed and thickness are proportional to power, and direction follows the sign (grid import vs export).
  - Missing roles hide their node.
  - Below the diagram: detail cards (water heater temperature and power, grid import/export).
- **Security**
  - **Hero:** the `alarm_control_panel` control (Disarmed / Home / Away / Night per `supported_features`), with a code pad if `code_arm_required`.
  - **Sensors:** a list of door, window, motion, smoke and moisture `binary_sensor`s, active or open ones first.
  - **Recent alerts:** push notifications from Room with an image thumbnail and ExoPlayer (Media3) playback of `video_url`.
- **Settings:** connection profile and mode override; theme (System / Night Glass / Warm Minimal); language; hidden entities; logs viewer; push registration status; about and licenses.

### 5.3 Accessibility and adaptivity

- Every card has a content description with name, state and room, and a state description for toggles.
- Contrast is at least 4.5:1 in both themes.
- Font scaling up to 200 % without clipping.
- Window-size classes: 2 columns (compact), 3–4 (medium/expanded), with a nav rail on tablets and foldables.

---

## 6. Data flow summary

```
broker ──(Direct TCP | Gateway SSE)──▶ TransportSelector.messages()
   │                                        │
   │                          ┌─────────────┴──────────────┐
   │                 <prefix>/# + oikos/ui/#          state topics
   │                          ▼                            ▼
   │                 MqttDiscoverySource ──entities──▶ StateStore ──states──▶ feature ViewModels ──▶ UI
   │                          ▲                            ▲
   └──◀── publish ◀── send(Command) → kind mapper → (topic, payload) ── optimistic pending/confirm
```

---

## 7. Error handling

| Situation | Behaviour |
|---|---|
| No network | `Offline`; cards dim after 10 s and show "last seen HH:MM" |
| Local probe fails and no gateway is configured | `Offline(reason = "local broker unreachable")` with a "Configure gateway" action |
| Gateway 401 (no key matched) | Blocking "Key not accepted" screen → Settings; no retry loop |
| Gateway 403 (port/destination/expired) | Show the decrypted reason. If it says expired, check the phone clock and suggest automatic time |
| Decrypting an event fails | Drop the event, count it; after 5 in a row, reconnect |
| Direct CONNACK refused (bad auth) | Fall back to the gateway if configured; log it |
| Invalid or unsupported discovery config | Skip that entity; log `topic + reason`; count shown in Settings → Logs |
| Unsupported template | State `—`; logged once per entity |
| Command not confirmed within 5 s | Revert; snackbar "Not confirmed — Retry" |
| Entity availability `offline` | Card greyed out with an "Unavailable" label; commands disabled |
| Push registration fails | Retry on the next connect; status visible in Settings |

The logs are an in-app ring buffer (the last 500 entries) with an export/share action. No remote logging in v1.

---

## 8. Security

- No secrets in the repository. `google-services.json`, signing configs and QR payloads are git-ignored. CI uses repository secrets.
- The gateway key is specific to each phone, is scoped on the gateway, and can be revoked there (§3.A).
- Secrets are protected with the Android Keystore (§4.7). Backups exclude the secret store (`dataExtractionRules`).
- The network security config allows cleartext only for hosts the user explicitly marks as local during onboarding. It is generated per profile and enforced in code for the MQTT TCP socket; HTTP cleartext stays off.
- Screenshots of the onboarding QR and key screens are blocked (`FLAG_SECURE`).
- MQTT credentials for gateway mode travel only inside the encrypted envelope.

---

## 9. Tech stack

- **Languages and build:** Kotlin 2.x (single version across the Kotlin and Compose compiler plugins), AGP 9.x, Gradle version catalog, convention plugins in `build-logic/`.
- **UI:** Compose BOM (latest stable), Material 3, Navigation 3.
- **Architecture:** Hilt, coroutines and Flow, kotlinx-serialization.
- **Networking:** HiveMQ MQTT client, OkHttp with `okhttp-sse`.
- **Storage:** DataStore (Proto), Room (notifications only).
- **Background and media:** WorkManager, Media3 ExoPlayer, Firebase Messaging (flavor `personal` only).
- **SDK levels:** minSdk 28, targetSdk and compileSdk 36, JVM 17.
- **Package:** `com.gekkotron.oikos`. It differs from DomoticApp, so both install side by side.
- **CI (GitHub Actions):** build, unit tests, screenshot tests and lint on each PR; debug APK artifact. Firebase App Distribution for the `personal` flavor later.
- **Coverage (same setup as DomoticApp):**
  - Kover is applied to every module through a convention plugin and merged into one root report (`./gradlew koverXmlReport`).
  - The report is uploaded with `codecov/codecov-action@v5` using the `CODECOV_TOKEN` secret, which is required while the repo is private.
  - `codecov.yml` sets project and patch status targets and ignores only Android-only entry points (Hilt modules, `Application`, `FirebaseMessagingService`) and generated code.
- **README badges** at the top of `Oikos/README.md`:
  ```markdown
  [![CI](https://github.com/Gekkotron/Oikos/actions/workflows/ci.yml/badge.svg)](https://github.com/Gekkotron/Oikos/actions/workflows/ci.yml)
  [![codecov](https://codecov.io/gh/Gekkotron/Oikos/graph/badge.svg?token=<CODECOV_BADGE_TOKEN>)](https://codecov.io/gh/Gekkotron/Oikos)
  [![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
  ```
  `<CODECOV_BADGE_TOKEN>` is the graph token from Codecov → repo settings → Badges. It is needed while the repo is private and is dropped once it is public.
- **`oikos-bridge`** gets the same treatment:
  - GitHub Actions runs `pytest --cov=oikos_bridge --cov-report=xml` and uploads to Codecov.
  - Its README carries CI, codecov and license badges with the same pattern (`Gekkotron/oikos-bridge`).

---

## 10. Testing

- **`:core:crypto`:** encrypt/decrypt round-trip, plus known vectors produced by the gateway's `test_client` Python code (fixed key, fixed nonce); wrong key; truncated input.
- **`:core:source`:**
  - Real fixtures: captured zigbee2mqtt configs (light, cover, contact, motion, temperature/humidity), device-based discovery with `cmps`, and an abbreviated Tasmota-style config.
  - `~` expansion, removal by empty payload, and override merge precedence.
- **`:core:state`:** the template subset (each supported form plus unsupported fallbacks); kind mappers for each Kind; optimistic confirm, revert and timeout, using a virtual clock.
- **`:core:transport`:**
  - `TransportSelector` with fake transports: probe success/fail, make-before-break switch without message loss, backoff schedule, background disconnect.
  - `GatewayTransport` against MockWebServer (SSE framing, keepalive timeout, encrypted error bodies).
- **UI:** Compose UI tests (onboarding QR parse → connection test → discovery list; card tap/long-press; control sheet), and screenshot tests for every card kind in both themes (Roborazzi).
- **End-to-end:**
  - `e2e/docker-compose.yml` runs Mosquitto, Athena-HttpMqttGateway and `fixture-publisher`, which publishes retained discovery and state for a fake house and echoes `…/set` commands back to the state topics.
  - An instrumented test runs the app against it in both Direct and Gateway modes.
  - CI runs it nightly.
- **`oikos-bridge`:** pytest for the YAML schema, payload generation (golden JSON), stale-config cleanup and republish on `homeassistant/status`.
- **Mqtt2FCMPush:** pytest for token map maintenance from `fcm-push/tokens/+` (register, update, empty = unregister, bad JSON ignored), de-duplicated merge with Firestore tokens, and invalid-token cleanup.

---

## 11. Roadmap

| Phase | Content |
|---|---|
| **1 — Showcase (this spec)** | Sub-projects A–E; Home, Energy, Security, Onboarding, Settings; both themes; personal push |
| 2 — History and detail | Gateway `/history` read-only endpoint over InfluxDB 2 (Flux, Telegraf schema in §2; credentials server-side); device-detail screen with charts; sparklines on cards |
| 3 — Parity migration | Widgets (Glance), geofencing/presence, camera NVR browser, voice assistant, Jeedom scenarios; retire DomoticApp |
| 4 — Open to others | `HomeAssistantSource` (HA WebSocket `subscribe_entities`), UnifiedPush/ntfy `PushProvider`, public flavor, store listing, public repo |

---

## 12. Risks and open points

- **Jeedom payload shapes** on `jeedom/data/…` are not yet captured. `devices.yaml` authoring (sub-project B) starts with a `mosquitto_sub -v -t 'jeedom/data/#'` capture. If a payload cannot be handled by the template subset, the bridge may later gain an optional republish or translation mode (out of scope for v1).
- **Tailscale Funnel and long SSE streams** may be cut by intermediaries. The design already tolerates this through 40 s dead-stream detection and fast reconnect with retained state refresh.
- **Non-retained state topics** (for example `water-heater/data` every 4 s, `roborock/status`) show `—` until the first message arrives after (re)connect. This is acceptable because they publish often; `roborock/status` may need retained publishing in RoborockMqtt.
- **Energy roles** need a one-time manual assignment in overrides. There is no auto-detection in v1.
- **HA template compatibility** is intentionally partial (§4.4). zigbee2mqtt and our bridge stay within the subset. Unsupported templates degrade visibly rather than silently.
- **Name clash:** check "Oikos" for existing app-store names and trademarks before the public release (phase 4).
