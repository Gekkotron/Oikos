# Oikos — Push alerts: OikosPush service, notifications, alert history

Status: approved design, 2026-10-07. Parent spec: `2026-09-24-oikos-design.md` §3.C, §4.8 and §5.2 (Security, "Recent alerts"). It is the phase 1 sub-project split out of `2026-10-02-oikos-security-design.md`, and it fills that spec's Recent alerts placeholder and the Settings spec's deferred "push registration status".

**This spec amends the parent:**
- **§3.C is dropped.** Mqtt2FCMPush is not changed. It signs in to Firebase project `domoticapp-99583`, while Oikos lives in `oikos-b8268`. FCM tokens only work inside their own project, so Mqtt2FCMPush cannot reach Oikos. A new service, **OikosPush**, does that job with the Oikos project's service account.
- **§4.8:** the default token topic becomes `oikos-push/tokens/{installId}`, and replies go to `oikos-push/responses`. There are no build flavors: Firebase is already a hard dependency through Crashlytics.
- **Media does not use Firebase Storage.** Producers send LAN URLs. Oikos fetches them directly at home and through the gateway's `/gateway` raw download elsewhere (gateway **v1.3**, already released and deployed).

## 1. Goal

Oikos receives the house's push alerts (alarm, sensors, cameras, energy, climate, system), shows them as Android notifications with images and action buttons, and keeps a browsable history with image and video playback.

Decisions:
- **Alongside DomoticApp.** DomoticApp and Mqtt2FCMPush keep running unchanged. Oikos has its own topic namespace, and producers move to it one at a time when the user chooses.
- **Two payload formats.** A clean v1 format for new producers, and the legacy Mqtt2FCMPush format for producers moved as they are. OikosPush normalizes both, so the app parses a single shape.
- **7 channels:** `alarm`, `security`, `camera`, `energy`, `climate`, `system`, `misc`.
  - `alarm` is the alarm system itself: armed, disarmed, triggered, siren.
  - `security` is individual sensors outside the alarm: openings, motion, leaks, smoke.
- **Media stays in the house.** It is fetched over HTTP, never through MQTT.
- **Background publishes** (token registration, action replies) use the app's normal routes: direct MQTT when the broker answers, the gateway's `/mqtt/publish` otherwise. WorkManager retries them.
- **Catch-up buffer.** OikosPush keeps recent alerts in a retained topic. Alerts that FCM never delivered (no token yet, TTL expired, reinstall) still reach the history.

Out of scope:
- Read/unread state and badges.
- Migrating the producers (Jeedom, Frigate, etc.). That's the user's work, one producer at a time.
- UnifiedPush and the public flavor (parent phase 4).
- Any change to Mqtt2FCMPush, DomoticApp or `firebase-storage-upload-api`.
- Full-screen image zoom; streaming playback (clips are downloaded first).

## 2. Topics and payloads

### 2.1 Topics (prefix `oikos-push`, configurable on the service)

| Topic | Direction | Retained | Content |
|---|---|---|---|
| `oikos-push/notification/<source>` | producer → OikosPush | no | v1 or legacy payload (§2.2, §2.3) |
| `oikos-push/tokens/<installId>` | Oikos → OikosPush | **yes** | `{token, device_name, lang, updated_at}`; empty = unregister |
| `oikos-push/responses` | Oikos → producer | no | `{id, action, install_id, ts}` |
| `oikos-push/history` | OikosPush → Oikos | **yes** | JSON array of normalized alerts (§3.5) |
| `oikos-push/heartbeat` | OikosPush | no | as Mqtt2FCMPush |

`responses` sits outside `notification/#` on purpose. Mqtt2FCMPush hears its own `fcm-push/notification/responses` because of that overlap.

### 2.2 v1 payload

```json
{ "v": 1,
  "id": "frigate-front-1696",
  "channel": "camera",
  "severity": "critical",
  "title": "Front door", "body": "Person detected",
  "image_url": "http://192.168.1.20:5000/api/events/1696/snapshot.jpg",
  "video_url": "http://192.168.1.20:5000/api/events/1696/clip.mp4",
  "entity_id": "binary_sensor.front_door",
  "actions": [ { "id": "disarm", "label": "Disarm" } ],
  "silent": false, "ttl": 43200 }
```

- **Required:** `v`, `id`, `title`.
- `channel` is one of the 7; an unknown or missing value becomes `misc`.
- `severity` is `info | warning | critical` (default `info`).
- Producers should keep `actions` to 3 or fewer; the app shows at most 3.
- The same `id` replaces the earlier alert, in history and in the notification shade.
- **Cancel:** `{ "v": 1, "id": "…", "cancel": true }` dismisses the notification and marks the alert resolved.

### 2.3 Legacy payload (no `v`)

Mqtt2FCMPush's format: `{title, body, data:{id, channelId, image_url, video_url, actions, silent}, priority, ttl}`, where `actions` is a JSON string `[{"btn","action"}]`.

Mapping:

| Legacy | Normalized |
|---|---|
| `channelId` `alarme` / `nvr` / `pv` / `surveillance` / `Supervisor` / other | `alarm` / `camera` / `energy` / `climate` / `system` / `misc` |
| `data.id` (number or string) | `id` as a string; if missing, `<source>-<sha1(title+body)[:8]>` |
| `actions` `[{btn, action}]` | `[{label: btn, id: action}]` |
| empty or missing `title` | `cancel: true` |
| `priority: "high"` | `severity: "warning"`, otherwise `info` |
| other `data.*` keys | dropped |

### 2.4 Normalized FCM data map (OikosPush → Oikos)

FCM data values are strings: `v="1"`, `id`, `channel`, `severity`, `title`, `body`, `image_url`, `video_url`, `entity_id`, `actions` (a JSON array string `[{id,label}]`, `"[]"` when there are none), `silent` (`"true"/"false"`), `cancel`, `sent_at` (unix seconds), `source` (the `<source>` topic segment).
- Absent optional fields are left out.
- The map has to stay under FCM's 4 KB limit. If it doesn't, `body` is truncated with "…".

## 3. OikosPush service

### 3.1 Repo

`Gekkotron/OikosPush`: Python 3.12, Docker Compose, MIT licence, author Gekkotron. The layout mirrors Mqtt2FCMPush, without the Firestore and admin code:
- `config.py`, read from env:
  - `MQTT_BROKER`, `MQTT_PORT`, `MQTT_USERNAME`, `MQTT_PASSWORD`;
  - `TOPIC_PREFIX=oikos-push`;
  - `FIREBASE_CREDENTIALS`;
  - `RETRY_INTERVAL=60`, `MAX_RETRIES=10`;
  - `HISTORY_SIZE=100`, `HISTORY_DAYS=7`;
  - `HEARTBEAT_INTERVAL=60`.
- `mqtt_handler.py`: connect, reconnect with backoff, subscribe, heartbeat.
- `normalize.py`: **pure functions**. `normalize(topic, payload) -> Alert | Rejected`, and `to_fcm_data(alert) -> dict[str, str]`.
- `tokens.py`: the `installId → Registration` map.
- `fcm_sender.py`: multicast send, returns the failed tokens per error kind.
- `notification_queue.py`: the persistent retry queue, as in Mqtt2FCMPush.
- `history.py`: the catch-up buffer.
- `README.md`: documents §2 as the contract.

### 3.2 Tokens

- Subscribes to `oikos-push/tokens/+`. The map is rebuilt from the retained messages on each connect.
- Only `token` is required; unknown fields are ignored.
- An empty payload removes the entry.
- Bad JSON or a missing `token` is logged and ignored.
- When two installs share a token, it is sent to only once.

### 3.3 Sending

1. Normalize. A rejected payload (v1 with no `id` or no `title` and no `cancel`) is logged and dropped.
2. Add the alert to the history (§3.5), whether or not any token exists.
3. If there are no tokens: log it and stop. The alert is not queued, because history covers it.
4. Send a data-only `MulticastMessage`:
   - Android priority is `high` when severity is `warning` or `critical`, or the channel is `alarm`, `security` or `camera`; `normal` otherwise.
   - TTL is `ttl` (default 43200 s). A cancel is sent with a TTL of 300 s.
5. **Failures:**
   - `UNREGISTERED`, and `INVALID_ARGUMENT` only when it concerns the registration token: publish an empty retained payload to that install's token topic, which clears it on the broker and in the map.
   - `INVALID_ARGUMENT` about the message itself (too big, bad TTL): log it and drop the alert. No token is cleared and nothing is retried.
   - Any other failure: queue that message for those tokens; it is retried every `RETRY_INTERVAL`, up to `MAX_RETRIES` attempts or TTL expiry.

Alerts that arrive before the token map is synced after a (re)connect (the `tokens/+` subscription acknowledged plus a short settle delay) are queued, with their recipients resolved at send time, and sent once synced. The retry worker does not touch the queue until then.

### 3.4 Replies

The service does not touch `oikos-push/responses`. Producers subscribe to it themselves.

### 3.5 History (catch-up buffer)

- A list of normalized alerts, newest first.
- A new `id` is prepended; an existing `id` is replaced in place and moved to the front.
- A cancel sets `cancel: true` on the stored alert, or is stored on its own if the `id` isn't there.
- Trimmed to `HISTORY_SIZE` entries and to `HISTORY_DAYS` by `sent_at`.
- Persisted to `data/history.json`, which is loaded on start.
- After every change, the whole list is republished **retained** to `oikos-push/history`, with the same keys as §2.4 but typed JSON values (booleans, numbers, `actions` as an array). At most about 50 KB.

## 4. Oikos: modules

| Module | Contents |
|---|---|
| `:core:push` (new) | `OikosMessagingService`, `AlertChannels`, `AlertNotifier`, `PushRegistrar`, `RegisterWorker`, `ReplyReceiver`, `ReplyWorker`, `OneShotPublisher`, `PushStatus` |
| `:core:alerts` (new) | Room `AlertDatabase` / `AlertDao`, `AlertRepository`, `HistorySync`, `MediaFetcher`, Coil `AlertMediaFetcher`, `VideoCache` |
| `:feature:alerts` (new) | Alerts screen, detail sheet, Recent alerts section composable |
| `:feature:security` | hosts the Recent alerts section and the permission banner |
| `:feature:settings` | Push alerts row |
| `:feature:onboarding` | notification permission step |
| `:app` | manifest (service, receiver, deep link), Hilt wiring, `AlertsKey`, starting `HistorySync` with the live transport |

New dependencies (all through the version catalog): Firebase Messaging (BoM already present), Room + KSP, WorkManager (+ `work-testing`), Coil 3 (+ OkHttp network), and Media3 ExoPlayer/UI. Room is new to the project. It's used only for alerts, as the parent spec §8 planned.

### 4.1 Model

```kotlin
enum class AlertChannel { ALARM, SECURITY, CAMERA, ENERGY, CLIMATE, SYSTEM, MISC }
enum class Severity { INFO, WARNING, CRITICAL }
data class AlertAction(val id: String, val label: String)
data class Alert(
    val id: String, val channel: AlertChannel, val severity: Severity,
    val title: String, val body: String,
    val imageUrl: String?, val videoUrl: String?, val entityId: String?,
    val actions: List<AlertAction>, val source: String?,
    val silent: Boolean, val resolved: Boolean,
    val sentAt: Instant, val receivedAt: Instant,
)
```

A single `AlertParser.fromData(Map<String, String>)` / `fromHistory(JsonObject)` returns `Alert?`; null means malformed, in which case the alert is logged and dropped.

## 5. Oikos: receiving and notifications (`:core:push`)

### 5.1 Channels

`AlertChannels.ensure()` runs in `Application.onCreate`:
- Channel ids are `alarm` … `misc`; names and descriptions come from string resources (`values`, `values-fr`).
- Importance is HIGH for `alarm`, `security` and `camera`, DEFAULT for the rest.
- A single monochrome small icon; the accent colour depends on the channel.

### 5.2 `OikosMessagingService.onMessageReceived`

1. Parse with `AlertParser`. A null result is logged and dropped.
2. **Cancel:** `NotificationManagerCompat.cancel(tag = id, NOTIFICATION_ID)`, and `AlertRepository.markResolved(id)`.
3. **Otherwise:** `AlertRepository.upsert(alert)` (which trims to 200), then `AlertNotifier.post(alert)`:
   - tag `id`, so a later alert with the same `id` replaces the notification;
   - title, body as `BigTextStyle`, the channel, `setSilent(alert.silent)`;
   - **image:** if there's an `imageUrl`, `MediaFetcher.image(url)` runs with an **8 s** budget. On success: `BigPictureStyle` plus a large icon, and the bytes stay in Coil's disk cache under the same key. On failure or timeout the notification is text only;
   - **tap:** a `PendingIntent` to `MainActivity` with `oikos://alert/<id>`;
   - **actions:** the first 3 become buttons with a `PendingIntent` to `ReplyReceiver` (extras: alert id, action id, label).
4. If `POST_NOTIFICATIONS` isn't granted, step 3 still stores the alert; it just isn't posted.

`onNewToken(token)` enqueues `RegisterWorker`.

### 5.3 `OneShotPublisher`

`suspend fun publish(topic, payload, retain): Result<Unit>`:
1. If the app's `ActiveTransport` is currently connected, publish there.
2. Otherwise, by the stored profile's route:
   - **auto:** `TcpProbe(localHost, localPort).reachable()`. If it answers: build a `DirectMqttTransport`, start it, wait up to 10 s for `Connected`, publish (QoS 1), close. If not, or if direct fails: `AthenaGatewayApi.publish(...)`.
   - **direct-only / gateway-only:** only that route.
3. The `Demo` profile, or no profile: `Result.failure(NotConfigured)`.

It reuses `TcpProbe`, `DirectMqttTransport`, `AthenaGatewayApi` and `SecretStore`; no `TransportSelector` is started.

### 5.4 Registration (`PushRegistrar`, `RegisterWorker`)

- **Topic:** the profile's `pushTokenTopic` with `{installId}` substituted; the default is `oikos-push/tokens/{installId}`.
- **Payload:** `{"token", "device_name": Build.MODEL, "lang": app locale, "updated_at": unix s}`, retained, QoS 1.
- **Triggers:**
  - `onNewToken`;
  - the end of onboarding;
  - the live transport connecting, **only if** the token or the topic differs from the last successful registration stored in DataStore (`lastRegistration {token, topic, at}`);
  - "Register again" in Settings.
- **`RegisterWorker`:** unique work `register`, REPLACE policy, `NetworkType.CONNECTED`, exponential backoff starting at 30 s. It gets the token with `FirebaseMessaging.getToken()`, publishes through `OneShotPublisher`, and stores `lastRegistration` on success.
- **"Set up again":** before wiping the profile, publish an empty retained payload to the token topic (best effort, 5 s), then delete the FCM token (`FirebaseMessaging.deleteToken()`).

### 5.5 Replies (`ReplyReceiver`, `ReplyWorker`)

- **`ReplyReceiver`:**
  - dismisses the notification;
  - enqueues `ReplyWorker`: expedited (falling back to normal if over quota), `NetworkType.CONNECTED`, backoff 10 s;
  - input: alert id, action id, label.
- **`ReplyWorker`:** publishes `{"id", "action", "install_id", "ts"}` to `oikos-push/responses` (QoS 1, not retained) through `OneShotPublisher`.
  - On failure: `Result.retry()` while `runAttemptCount < 5`.
  - After that: post "Couldn't send *<label>*" on the `system` channel and return `Result.failure()`.
- The detail sheet's action buttons enqueue the same worker and observe its `WorkInfo` to show the *Sent* / *Couldn't send* snackbar.

### 5.6 `PushStatus`

A `StateFlow` combining the notification permission, `lastRegistration` and the `RegisterWorker` state:
- `NotificationsOff`
- `Registered(at)`
- `Waiting` (enqueued, or retrying without an error yet)
- `Failed(reason)` (last attempt's short, secret-free message)
- `Demo`

## 6. Oikos: history and media (`:core:alerts`)

### 6.1 Room

- Table `alerts`, mirroring `Alert`; `actions` is stored as JSON.
- Index on `(channel, receivedAt)`.
- DAO:
  - `upsert(alert)`, which keeps the original `receivedAt` on replace;
  - `markResolved(id)`;
  - `trim(keep = 200)`, deleting oldest by `sentAt`;
  - `recent(channels, limit)`, `all(channel?)` and `byId(id)` as `Flow`s;
  - `clear()`.
- `upsert` and `trim` run in a single transaction.
- Schema exported, version 1.

### 6.2 `HistorySync`

- Started by `:app` alongside the live transport: it adds `oikos-push/history` to the subscriptions while the app is connected.
- On each retained message:
  - parse the array;
  - for each alert, if `byId` is null, insert it, never posting a notification;
  - if it has `cancel: true`, `markResolved`;
  - then trim.
- It returns how many alerts were new; `:feature:security` shows "*n* alerts arrived while you were offline" once per sync when *n* > 0.
- Malformed entries are skipped one by one.

### 6.3 `MediaFetcher`

`suspend fun bytes(url, maxBytes): Result<Media(bytes, contentType)>`:
1. **LAN first** when the profile has a local broker and `TcpProbe` answers: an OkHttp GET with a 5 s connect and 15 s read timeout.
2. **Otherwise, or if the LAN attempt fails with a connection error:** `AthenaGatewayClient.download(url, timeoutSeconds = 30)`.
3. **Errors:** HTTP 404 or 410 becomes `Gone`; gateway "destination not allowed" becomes `NotAllowed` (logged once with the host, so the missing key rule is obvious); anything else becomes `Unavailable(reason)`.

- **Images:** a Coil `Fetcher` for the `AlertMedia(url)` request type (not raw strings, so other app images never go through the gateway) calls `MediaFetcher`, with Coil's disk cache in front. The notifier writes to the same cache.
- **Videos:** `VideoCache.file(alertId, url)` downloads to `cache/alerts/video/<id>.mp4` (LRU by access time, 200 MB cap), exposing progress, then hands the `File` to Media3 `ExoPlayer`. Videos are capped at 25 MB, the gateway's raw limit.
- "Set up again" clears both caches and the `alerts` table.

## 7. Oikos: UI

### 7.1 Security tab: Recent alerts

The section replaces the current placeholder, under the sensor sections:
- Section title "Recent alerts" with a "See all" text button (it opens `AlertsKey`).
- Up to 5 rows from the `alarm`, `security` and `camera` channels, newest first. Each row has:
  - a 56 dp rounded thumbnail when there's an `imageUrl`, otherwise the channel glyph;
  - the title, a one-line body and a relative time ("2 min ago");
  - a "Resolved" label when the alert is resolved.
- Empty state: "No alerts yet".
- When `PushStatus` is `NotificationsOff`, a slim banner above the rows reads "Notifications are off · Turn on". It asks for the permission, or opens the app's notification settings once the system no longer shows the prompt.
- Tapping a row opens the detail sheet.

### 7.2 Alerts screen (`AlertsKey`)

- Header with a back arrow, the title "Alerts", and an overflow menu with "Clear all" (confirm dialog: "Clear all alerts? This only clears this phone.").
- `RoomChips`-style filter chips: All, Alarm, Security, Camera, Energy, Climate, System, Misc.
- A `LazyColumn` with day headers (Today, Yesterday, then a localized date) and the same rows as §7.1.
- Empty state for each filter.

### 7.3 Detail sheet

The detail sheet is a `ModalBottomSheet`, opened from a row or from the `oikos://alert/<id>` deep link. If the alert isn't in Room, the sheet shows "This alert is no longer available". Its contents:
- A full-width image (16:9 crop, rounded) when present; a placeholder while loading; "Media no longer available" for `Gone` or `NotAllowed`.
- A play button when a `videoUrl` is set, with a progress ring while the clip downloads, then an inline Media3 player.
- Title, body, a channel chip, and the full date and time; a "Resolved" chip if applicable.
- Action buttons (§5.5).
- "Open device" when `entityId` matches a known entity's `entity_id`/unique id; it opens that entity's existing control sheet.

### 7.4 Settings: Push alerts row

The row shows the `PushStatus` text:
- "Registered · 2 min ago"
- "Waiting for connection"
- "Failed: <reason>"
- "Notifications off"
- "Not available in demo"

Tapping it opens a small sheet with "Register again" and "Notification channels", which opens the system's channel settings.

### 7.5 Onboarding

The last step, after room assignment, explains alerts and asks for `POST_NOTIFICATIONS` on Android 13+. "Not now" skips it. The registration is enqueued either way.

### 7.6 Demo house

- No registration.
- `:core:demo` seeds 6 fixed alerts, one per main channel, with bundled images, so that previews, screenshots and the demo mode show real content.
- No media fetching for demo alerts: their images are local resources.

## 8. Error handling

| Case | Behaviour |
|---|---|
| Malformed FCM data / `v ≠ 1` | Logged and dropped |
| Image fetch over the 8 s budget | Text notification; the history loads the image later |
| Media `Gone` | "Media no longer available" |
| Gateway `destination not allowed` | Same as `Gone` in the UI; logged once with the host |
| Registration fails | `RegisterWorker` backoff; Settings shows Waiting or Failed |
| Reply fails 5 times | "Couldn't send *<label>*" on `system` |
| Notifications permission denied | Alerts stored, not posted; Security banner; Settings status |
| No profile / demo | `OneShotPublisher` → `NotConfigured`; workers finish without retrying |
| OikosPush down | Producers' messages are lost unless they publish QoS 1 to a persistent session. The heartbeat makes the outage visible. History covers only what the service saw |
| History message malformed | Skipped; the next retained update replaces it |

## 9. Testing

**OikosPush (pytest, MQTT and FCM stubbed):**
- `normalize`:
  - every v1 field;
  - the full legacy mapping table;
  - rejected payloads;
  - FCM-size truncation.
- Token map: register, update, empty = unregister, bad JSON, duplicate tokens.
- Priority selection.
- Invalid-token cleanup publishes an empty retained payload.
- Retry queue.
- History:
  - prepend and replace-and-move;
  - cancel;
  - size and day caps;
  - persistence round trip;
  - retained republish.

**Oikos unit tests:**
- `AlertParser` (data map and history JSON).
- `OneShotPublisher`:
  - live transport first;
  - auto with probe true or false;
  - direct failure falling back to the gateway;
  - fixed routes;
  - demo.
- `PushRegistrar`:
  - republishes only on a difference;
  - topic substitution;
  - unregister on reset.
- `RegisterWorker` and `ReplyWorker` with `work-testing` (retry, the failure notification after 5 attempts).
- `AlertDao` (Robolectric in-memory Room): upsert keeps `receivedAt`, trim at 200, `markResolved`.
- `HistorySync`: inserts only missing alerts, counts the new ones, applies cancels, skips bad entries.
- `MediaFetcher`: LAN first, gateway fallback, error mapping (fake gateway API and MockWebServer).
- `PushStatus` combinations.

**UI:** Robolectric screenshot tests in both themes, with demo alerts:
- Recent alerts (with rows, empty, banner);
- the Alerts screen (All and one filter);
- the detail sheet (image + video + actions, resolved, gone).

**Device check (manual):**
1. `mosquitto_pub` one v1 alert with a Frigate snapshot and an action, and one legacy `alarme` alert.
2. Check: notification with picture → tap opens the sheet → the action reply shows on `oikos-push/responses`.
3. Turn on airplane mode, publish 2 alerts, turn airplane mode off and open the app → the catch-up snackbar appears.
4. Move away from home Wi-Fi and open a clip → it downloads through the gateway.

## 10. Rollout

1. Create and deploy OikosPush with a service-account key for Firebase project `oikos-b8268`.
2. On the gateway, add a rule to the Oikos key allowing HTTP (port 80 family) to the Frigate host (`server/add_secret.py`).
3. If the onboarding QR sets `push.token_topic` to the old default `fcm-push/tokens/{installId}`, regenerate it with `tools/setup-qr.py`, or leave it out to use the new default.
4. Install the app, grant notifications, and check that Settings shows "Registered".
5. Move the producers to `oikos-push/notification/<source>` one at a time, using the v1 format or as they are. Mute DomoticApp's matching channels on the phone once Oikos has proven itself.

## 11. Risks

- **Duplicate alerts** while both apps are installed and a producer publishes to both namespaces. The user mutes channels in one of the two apps.
- **Producers on QoS 0** lose alerts while OikosPush is down; the history can't help there.
- **The 8 s image budget** relies on a fast gateway round trip. A slow upstream (Frigate busy) means a text-only notification.
- **The 25 MB clip cap** (gateway v1.3): longer clips show "Media no longer available". Frigate event clips are normally well under it.
- **Coil `Fetcher` scope:** it must only handle alert media URLs, so it doesn't send every app image through the gateway. Requests are keyed by an `AlertMedia(url)` data type, not raw strings.
