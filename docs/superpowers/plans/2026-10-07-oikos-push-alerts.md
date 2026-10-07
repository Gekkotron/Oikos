# Push Alerts (App) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Oikos receives the house's push alerts through FCM (sent by the separate OikosPush service), shows them as Android notifications with a picture and up to 3 action buttons, keeps a Room history that also catches up from the retained `oikos-push/history` topic, and lets the user browse it (Security tab, Alerts screen, detail sheet with image and clip), answer actions, and see the registration status in Settings.

**Architecture:** Three new modules. `:core:alerts` owns the `Alert` model, `AlertParser`, Room (`AlertDatabase`/`AlertDao`/`AlertRepository`), `HistorySync`, `MediaFetcher` (LAN first, gateway `/gateway` raw download as fallback), the Coil `AlertMediaFetcher` and `VideoCache`. `:core:push` depends on it and owns the FCM service, channels, notifier, `OneShotPublisher`, registration and reply workers, and `PushStatus`. `:feature:alerts` holds the Recent-alerts section, the Alerts screen and the detail sheet. `:core:push` and `:core:alerts` stay free of Hilt: `:app` builds every object in Hilt modules, and the service and receivers reach them through a small `PushComponentProvider` that `OikosApplication` implements. Background publishes reuse the app's routes (live transport, then direct MQTT, then the gateway) through `OneShotPublisher`, retried by WorkManager.

**Tech Stack:** Kotlin 2.4, Jetpack Compose (Material 3), Hilt, Navigation 3, Room 2.8 (KSP), WorkManager 2.12, Firebase Messaging (BoM 34.19), Coil 3.5, Media3 1.11, kotlinx.serialization, `athena-gateway-client` v1.3, JUnit 4 + Robolectric (Android modules) and JUnit 5 (`:core:transport`), Gradle 9.

**Spec:** `docs/superpowers/specs/2026-10-07-oikos-push-alerts-design.md` (this plan covers spec §4–§8, the Oikos half of §9, and §10 steps 3–4; the OikosPush service is a separate plan and its MQTT/FCM contract, spec §2, is taken as given).

## Global Constraints

- Git identity on every commit: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "<msg>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"`. All work is on branch `feat/push-alerts` (Task 1 creates it). Never write a real person's name anywhere (code, strings, docs, commits).
- History is trimmed to **200 rows** after every upsert (oldest by `sentAt`); `AlertDao.KEEP = 200`.
- A notification's picture gets an **8 s** budget for the whole fetch (LAN attempt, gateway fallback and decode together); on timeout the notification is posted text-only.
- `OneShotPublisher` waits at most **10 s** for a direct connection before falling back; every background publish is QoS 1.
- A reply is tried **5 times** in total (`runAttemptCount` 0..4), then "Couldn't send *label*" is posted on `system`. `ReplyWorker` backoff is exponential from **10 s**; `RegisterWorker` backoff is exponential from **30 s**.
- Video clips: **25 MB** cap per clip (the gateway's raw limit), **200 MB** LRU by access time in `cache/alerts/video`.
- Topics: token topic default `oikos-push/tokens/{installId}` (profile `pushTokenTopic` overrides, retained, empty payload = unregister), responses `oikos-push/responses` (not retained), history `oikos-push/history` (retained, subscribed while a direct profile is live).
- Seven channels, ids `alarm`, `security`, `camera`, `energy`, `climate`, `system`, `misc`; importance HIGH for `alarm`/`security`/`camera`, DEFAULT for the rest. A notification shows at most **3** action buttons.
- Notification tag = alert id, notification id = `NotificationIds.ALERT` (1), so a later alert with the same id replaces it and a cancel dismisses it.
- Every user-facing string exists in `values/strings.xml` and `values-fr/strings.xml` of the module that owns it.
- No build flavors. Debug builds have no Firebase configuration (`processDebugGoogleServices` is disabled), so FCM calls must degrade to a clear `Failed(...)` status instead of crashing.
- Match the surrounding code: KDoc one-liners citing the spec section, no logging of alert titles or bodies (log ids and key names only).

## Review Focus

1. **Two alerts with the same action id collapse into one `PendingIntent`.** Android ignores extras when it matches `PendingIntent`s, so every alert's "Disarm" button would answer for whichever alert posted first. → Task 8, test `two alerts with the same action id get distinct reply intents`.
2. **A cancel (or a newer alert) arrives while the first alert's picture is still downloading.** The late `post` must not resurrect a dismissed notification or overwrite a newer one with the same id. → Task 8, tests `a cancel that arrives while the picture loads is not undone by the late post` and `an older alert finishing late does not replace a newer one with the same id`.
3. **The retained history message is redelivered on every reconnect.** After "Clear all" the cleared alerts would come straight back, and an alert older than the 200th row would be counted as "arrived while you were offline" although trimming deleted it. → Task 11, tests `a cleared history is not resurrected by the retained history message` and `an alert trimmed away in the same sync is not counted`.
4. **A reply retried after a publish that actually reached the broker, or a double tap on the button.** Producers would act twice. The retry must keep the original `ts` and a double tap must enqueue once. → Task 7, test `a retried reply keeps its ts and a double tap enqueues once`.
5. **A flapping connection re-enqueues `register` with REPLACE every time.** Each REPLACE restarts the exponential backoff, so a broken token topic would be hammered every few seconds. A reconnect must not touch registration work that is already pending. → Task 6, test `a reconnect while registration is still retrying does not reset its backoff`.

---

## File Structure

Dependency direction: `:feature:*` and `:app` -> `:core:push` -> `:core:alerts` -> `:core:transport`/`:core:data`. The `Alert` model and `AlertParser` live in `:core:alerts` (decision fixed in the brief); `:core:push` depends on it. `:core:demo` is a pure JVM module and cannot depend on the Android `:core:alerts`, so the six demo alerts live in `:core:alerts` (`DemoAlerts`), see Task 17.

**Build and catalog**
- Modify `gradle/libs.versions.toml`: `athenaGatewayClient = "v1.3"`; versions `room`, `work`, `coil`, `media3`, `androidxCore`; libraries `androidx-room-runtime`, `androidx-room-ktx`, `androidx-room-compiler`, `androidx-work-runtime-ktx`, `androidx-work-testing`, `androidx-core-ktx`, `firebase-messaging`, `coil-core`, `coil-compose`, `coil-network-okhttp`, `androidx-media3-exoplayer`, `androidx-media3-ui`.
- Create `build-logic/convention/src/main/kotlin/oikos.room.gradle.kts`: KSP + Room deps + schema export.
- Modify `settings.gradle.kts`, `build.gradle.kts` (Kover): add `:core:alerts`, `:core:push`, `:feature:alerts`.

**`:core:transport`** (`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/`)
- Create `GatewayDownloader.kt`: `GatewayDownloader`, `RawDownload`, `DownloadNotAllowed`, `DownloadFailed`, `AthenaGatewayDownloader` over the SDK's `download`.
- Test `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayDownloaderTest.kt`.

**`:core:data`**
- Modify `StoredProfile.kt` (`notificationsPending`), `ConnectionProfile.kt` (`ProfileState.Ready.notificationsPending`), `ProfileRepository.kt` (`finishNotifications()`), and two test files (Task 16).

**`:core:alerts`** (`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/`)
- `Alert.kt`: `AlertChannel`, `Severity`, `AlertAction`, `Alert`.
- `AlertParser.kt`: `fromData`, `fromHistory`, `parseHistory`, `parseActions`.
- `PushTopics.kt`: topic constants and `tokenTopic(template, installId)`.
- `db/AlertEntity.kt`, `db/AlertDao.kt`, `db/AlertDatabase.kt`, `db/Mapping.kt`; `AlertRepository.kt`.
- `media/MediaFetcher.kt` (`Media`, `MediaFailure`, `MediaException`, `MediaRoute`, `MediaRoutes`, `MediaSource`, `MediaFetcher`), `media/AlertMedia.kt` (`AlertMedia`, `AlertImageCache`, `AlertMediaFetcher`), `media/VideoCache.kt` (`VideoCache`, `VideoState`).
- `sync/HistorySync.kt`.
- `demo/DemoAlerts.kt`.
- Tests under `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/...` mirror these files.

**`:core:push`** (`core/push/src/main/kotlin/com/gekkotron/oikos/push/`, resources in `core/push/src/main/res`)
- `OneShotPublisher.kt` (`TopicPublisher`, `NotConfigured`, `OneShotPublisher`).
- `PushTarget.kt` (`PushTarget`, `PushTargets`).
- `FcmTokens.kt` (`FcmTokens`, `FirebaseFcmTokens`, `FirebaseUnavailable`).
- `RegistrationStore.kt` (`RegistrationStore`, `LastRegistration`).
- `PushRegistrar.kt` (`PushRegistrar`, `RegistrationQueue`), `RegisterWorker.kt` (`RegisterWorker`, `WorkManagerRegistrationQueue`), `PushWorkerFactory.kt`.
- `AlertChannels.kt` (`AlertChannels`, `NotificationIds`).
- `ReplyQueue.kt` (`ReplyQueue`, `ReplyState`, `WorkManagerReplyQueue`), `ReplyWorker.kt`, `ReplyReceiver.kt`, `ReplyFailureNotifier.kt`.
- `AlertNotifier.kt` (`AlertNotifier`, `NotificationImages`, `FetchedNotificationImages`).
- `AlertIngestor.kt`, `OikosMessagingService.kt`, `PushComponent.kt` (`PushComponent`, `PushComponentProvider`).
- `PushStatus.kt` (`PushStatus`, `pushStatus`, `PushStatusSource`, `DefaultPushStatusSource`).
- `src/main/AndroidManifest.xml` (service, receiver, `POST_NOTIFICATIONS`), `res/drawable/ic_stat_oikos.xml`, `res/values{,-fr}/strings.xml`.

**`:feature:alerts`** (`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/`)
- `presentation/`: `AlertTimes.kt`, `EntityMatch.kt`, `AlertDetailViewModel.kt`, `DayGroups.kt`, `AlertsViewModel.kt`, `RecentAlertsViewModel.kt`.
- `ui/`: `AlertImage.kt`, `AlertRow.kt`, `AlertDetailSheet.kt`, `VideoPlayer.kt`, `RecentAlertsSection.kt` (with its route), `AlertsScreen.kt` (with its route), `Now.kt`.
- `src/test/.../ScreenshotSupport.kt`, `FakeEntitySource.kt`, `FakePushStatusSource.kt` and the tests.

**`:core:designsystem`**
- Create `permissions/NotificationEnabler.kt` (`rememberNotificationEnabler`, `shouldOpenSettings`, `openNotificationSettings`); modify `build.gradle.kts` (activity-compose).

**`:feature:security`, `:feature:settings`, `:feature:onboarding`, `:feature:home`**
- Security: `SecurityScreen.kt` takes an `alertsSection` slot; `SecurityRoute` forwards it; the placeholder strings are removed.
- Settings: `SettingsViewModel.kt`, `SettingsScreen.kt`, `PushSheet.kt`, strings.
- Onboarding: `ui/NotificationsScreen.kt`, strings.
- Home: public `ui/EntityControlSheet.kt` and `CustomizeViewModel.send` for "Open device".

**`:app`**
- Modify `AndroidManifest.xml` (deep link, singleTop, permissions, WorkManager initializer removal), `OikosApplication.kt`, `MainActivity.kt`, `AppViewModel.kt`, `app/build.gradle.kts`, `res/xml/network_security_config.xml`, `navigation/OikosNavHost.kt` (keys `AlertsKey`, `AlertDetailKey`, `NotificationsKey`).
- Create `di/AlertsModule.kt`, `di/PushModule.kt`, `di/WorkModule.kt`, `connection/MediaRoutes.kt` (`ProfileMediaRoutes`, `mediaRouteFor`), `connection/PushTargets.kt` (`ProfilePushTargets`, `pushTargetFor`, `oneShotSpec`), `connection/PushWiring.kt`, `connection/SharedSubscriptions.kt`, `connection/HistoryWiring.kt`, `DeepLinks.kt`, `SetUpAgainCleanup.kt`, `DemoAlertSeeder.kt`.
- Tests: `app/src/test/.../TestWorkModule.kt` plus the unit tests named in each task.

---

### Task 1: Branch, `:core:alerts` module, `Alert` model and `AlertParser`

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/alerts/build.gradle.kts`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/Alert.kt`, `AlertParser.kt`, `PushTopics.kt`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/AlertParserTest.kt`, `PushTopicsTest.kt`

**Interfaces:**
- Produces:
  - `enum class AlertChannel(val id: String) { ALARM, SECURITY, CAMERA, ENERGY, CLIMATE, SYSTEM, MISC }` with `AlertChannel.fromId(id: String?): AlertChannel` (unknown or null is `MISC`)
  - `enum class Severity(val id: String) { INFO, WARNING, CRITICAL }` with `Severity.fromId(id: String?): Severity` (default `INFO`)
  - `data class AlertAction(val id: String, val label: String)`
  - `data class Alert(id, channel, severity, title, body, imageUrl, videoUrl, entityId, actions, source, silent, resolved, sentAt: Instant, receivedAt: Instant)` with `val shownActions: List<AlertAction>` (first 3) and `Alert.MAX_ACTIONS = 3`
  - `AlertParser.fromData(data: Map<String, String>, receivedAt: Instant = Instant.now()): Alert?`, `AlertParser.fromHistory(obj: JsonObject, now: Instant = Instant.now()): Alert?`, `AlertParser.parseHistory(payload: String, now: Instant = Instant.now()): List<Alert>?`, `internal AlertParser.parseActions(raw: String?): List<AlertAction>`
  - `object PushTopics { DEFAULT_TOKEN_TOPIC, RESPONSES, HISTORY, fun tokenTopic(template: String?, installId: String): String }`

A cancel (`cancel = "true"`, no title needed) parses to an `Alert` with `resolved = true`; no other path produces `resolved = true`, so "resolved" doubles as the cancel flag for the ingestor and `HistorySync`.

- [ ] **Step 1: Create the branch and register the module**

```bash
git switch -c feat/push-alerts
```

In `settings.gradle.kts`, after `include(":core:data")` add:

```kotlin
include(":core:alerts")
```

In `build.gradle.kts` (root), inside `dependencies { ... }` after `kover(project(":core:data"))` add:

```kotlin
    kover(project(":core:alerts"))
```

Create `core/alerts/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.alerts"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 2: Write the failing tests**

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/AlertParserTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertParserTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z")

    private fun v1(vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("v" to "1", "id" to "frigate-front-1696", "title" to "Front door") + extra

    @Test
    fun `every field of the normalized data map is read`() {
        val alert = AlertParser.fromData(
            v1(
                "channel" to "camera", "severity" to "critical", "body" to "Person detected",
                "image_url" to "http://192.168.1.20:5000/s.jpg", "video_url" to "http://192.168.1.20:5000/c.mp4",
                "entity_id" to "binary_sensor.front_door", "source" to "frigate", "silent" to "true",
                "actions" to """[{"id":"disarm","label":"Disarm"}]""", "sent_at" to "1790000000",
            ),
            receivedAt = now,
        )
        assertNotNull(alert)
        assertEquals("frigate-front-1696", alert.id)
        assertEquals(AlertChannel.CAMERA, alert.channel)
        assertEquals(Severity.CRITICAL, alert.severity)
        assertEquals("Front door", alert.title)
        assertEquals("Person detected", alert.body)
        assertEquals("http://192.168.1.20:5000/s.jpg", alert.imageUrl)
        assertEquals("http://192.168.1.20:5000/c.mp4", alert.videoUrl)
        assertEquals("binary_sensor.front_door", alert.entityId)
        assertEquals("frigate", alert.source)
        assertTrue(alert.silent)
        assertFalse(alert.resolved)
        assertEquals(listOf(AlertAction("disarm", "Disarm")), alert.actions)
        assertEquals(Instant.ofEpochSecond(1790000000), alert.sentAt)
        assertEquals(now, alert.receivedAt)
    }

    @Test
    fun `an unknown or missing channel is misc and severity defaults to info`() {
        assertEquals(AlertChannel.MISC, AlertParser.fromData(v1("channel" to "garden"))!!.channel)
        assertEquals(AlertChannel.MISC, AlertParser.fromData(v1())!!.channel)
        assertEquals(Severity.INFO, AlertParser.fromData(v1("severity" to "loud"))!!.severity)
    }

    @Test
    fun `optional fields left out stay null or empty and sent_at falls back to receivedAt`() {
        val alert = AlertParser.fromData(v1(), receivedAt = now)!!
        assertNull(alert.imageUrl)
        assertNull(alert.videoUrl)
        assertNull(alert.entityId)
        assertNull(alert.source)
        assertEquals("", alert.body)
        assertEquals(emptyList(), alert.actions)
        assertFalse(alert.silent)
        assertEquals(now, alert.sentAt)
    }

    @Test
    fun `malformed data is null`() {
        assertNull(AlertParser.fromData(v1("v" to "2")))
        assertNull(AlertParser.fromData(mapOf("id" to "x", "title" to "t")))
        assertNull(AlertParser.fromData(v1("id" to " ")))
        assertNull(AlertParser.fromData(mapOf("v" to "1", "title" to "t")))
        assertNull(AlertParser.fromData(v1("title" to "")))
    }

    @Test
    fun `a cancel needs no title and is resolved`() {
        val cancel = AlertParser.fromData(mapOf("v" to "1", "id" to "frigate-front-1696", "cancel" to "true"), receivedAt = now)
        assertNotNull(cancel)
        assertTrue(cancel.resolved)
        assertEquals("", cancel.title)
    }

    @Test
    fun `bad actions json keeps the alert with no actions`() {
        assertEquals(emptyList(), AlertParser.fromData(v1("actions" to "[{oops"))!!.actions)
        assertEquals(emptyList(), AlertParser.fromData(v1("actions" to "[]"))!!.actions)
        assertEquals(
            listOf(AlertAction("a", "a"), AlertAction("b", "Bee")),
            AlertParser.fromData(v1("actions" to """[{"id":"a"},{"id":"b","label":"Bee"},{"label":"no id"},3]"""))!!.actions,
        )
    }

    @Test
    fun `at most three actions are shown`() {
        val four = """[{"id":"1","label":"1"},{"id":"2","label":"2"},{"id":"3","label":"3"},{"id":"4","label":"4"}]"""
        val alert = AlertParser.fromData(v1("actions" to four))!!
        assertEquals(4, alert.actions.size)
        assertEquals(listOf("1", "2", "3"), alert.shownActions.map { it.id })
    }

    @Test
    fun `a history entry has typed json values and its receivedAt is its sentAt`() {
        val obj = Json.parseToJsonElement(
            """{"v":1,"id":"a1","channel":"alarm","severity":"warning","title":"Alarm","body":"Triggered",
                "actions":[{"id":"silence","label":"Silence"}],"silent":false,"cancel":true,"sent_at":1790000000,"source":"jeedom"}""",
        ).jsonObject
        val alert = AlertParser.fromHistory(obj, now)!!
        assertEquals(AlertChannel.ALARM, alert.channel)
        assertEquals(Severity.WARNING, alert.severity)
        assertEquals(listOf(AlertAction("silence", "Silence")), alert.actions)
        assertTrue(alert.resolved)
        assertEquals(Instant.ofEpochSecond(1790000000), alert.sentAt)
        assertEquals(alert.sentAt, alert.receivedAt)
        assertEquals("jeedom", alert.source)
    }

    @Test
    fun `parseHistory skips malformed entries and rejects a non array`() {
        val payload = """[{"v":1,"id":"a","title":"A"},{"v":1,"title":"no id"},"junk",{"v":2,"id":"c","title":"C"},{"v":1,"id":"d","title":"D"}]"""
        assertEquals(listOf("a", "d"), AlertParser.parseHistory(payload, now)!!.map { it.id })
        assertNull(AlertParser.parseHistory("""{"v":1}""", now))
        assertNull(AlertParser.parseHistory("not json", now))
        assertEquals(emptyList(), AlertParser.parseHistory("[]", now))
    }
}
```

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/PushTopicsTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts

import org.junit.Test
import kotlin.test.assertEquals

class PushTopicsTest {
    @Test
    fun `the default template gets the install id`() {
        assertEquals("oikos-push/tokens/abc-123", PushTopics.tokenTopic(null, "abc-123"))
    }

    @Test
    fun `a profile template overrides the default`() {
        assertEquals("custom/tokens/abc-123", PushTopics.tokenTopic("custom/tokens/{installId}", "abc-123"))
    }

    @Test
    fun `the fixed topics`() {
        assertEquals("oikos-push/responses", PushTopics.RESPONSES)
        assertEquals("oikos-push/history", PushTopics.HISTORY)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.AlertParserTest' --tests 'com.gekkotron.oikos.alerts.PushTopicsTest'`
Expected: compilation FAIL (`Unresolved reference: AlertParser`, `PushTopics`, `AlertChannel`).

- [ ] **Step 4: Implement**

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/Alert.kt`:

```kotlin
package com.gekkotron.oikos.alerts

import java.time.Instant

/** The seven notification channels (push alerts spec §1); the id is also the Android channel id. */
enum class AlertChannel(val id: String) {
    ALARM("alarm"),
    SECURITY("security"),
    CAMERA("camera"),
    ENERGY("energy"),
    CLIMATE("climate"),
    SYSTEM("system"),
    MISC("misc"),
    ;

    companion object {
        /** An unknown or missing id is [MISC] (spec §2.2). */
        fun fromId(id: String?): AlertChannel = entries.firstOrNull { it.id == id } ?: MISC
    }
}

enum class Severity(val id: String) {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical"),
    ;

    companion object {
        fun fromId(id: String?): Severity = entries.firstOrNull { it.id == id } ?: INFO
    }
}

data class AlertAction(val id: String, val label: String)

/**
 * One normalized alert (spec §4.1). [resolved] is also what a cancel parses to: no other path sets it.
 * [receivedAt] is when this phone got it; for a history entry it is [sentAt].
 */
data class Alert(
    val id: String,
    val channel: AlertChannel,
    val severity: Severity,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val entityId: String?,
    val actions: List<AlertAction>,
    val source: String?,
    val silent: Boolean,
    val resolved: Boolean,
    val sentAt: Instant,
    val receivedAt: Instant,
) {
    /** A notification and the sheet show at most [MAX_ACTIONS] buttons (spec §2.2). */
    val shownActions: List<AlertAction> get() = actions.take(MAX_ACTIONS)

    companion object {
        const val MAX_ACTIONS = 3
    }
}
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/AlertParser.kt`:

```kotlin
package com.gekkotron.oikos.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** Turns the two shapes OikosPush sends (spec §2.4 FCM data, §3.5 history JSON) into [Alert]; null means malformed. */
object AlertParser {
    /** [data] is the FCM data map: every value a string. Needs `v == "1"` and an `id`; a title unless it is a cancel. */
    fun fromData(data: Map<String, String>, receivedAt: Instant = Instant.now()): Alert? {
        if (data["v"] != "1") return null
        val id = data["id"]?.takeIf { it.isNotBlank() } ?: return null
        val cancel = data["cancel"] == "true"
        val title = data["title"].orEmpty()
        if (!cancel && title.isBlank()) return null
        return Alert(
            id = id,
            channel = AlertChannel.fromId(data["channel"]),
            severity = Severity.fromId(data["severity"]),
            title = title,
            body = data["body"].orEmpty(),
            imageUrl = data["image_url"]?.takeIf { it.isNotBlank() },
            videoUrl = data["video_url"]?.takeIf { it.isNotBlank() },
            entityId = data["entity_id"]?.takeIf { it.isNotBlank() },
            actions = parseActions(data["actions"]),
            source = data["source"]?.takeIf { it.isNotBlank() },
            silent = data["silent"] == "true",
            resolved = cancel,
            sentAt = data["sent_at"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: receivedAt,
            receivedAt = receivedAt,
        )
    }

    /** One entry of `oikos-push/history`: the same keys with typed values. [Alert.receivedAt] is its `sent_at`. */
    fun fromHistory(obj: JsonObject, now: Instant = Instant.now()): Alert? {
        val data = obj.mapNotNull { (key, value) ->
            when (value) {
                is JsonNull -> null
                is JsonPrimitive -> key to value.content
                else -> key to value.toString()
            }
        }.toMap()
        val sent = data["sent_at"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: now
        return fromData(data, receivedAt = sent)
    }

    /** The whole retained history message; null when it is not a JSON array. Bad entries are skipped one by one. */
    fun parseHistory(payload: String, now: Instant = Instant.now()): List<Alert>? {
        val array = try {
            Json.parseToJsonElement(payload) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let { entry -> fromHistory(entry, now) } }
    }

    /** `[{id,label}]` as text. Bad JSON means no actions, not a dropped alert. */
    internal fun parseActions(raw: String?): List<AlertAction> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyList()
        return array.mapNotNull(::actionOf)
    }

    private fun actionOf(element: JsonElement): AlertAction? {
        val obj = element as? JsonObject ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val label = (obj["label"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
        return AlertAction(id, label)
    }
}
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/PushTopics.kt`:

```kotlin
package com.gekkotron.oikos.alerts

/** The OikosPush topic contract (push alerts spec §2.1). */
object PushTopics {
    const val DEFAULT_TOKEN_TOPIC = "oikos-push/tokens/{installId}"

    /** Oikos to producers; not retained. */
    const val RESPONSES = "oikos-push/responses"

    /** OikosPush to Oikos; retained JSON array of normalized alerts. */
    const val HISTORY = "oikos-push/history"

    /** The profile's `pushTokenTopic` (or the default) with `{installId}` substituted. */
    fun tokenTopic(template: String?, installId: String): String =
        (template?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_TOPIC).replace("{installId}", installId)
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.AlertParserTest' --tests 'com.gekkotron.oikos.alerts.PushTopicsTest'`
Expected: PASS (11 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/alerts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): Alert model, AlertParser and push topics" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Room — entities, DAO, database, `AlertRepository`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/alerts/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/oikos.room.gradle.kts`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/db/AlertEntity.kt`, `AlertDao.kt`, `AlertDatabase.kt`, `Mapping.kt`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/AlertRepository.kt`
- Create (generated, committed): `core/alerts/schemas/com.gekkotron.oikos.alerts.db.AlertDatabase/1.json`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/db/AlertDaoTest.kt`, `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/AlertRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 `Alert`, `AlertChannel`, `AlertParser.parseActions`.
- Produces:
  - `AlertDao` (abstract class): `suspend fun upsert(entity: AlertEntity)`, `suspend fun insertIfMissing(entity: AlertEntity): Boolean`, `suspend fun markResolved(id: String)`, `fun recent(channels: List<String>, limit: Int): Flow<List<AlertEntity>>`, `fun all(channel: String?): Flow<List<AlertEntity>>`, `fun byId(id: String): Flow<AlertEntity?>`, `suspend fun clearAll(now: Long)`, `suspend fun reset()`, `suspend fun trim(keep: Int)`, constants `AlertDao.KEEP = 200`, `AlertDao.CLEARED_AT = "clearedAt"`
  - `class AlertDatabase : RoomDatabase` with `fun alerts(): AlertDao` and `AlertDatabase.create(context: Context): AlertDatabase`
  - `class AlertRepository(dao: AlertDao, clock: () -> Instant = Instant::now)`: `suspend fun upsert(alert: Alert)`, `suspend fun insertIfMissing(alert: Alert): Boolean`, `suspend fun markResolved(id: String)`, `suspend fun clearAll()`, `suspend fun reset()`, `fun recent(channels: Set<AlertChannel>, limit: Int = 5): Flow<List<Alert>>`, `fun all(channel: AlertChannel?): Flow<List<Alert>>`, `fun byId(id: String): Flow<Alert?>`

Design notes: `clearAll()` (the user's "Clear all") records a tombstone `clearedAt`; `insertIfMissing` refuses an alert whose `sentAt` is not after it, so the retained history cannot resurrect what the user cleared (Review Focus 3). `reset()` ("Set up again") deletes everything *and* the tombstone, so a new house starts clean. `insertIfMissing` runs insert and trim in one transaction and answers whether the row survived the trim.

- [ ] **Step 1: Catalog, convention plugin and module build**

In `gradle/libs.versions.toml` `[versions]` add:

```toml
room = "2.8.5"
```

and in `[libraries]`:

```toml
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
```

Create `build-logic/convention/src/main/kotlin/oikos.room.gradle.kts`:

```kotlin
// Room through KSP, schemas exported next to the module.
plugins {
    id("com.google.devtools.ksp")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("androidx-room-runtime").get())
    "implementation"(libs.findLibrary("androidx-room-ktx").get())
    "ksp"(libs.findLibrary("androidx-room-compiler").get())
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Replace `core/alerts/build.gradle.kts` with:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.serialization")
    id("oikos.room")
}

android {
    namespace = "com.gekkotron.oikos.alerts"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 2: Write the failing tests**

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/db/AlertDaoTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertDaoTest {
    private lateinit var db: AlertDatabase
    private lateinit var dao: AlertDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        dao = db.alerts()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(id: String, sentAt: Long = 1_000, receivedAt: Long = sentAt, channel: String = "camera", title: String = "t-$id") = AlertEntity(
        id = id, channel = channel, severity = "info", title = title, body = "", imageUrl = null, videoUrl = null,
        entityId = null, actionsJson = "[]", source = null, silent = false, resolved = false, sentAt = sentAt, receivedAt = receivedAt,
    )

    @Test
    fun `upsert replaces the content but keeps the original receivedAt`() = runBlocking {
        dao.upsert(entity("a", sentAt = 1_000, receivedAt = 1_500, title = "first"))
        dao.upsert(entity("a", sentAt = 2_000, receivedAt = 2_500, title = "second"))
        val row = dao.byId("a").first()!!
        assertEquals("second", row.title)
        assertEquals(2_000, row.sentAt)
        assertEquals(1_500, row.receivedAt)
    }

    @Test
    fun `upsert trims to 200 rows, deleting the oldest by sentAt`() = runBlocking {
        for (i in 1..205) dao.upsert(entity("a$i", sentAt = i * 1_000L))
        val ids = dao.all(null).first().map { it.id }
        assertEquals(200, ids.size)
        assertFalse("a1" in ids)
        assertFalse("a5" in ids)
        assertTrue("a6" in ids)
        assertTrue("a205" in ids)
    }

    @Test
    fun `markResolved flags the row and ignores an unknown id`() = runBlocking {
        dao.upsert(entity("a"))
        dao.markResolved("a")
        dao.markResolved("ghost")
        assertTrue(dao.byId("a").first()!!.resolved)
        assertNull(dao.byId("ghost").first())
    }

    @Test
    fun `recent filters by channel and returns the newest first, limited`() = runBlocking {
        dao.upsert(entity("old", receivedAt = 1_000, channel = "alarm"))
        dao.upsert(entity("mid", receivedAt = 2_000, channel = "camera"))
        dao.upsert(entity("energy", receivedAt = 2_500, channel = "energy"))
        dao.upsert(entity("new", receivedAt = 3_000, channel = "security"))
        assertEquals(listOf("new", "mid"), dao.recent(listOf("alarm", "security", "camera"), 2).first().map { it.id })
    }

    @Test
    fun `all with a null channel returns every channel, with a channel only that one`() = runBlocking {
        dao.upsert(entity("a", receivedAt = 1_000, channel = "alarm"))
        dao.upsert(entity("b", receivedAt = 2_000, channel = "misc"))
        assertEquals(listOf("b", "a"), dao.all(null).first().map { it.id })
        assertEquals(listOf("a"), dao.all("alarm").first().map { it.id })
    }

    @Test
    fun `insertIfMissing inserts a new alert and leaves an existing one alone`() = runBlocking {
        assertTrue(dao.insertIfMissing(entity("a", title = "first")))
        assertFalse(dao.insertIfMissing(entity("a", title = "second")))
        assertEquals("first", dao.byId("a").first()!!.title)
    }

    @Test
    fun `insertIfMissing answers false when the trim deleted the alert it just inserted`() = runBlocking {
        for (i in 1..200) dao.upsert(entity("n$i", sentAt = 10_000L + i))
        assertFalse(dao.insertIfMissing(entity("ancient", sentAt = 5)))
        assertNull(dao.byId("ancient").first())
        assertEquals(200, dao.all(null).first().size)
    }

    @Test
    fun `clearAll deletes everything and refuses alerts sent before it`() = runBlocking {
        dao.upsert(entity("a", sentAt = 1_000))
        dao.clearAll(now = 5_000)
        assertTrue(dao.all(null).first().isEmpty())
        assertFalse(dao.insertIfMissing(entity("a", sentAt = 1_000)))
        assertFalse(dao.insertIfMissing(entity("edge", sentAt = 5_000)))
        assertTrue(dao.insertIfMissing(entity("fresh", sentAt = 5_001)))
    }

    @Test
    fun `reset deletes everything and forgets the clear marker`() = runBlocking {
        dao.clearAll(now = 5_000)
        dao.upsert(entity("x", sentAt = 6_000))
        dao.reset()
        assertTrue(dao.all(null).first().isEmpty())
        assertTrue(dao.insertIfMissing(entity("old", sentAt = 1_000)))
        assertNotNull(dao.byId("old").first())
    }
}
```

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/AlertRepositoryTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.db.AlertDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertRepositoryTest {
    private lateinit var db: AlertDatabase
    private var now = Instant.parse("2026-10-07T10:00:00Z")
    private lateinit var repo: AlertRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun alert(id: String, channel: AlertChannel = AlertChannel.CAMERA, sent: Instant = now) = Alert(
        id = id, channel = channel, severity = Severity.WARNING, title = "Title $id", body = "Body", imageUrl = "http://h/i.jpg",
        videoUrl = "http://h/c.mp4", entityId = "binary_sensor.door", actions = listOf(AlertAction("disarm", "Disarm")), source = "frigate",
        silent = true, resolved = false, sentAt = sent, receivedAt = sent,
    )

    @Test
    fun `an alert survives the round trip through Room`() = runBlocking {
        val original = alert("a")
        repo.upsert(original)
        assertEquals(original, repo.byId("a").first())
    }

    @Test
    fun `recent keeps the requested channels`() = runBlocking {
        repo.upsert(alert("cam", AlertChannel.CAMERA, now))
        repo.upsert(alert("nrg", AlertChannel.ENERGY, now.plusSeconds(1)))
        assertEquals(listOf("cam"), repo.recent(setOf(AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA)).first().map { it.id })
    }

    @Test
    fun `clearAll uses the clock for its marker`() = runBlocking {
        repo.upsert(alert("a", sent = now.minusSeconds(60)))
        repo.clearAll()
        assertTrue(repo.all(null).first().isEmpty())
        assertEquals(false, repo.insertIfMissing(alert("a", sent = now.minusSeconds(60))))
        assertEquals(true, repo.insertIfMissing(alert("b", sent = now.plusSeconds(1))))
    }

    @Test
    fun `markResolved shows in the flow`() = runBlocking {
        repo.upsert(alert("a"))
        repo.markResolved("a")
        assertTrue(repo.byId("a").first()!!.resolved)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.db.AlertDaoTest' --tests 'com.gekkotron.oikos.alerts.AlertRepositoryTest'`
Expected: compilation FAIL (`Unresolved reference: AlertDatabase`, `AlertEntity`, `AlertRepository`).

- [ ] **Step 4: Implement**

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/db/AlertEntity.kt`:

```kotlin
package com.gekkotron.oikos.alerts.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Table `alerts`, mirroring [com.gekkotron.oikos.alerts.Alert]; actions are JSON, times epoch millis (spec §6.1). */
@Entity(tableName = "alerts", indices = [Index(value = ["channel", "receivedAt"])])
data class AlertEntity(
    @PrimaryKey val id: String,
    val channel: String,
    val severity: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val entityId: String?,
    val actionsJson: String,
    val source: String?,
    val silent: Boolean,
    val resolved: Boolean,
    val sentAt: Long,
    val receivedAt: Long,
)

/** Small key/value rows, for the "Clear all" marker. */
@Entity(tableName = "alert_meta")
data class MetaEntity(@PrimaryKey val key: String, val value: Long)
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/db/AlertDao.kt`:

```kotlin
package com.gekkotron.oikos.alerts.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Spec §6.1. An abstract class, not an interface: the transactions have bodies. */
@Dao
abstract class AlertDao {
    @Query("SELECT receivedAt FROM alerts WHERE id = :id")
    abstract suspend fun receivedAtOf(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: AlertEntity)

    @Query("UPDATE alerts SET resolved = 1 WHERE id = :id")
    abstract suspend fun markResolved(id: String)

    /** Keeps the [keep] newest rows by `sentAt`. */
    @Query("DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY sentAt DESC, id LIMIT :keep)")
    abstract suspend fun trim(keep: Int)

    @Query("SELECT * FROM alerts WHERE channel IN (:channels) ORDER BY receivedAt DESC, id LIMIT :limit")
    abstract fun recent(channels: List<String>, limit: Int): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE (:channel IS NULL OR channel = :channel) ORDER BY receivedAt DESC, id")
    abstract fun all(channel: String?): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE id = :id")
    abstract fun byId(id: String): Flow<AlertEntity?>

    @Query("DELETE FROM alerts")
    abstract suspend fun deleteAll()

    @Query("SELECT value FROM alert_meta WHERE `key` = :key")
    abstract suspend fun meta(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(meta: MetaEntity)

    @Query("DELETE FROM alert_meta WHERE `key` = :key")
    abstract suspend fun deleteMeta(key: String)

    /** Replaces the alert with the same id but keeps its first `receivedAt`, then trims. One transaction. */
    @Transaction
    open suspend fun upsert(entity: AlertEntity) {
        val kept = receivedAtOf(entity.id)
        insert(if (kept == null) entity else entity.copy(receivedAt = kept))
        trim(KEEP)
    }

    /**
     * History catch-up: inserts only an id we do not have, and not one sent before the user last cleared.
     * Returns whether the row is still there after the trim (an alert older than the 200th is not).
     */
    @Transaction
    open suspend fun insertIfMissing(entity: AlertEntity): Boolean {
        if (receivedAtOf(entity.id) != null) return false
        val clearedAt = meta(CLEARED_AT) ?: Long.MIN_VALUE
        if (entity.sentAt <= clearedAt) return false
        insert(entity)
        trim(KEEP)
        return receivedAtOf(entity.id) != null
    }

    /** The user's "Clear all": deletes every row and remembers [now], so history cannot bring them back. */
    @Transaction
    open suspend fun clearAll(now: Long) {
        deleteAll()
        putMeta(MetaEntity(CLEARED_AT, now))
    }

    /** "Set up again": a clean slate, marker included. */
    @Transaction
    open suspend fun reset() {
        deleteAll()
        deleteMeta(CLEARED_AT)
    }

    companion object {
        const val KEEP = 200
        const val CLEARED_AT = "clearedAt"
    }
}
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/db/AlertDatabase.kt`:

```kotlin
package com.gekkotron.oikos.alerts.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Version 1, schema exported to `core/alerts/schemas` (spec §6.1). */
@Database(entities = [AlertEntity::class, MetaEntity::class], version = 1, exportSchema = true)
abstract class AlertDatabase : RoomDatabase() {
    abstract fun alerts(): AlertDao

    companion object {
        const val FILE = "alerts.db"

        fun create(context: Context): AlertDatabase =
            Room.databaseBuilder(context.applicationContext, AlertDatabase::class.java, FILE).build()
    }
}
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/db/Mapping.kt`:

```kotlin
package com.gekkotron.oikos.alerts.db

import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertParser
import com.gekkotron.oikos.alerts.Severity
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

internal fun Alert.toEntity(): AlertEntity = AlertEntity(
    id = id, channel = channel.id, severity = severity.id, title = title, body = body, imageUrl = imageUrl, videoUrl = videoUrl,
    entityId = entityId, actionsJson = actions.toJson(), source = source, silent = silent, resolved = resolved,
    sentAt = sentAt.toEpochMilli(), receivedAt = receivedAt.toEpochMilli(),
)

internal fun AlertEntity.toAlert(): Alert = Alert(
    id = id, channel = AlertChannel.fromId(channel), severity = Severity.fromId(severity), title = title, body = body,
    imageUrl = imageUrl, videoUrl = videoUrl, entityId = entityId, actions = AlertParser.parseActions(actionsJson), source = source,
    silent = silent, resolved = resolved, sentAt = Instant.ofEpochMilli(sentAt), receivedAt = Instant.ofEpochMilli(receivedAt),
)

private fun List<AlertAction>.toJson(): String = buildJsonArray {
    forEach { action ->
        add(
            buildJsonObject {
                put("id", action.id)
                put("label", action.label)
            },
        )
    }
}.toString()
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/AlertRepository.kt`:

```kotlin
package com.gekkotron.oikos.alerts

import com.gekkotron.oikos.alerts.db.AlertDao
import com.gekkotron.oikos.alerts.db.AlertEntity
import com.gekkotron.oikos.alerts.db.toAlert
import com.gekkotron.oikos.alerts.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** The alert history (spec §6.1). Everything the UI and the push service need from Room, as [Alert]. */
class AlertRepository(private val dao: AlertDao, private val clock: () -> Instant = Instant::now) {
    /** Stores or replaces the alert with this id (keeping its first `receivedAt`) and trims to 200 rows. */
    suspend fun upsert(alert: Alert) = dao.upsert(alert.toEntity())

    /** Catch-up insert; true when the alert is new and still stored after trimming. */
    suspend fun insertIfMissing(alert: Alert): Boolean = dao.insertIfMissing(alert.toEntity())

    suspend fun markResolved(id: String) = dao.markResolved(id)

    /** "Clear all" on this phone: also stops older history alerts from coming back. */
    suspend fun clearAll() = dao.clearAll(clock().toEpochMilli())

    /** "Set up again": every row and the clear marker. */
    suspend fun reset() = dao.reset()

    fun recent(channels: Set<AlertChannel>, limit: Int = 5): Flow<List<Alert>> =
        dao.recent(channels.map { it.id }, limit).map { rows -> rows.map(AlertEntity::toAlert) }

    fun all(channel: AlertChannel?): Flow<List<Alert>> = dao.all(channel?.id).map { rows -> rows.map(AlertEntity::toAlert) }

    fun byId(id: String): Flow<Alert?> = dao.byId(id).map { it?.toAlert() }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.db.AlertDaoTest' --tests 'com.gekkotron.oikos.alerts.AlertRepositoryTest'`
Expected: PASS (13 tests). The build also writes `core/alerts/schemas/com.gekkotron.oikos.alerts.db.AlertDatabase/1.json`; check it exists.

If `id("com.google.devtools.ksp")` inside the new convention plugin is reported unresolved, it is because `build-logic/convention/build.gradle.kts` lacks the KSP Gradle plugin on its classpath; `oikos.hilt` already applies the same id, so it is there. Do not add anything.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build-logic core/alerts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): Room history with 200-row trim and a clear marker" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Gateway SDK v1.3 and `MediaFetcher` (LAN first, gateway fallback)

**Files:**
- Modify: `gradle/libs.versions.toml` (`athenaGatewayClient = "v1.3"`), `core/alerts/build.gradle.kts`, `app/src/main/res/xml/network_security_config.xml`
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayDownloader.kt`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcher.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayDownloaderTest.kt`, `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcherTest.kt`

**Interfaces:**
- Consumes: `LocalProbe` (`core/transport`), `isLikelyLocalHost(host: String): Boolean` (`:core:data`), SDK `AthenaGatewayClient.download(url, headers, timeoutSeconds, throwOnUpstreamError): GatewayDownload(status, bytes, contentType)` and `GatewayException.Rejected(reason)` (verified in the v1.3 sources: a refused destination is `Rejected("destination not allowed for this secret")`, a refused port is `Rejected("port not allowed for this secret")`, a file over the gateway cap or a gateway older than v1.3 is `GatewayError`).
- Produces (`:core:transport`, package `com.gekkotron.oikos.transport`):
  - `interface GatewayDownloader { suspend fun download(url: String, timeoutSeconds: Int): RawDownload }`
  - `class RawDownload(val status: Int, val bytes: ByteArray, val contentType: String?)`
  - `class DownloadNotAllowed(val reason: String) : Exception(reason)`, `class DownloadFailed(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)`
  - `class AthenaGatewayDownloader : GatewayDownloader` with `constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient())` and `internal constructor(fetch: suspend (url: String, timeoutSeconds: Int) -> GatewayDownload)`
- Produces (`:core:alerts`, package `com.gekkotron.oikos.alerts.media`):
  - `class Media(val bytes: ByteArray, val contentType: String?)`
  - `sealed interface MediaFailure { data object Gone; data object NotAllowed; data class Unavailable(val reason: String) }`
  - `class MediaException(val failure: MediaFailure) : Exception`
  - `class MediaRoute(val local: LocalProbe?, val gateway: GatewayDownloader?)`, `fun interface MediaRoutes { suspend fun current(): MediaRoute }`
  - `interface MediaSource { suspend fun bytes(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): Result<Media> }`
  - `class MediaFetcher(routes: MediaRoutes, http: OkHttpClient, io: CoroutineDispatcher = Dispatchers.IO, isLanHost: (String) -> Boolean = ::isLikelyLocalHost, log: (String) -> Unit = ...) : MediaSource`

Rules (spec §6.3): LAN first when the route has a local probe, the URL host is a private-network host and the probe answers: OkHttp GET, 5 s connect and 15 s read. Only a *connection* error falls through to the gateway; a 404/410 is `Gone`, any other HTTP error is `Unavailable("http N")` and neither retries elsewhere. Otherwise (or after a connection error) `download(url, timeoutSeconds = 30)`. Gateway 404/410 is `Gone`, "destination/port not allowed" is `NotAllowed` and is logged once per host, anything else is `Unavailable`. The body is read in chunks and cut off at `maxBytes` (`Unavailable("too large")`), never buffered whole first.

**Spec gap found while planning:** `app/src/main/res/xml/network_security_config.xml` forbids cleartext for the whole app, and OkHttp on Android honours that policy, so the LAN branch (Frigate is `http://192.168.x.x:5000`) could never connect. Step 6 relaxes the base config and documents why that is safe (the gateway URL is still forced to HTTPS in code by `requireHttps`, and `MediaFetcher` only goes cleartext for private-network hosts after a LAN broker probe answered).

- [ ] **Step 1: Bump the SDK and confirm it resolves**

In `gradle/libs.versions.toml` change `athenaGatewayClient = "v1.2"` to `athenaGatewayClient = "v1.3"`.

Run: `./gradlew :core:transport:compileKotlin --refresh-dependencies`
Expected: BUILD SUCCESSFUL. JitPack builds a tag on first request; if it answers with a build in progress or a timeout, wait a minute and run it again. If the tag was never built, open `https://jitpack.io/#Gekkotron/Athena-HttpMqttGateway/v1.3` once to trigger the build.

Run: `./gradlew :core:transport:test`
Expected: PASS (nothing in the existing tests depends on v1.3 changes).

- [ ] **Step 2: Write the failing downloader test**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayDownloaderTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.GatewayDownload
import io.github.gekkotron.athena.gateway.client.GatewayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GatewayDownloaderTest {
    private fun downloader(fetch: suspend (String, Int) -> GatewayDownload) = AthenaGatewayDownloader(fetch)

    @Test
    fun `a download maps status, bytes and content type and passes the timeout on`() = runBlocking {
        var seen: Pair<String, Int>? = null
        val result = downloader { url, timeout ->
            seen = url to timeout
            GatewayDownload(200, byteArrayOf(1, 2, 3), "image/jpeg")
        }.download("http://192.168.1.20:5000/s.jpg", 30)
        assertEquals("http://192.168.1.20:5000/s.jpg" to 30, seen)
        assertEquals(200, result.status)
        assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
        assertEquals("image/jpeg", result.contentType)
    }

    @Test
    fun `a non-2xx answer is returned, not thrown`() = runBlocking {
        val result = downloader { _, _ -> GatewayDownload(404, ByteArray(0), null) }.download("http://h/x", 30)
        assertEquals(404, result.status)
    }

    @Test
    fun `a refused destination or port is DownloadNotAllowed`() = runBlocking {
        for (reason in listOf("destination not allowed for this secret", "port not allowed for this secret")) {
            val e = assertFailsWith<DownloadNotAllowed> {
                downloader { _, _ -> throw GatewayException.Rejected(reason) }.download("http://h/x", 30)
            }
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun `other gateway failures are DownloadFailed with a short text`() = runBlocking {
        val expired = assertFailsWith<DownloadFailed> {
            downloader { _, _ -> throw GatewayException.Rejected("Request expired") }.download("http://h/x", 30)
        }
        assertEquals(LinkState.Offline.CLOCK_SKEW, expired.message)
        val tooLarge = assertFailsWith<DownloadFailed> {
            downloader { _, _ -> throw GatewayException.GatewayError("response too large") }.download("http://h/x", 30)
        }
        assertEquals("response too large", tooLarge.message)
        assertIs<GatewayException.Unauthorized>(
            assertFailsWith<DownloadFailed> { downloader { _, _ -> throw GatewayException.Unauthorized() }.download("http://h/x", 30) }.cause,
        )
    }

    @Test
    fun `cancellation is not turned into a failure`() {
        assertFailsWith<CancellationException> {
            runBlocking { downloader { _, _ -> throw CancellationException("stop") }.download("http://h/x", 30) }
        }
    }

    @Test
    fun `a plain http gateway url is refused`() {
        val spec = TransportSpec.Gateway("http://gw.example", ByteArray(32), null, null, null)
        assertFailsWith<IllegalArgumentException> { AthenaGatewayDownloader(spec) }
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :core:transport:test --tests 'com.gekkotron.oikos.transport.GatewayDownloaderTest'`
Expected: compilation FAIL (`Unresolved reference: AthenaGatewayDownloader`).

- [ ] **Step 4: Implement the downloader**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayDownloader.kt`:

```kotlin
package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.GatewayDownload
import io.github.gekkotron.athena.gateway.client.GatewayException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.IOException

/** A raw GET through the gateway (gateway v1.3 `/gateway`, push alerts spec §6.3). */
interface GatewayDownloader {
    /** @throws DownloadNotAllowed the gateway key has no rule for this destination or port. @throws DownloadFailed anything else. */
    suspend fun download(url: String, timeoutSeconds: Int): RawDownload
}

/** [status] may be an upstream error (404...): the caller decides what that means. */
class RawDownload(val status: Int, val bytes: ByteArray, val contentType: String?)

/** "destination not allowed for this secret": the user has to add the host to the key's rules (spec §10 step 2). */
class DownloadNotAllowed(val reason: String) : Exception(reason)

class DownloadFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** [GatewayDownloader] over the SDK's `download`, with non-2xx answers returned instead of thrown. */
class AthenaGatewayDownloader internal constructor(
    private val fetch: suspend (url: String, timeoutSeconds: Int) -> GatewayDownload,
) : GatewayDownloader {
    constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient()) : this(clientFor(spec, http))

    override suspend fun download(url: String, timeoutSeconds: Int): RawDownload = try {
        fetch(url, timeoutSeconds).let { RawDownload(it.status, it.bytes, it.contentType) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        throw e.toDownloadFailure()
    }

    private companion object {
        fun clientFor(spec: TransportSpec.Gateway, http: OkHttpClient): suspend (String, Int) -> GatewayDownload {
            require(spec.url.startsWith("https://")) { "the gateway URL must use https" }
            val client = AthenaGatewayClient(spec.url, spec.key.toHex(), http)
            return { url, timeout -> client.download(url, timeoutSeconds = timeout, throwOnUpstreamError = false) }
        }
    }
}

internal fun GatewayException.toDownloadFailure(): Exception = when {
    this is GatewayException.Rejected && (reason.startsWith("destination not allowed") || reason.startsWith("port not allowed")) ->
        DownloadNotAllowed(reason)
    else -> DownloadFailed(publishText(), this)
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew :core:transport:test --tests 'com.gekkotron.oikos.transport.GatewayDownloaderTest'`
Expected: PASS (6 tests).

- [ ] **Step 6: Allow cleartext for the LAN media fetch**

Replace `app/src/main/res/xml/network_security_config.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Gateway spec §5 (amends parent spec §8): only system CAs are trusted, and the gateway is HTTPS only; that is
  enforced in code (`requireHttps` in AthenaGatewayApi and AthenaGatewayDownloader), not by this file.
  Push alerts spec §6.3: cleartext HTTP is permitted at the OS level because alert media (Frigate snapshots and
  clips) lives on the LAN as plain http://192.168.x.x URLs. The only cleartext client is MediaFetcher's LAN branch,
  which runs only for private-network hosts and only after the LAN broker answered a TCP probe; everything else
  reaches the gateway over HTTPS. The MQTT TCP socket is not governed by this file.
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 7: Write the failing `MediaFetcher` test**

In `core/alerts/build.gradle.kts` add, inside `dependencies { }`:

```kotlin
    // LocalProbe, GatewayDownloader and (through the gateway SDK) OkHttp.
    api(project(":core:transport"))
    implementation(project(":core:data")) // isLikelyLocalHost
    testImplementation(libs.mockwebserver)
```

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcherTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.media

import com.gekkotron.oikos.transport.DownloadFailed
import com.gekkotron.oikos.transport.DownloadNotAllowed
import com.gekkotron.oikos.transport.GatewayDownloader
import com.gekkotron.oikos.transport.LocalProbe
import com.gekkotron.oikos.transport.RawDownload
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaFetcherTest {
    private val server = MockWebServer()
    private val logged = mutableListOf<String>()

    private class FakeGateway(var answer: () -> RawDownload = { RawDownload(200, byteArrayOf(9, 9), "image/png") }) : GatewayDownloader {
        val urls = mutableListOf<String>()
        override suspend fun download(url: String, timeoutSeconds: Int): RawDownload {
            urls += url
            check(timeoutSeconds == 30) { "gateway timeout must be 30 s" }
            return answer()
        }
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun fetcher(local: Boolean?, gateway: GatewayDownloader?, lan: (String) -> Boolean = { true }) = MediaFetcher(
        routes = { MediaRoute(local?.let { up -> LocalProbe { up } }, gateway) },
        http = OkHttpClient(),
        isLanHost = lan,
        log = { logged += it },
    )

    private fun url(path: String = "/snap.jpg") = server.url(path).toString()

    private fun failure(r: Result<Media>): MediaFailure = (r.exceptionOrNull() as MediaException).failure

    @Test
    fun `the LAN answers first and the gateway is not touched`() = runBlocking {
        server.enqueue(MockResponse().setBody("lan-bytes").setHeader("Content-Type", "image/jpeg"))
        val gateway = FakeGateway()
        val media = fetcher(local = true, gateway = gateway).bytes(url(), 1_000).getOrThrow()
        assertContentEquals("lan-bytes".encodeToByteArray(), media.bytes)
        assertEquals("image/jpeg", media.contentType)
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a LAN 404 is Gone and does not fall back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val gateway = FakeGateway()
        assertEquals(MediaFailure.Gone, failure(fetcher(true, gateway).bytes(url(), 1_000)))
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a LAN 500 is Unavailable and does not fall back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val gateway = FakeGateway()
        assertEquals(MediaFailure.Unavailable("http 500"), failure(fetcher(true, gateway).bytes(url(), 1_000)))
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a connection error falls back to the gateway`() = runBlocking {
        val dead = url()
        server.shutdown()
        val gateway = FakeGateway()
        val media = fetcher(true, gateway).bytes(dead, 1_000).getOrThrow()
        assertContentEquals(byteArrayOf(9, 9), media.bytes)
        assertEquals(listOf(dead), gateway.urls)
    }

    @Test
    fun `a probe that does not answer goes straight to the gateway`() = runBlocking {
        val gateway = FakeGateway()
        fetcher(local = false, gateway = gateway).bytes(url(), 1_000).getOrThrow()
        assertEquals(0, server.requestCount)
        assertEquals(1, gateway.urls.size)
    }

    @Test
    fun `a host outside the private network never gets a direct request`() = runBlocking {
        val gateway = FakeGateway()
        fetcher(local = true, gateway = gateway, lan = { false }).bytes(url(), 1_000).getOrThrow()
        assertEquals(0, server.requestCount)
        assertEquals(1, gateway.urls.size)
    }

    @Test
    fun `gateway 404 and 410 are Gone, other statuses are Unavailable`() = runBlocking {
        val gateway = FakeGateway()
        val f = fetcher(local = null, gateway = gateway)
        for (status in listOf(404, 410)) {
            gateway.answer = { RawDownload(status, ByteArray(0), null) }
            assertEquals(MediaFailure.Gone, failure(f.bytes("http://192.168.1.20/x", 1_000)))
        }
        gateway.answer = { RawDownload(502, ByteArray(0), null) }
        assertEquals(MediaFailure.Unavailable("http 502"), failure(f.bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `a refused destination is NotAllowed and logged once per host`() = runBlocking {
        val gateway = FakeGateway { throw DownloadNotAllowed("destination not allowed for this secret") }
        val f = fetcher(local = null, gateway = gateway)
        assertEquals(MediaFailure.NotAllowed, failure(f.bytes("http://192.168.1.20:5000/a.jpg", 1_000)))
        assertEquals(MediaFailure.NotAllowed, failure(f.bytes("http://192.168.1.20:5000/b.jpg", 1_000)))
        assertEquals(1, logged.size)
        assertTrue("192.168.1.20" in logged.single())
        assertEquals(MediaFailure.NotAllowed, failure(f.bytes("http://192.168.1.99/c.jpg", 1_000)))
        assertEquals(2, logged.size)
    }

    @Test
    fun `any other gateway failure is Unavailable with its text`() = runBlocking {
        val gateway = FakeGateway { throw DownloadFailed("gateway key not accepted") }
        assertEquals(MediaFailure.Unavailable("gateway key not accepted"), failure(fetcher(null, gateway).bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `with no route at all the media is Unavailable`() = runBlocking {
        val f = fetcher(local = null, gateway = null)
        assertIs<MediaFailure.Unavailable>(failure(f.bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `a body over maxBytes is cut off, by Content-Length or while streaming`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2_000))))
        assertEquals(MediaFailure.Unavailable("too large"), failure(fetcher(true, null).bytes(url(), 1_000)))
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(40_000)), 4_096))
        assertEquals(MediaFailure.Unavailable("too large"), failure(fetcher(true, null).bytes(url(), 10_000)))
    }

    @Test
    fun `a gateway body over maxBytes is refused too`() = runBlocking {
        val gateway = FakeGateway { RawDownload(200, ByteArray(2_000), null) }
        assertEquals(MediaFailure.Unavailable("too large"), failure(fetcher(null, gateway).bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `progress is reported with the total when the server sends a length`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(40_000))))
        val seen = mutableListOf<Pair<Long, Long?>>()
        fetcher(true, null).bytes(url(), 100_000) { read, total -> seen += read to total }.getOrThrow()
        assertTrue(seen.isNotEmpty())
        assertEquals(40_000L, seen.last().first)
        assertEquals(40_000L, seen.last().second)
    }

    @Test
    fun `a url that is not http is Unavailable`() = runBlocking {
        assertNull(fetcher(true, FakeGateway()).bytes("not a url", 1_000).getOrNull())
    }
}
```

- [ ] **Step 8: Run it to verify it fails**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.media.MediaFetcherTest'`
Expected: compilation FAIL (`Unresolved reference: MediaFetcher`, `MediaRoute`).

- [ ] **Step 9: Implement `MediaFetcher`**

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcher.kt`:

```kotlin
package com.gekkotron.oikos.alerts.media

import android.util.Log
import com.gekkotron.oikos.data.isLikelyLocalHost
import com.gekkotron.oikos.transport.DownloadNotAllowed
import com.gekkotron.oikos.transport.GatewayDownloader
import com.gekkotron.oikos.transport.LocalProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Media(val bytes: ByteArray, val contentType: String?)

sealed interface MediaFailure {
    /** HTTP 404 or 410: the event is gone from the producer. */
    data object Gone : MediaFailure

    /** The gateway key has no rule for this host (spec §10 step 2). */
    data object NotAllowed : MediaFailure

    data class Unavailable(val reason: String) : MediaFailure
}

class MediaException(val failure: MediaFailure) : Exception(failure.toString())

/** How media can be reached right now (spec §6.3): a local probe when a LAN broker is allowed, a gateway when allowed. */
class MediaRoute(val local: LocalProbe?, val gateway: GatewayDownloader?)

fun interface MediaRoutes {
    suspend fun current(): MediaRoute
}

/** What the notifier, the Coil fetcher and the video cache need; tests fake it. */
interface MediaSource {
    suspend fun bytes(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): Result<Media>
}

/** LAN first (5 s connect, 15 s read), the gateway's raw download otherwise (30 s); spec §6.3. */
class MediaFetcher(
    private val routes: MediaRoutes,
    http: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val isLanHost: (String) -> Boolean = ::isLikelyLocalHost,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : MediaSource {
    private val lan: OkHttpClient = http.newBuilder()
        .connectTimeout(LAN_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LAN_READ_SECONDS, TimeUnit.SECONDS)
        .build()
    private val warnedHosts = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Lan {
        class Got(val media: Media) : Lan
        class Refused(val failure: MediaFailure) : Lan
        class Down(val reason: String) : Lan
    }

    override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
        val host = url.toHttpUrlOrNull()?.host ?: return fail(MediaFailure.Unavailable("not an http url"))
        val route = routes.current()
        var lanReason: String? = null
        val probe = route.local
        if (probe != null && isLanHost(host) && probe.reachable()) {
            when (val result = fromLan(url, maxBytes, onProgress)) {
                is Lan.Got -> return Result.success(result.media)
                is Lan.Refused -> return fail(result.failure)
                is Lan.Down -> lanReason = result.reason
            }
        }
        val gateway = route.gateway ?: return fail(MediaFailure.Unavailable(lanReason ?: "no route to the media"))
        return fromGateway(gateway, url, host, maxBytes)
    }

    private suspend fun fromGateway(gateway: GatewayDownloader, url: String, host: String, maxBytes: Long): Result<Media> = try {
        val download = gateway.download(url, GATEWAY_TIMEOUT_SECONDS)
        when {
            download.status == 404 || download.status == 410 -> fail(MediaFailure.Gone)
            download.status !in 200..299 -> fail(MediaFailure.Unavailable("http ${download.status}"))
            download.bytes.size > maxBytes -> fail(MediaFailure.Unavailable(TOO_LARGE))
            else -> Result.success(Media(download.bytes, download.contentType))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DownloadNotAllowed) {
        if (warnedHosts.add(host)) log("the gateway refused $host (${e.reason}); add the host to this key's rules")
        fail(MediaFailure.NotAllowed)
    } catch (e: Exception) {
        fail(MediaFailure.Unavailable(e.message ?: e.javaClass.simpleName))
    }

    private suspend fun fromLan(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Lan = try {
        lan.newCall(Request.Builder().url(url).build()).await().use { response ->
            when {
                response.code == 404 || response.code == 410 -> Lan.Refused(MediaFailure.Gone)
                !response.isSuccessful -> Lan.Refused(MediaFailure.Unavailable("http ${response.code}"))
                else -> readBody(response, maxBytes, onProgress)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Lan.Down(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun readBody(response: Response, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Lan = withContext(io) {
        val body = response.body ?: return@withContext Lan.Refused(MediaFailure.Unavailable("empty body"))
        val total = body.contentLength().takeIf { it >= 0 }
        if (total != null && total > maxBytes) return@withContext Lan.Refused(MediaFailure.Unavailable(TOO_LARGE))
        val out = ByteArrayOutputStream(total?.toInt()?.coerceIn(1, 1 shl 20) ?: INITIAL_BUFFER)
        val source = body.source()
        val chunk = ByteArray(CHUNK)
        var read = 0L
        while (true) {
            ensureActive()
            val n = source.read(chunk)
            if (n < 0) break
            read += n
            if (read > maxBytes) return@withContext Lan.Refused(MediaFailure.Unavailable(TOO_LARGE))
            out.write(chunk, 0, n)
            onProgress(read, total)
        }
        Lan.Got(Media(out.toByteArray(), body.contentType()?.toString()))
    }

    private fun fail(failure: MediaFailure): Result<Media> = Result.failure(MediaException(failure))

    private companion object {
        const val TAG = "OikosMedia"
        const val LAN_CONNECT_SECONDS = 5L
        const val LAN_READ_SECONDS = 15L
        const val GATEWAY_TIMEOUT_SECONDS = 30
        const val TOO_LARGE = "too large"
        const val INITIAL_BUFFER = 64 * 1024
        const val CHUNK = 16 * 1024
    }
}

/** Cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        },
    )
}
```

- [ ] **Step 10: Run all of the module's tests**

Run: `./gradlew :core:alerts:testDebugUnitTest :core:transport:test`
Expected: PASS. The cut-off test with chunked bodies reads at most `maxBytes + one chunk` into memory; if it flakes on slow machines raise nothing: it asserts the failure only.

- [ ] **Step 11: Commit**

```bash
git add gradle/libs.versions.toml core/transport core/alerts app/src/main/res/xml/network_security_config.xml
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): MediaFetcher with LAN first and gateway v1.3 raw download" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Coil `AlertMediaFetcher`, image cache and `VideoCache`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/alerts/build.gradle.kts`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/AlertMedia.kt`, `VideoCache.kt`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/AlertMediaFetcherTest.kt`, `VideoCacheTest.kt`

**Interfaces:**
- Consumes: Task 3 `MediaSource`, `Media`, `MediaException`, `MediaFailure`.
- Produces (package `com.gekkotron.oikos.alerts.media`):
  - `data class AlertMedia(val url: String)` (the only Coil request type that reaches the gateway)
  - `object AlertMediaKeyer : coil3.key.Keyer<AlertMedia>` (key `alert-media:<url>`, so Coil's memory cache can hold the pictures)
  - `class AlertImageCache(val diskCache: coil3.disk.DiskCache)` with `suspend fun put(url: String, bytes: ByteArray)`, `fun open(url: String): DiskCache.Snapshot?`, `fun clear()`, `AlertImageCache.create(context: Context): AlertImageCache` (64 MB under `cache/alerts/image`)
  - `class AlertMediaFetcher.Factory(source: MediaSource, cache: AlertImageCache, maxBytes: Long = 5L * 1024 * 1024) : Fetcher.Factory<AlertMedia>`
  - `sealed interface VideoState { data class Downloading(val read: Long, val total: Long?) { val fraction: Float? }; data class Ready(val file: File); data class Failed(val failure: MediaFailure) }`
  - `class VideoCache(dir: File, source: MediaSource, capBytes: Long = 200L * 1024 * 1024, clipLimit: Long = 25L * 1024 * 1024, io: CoroutineDispatcher = Dispatchers.IO)` with `fun file(alertId: String, url: String): Flow<VideoState>`, `fun clear()`, `VideoCache.create(context: Context, source: MediaSource): VideoCache` (`cache/alerts/video`)

Notes: the Fetcher is registered for the `AlertMedia` type only, so a raw `String` URL given to an `AsyncImage` never goes through the gateway (spec §6.3, §11). The image cache is Coil's own `DiskCache` type, built once and handed both to the `ImageLoader` (Task 9) and to the notifier, so a picture downloaded for a notification is a cache hit when the history shows it. Clip files are named `<sanitized id>-<8 hex of sha1(url)>.mp4`: an alert re-sent with the same id and a new clip must not play the old file. A clip is written to a `.part` file and renamed, so a cancelled download never leaves a truncated mp4 that looks complete.

- [ ] **Step 1: Catalog and build**

`gradle/libs.versions.toml` `[versions]`:

```toml
coil = "3.5.0"
```

`[libraries]`:

```toml
coil-core = { module = "io.coil-kt.coil3:coil-core", version.ref = "coil" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }
```

Coil 3.5.0 is the newest release that compiles against `compileSdk` 36; 3.6.x needs 37 (checked from each AAR's `aar-metadata.properties`). `core/alerts/build.gradle.kts` `dependencies { }` gains:

```kotlin
    api(libs.coil.core)
```

- [ ] **Step 2: Write the failing tests**

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/AlertMediaFetcherTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.media

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class AlertMediaFetcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()

    private class FakeSource(var answer: () -> Result<Media>) : MediaSource {
        var calls = 0
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
            calls++
            return answer()
        }
    }

    private fun cache() = AlertImageCache(DiskCache.Builder().directory(tmp.newFolder("img").toOkioPath()).maxSizeBytes(1_000_000).build())
    private val jpeg = Media(byteArrayOf(1, 2, 3, 4), "image/jpeg")

    private fun fetcher(source: MediaSource, cache: AlertImageCache, url: String = "http://192.168.1.20:5000/s.jpg") =
        AlertMediaFetcher.Factory(source, cache).create(AlertMedia(url), Options(context), ImageLoader(context))!!

    @Test
    fun `the first fetch goes to the source, the second is served from the disk cache`() = runBlocking {
        val source = FakeSource { Result.success(jpeg) }
        val cache = cache()
        val first = fetcher(source, cache).fetch() as SourceFetchResult
        assertEquals(DataSource.NETWORK, first.dataSource)
        assertContentEquals(jpeg.bytes, first.source.source().readByteArray())
        val second = fetcher(source, cache).fetch() as SourceFetchResult
        assertEquals(DataSource.DISK, second.dataSource)
        assertContentEquals(jpeg.bytes, second.source.source().readByteArray())
        assertEquals(1, source.calls)
    }

    @Test
    fun `a picture the notifier already stored is a cache hit`() = runBlocking {
        val source = FakeSource { error("must not be called") }
        val cache = cache()
        cache.put("http://192.168.1.20:5000/s.jpg", jpeg.bytes)
        val result = fetcher(source, cache).fetch() as SourceFetchResult
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals(0, source.calls)
    }

    @Test
    fun `a source failure propagates as its MediaException`() {
        val source = FakeSource { Result.failure(MediaException(MediaFailure.Gone)) }
        val e = assertFailsWith<MediaException> { runBlocking { fetcher(source, cache()).fetch() } }
        assertEquals(MediaFailure.Gone, e.failure)
    }

    @Test
    fun `the keyer keys by url so the memory cache can hold the picture`() {
        assertEquals("alert-media:http://h/s.jpg", AlertMediaKeyer.key(AlertMedia("http://h/s.jpg"), Options(context)))
    }

    @Test
    fun `a raw string url never reaches the alert media source`() = runBlocking {
        val source = FakeSource { Result.success(jpeg) }
        val loader = ImageLoader.Builder(context).components { add(AlertMediaFetcher.Factory(source, cache())) }.build()
        val result = loader.execute(ImageRequest.Builder(context).data("https://example.com/x.jpg").build())
        assertIs<coil3.request.ErrorResult>(result)
        assertEquals(0, source.calls)
    }
}
```

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/VideoCacheTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.media

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VideoCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource : MediaSource {
        var calls = 0
        var limit: Long? = null
        var answer: (String) -> Result<Media> = { Result.success(Media(ByteArray(4) { 7 }, "video/mp4")) }
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
            calls++
            limit = maxBytes
            onProgress(2, 4)
            return answer(url)
        }
    }

    private val source = FakeSource()
    private fun cache(cap: Long = 1_000, dir: File = tmp.newFolder("video")) = VideoCache(dir, source, capBytes = cap)

    private suspend fun VideoCache.ready(id: String, url: String) = file(id, url).toList().last().let { assertIs<VideoState.Ready>(it).file }

    @Test
    fun `a miss downloads with progress, a hit does not download again`() = runBlocking {
        val cache = cache()
        val states = cache.file("a1", "http://h/c.mp4").toList()
        assertEquals(VideoState.Downloading(0, null), states.first())
        assertTrue(VideoState.Downloading(2, 4) in states)
        val file = assertIs<VideoState.Ready>(states.last()).file
        assertEquals(4, file.length())
        assertEquals(VideoState.Ready(file), cache.file("a1", "http://h/c.mp4").first())
        assertEquals(1, source.calls)
    }

    @Test
    fun `the clip cap is 25 MB`() = runBlocking {
        cache().ready("a1", "http://h/c.mp4")
        assertEquals(25L * 1024 * 1024, source.limit)
    }

    @Test
    fun `fraction is read over total, and unknown without a total`() {
        assertEquals(0.5f, VideoState.Downloading(2, 4).fraction)
        assertEquals(null, VideoState.Downloading(2, null).fraction)
        assertEquals(null, VideoState.Downloading(2, 0).fraction)
    }

    @Test
    fun `a failure is reported and leaves no file behind`() = runBlocking {
        source.answer = { Result.failure(MediaException(MediaFailure.Gone)) }
        val dir = tmp.newFolder("video2")
        val last = cache(dir = dir).file("a1", "http://h/c.mp4").toList().last()
        assertEquals(VideoState.Failed(MediaFailure.Gone), last)
        assertEquals(emptyList(), dir.listFiles()!!.toList())
    }

    @Test
    fun `the same alert id with a new clip url plays the new clip`() = runBlocking {
        val cache = cache()
        val old = cache.ready("a1", "http://h/old.mp4")
        val new = cache.ready("a1", "http://h/new.mp4")
        assertNotEquals(old, new)
        assertEquals(2, source.calls)
    }

    @Test
    fun `an alert id with path characters stays inside the cache directory`() = runBlocking {
        val dir = tmp.newFolder("video3")
        val file = cache(dir = dir).ready("../../etc/x/1", "http://h/c.mp4")
        assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
        assertTrue(file.name.endsWith(".mp4"))
    }

    @Test
    fun `the least recently used clip goes first when the cap is exceeded`() = runBlocking {
        val dir = tmp.newFolder("video4")
        val cache = cache(cap = 10, dir = dir)
        val a = cache.ready("a", "http://h/a.mp4").also { it.setLastModified(1_000) }
        val b = cache.ready("b", "http://h/b.mp4").also { it.setLastModified(2_000) }
        // 8 bytes now; a third clip makes 12 over the cap of 10: the oldest (a) is evicted, the new one stays.
        val c = cache.ready("c", "http://h/c.mp4")
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
    }

    @Test
    fun `a hit refreshes the access time`() = runBlocking {
        val cache = cache()
        val a = cache.ready("a", "http://h/a.mp4").also { it.setLastModified(1_000) }
        cache.ready("a", "http://h/a.mp4")
        assertTrue(a.lastModified() > 1_000)
    }

    @Test
    fun `clear empties the directory`() = runBlocking {
        val dir = tmp.newFolder("video5")
        val cache = cache(dir = dir)
        cache.ready("a", "http://h/a.mp4")
        cache.clear()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.media.AlertMediaFetcherTest' --tests 'com.gekkotron.oikos.alerts.media.VideoCacheTest'`
Expected: compilation FAIL (`Unresolved reference: AlertMediaFetcher`, `AlertImageCache`, `VideoCache`).

- [ ] **Step 4: Implement**

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/AlertMedia.kt`:

```kotlin
package com.gekkotron.oikos.alerts.media

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** The Coil request type for alert pictures. Only this type is routed through [MediaFetcher]; raw strings never are (spec §6.3). */
data class AlertMedia(val url: String)

/** Lets Coil's memory cache hold alert pictures: a data class without a keyer is not cached in memory. */
object AlertMediaKeyer : Keyer<AlertMedia> {
    override fun key(data: AlertMedia, options: Options): String = "alert-media:${data.url}"
}

/** Coil's disk cache, shared by the `ImageLoader` and the notifier so a picture is downloaded once (spec §5.2, §6.3). */
class AlertImageCache(val diskCache: DiskCache) {
    suspend fun put(url: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val editor = diskCache.openEditor(url) ?: return@withContext
        try {
            diskCache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
        } catch (e: java.io.IOException) {
            editor.abort()
        }
    }

    fun open(url: String): DiskCache.Snapshot? = diskCache.openSnapshot(url)

    fun clear() {
        diskCache.clear()
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024

        fun create(context: Context): AlertImageCache = AlertImageCache(
            DiskCache.Builder().directory(context.cacheDir.resolve("alerts/image").toOkioPath()).maxSizeBytes(MAX_BYTES).build(),
        )
    }
}

class AlertMediaFetcher(
    private val data: AlertMedia,
    private val source: MediaSource,
    private val cache: AlertImageCache,
    private val maxBytes: Long,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        cache.open(data.url)?.let { snapshot ->
            return SourceFetchResult(
                source = ImageSource(snapshot.data, cache.diskCache.fileSystem, diskCacheKey = data.url, closeable = snapshot),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
        val media = source.bytes(data.url, maxBytes).getOrThrow()
        cache.put(data.url, media.bytes)
        return SourceFetchResult(
            source = ImageSource(Buffer().apply { write(media.bytes) }, FileSystem.SYSTEM),
            mimeType = media.contentType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val source: MediaSource,
        private val cache: AlertImageCache,
        private val maxBytes: Long = DEFAULT_MAX_BYTES,
    ) : Fetcher.Factory<AlertMedia> {
        override fun create(data: AlertMedia, options: Options, imageLoader: ImageLoader): Fetcher =
            AlertMediaFetcher(data, source, cache, maxBytes)
    }

    companion object {
        /** A notification or thumbnail picture; a snapshot is far below this. */
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
    }
}
```

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/VideoCache.kt`:

```kotlin
package com.gekkotron.oikos.alerts.media

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface VideoState {
    data class Downloading(val read: Long, val total: Long?) : VideoState {
        /** 0..1, or null when the size is not known (the gateway answers in one piece). */
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (read.toFloat() / it).coerceIn(0f, 1f) }
    }

    data class Ready(val file: File) : VideoState

    data class Failed(val failure: MediaFailure) : VideoState
}

/** Clips downloaded before playback: LRU by access time under [capBytes], [clipLimit] per clip (spec §6.3). */
class VideoCache(
    private val dir: File,
    private val source: MediaSource,
    private val capBytes: Long = 200L * 1024 * 1024,
    private val clipLimit: Long = 25L * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Emits `Downloading` updates, then `Ready` (also at once on a hit) or `Failed`. */
    fun file(alertId: String, url: String): Flow<VideoState> = channelFlow {
        val target = withContext(io) { fileFor(alertId, url) }
        if (withContext(io) { touch(target) }) {
            send(VideoState.Ready(target))
            return@channelFlow
        }
        send(VideoState.Downloading(0, null))
        val result = source.bytes(url, clipLimit) { read, total -> trySend(VideoState.Downloading(read, total)) }
        result.fold(
            onSuccess = { media ->
                withContext(io) { store(target, media.bytes) }
                send(VideoState.Ready(target))
            },
            onFailure = { send(VideoState.Failed((it as? MediaException)?.failure ?: MediaFailure.Unavailable(it.message ?: "error"))) },
        )
    }

    /** Deletes every clip ("Set up again"). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(alertId: String, url: String): File {
        dir.mkdirs()
        val safe = alertId.replace(UNSAFE, "_").take(64)
        return File(dir, "$safe-${sha1(url).take(8)}.mp4")
    }

    /** True for a complete clip; it also becomes the most recently used. */
    private fun touch(file: File): Boolean = file.isFile && file.length() > 0 && file.setLastModified(System.currentTimeMillis())

    private fun store(target: File, bytes: ByteArray) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) error("could not move the clip into place")
        } finally {
            part.delete()
        }
        evict(keep = target)
    }

    private fun evict(keep: File) {
        val clips = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.sortedBy { it.lastModified() }.orEmpty().toMutableList()
        var total = clips.sumOf { it.length() }
        for (clip in clips) {
            if (total <= capBytes) break
            if (clip == keep) continue
            total -= clip.length()
            clip.delete()
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        fun create(context: Context, source: MediaSource): VideoCache = VideoCache(context.cacheDir.resolve("alerts/video"), source)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:alerts:testDebugUnitTest`
Expected: PASS (all of `:core:alerts`). In `the least recently used clip goes first` the two earlier clips have mtimes 1000 and 2000 and the new clip is stamped "now", so `a` is the oldest and is evicted (8 + 4 = 12 > 10).

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml core/alerts
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): Coil AlertMediaFetcher, shared image cache and clip LRU" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `:core:push` module and `OneShotPublisher`

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts` (Kover)
- Create: `core/push/build.gradle.kts`
- Create: `core/push/src/main/kotlin/com/gekkotron/oikos/push/OneShotPublisher.kt`, `PushTarget.kt`
- Test: `core/push/src/test/kotlin/com/gekkotron/oikos/push/OneShotPublisherTest.kt`

**Interfaces:**
- Consumes: `MqttTransport`/`ManagedTransport`/`LinkState`/`LocalProbe`/`TcpProbe`/`DirectMqttTransport`/`GatewayApi`/`TransportSpec`/`Route` (`:core:transport`).
- Produces (package `com.gekkotron.oikos.push`):
  - `fun interface TopicPublisher { suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit> }`
  - `class NotConfigured : Exception("no connection profile")`
  - `class OneShotPublisher(live: MqttTransport, specs: suspend () -> TransportSpec?, probe: (TransportSpec.Direct) -> LocalProbe = { TcpProbe(it.host, it.port) }, directTransport: (TransportSpec.Direct, CoroutineScope) -> ManagedTransport = { s, scope -> DirectMqttTransport(s, scope) }, gatewayApi: (TransportSpec.Gateway) -> GatewayApi, directBudget: Duration = 10.seconds) : TopicPublisher`
  - `sealed interface PushTarget { data object None; data object Demo; data class Ready(val installId: String, val tokenTopic: String) }`
  - `interface PushTargets { suspend fun current(): PushTarget; val changes: Flow<PushTarget> }`

Spec §5.3 route order, exactly: the app's live transport if it is `Connected`; otherwise by the stored spec: **auto** probes the local broker, and a reachable broker gets a throwaway `DirectMqttTransport` (wait up to 10 s for `Connected`, publish QoS 1, close), while an unreachable one, or any direct failure, goes to the gateway; **direct-only** and **gateway-only** use only their route; `Demo` or no spec is `NotConfigured`. A failed live publish falls through to the stored route rather than failing, because the live link may have dropped between the check and the publish. The caller passes a spec whose direct `clientId` differs from the live transport's (Task 9), otherwise the broker would kick the live client.

- [ ] **Step 1: Register the module and its build file**

`settings.gradle.kts`, after `include(":core:alerts")`:

```kotlin
include(":core:push")
```

Root `build.gradle.kts`, after `kover(project(":core:alerts"))`:

```kotlin
    kover(project(":core:push"))
```

`core/push/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.push"
}

dependencies {
    api(project(":core:alerts"))
    api(project(":core:transport"))
    implementation(project(":core:data"))
    testImplementation(testFixtures(project(":core:transport")))
}
```

- [ ] **Step 2: Write the failing test**

`core/push/src/test/kotlin/com/gekkotron/oikos/push/OneShotPublisherTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.LocalProbe
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.TransportSpec
import com.gekkotron.oikos.transport.testing.FakeGatewayApi
import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OneShotPublisherTest {
    private val direct = TransportSpec.Direct("192.168.1.91", 1883, false, false, "oikos", "pw", "oikos-abc-push")
    private val gateway = TransportSpec.Gateway("https://gw.example", ByteArray(32), "192.168.1.91", "oikos", "pw")
    private fun routed(route: Route) = TransportSpec.Routed(direct, gateway, route)

    private val live = FakeMqttTransport().apply { setLink(LinkState.Offline(LinkState.Offline.PAUSED)) }
    private val gatewayApi = FakeGatewayApi()
    private val directs = mutableListOf<FakeManagedTransport>()
    private var probeAnswer = true
    private var probes = 0
    private var directLink: LinkState = LinkState.Connected(TransportMode.DIRECT)
    private val payload = """{"token":"t"}""".encodeToByteArray()

    private fun publisher(spec: TransportSpec?) = OneShotPublisher(
        live = live,
        specs = { spec },
        probe = { LocalProbe { probes++; probeAnswer } },
        directTransport = { s, _ -> FakeManagedTransport(s).also { it.fake.setLink(directLink); directs += it } },
        gatewayApi = { gatewayApi },
    )

    private suspend fun OneShotPublisher.send(retain: Boolean = true) = publish("oikos-push/tokens/abc", payload, retain)

    @Test
    fun `a connected live transport is used first and nothing else is touched`() = runTest {
        live.setLink(LinkState.Connected(TransportMode.DIRECT))
        assertTrue(publisher(routed(Route.AUTO)).send().isSuccess)
        assertEquals(listOf("oikos-push/tokens/abc" to """{"token":"t"}"""), live.publishedStrings())
        assertEquals(true, live.published.single().retained)
        assertEquals(0, probes)
        assertTrue(directs.isEmpty() && gatewayApi.published.isEmpty())
    }

    @Test
    fun `a failing live publish falls through to the stored route`() = runTest {
        live.setLink(LinkState.Connected(TransportMode.DIRECT))
        live.failPublishes = true
        probeAnswer = false
        assertTrue(publisher(routed(Route.AUTO)).send().isSuccess)
        assertEquals(1, gatewayApi.published.size)
    }

    @Test
    fun `auto with a reachable broker publishes through a throwaway direct transport and closes it`() = runTest {
        assertTrue(publisher(routed(Route.AUTO)).send(retain = true).isSuccess)
        val transport = directs.single()
        assertTrue(transport.started)
        assertTrue(transport.fake.published.single().let { it.topic == "oikos-push/tokens/abc" })
        assertTrue(transport.closed)
        assertTrue(gatewayApi.published.isEmpty())
    }

    @Test
    fun `auto with an unreachable broker goes to the gateway without starting a direct transport`() = runTest {
        probeAnswer = false
        assertTrue(publisher(routed(Route.AUTO)).send().isSuccess)
        assertTrue(directs.isEmpty())
        val sent = gatewayApi.published.single()
        assertEquals("oikos-push/tokens/abc", sent.topic)
        assertEquals(true, sent.retained)
    }

    @Test
    fun `auto falls back to the gateway when the direct broker never answers within 10 s`() = runTest {
        directLink = LinkState.Connecting
        assertTrue(publisher(routed(Route.AUTO)).send().isSuccess)
        assertEquals(10_000, testScheduler.currentTime)
        assertTrue(directs.single().closed)
        assertEquals(1, gatewayApi.published.size)
    }

    @Test
    fun `auto falls back to the gateway at once when the broker rejects the credentials`() = runTest {
        directLink = LinkState.Offline(LinkState.Offline.REJECTED)
        assertTrue(publisher(routed(Route.AUTO)).send().isSuccess)
        assertEquals(0, testScheduler.currentTime)
        assertEquals(1, gatewayApi.published.size)
    }

    @Test
    fun `direct-only never uses the gateway, even when the direct publish fails`() = runTest {
        directLink = LinkState.Connecting
        val result = publisher(routed(Route.DIRECT)).send()
        assertTrue(result.isFailure)
        assertIs<PublishFailed>(result.exceptionOrNull())
        assertTrue(gatewayApi.published.isEmpty())
        assertEquals(0, probes)
    }

    @Test
    fun `gateway-only never probes or starts a direct transport`() = runTest {
        assertTrue(publisher(routed(Route.GATEWAY)).send().isSuccess)
        assertEquals(0, probes)
        assertTrue(directs.isEmpty())
        assertEquals(1, gatewayApi.published.size)
    }

    @Test
    fun `a plain direct or gateway spec uses its only route`() = runTest {
        assertTrue(publisher(direct).send().isSuccess)
        assertEquals(1, directs.size)
        assertTrue(publisher(gateway).send().isSuccess)
        assertEquals(1, gatewayApi.published.size)
    }

    @Test
    fun `a failing gateway publish is the result`() = runTest {
        probeAnswer = false
        gatewayApi.failPublish = "gateway key not accepted"
        val result = publisher(routed(Route.AUTO)).send()
        assertEquals("gateway key not accepted", result.exceptionOrNull()?.message)
    }

    @Test
    fun `the demo and no profile are NotConfigured and publish nothing`() = runTest {
        for (spec in listOf<TransportSpec?>(TransportSpec.Demo, null)) {
            assertIs<NotConfigured>(publisher(spec).send().exceptionOrNull())
        }
        assertTrue(live.published.isEmpty() && gatewayApi.published.isEmpty() && directs.isEmpty())
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.OneShotPublisherTest'`
Expected: compilation FAIL (`Unresolved reference: OneShotPublisher`, `NotConfigured`).

- [ ] **Step 4: Implement**

`core/push/src/main/kotlin/com/gekkotron/oikos/push/PushTarget.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.flow.Flow

/** Where a push registration goes, derived from the saved profile (push alerts spec §5.4). */
sealed interface PushTarget {
    /** No profile yet, or its secrets are locked. */
    data object None : PushTarget

    /** The demo house never registers (spec §7.6). */
    data object Demo : PushTarget

    data class Ready(val installId: String, val tokenTopic: String) : PushTarget
}

interface PushTargets {
    suspend fun current(): PushTarget

    val changes: Flow<PushTarget>
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/OneShotPublisher.kt`:

```kotlin
package com.gekkotron.oikos.push

import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.GatewayApi
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.LocalProbe
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TcpProbe
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface TopicPublisher {
    suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit>
}

class NotConfigured : Exception("no connection profile")

/**
 * Publishes one message from the background, without a `TransportSelector` (push alerts spec §5.3). Order: the live
 * transport when connected, then the stored profile's route. Always QoS 1.
 */
class OneShotPublisher(
    private val live: MqttTransport,
    private val specs: suspend () -> TransportSpec?,
    private val probe: (TransportSpec.Direct) -> LocalProbe = { TcpProbe(it.host, it.port) },
    private val directTransport: (TransportSpec.Direct, CoroutineScope) -> ManagedTransport = { spec, scope -> DirectMqttTransport(spec, scope) },
    private val gatewayApi: (TransportSpec.Gateway) -> GatewayApi,
    private val directBudget: Duration = 10.seconds,
) : TopicPublisher {
    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        if (live.link.value is LinkState.Connected && attempt { live.publish(topic, payload, QOS, retain) }.isSuccess) return Result.success(Unit)
        return when (val spec = specs()) {
            null, TransportSpec.Demo -> Result.failure(NotConfigured())
            is TransportSpec.Direct -> viaDirect(spec, topic, payload, retain)
            is TransportSpec.Gateway -> viaGateway(spec, topic, payload, retain)
            is TransportSpec.Routed -> when (spec.route) {
                Route.DIRECT -> viaDirect(spec.direct, topic, payload, retain)
                Route.GATEWAY -> viaGateway(spec.gateway, topic, payload, retain)
                Route.AUTO -> routedAuto(spec, topic, payload, retain)
            }
        }
    }

    private suspend fun routedAuto(spec: TransportSpec.Routed, topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        if (probe(spec.direct).reachable()) {
            val viaDirect = viaDirect(spec.direct, topic, payload, retain)
            if (viaDirect.isSuccess) return viaDirect
        }
        return viaGateway(spec.gateway, topic, payload, retain)
    }

    private suspend fun viaGateway(spec: TransportSpec.Gateway, topic: String, payload: ByteArray, retain: Boolean): Result<Unit> =
        attempt { gatewayApi(spec).publish(topic, payload, QOS, retain) }

    private suspend fun viaDirect(spec: TransportSpec.Direct, topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = directTransport(spec, scope)
        try {
            transport.start()
            val state = withTimeoutOrNull(directBudget) {
                transport.link.first { it is LinkState.Connected || (it is LinkState.Offline && it.reason == LinkState.Offline.REJECTED) }
            }
            return when {
                state is LinkState.Connected -> attempt { transport.publish(topic, payload, QOS, retain) }
                state == null -> Result.failure(PublishFailed("the broker did not answer in time"))
                else -> Result.failure(PublishFailed("the broker rejected the credentials"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            withContext(NonCancellable) {
                try {
                    transport.close()
                } catch (e: Exception) {
                    // close() is contractually quiet; a failure here must not hide the publish result.
                }
                scope.cancel()
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val QOS = 1
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.OneShotPublisherTest'`
Expected: PASS (11 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/push
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): OneShotPublisher with live, direct and gateway routes" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `PushRegistrar`, `RegisterWorker`, `RegistrationStore`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/push/build.gradle.kts`
- Create: `core/push/src/main/kotlin/com/gekkotron/oikos/push/FcmTokens.kt`, `RegistrationStore.kt`, `PushRegistrar.kt`, `RegisterWorker.kt`, `PushWorkerFactory.kt`
- Test: `core/push/src/test/kotlin/com/gekkotron/oikos/push/PushRegistrarTest.kt`, `RegisterWorkerTest.kt`, `RegistrationStoreTest.kt`, `PushFakes.kt`

**Interfaces:**
- Consumes: Task 5 `TopicPublisher`, `NotConfigured`, `PushTarget`, `PushTargets`; Task 1 `PushTopics`.
- Produces:
  - `interface FcmTokens { suspend fun token(): String; suspend fun delete() }`, `class FirebaseUnavailable : Exception`, `class FirebaseFcmTokens(context: Context) : FcmTokens`
  - `data class LastRegistration(val token: String, val topic: String, val at: Instant)`; `class RegistrationStore` with `fun observe(): Flow<LastRegistration?>`, `suspend fun last(): LastRegistration?`, `suspend fun save(token: String, topic: String, at: Instant)`, `suspend fun clear()`, `RegistrationStore.create(context: Context, scope: CoroutineScope)`, `RegistrationStore.create(file: File, scope: CoroutineScope)`
  - `interface RegistrationQueue { fun enqueue(); fun cancel(); val active: Flow<Boolean> }` and `suspend fun RegistrationQueue.isActive(): Boolean`
  - `class PushRegistrar(targets, tokens, publisher: TopicPublisher, store, queue, device: () -> PushRegistrar.DeviceInfo, clock: () -> Instant = Instant::now, unregisterBudget: Duration = 5.seconds)` with `data class DeviceInfo(name: String, lang: String)`, `sealed interface RegisterOutcome { Done; NotConfigured; Unavailable(reason); Retry(reason) }`, `val lastError: StateFlow<String?>`, `suspend fun register(): RegisterOutcome`, `fun request()`, `suspend fun onLinkConnected()`, `suspend fun unregister()`
  - `class RegisterWorker(context, params, registrar)`, `RegisterWorker.UNIQUE_NAME = "register"`, `class WorkManagerRegistrationQueue(workManager: () -> WorkManager) : RegistrationQueue`
  - `class PushWorkerFactory(registrar: PushRegistrar) : WorkerFactory` (extended in Task 7)

Semantics (spec §5.4): `register()` gets the FCM token, publishes `{"token","device_name","lang","updated_at"}` retained to the substituted token topic, and only a successful publish stores `lastRegistration`. `request()` is the forced path (new token, end of onboarding, "Register again"): unique work `register`, REPLACE. `onLinkConnected()` runs on each live connect: it re-enqueues only when the token or the topic differs from the last successful registration **and** no registration work is already pending, so a flapping link cannot keep restarting the exponential backoff (Review Focus 5). `unregister()` ("Set up again"): cancel the pending work first, then publish an empty retained payload (5 s cap), then delete the FCM token, then forget the local registration.

- [ ] **Step 1: Catalog and build**

`gradle/libs.versions.toml` `[versions]`:

```toml
work = "2.12.0"
```

`[libraries]`:

```toml
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
# Resolved by the Firebase BoM.
firebase-messaging = { module = "com.google.firebase:firebase-messaging" }
```

`core/push/build.gradle.kts` `dependencies { }` becomes:

```kotlin
dependencies {
    api(project(":core:alerts"))
    api(project(":core:transport"))
    implementation(project(":core:data"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(testFixtures(project(":core:transport")))
    testImplementation(libs.androidx.work.testing)
}
```

Work-runtime 2.12.0 needs `compileSdk` 35 and the firebase-messaging that BoM 34.19.0 selects (25.1.3) needs nothing newer; both are below the project's 36.

- [ ] **Step 2: Write the shared test fakes and the failing tests**

`core/push/src/test/kotlin/com/gekkotron/oikos/push/PushFakes.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeTargets(var target: PushTarget = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")) : PushTargets {
    override suspend fun current(): PushTarget = target
    override val changes: Flow<PushTarget> get() = flowOf(target)
}

class FakeFcmTokens(var token: String = "token-1", var failure: Exception? = null) : FcmTokens {
    var deleted = 0
    override suspend fun token(): String = failure?.let { throw it } ?: token
    override suspend fun delete() {
        deleted++
    }
}

class RecordingPublisher(var result: Result<Unit> = Result.success(Unit), val onPublish: suspend () -> Unit = {}) : TopicPublisher {
    class Sent(val topic: String, val payload: String, val retain: Boolean)

    val sent = mutableListOf<Sent>()
    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean): Result<Unit> {
        sent += Sent(topic, payload.decodeToString(), retain)
        onPublish()
        return result
    }
}

class FakeQueue(val events: MutableList<String> = mutableListOf()) : RegistrationQueue {
    val activeState = MutableStateFlow(false)
    var enqueued = 0
    override fun enqueue() {
        enqueued++
        events += "enqueue"
        activeState.value = true
    }

    override fun cancel() {
        events += "cancel"
        activeState.value = false
    }

    override val active: Flow<Boolean> get() = activeState
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/RegistrationStoreTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistrationStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `nothing is registered at first`() = runTest {
        assertNull(RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope).last())
    }

    @Test
    fun `a registration round-trips and clears`() = runTest {
        val store = RegistrationStore.create(tmp.root.resolve("r.json"), backgroundScope)
        store.save("tok", "oikos-push/tokens/a", Instant.ofEpochSecond(1_790_000_000))
        assertEquals(LastRegistration("tok", "oikos-push/tokens/a", Instant.ofEpochSecond(1_790_000_000)), store.last())
        store.clear()
        assertNull(store.last())
    }

    @Test
    fun `a corrupt file reads as not registered`() = runTest {
        val file = tmp.root.resolve("r.json").apply { writeText("{nope") }
        assertNull(RegistrationStore.create(file, backgroundScope).last())
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/PushRegistrarTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRegistrarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val targets = FakeTargets()
    private val tokens = FakeFcmTokens()
    private val publisher = RecordingPublisher()
    private val events = mutableListOf<String>()
    private val queue = FakeQueue(events)

    private fun TestScope.registrar(
        store: RegistrationStore = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope),
        pub: TopicPublisher = publisher,
    ) = PushRegistrar(targets, tokens, pub, store, queue, device = { PushRegistrar.DeviceInfo("Pixel 6a", "fr") }, clock = { now })

    private fun TestScope.newStore() = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope)

    @Test
    fun `register publishes the retained token payload to the substituted topic and remembers it`() = runTest {
        val store = newStore()
        assertEquals(PushRegistrar.RegisterOutcome.Done, registrar(store).register())
        val sent = publisher.sent.single()
        assertEquals("oikos-push/tokens/install-1", sent.topic)
        assertTrue(sent.retain)
        val json = Json.parseToJsonElement(sent.payload).jsonObject
        assertEquals("token-1", json["token"]!!.jsonPrimitive.content)
        assertEquals("Pixel 6a", json["device_name"]!!.jsonPrimitive.content)
        assertEquals("fr", json["lang"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000L, json["updated_at"]!!.jsonPrimitive.content.toLong())
        assertEquals(LastRegistration("token-1", "oikos-push/tokens/install-1", now), store.last())
    }

    @Test
    fun `a failed publish asks for a retry, keeps no registration and exposes a short reason`() = runTest {
        val store = newStore()
        publisher.result = Result.failure(IOException("broker did not answer in time"))
        val r = registrar(store)
        assertEquals(PushRegistrar.RegisterOutcome.Retry("broker did not answer in time"), r.register())
        assertNull(store.last())
        assertEquals("broker did not answer in time", r.lastError.value)
        publisher.result = Result.success(Unit)
        r.register()
        assertNull(r.lastError.value)
    }

    @Test
    fun `no profile or the demo finishes without retrying and publishes nothing`() = runTest {
        for (target in listOf(PushTarget.None, PushTarget.Demo)) {
            targets.target = target
            assertEquals(PushRegistrar.RegisterOutcome.NotConfigured, registrar().register())
        }
        assertTrue(publisher.sent.isEmpty())
    }

    @Test
    fun `a NotConfigured publish result also finishes without retrying`() = runTest {
        publisher.result = Result.failure(NotConfigured())
        assertEquals(PushRegistrar.RegisterOutcome.NotConfigured, registrar().register())
    }

    @Test
    fun `a build without Firebase is a terminal failure with a clear reason`() = runTest {
        tokens.failure = FirebaseUnavailable()
        val r = registrar()
        assertIs<PushRegistrar.RegisterOutcome.Unavailable>(r.register())
        assertEquals("Firebase is not configured in this build", r.lastError.value)
    }

    @Test
    fun `a token error from Firebase is retried`() = runTest {
        tokens.failure = IOException("SERVICE_NOT_AVAILABLE")
        assertEquals(PushRegistrar.RegisterOutcome.Retry("SERVICE_NOT_AVAILABLE"), registrar().register())
    }

    @Test
    fun `the topic template comes from the profile`() = runTest {
        targets.target = PushTarget.Ready("install-2", "custom/tokens/install-2")
        registrar().register()
        assertEquals("custom/tokens/install-2", publisher.sent.single().topic)
    }

    @Test
    fun `a connect republishes only when the token or the topic differs from the last registration`() = runTest {
        val store = newStore()
        store.save("token-1", "oikos-push/tokens/install-1", now)
        val r = registrar(store)
        r.onLinkConnected()
        assertEquals(0, queue.enqueued)
        tokens.token = "token-2"
        r.onLinkConnected()
        assertEquals(1, queue.enqueued)
        queue.activeState.value = false
        tokens.token = "token-1"
        targets.target = PushTarget.Ready("install-1", "other/tokens/install-1")
        r.onLinkConnected()
        assertEquals(2, queue.enqueued)
    }

    @Test
    fun `a first connect with no registration yet enqueues one`() = runTest {
        registrar().onLinkConnected()
        assertEquals(1, queue.enqueued)
    }

    @Test
    fun `a connect with no profile, the demo or no Firebase enqueues nothing`() = runTest {
        val r = registrar()
        targets.target = PushTarget.None
        r.onLinkConnected()
        targets.target = PushTarget.Demo
        r.onLinkConnected()
        targets.target = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")
        tokens.failure = FirebaseUnavailable()
        r.onLinkConnected()
        assertEquals(0, queue.enqueued)
    }

    @Test
    fun `a reconnect while registration is still retrying does not reset its backoff`() = runTest {
        val r = registrar()
        r.request()
        assertEquals(1, queue.enqueued)
        // The link flaps while the worker is waiting out its backoff: each connect sees the same difference.
        repeat(5) { r.onLinkConnected() }
        assertEquals(1, queue.enqueued)
        // A new FCM token or "Register again" is explicit and does replace the pending work.
        r.request()
        assertEquals(2, queue.enqueued)
    }

    @Test
    fun `unregister cancels pending work before publishing, then deletes the token and forgets the registration`() = runTest {
        val store = newStore()
        store.save("token-1", "oikos-push/tokens/install-1", now)
        val pub = RecordingPublisher(onPublish = { events += "publish" })
        registrar(store, pub).unregister()
        assertEquals(listOf("cancel", "publish"), events)
        val sent = pub.sent.single()
        assertEquals("oikos-push/tokens/install-1", sent.topic)
        assertEquals("", sent.payload)
        assertTrue(sent.retain)
        assertEquals(1, tokens.deleted)
        assertNull(store.last())
    }

    @Test
    fun `unregister gives up on a publish that hangs after 5 seconds but still finishes the cleanup`() = runTest {
        val store = newStore()
        store.save("token-1", "oikos-push/tokens/install-1", now)
        val hanging = RecordingPublisher(onPublish = { delay(60_000) })
        registrar(store, hanging).unregister()
        assertEquals(5_000, testScheduler.currentTime)
        assertEquals(1, tokens.deleted)
        assertNull(store.last())
    }

    @Test
    fun `unregister with no profile still cancels, deletes and clears`() = runTest {
        targets.target = PushTarget.None
        registrar().unregister()
        assertTrue(publisher.sent.isEmpty())
        assertEquals(1, tokens.deleted)
        assertEquals(listOf("cancel"), events)
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/RegisterWorkerTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RegisterWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val targets = FakeTargets()
    private val tokens = FakeFcmTokens()
    private val publisher = RecordingPublisher()

    private fun run(): Result = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, scope)
        val registrar = PushRegistrar(targets, tokens, publisher, store, FakeQueue(), { PushRegistrar.DeviceInfo("Pixel", "en") }, { Instant.EPOCH })
        TestListenableWorkerBuilder<RegisterWorker>(context).setWorkerFactory(PushWorkerFactory(registrar)).build().doWork()
    }

    @Test
    fun `a published registration is a success`() {
        assertEquals(Result.success(), run())
    }

    @Test
    fun `a publish failure is retried with the worker backoff`() {
        publisher.result = kotlin.Result.failure(IOException("offline"))
        assertEquals(Result.retry(), run())
    }

    @Test
    fun `no profile finishes without a retry`() {
        targets.target = PushTarget.None
        assertEquals(Result.success(), run())
    }

    @Test
    fun `a build without Firebase fails for good`() {
        tokens.failure = FirebaseUnavailable()
        assertEquals(Result.failure(), run())
    }

    @Test
    fun `the unique name is register`() {
        assertEquals("register", RegisterWorker.UNIQUE_NAME)
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.PushRegistrarTest' --tests 'com.gekkotron.oikos.push.RegisterWorkerTest' --tests 'com.gekkotron.oikos.push.RegistrationStoreTest'`
Expected: compilation FAIL (`Unresolved reference: PushRegistrar`, `RegistrationStore`, `FcmTokens`...).

- [ ] **Step 4: Implement**

`core/push/src/main/kotlin/com/gekkotron/oikos/push/FcmTokens.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The FCM token behind a seam, so unit tests need no Firebase. */
interface FcmTokens {
    /** @throws FirebaseUnavailable in a build without a Firebase configuration. */
    suspend fun token(): String

    suspend fun delete()
}

/** Debug builds carry no `google-services.json` (the plugin task is disabled), so there is no FirebaseApp. */
class FirebaseUnavailable : Exception("Firebase is not configured in this build")

class FirebaseFcmTokens(private val context: Context) : FcmTokens {
    override suspend fun token(): String {
        if (FirebaseApp.getApps(context).isEmpty()) throw FirebaseUnavailable()
        return FirebaseMessaging.getInstance().token.await()
    }

    override suspend fun delete() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/RegistrationStore.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

@Serializable
internal data class StoredRegistration(val token: String = "", val topic: String = "", val at: Long = 0)

/** The last registration that reached the broker (push alerts spec §5.4). */
data class LastRegistration(val token: String, val topic: String, val at: Instant)

/** Kept apart from the profile and preferences so "Set up again" can forget it on its own. */
class RegistrationStore internal constructor(private val store: DataStore<StoredRegistration>) {
    fun observe(): Flow<LastRegistration?> = store.data.map { stored ->
        stored.takeIf { it.token.isNotBlank() }?.let { LastRegistration(it.token, it.topic, Instant.ofEpochSecond(it.at)) }
    }

    suspend fun last(): LastRegistration? = observe().first()

    suspend fun save(token: String, topic: String, at: Instant) {
        store.updateData { StoredRegistration(token, topic, at.epochSecond) }
    }

    suspend fun clear() {
        store.updateData { StoredRegistration() }
    }

    companion object {
        const val FILE = "datastore/push-registration.json"

        fun create(context: Context, scope: CoroutineScope): RegistrationStore = create(context.filesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): RegistrationStore = RegistrationStore(
            DataStoreFactory.create(
                serializer = RegistrationSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredRegistration() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            ),
        )
    }
}

internal object RegistrationSerializer : Serializer<StoredRegistration> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredRegistration = StoredRegistration()

    override suspend fun readFrom(input: InputStream): StoredRegistration = try {
        json.decodeFromString(StoredRegistration.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable registration", e)
    }

    override suspend fun writeTo(t: StoredRegistration, output: OutputStream) {
        output.write(json.encodeToString(StoredRegistration.serializer(), t).encodeToByteArray())
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/PushRegistrar.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** WorkManager behind a seam: unique work `register`, REPLACE (push alerts spec §5.4). */
interface RegistrationQueue {
    fun enqueue()

    fun cancel()

    /** True while registration work is enqueued, running or waiting out a backoff. */
    val active: Flow<Boolean>
}

suspend fun RegistrationQueue.isActive(): Boolean = active.first()

/** Spec §5.4: when and how this install tells OikosPush its FCM token. */
class PushRegistrar(
    private val targets: PushTargets,
    private val tokens: FcmTokens,
    private val publisher: TopicPublisher,
    private val store: RegistrationStore,
    private val queue: RegistrationQueue,
    private val device: () -> DeviceInfo,
    private val clock: () -> Instant = Instant::now,
    private val unregisterBudget: Duration = 5.seconds,
) {
    data class DeviceInfo(val name: String, val lang: String)

    sealed interface RegisterOutcome {
        data object Done : RegisterOutcome

        /** No profile, the demo, or no route at all: finish without retrying. */
        data object NotConfigured : RegisterOutcome

        /** Terminal: retrying cannot help (a build without Firebase). */
        data class Unavailable(val reason: String) : RegisterOutcome

        data class Retry(val reason: String) : RegisterOutcome
    }

    private val _lastError = MutableStateFlow<String?>(null)

    /** The last attempt's short, secret-free message; null after a success. Read by `PushStatus`. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The worker's body. */
    suspend fun register(): RegisterOutcome {
        val target = targets.current() as? PushTarget.Ready ?: return RegisterOutcome.NotConfigured
        val token = try {
            tokens.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseUnavailable) {
            return failed(RegisterOutcome.Unavailable(e.message.orEmpty()))
        } catch (e: Exception) {
            return failed(RegisterOutcome.Retry(reasonOf(e)))
        }
        val info = device()
        val payload = buildJsonObject {
            put("token", token)
            put("device_name", info.name)
            put("lang", info.lang)
            put("updated_at", clock().epochSecond)
        }
        val result = publisher.publish(target.tokenTopic, payload.toString().encodeToByteArray(), retain = true)
        val error = result.exceptionOrNull()
        return when {
            error == null -> {
                store.save(token, target.tokenTopic, clock())
                _lastError.value = null
                RegisterOutcome.Done
            }
            error is NotConfigured -> RegisterOutcome.NotConfigured
            else -> failed(RegisterOutcome.Retry(reasonOf(error)))
        }
    }

    /** A new FCM token, the end of onboarding or "Register again": replaces any pending work. */
    fun request() = queue.enqueue()

    /**
     * Every time the live transport connects. Re-enqueues only when the token or the topic differs from the last
     * successful registration, and never over work that is already pending: REPLACE would restart its backoff on
     * every flap of the link.
     */
    suspend fun onLinkConnected() {
        val target = targets.current() as? PushTarget.Ready ?: return
        val token = try {
            tokens.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // register() reports it when it runs; a connect has nothing to add
        }
        val last = store.last()
        if (last != null && last.token == token && last.topic == target.tokenTopic) return
        if (!queue.isActive()) queue.enqueue()
    }

    /**
     * "Set up again", before the profile is wiped. Order matters: pending registration work is cancelled first so
     * it cannot publish the token again after the empty payload; the publish is best effort and capped.
     */
    suspend fun unregister() {
        queue.cancel()
        (targets.current() as? PushTarget.Ready)?.let { target ->
            try {
                withTimeoutOrNull(unregisterBudget) { publisher.publish(target.tokenTopic, ByteArray(0), retain = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the rest of the cleanup must still run.
            }
        }
        try {
            tokens.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the token is useless to OikosPush once its retained entry is gone.
        }
        store.clear()
        _lastError.value = null
    }

    private fun failed(outcome: RegisterOutcome): RegisterOutcome {
        _lastError.value = when (outcome) {
            is RegisterOutcome.Retry -> outcome.reason
            is RegisterOutcome.Unavailable -> outcome.reason
            else -> null
        }
        return outcome
    }

    private fun reasonOf(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(MAX_REASON)

    private companion object {
        const val MAX_REASON = 80
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/RegisterWorker.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class RegisterWorker(
    context: Context,
    params: WorkerParameters,
    private val registrar: PushRegistrar,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (registrar.register()) {
        PushRegistrar.RegisterOutcome.Done, PushRegistrar.RegisterOutcome.NotConfigured -> Result.success()
        is PushRegistrar.RegisterOutcome.Unavailable -> Result.failure()
        is PushRegistrar.RegisterOutcome.Retry -> Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "register"
    }
}

/** Unique work `register`, REPLACE, network required, exponential backoff from 30 s (spec §5.4). */
class WorkManagerRegistrationQueue(private val workManager: () -> WorkManager) : RegistrationQueue {
    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<RegisterWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager().enqueueUniqueWork(RegisterWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager().cancelUniqueWork(RegisterWorker.UNIQUE_NAME)
    }

    override val active: Flow<Boolean> = flow {
        emitAll(workManager().getWorkInfosForUniqueWorkFlow(RegisterWorker.UNIQUE_NAME).map { infos -> infos.any { !it.state.isFinished } })
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/PushWorkerFactory.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/** Builds the push workers with their dependencies; the app installs it as WorkManager's factory (Task 9). */
class PushWorkerFactory(private val registrar: PushRegistrar) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            RegisterWorker::class.java.name -> RegisterWorker(appContext, workerParameters, registrar)
            else -> null
        }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:push:testDebugUnitTest`
Expected: PASS. In `the unregister gives up on a publish that hangs` the virtual clock reads exactly 5000 ms because `withTimeoutOrNull(5.seconds)` is the only delay.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml core/push
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): PushRegistrar, RegisterWorker and the registration store" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Channels, reply queue, `ReplyWorker`, `ReplyReceiver`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/push/build.gradle.kts`, `core/push/src/main/kotlin/com/gekkotron/oikos/push/PushWorkerFactory.kt`
- Create: `core/push/src/main/AndroidManifest.xml`, `core/push/src/main/res/drawable/ic_stat_oikos.xml`, `core/push/src/main/res/values/strings.xml`, `core/push/src/main/res/values-fr/strings.xml`
- Create: `core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertChannels.kt`, `ReplyQueue.kt`, `ReplyWorker.kt`, `ReplyReceiver.kt`, `ReplyFailureNotifier.kt`, `PushComponent.kt`
- Test: `core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertChannelsTest.kt`, `ReplyQueueTest.kt`, `ReplyWorkerTest.kt`, `ReplyReceiverTest.kt`, `TestPushApp.kt`

**Interfaces:**
- Consumes: Task 5 `TopicPublisher`, `NotConfigured`, `PushTargets`, `PushTarget`; Task 6 `PushRegistrar`, `PushWorkerFactory`; Task 1 `AlertChannel`, `PushTopics`.
- Produces:
  - `object NotificationIds { ALERT = 1; REPLY_FAILED = 2; REPLY_PROGRESS = 3 }`
  - `class AlertChannels(context: Context) { fun ensure() }` with `companion { fun importance(channel: AlertChannel): Int; @ColorInt fun accent(channel: AlertChannel): Int; @StringRes fun nameRes(channel: AlertChannel): Int; @StringRes fun descriptionRes(channel: AlertChannel): Int }`
  - `enum class ReplyState { PENDING, SENT, FAILED }`; `interface ReplyQueue { fun enqueue(alertId: String, actionId: String, label: String): UUID; fun state(id: UUID): Flow<ReplyState>; fun cancelAll() }`; `class WorkManagerReplyQueue(workManager: () -> WorkManager, clock: () -> Instant = Instant::now) : ReplyQueue`; `internal fun replyRequest(alertId: String, actionId: String, label: String, ts: Long): OneTimeWorkRequest`
  - `class ReplyWorker(context, params, publisher: TopicPublisher, targets: PushTargets, failures: ReplyFailureNotifier)` with `ReplyWorker.TAG = "reply"`, `KEY_ALERT`, `KEY_ACTION`, `KEY_LABEL`, `KEY_TS`, `MAX_ATTEMPTS = 5`
  - `class ReplyFailureNotifier(context: Context, canPost: () -> Boolean = ...) { fun couldNotSend(alertId: String, label: String) }`
  - `class ReplyReceiver : BroadcastReceiver` with `ACTION_REPLY`, `EXTRA_ALERT_ID`, `EXTRA_ACTION_ID`, `EXTRA_LABEL`
  - `interface PushComponent { val registrar: PushRegistrar; val replies: ReplyQueue }`, `interface PushComponentProvider { fun pushComponent(): PushComponent }` (Task 9 adds `ingestor`)
  - `PushWorkerFactory(registrar, publisher: TopicPublisher, targets: PushTargets, failures: ReplyFailureNotifier)`

Semantics (spec §5.1, §5.5): the receiver dismisses the alert's notification, then enqueues the reply as unique work `reply-<alertId>-<actionId>` with `KEEP`, so a second tap on the same button while the first is still pending is a no-op. The request carries the tap time as `ts` in its input data, so every retry publishes the same `{"id","action","install_id","ts"}`, which lets a producer drop a duplicate (Review Focus 4). Expedited with `RUN_AS_NON_EXPEDITED_WORK_REQUEST`, network required, exponential backoff from 10 s. Five attempts in total: attempts 0..3 return `retry()`, the fifth fails, posts "Couldn't send *label*" on `system` and returns `failure()`. `NotConfigured` (demo, no profile) finishes quietly. On Android 9 to 11 an expedited request runs as a foreground service, so the worker implements `getForegroundInfo()`.

- [ ] **Step 1: Catalog, build, manifest, icon, strings**

`gradle/libs.versions.toml` `[versions]`: `androidxCore = "1.18.0"`; `[libraries]`:

```toml
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
```

1.18.0 is the newest `core` that compiles against `compileSdk` 36 (1.19 needs 37).

`core/push/build.gradle.kts` `dependencies { }` gains `implementation(libs.androidx.core.ktx)`.

`core/push/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application>
        <!-- Notification action buttons (push alerts spec §5.2). Not exported: only our own PendingIntents reach it. -->
        <receiver
            android:name="com.gekkotron.oikos.push.ReplyReceiver"
            android:exported="false" />
    </application>
</manifest>
```

`core/push/src/main/res/drawable/ic_stat_oikos.xml` (monochrome house, from the Oikos glyph):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,3L3,11h2v9h5v-6h4v6h5v-9h2z" />
</vector>
```

`core/push/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="push_channel_alarm">Alarm</string>
    <string name="push_channel_alarm_desc">The alarm system: armed, disarmed, triggered, siren</string>
    <string name="push_channel_security">Security</string>
    <string name="push_channel_security_desc">Doors, windows, motion, leaks and smoke</string>
    <string name="push_channel_camera">Cameras</string>
    <string name="push_channel_camera_desc">Camera detections and snapshots</string>
    <string name="push_channel_energy">Energy</string>
    <string name="push_channel_energy_desc">Solar, grid and consumption</string>
    <string name="push_channel_climate">Climate</string>
    <string name="push_channel_climate_desc">Heating, cooling and temperature</string>
    <string name="push_channel_system">System</string>
    <string name="push_channel_system_desc">Service status and messages from Oikos</string>
    <string name="push_channel_misc">Other</string>
    <string name="push_channel_misc_desc">Everything else</string>
    <string name="push_reply_failed">Couldn\'t send %1$s</string>
    <string name="push_sending">Sending %1$s…</string>
</resources>
```

`core/push/src/main/res/values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="push_channel_alarm">Alarme</string>
    <string name="push_channel_alarm_desc">Le système d\'alarme : armée, désarmée, déclenchée, sirène</string>
    <string name="push_channel_security">Sécurité</string>
    <string name="push_channel_security_desc">Portes, fenêtres, mouvements, fuites et fumée</string>
    <string name="push_channel_camera">Caméras</string>
    <string name="push_channel_camera_desc">Détections et images des caméras</string>
    <string name="push_channel_energy">Énergie</string>
    <string name="push_channel_energy_desc">Solaire, réseau et consommation</string>
    <string name="push_channel_climate">Climat</string>
    <string name="push_channel_climate_desc">Chauffage, climatisation et température</string>
    <string name="push_channel_system">Système</string>
    <string name="push_channel_system_desc">État des services et messages d\'Oikos</string>
    <string name="push_channel_misc">Autres</string>
    <string name="push_channel_misc_desc">Tout le reste</string>
    <string name="push_reply_failed">Impossible d\'envoyer %1$s</string>
    <string name="push_sending">Envoi de %1$s…</string>
</resources>
```

- [ ] **Step 2: Write the failing tests**

`core/push/src/test/kotlin/com/gekkotron/oikos/push/TestPushApp.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.app.Application

/** The application Robolectric tests of the service and receivers run under. */
class TestPushApp : Application(), PushComponentProvider {
    lateinit var component: PushComponent

    override fun pushComponent(): PushComponent = component
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertChannelsTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class AlertChannelsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `ensure creates the seven channels with their importance`() {
        AlertChannels(context).ensure()
        val expected = mapOf(
            "alarm" to NotificationManager.IMPORTANCE_HIGH,
            "security" to NotificationManager.IMPORTANCE_HIGH,
            "camera" to NotificationManager.IMPORTANCE_HIGH,
            "energy" to NotificationManager.IMPORTANCE_DEFAULT,
            "climate" to NotificationManager.IMPORTANCE_DEFAULT,
            "system" to NotificationManager.IMPORTANCE_DEFAULT,
            "misc" to NotificationManager.IMPORTANCE_DEFAULT,
        )
        assertEquals(expected.keys, manager.notificationChannels.map { it.id }.toSet())
        expected.forEach { (id, importance) -> assertEquals(importance, manager.getNotificationChannel(id).importance, id) }
    }

    @Test
    fun `names and descriptions come from resources`() {
        AlertChannels(context).ensure()
        assertEquals("Alarm", manager.getNotificationChannel("alarm").name.toString())
        assertNotNull(manager.getNotificationChannel("camera").description)
        assertEquals("Other", manager.getNotificationChannel("misc").name.toString())
    }

    @Test
    fun `ensure is safe to run on every launch`() {
        AlertChannels(context).ensure()
        AlertChannels(context).ensure()
        assertEquals(7, NotificationManagerCompat.from(context).notificationChannels.size)
    }

    @Test
    fun `every channel has its own name, description and an opaque accent`() {
        val names = AlertChannel.entries.map { AlertChannels.nameRes(it) }
        assertEquals(names.size, names.toSet().size)
        AlertChannel.entries.forEach { assertEquals(0xFF, AlertChannels.accent(it) ushr 24, it.id) }
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/ReplyQueueTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReplyQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the request carries the alert, the action, the label and the tap time`() {
        val request = replyRequest("frigate-1", "disarm", "Disarm", ts = 1_790_000_000L)
        val data = request.workSpec.input
        assertEquals("frigate-1", data.getString(ReplyWorker.KEY_ALERT))
        assertEquals("disarm", data.getString(ReplyWorker.KEY_ACTION))
        assertEquals("Disarm", data.getString(ReplyWorker.KEY_LABEL))
        assertEquals(1_790_000_000L, data.getLong(ReplyWorker.KEY_TS, 0))
        assertTrue(ReplyWorker.TAG in request.tags)
        assertTrue(request.workSpec.expedited)
    }

    @Test
    fun `a retried reply keeps its ts and a double tap enqueues once`() = runBlocking {
        // Retries: the same input data (and so the same ts) is published on every attempt.
        val publisher = RecordingPublisher(result = Result.failure(IOException("offline")))
        val failures = ReplyFailureNotifier(context, canPost = { false })
        val registrar = PushRegistrar(FakeTargets(), FakeFcmTokens(), publisher, RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), FakeQueue(), { PushRegistrar.DeviceInfo("p", "en") })
        val factory = PushWorkerFactory(registrar, publisher, FakeTargets(), failures)
        val input = replyRequest("frigate-1", "disarm", "Disarm", ts = 1_790_000_000L).workSpec.input
        for (attempt in 0..2) {
            val result = TestListenableWorkerBuilder<ReplyWorker>(context).setWorkerFactory(factory).setInputData(input).setRunAttemptCount(attempt).build().doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }
        assertEquals(1, publisher.sent.map { it.payload }.toSet().size)

        // Double tap: the second enqueue is KEEP, so one work item exists.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val queue = WorkManagerReplyQueue({ WorkManager.getInstance(context) }, clock = { Instant.ofEpochSecond(1_790_000_000L) })
        val first = queue.enqueue("frigate-1", "disarm", "Disarm")
        val second = queue.enqueue("frigate-1", "disarm", "Disarm")
        assertNotEquals(first, second)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("reply-frigate-1-disarm").get().size)
        // Another button on the same alert is separate work.
        queue.enqueue("frigate-1", "silence", "Silence")
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("reply-frigate-1-silence").get().size)
    }

    @Test
    fun `work states map to reply states`() {
        assertEquals(ReplyState.PENDING, null.toReplyState())
        assertEquals(ReplyState.PENDING, WorkInfo.State.ENQUEUED.toReplyState())
        assertEquals(ReplyState.PENDING, WorkInfo.State.RUNNING.toReplyState())
        assertEquals(ReplyState.SENT, WorkInfo.State.SUCCEEDED.toReplyState())
        assertEquals(ReplyState.FAILED, WorkInfo.State.FAILED.toReplyState())
        assertEquals(ReplyState.FAILED, WorkInfo.State.CANCELLED.toReplyState())
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/ReplyWorkerTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReplyWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val publisher = RecordingPublisher()
    private val targets = FakeTargets()
    private val input: Data = workDataOf(
        ReplyWorker.KEY_ALERT to "frigate-1", ReplyWorker.KEY_ACTION to "disarm", ReplyWorker.KEY_LABEL to "Disarm", ReplyWorker.KEY_TS to 1_790_000_000L,
    )

    private fun run(attempt: Int = 0, data: Data = input): Result = runBlocking {
        AlertChannels(context).ensure()
        val registrar = PushRegistrar(targets, FakeFcmTokens(), publisher, RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), FakeQueue(), { PushRegistrar.DeviceInfo("p", "en") })
        val factory = PushWorkerFactory(registrar, publisher, targets, ReplyFailureNotifier(context))
        TestListenableWorkerBuilder<ReplyWorker>(context).setWorkerFactory(factory).setInputData(data).setRunAttemptCount(attempt).build().doWork()
    }

    @Test
    fun `it publishes the answer to the responses topic, not retained`() {
        assertEquals(Result.success(), run())
        val sent = publisher.sent.single()
        assertEquals("oikos-push/responses", sent.topic)
        assertFalse(sent.retain)
        val json = Json.parseToJsonElement(sent.payload).jsonObject
        assertEquals("frigate-1", json["id"]!!.jsonPrimitive.content)
        assertEquals("disarm", json["action"]!!.jsonPrimitive.content)
        assertEquals("install-1", json["install_id"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000L, json["ts"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `a failing publish is retried for the first four attempts`() {
        publisher.result = kotlin.Result.failure(IOException("offline"))
        for (attempt in 0..3) assertEquals(Result.retry(), run(attempt), "attempt ${attempt + 1}")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `the fifth failure posts Couldn't send on system and gives up`() {
        publisher.result = kotlin.Result.failure(IOException("offline"))
        assertEquals(Result.failure(), run(attempt = 4))
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("system", notification.channelId)
        assertEquals("Couldn't send Disarm", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `no profile or the demo finishes without a retry or a notification`() {
        publisher.result = kotlin.Result.failure(NotConfigured())
        assertEquals(Result.success(), run(attempt = 4))
        targets.target = PushTarget.Demo
        assertEquals(Result.success(), run())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `missing input fails without publishing`() {
        assertEquals(Result.failure(), run(data = Data.EMPTY))
        assertNull(publisher.sent.firstOrNull())
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/ReplyReceiverTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(application = TestPushApp::class)
class ReplyReceiverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingReplies : ReplyQueue {
        val enqueued = mutableListOf<Triple<String, String, String>>()
        override fun enqueue(alertId: String, actionId: String, label: String): UUID {
            enqueued += Triple(alertId, actionId, label)
            return UUID.randomUUID()
        }

        override fun state(id: UUID): Flow<ReplyState> = flowOf(ReplyState.PENDING)

        override fun cancelAll() = Unit
    }

    private val app = RuntimeEnvironment.getApplication() as TestPushApp
    private val replies = RecordingReplies()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun component() = object : PushComponent {
        override val registrar = PushRegistrar(
            FakeTargets(), FakeFcmTokens(), RecordingPublisher(),
            RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), FakeQueue(), { PushRegistrar.DeviceInfo("p", "en") },
        )
        override val replies: ReplyQueue = this@ReplyReceiverTest.replies
    }

    @Test
    fun `a tap dismisses the notification and enqueues the reply`() {
        app.component = component()
        AlertChannels(app).ensure()
        val posted = Notification.Builder(app, "alarm").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        NotificationManagerCompat.from(app).notify("frigate-1", NotificationIds.ALERT, posted)
        assertEquals(1, shadowOf(manager).allNotifications.size)

        ReplyReceiver().onReceive(
            app,
            Intent(ReplyReceiver.ACTION_REPLY)
                .putExtra(ReplyReceiver.EXTRA_ALERT_ID, "frigate-1")
                .putExtra(ReplyReceiver.EXTRA_ACTION_ID, "disarm")
                .putExtra(ReplyReceiver.EXTRA_LABEL, "Disarm"),
        )

        assertNull(shadowOf(manager).getNotification("frigate-1", NotificationIds.ALERT))
        assertEquals(listOf(Triple("frigate-1", "disarm", "Disarm")), replies.enqueued)
    }

    @Test
    fun `an intent without an alert or action is ignored`() {
        app.component = component()
        ReplyReceiver().onReceive(app, Intent(ReplyReceiver.ACTION_REPLY).putExtra(ReplyReceiver.EXTRA_ALERT_ID, "frigate-1"))
        assertEquals(emptyList(), replies.enqueued)
    }

    @Test
    fun `the receiver class name matches its manifest entry`() {
        assertEquals("com.gekkotron.oikos.push.ReplyReceiver", ReplyReceiver::class.java.name)
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.AlertChannelsTest' --tests 'com.gekkotron.oikos.push.ReplyQueueTest' --tests 'com.gekkotron.oikos.push.ReplyWorkerTest' --tests 'com.gekkotron.oikos.push.ReplyReceiverTest'`
Expected: compilation FAIL (`Unresolved reference: AlertChannels`, `ReplyWorker`, `ReplyReceiver`, `PushComponent`).

- [ ] **Step 4: Implement**

`core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertChannels.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.gekkotron.oikos.alerts.AlertChannel

object NotificationIds {
    /** Every alert notification; the alert id is the tag, so the same id replaces (spec §5.2). */
    const val ALERT = 1
    const val REPLY_FAILED = 2
    const val REPLY_PROGRESS = 3
}

/** The seven Android notification channels (push alerts spec §5.1). Run [ensure] in `Application.onCreate`. */
class AlertChannels(private val context: Context) {
    fun ensure() {
        val manager = NotificationManagerCompat.from(context)
        AlertChannel.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(channel.id, importance(channel))
                    .setName(context.getString(nameRes(channel)))
                    .setDescription(context.getString(descriptionRes(channel)))
                    .build(),
            )
        }
    }

    companion object {
        /** HIGH for the alarm, security and camera channels; DEFAULT for the rest. */
        fun importance(channel: AlertChannel): Int = when (channel) {
            AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA -> NotificationManagerCompat.IMPORTANCE_HIGH
            AlertChannel.ENERGY, AlertChannel.CLIMATE, AlertChannel.SYSTEM, AlertChannel.MISC -> NotificationManagerCompat.IMPORTANCE_DEFAULT
        }

        /** The one small icon is monochrome; the channel only changes its accent colour. */
        @ColorInt
        fun accent(channel: AlertChannel): Int = when (channel) {
            AlertChannel.ALARM -> 0xFFE5484D.toInt()
            AlertChannel.SECURITY -> 0xFFF76B15.toInt()
            AlertChannel.CAMERA -> 0xFF3E63DD.toInt()
            AlertChannel.ENERGY -> 0xFFFFB224.toInt()
            AlertChannel.CLIMATE -> 0xFF12A594.toInt()
            AlertChannel.SYSTEM -> 0xFF8B8D98.toInt()
            AlertChannel.MISC -> 0xFF6E6F78.toInt()
        }

        @StringRes
        fun nameRes(channel: AlertChannel): Int = when (channel) {
            AlertChannel.ALARM -> R.string.push_channel_alarm
            AlertChannel.SECURITY -> R.string.push_channel_security
            AlertChannel.CAMERA -> R.string.push_channel_camera
            AlertChannel.ENERGY -> R.string.push_channel_energy
            AlertChannel.CLIMATE -> R.string.push_channel_climate
            AlertChannel.SYSTEM -> R.string.push_channel_system
            AlertChannel.MISC -> R.string.push_channel_misc
        }

        @StringRes
        fun descriptionRes(channel: AlertChannel): Int = when (channel) {
            AlertChannel.ALARM -> R.string.push_channel_alarm_desc
            AlertChannel.SECURITY -> R.string.push_channel_security_desc
            AlertChannel.CAMERA -> R.string.push_channel_camera_desc
            AlertChannel.ENERGY -> R.string.push_channel_energy_desc
            AlertChannel.CLIMATE -> R.string.push_channel_climate_desc
            AlertChannel.SYSTEM -> R.string.push_channel_system_desc
            AlertChannel.MISC -> R.string.push_channel_misc_desc
        }
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/PushComponent.kt`:

```kotlin
package com.gekkotron.oikos.push

/**
 * What the Android entry points (service, receivers) need. `:core:push` has no Hilt: `:app` builds the objects and
 * `OikosApplication` hands them out through [PushComponentProvider].
 */
interface PushComponent {
    val registrar: PushRegistrar
    val replies: ReplyQueue
}

interface PushComponentProvider {
    fun pushComponent(): PushComponent
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/ReplyQueue.kt`:

```kotlin
package com.gekkotron.oikos.push

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ReplyState { PENDING, SENT, FAILED }

/** Answers to notification and sheet action buttons (push alerts spec §5.5). */
interface ReplyQueue {
    /**
     * A second call for the same alert and action while one is pending does nothing (KEEP). Returns the id of the
     * request it enqueued; a duplicate's id never runs, so its [state] stays `PENDING` while the first one is reported.
     */
    fun enqueue(alertId: String, actionId: String, label: String): UUID

    /** One reply, for the sheet's "Sent" / "Couldn't send" snackbar. */
    fun state(id: UUID): Flow<ReplyState>

    /** Drops every pending reply ("Set up again"). */
    fun cancelAll()
}

internal fun uniqueName(alertId: String, actionId: String) = "reply-$alertId-$actionId"

/** [ts] is the tap time: it travels in the input data, so every retry publishes the same answer. */
internal fun replyRequest(alertId: String, actionId: String, label: String, ts: Long): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<ReplyWorker>()
        .setInputData(workDataOf(ReplyWorker.KEY_ALERT to alertId, ReplyWorker.KEY_ACTION to actionId, ReplyWorker.KEY_LABEL to label, ReplyWorker.KEY_TS to ts))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .addTag(ReplyWorker.TAG)
        .build()

internal fun WorkInfo.State?.toReplyState(): ReplyState = when (this) {
    WorkInfo.State.SUCCEEDED -> ReplyState.SENT
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ReplyState.FAILED
    else -> ReplyState.PENDING
}

class WorkManagerReplyQueue(
    private val workManager: () -> WorkManager,
    private val clock: () -> Instant = Instant::now,
) : ReplyQueue {
    override fun enqueue(alertId: String, actionId: String, label: String): UUID {
        val request = replyRequest(alertId, actionId, label, clock().epochSecond)
        workManager().enqueueUniqueWork(uniqueName(alertId, actionId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    override fun state(id: UUID): Flow<ReplyState> = flow {
        emitAll(workManager().getWorkInfoByIdFlow(id).map { it?.state.toReplyState() })
    }

    override fun cancelAll() {
        workManager().cancelAllWorkByTag(ReplyWorker.TAG)
    }

    private companion object {
        const val BACKOFF_SECONDS = 10L
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/ReplyFailureNotifier.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gekkotron.oikos.alerts.AlertChannel

/** "Couldn't send *label*" on the `system` channel after the fifth failed attempt (spec §5.5). */
class ReplyFailureNotifier(
    private val context: Context,
    private val canPost: () -> Boolean = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
) {
    @SuppressLint("MissingPermission") // canPost() is the permission check
    fun couldNotSend(alertId: String, label: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, AlertChannel.SYSTEM.id)
            .setSmallIcon(R.drawable.ic_stat_oikos)
            .setColor(AlertChannels.accent(AlertChannel.SYSTEM))
            .setContentTitle(context.getString(R.string.push_reply_failed, label))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("reply-failed:$alertId", NotificationIds.REPLY_FAILED, notification)
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/ReplyWorker.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.PushTopics
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Publishes `{"id","action","install_id","ts"}` to `oikos-push/responses`, QoS 1, not retained (spec §5.5). */
class ReplyWorker(
    context: Context,
    params: WorkerParameters,
    private val publisher: TopicPublisher,
    private val targets: PushTargets,
    private val failures: ReplyFailureNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alertId = inputData.getString(KEY_ALERT) ?: return Result.failure()
        val actionId = inputData.getString(KEY_ACTION) ?: return Result.failure()
        val label = inputData.getString(KEY_LABEL) ?: actionId
        val ts = inputData.getLong(KEY_TS, 0L)
        val installId = (targets.current() as? PushTarget.Ready)?.installId ?: return Result.success()
        val payload = buildJsonObject {
            put("id", alertId)
            put("action", actionId)
            put("install_id", installId)
            put("ts", ts)
        }
        val error = publisher.publish(PushTopics.RESPONSES, payload.toString().encodeToByteArray(), retain = false).exceptionOrNull()
        return when {
            error == null || error is NotConfigured -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            else -> {
                failures.couldNotSend(alertId, label)
                Result.failure()
            }
        }
    }

    /** Android 9 to 11 run expedited work as a foreground service and need something to show. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val label = inputData.getString(KEY_LABEL).orEmpty()
        val notification = NotificationCompat.Builder(applicationContext, AlertChannel.SYSTEM.id)
            .setSmallIcon(R.drawable.ic_stat_oikos)
            .setContentTitle(applicationContext.getString(R.string.push_sending, label))
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.REPLY_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.REPLY_PROGRESS, notification)
        }
    }

    companion object {
        const val TAG = "reply"
        const val KEY_ALERT = "alert"
        const val KEY_ACTION = "action"
        const val KEY_LABEL = "label"
        const val KEY_TS = "ts"

        /** Five tries in total: the first four failures retry, the fifth gives up. */
        const val MAX_ATTEMPTS = 5
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/ReplyReceiver.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** A notification action button: dismiss the notification and queue the answer (push alerts spec §5.5). */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: actionId
        NotificationManagerCompat.from(context).cancel(alertId, NotificationIds.ALERT)
        (context.applicationContext as PushComponentProvider).pushComponent().replies.enqueue(alertId, actionId, label)
    }

    companion object {
        const val ACTION_REPLY = "com.gekkotron.oikos.push.REPLY"
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_LABEL = "label"
    }
}
```

Replace `PushWorkerFactory.kt` with:

```kotlin
package com.gekkotron.oikos.push

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/** Builds the push workers with their dependencies; the app installs it as WorkManager's factory (Task 9). */
class PushWorkerFactory(
    private val registrar: PushRegistrar,
    private val publisher: TopicPublisher,
    private val targets: PushTargets,
    private val failures: ReplyFailureNotifier,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            RegisterWorker::class.java.name -> RegisterWorker(appContext, workerParameters, registrar)
            ReplyWorker::class.java.name -> ReplyWorker(appContext, workerParameters, publisher, targets, failures)
            else -> null
        }
}
```

Update the Task 6 `RegisterWorkerTest.run()` to build the factory with the new constructor: replace `PushWorkerFactory(registrar)` with `PushWorkerFactory(registrar, publisher, targets, ReplyFailureNotifier(context))`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:push:testDebugUnitTest`
Expected: PASS (all of `:core:push`). Robolectric's `ShadowNotificationManager` keeps notifications by (tag, id), so `getNotification("frigate-1", 1)` is null once the receiver has cancelled it.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml core/push
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): channels, reply queue, ReplyWorker and ReplyReceiver" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `AlertNotifier` — picture budget, tap target, action buttons, supersession

**Files:**
- Create: `core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertNotifier.kt`
- Test: `core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertNotifierTest.kt`, `FetchedNotificationImagesTest.kt`, `AlertFixtures.kt`

**Interfaces:**
- Consumes: Task 7 `AlertChannels`, `NotificationIds`, `ReplyReceiver`; Task 4 `MediaSource`, `AlertImageCache`; Task 1 `Alert`.
- Produces:
  - `fun interface NotificationImages { suspend fun load(url: String): Bitmap? }`
  - `class FetchedNotificationImages(source: MediaSource, cache: AlertImageCache, targetPx: Int = 1024) : NotificationImages`; `internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int`
  - `class AlertNotifier(context: Context, images: NotificationImages, imageBudget: Duration = 8.seconds, canPost: () -> Boolean = ..., show: (tag: String, notification: Notification) -> Unit = ...)` with `suspend fun post(alert: Alert): Boolean`, `fun cancel(id: String)`, `internal fun build(alert: Alert, picture: Bitmap?): Notification`, `internal fun openIntent(alertId: String): PendingIntent`, `internal fun replyIntent(alertId: String, action: AlertAction): PendingIntent`

Semantics (spec §5.2): tag = alert id, id = `NotificationIds.ALERT`; title; text as `BigTextStyle`; `setSilent(alert.silent)`; the picture (if any) with an 8 s budget around the *whole* load (LAN, gateway and decode), then `BigPictureStyle` plus a large icon, otherwise text only; tap opens `oikos://alert/<id>` in this app's package; the first 3 actions become buttons that broadcast to `ReplyReceiver`. Two guarantees the spec implies: every `PendingIntent` is unique per (alert, action) because Android ignores extras when matching (Review Focus 1), and the last call for an id wins, so a cancel or a newer alert that arrives while the picture is loading is never overwritten by the slow, older post (Review Focus 2). When notifications are not allowed, `post` returns false and does not even fetch the picture.

- [ ] **Step 1: Write the fixtures and the failing tests**

`core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertFixtures.kt`:

```kotlin
package com.gekkotron.oikos.push

import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.Severity
import java.time.Instant

fun testAlert(
    id: String = "frigate-1",
    title: String = "Front door",
    body: String = "Person detected",
    channel: AlertChannel = AlertChannel.CAMERA,
    imageUrl: String? = null,
    actions: List<AlertAction> = emptyList(),
    silent: Boolean = false,
    resolved: Boolean = false,
    sent: Instant = Instant.parse("2026-10-07T10:00:00Z"),
) = Alert(
    id = id, channel = channel, severity = Severity.WARNING, title = title, body = body, imageUrl = imageUrl, videoUrl = null,
    entityId = null, actions = actions, source = "frigate", silent = silent, resolved = resolved, sentAt = sent, receivedAt = sent,
)
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertNotifierTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val picture: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private var allowed = true

    @Before
    fun setUp() = AlertChannels(context).ensure()

    private fun notifier(images: NotificationImages = NotificationImages { picture }) =
        AlertNotifier(context, images, canPost = { allowed })

    private fun posted(tag: String): Notification? = shadowOf(manager).getNotification(tag, NotificationIds.ALERT)

    private fun Notification.template() = extras.getString(Notification.EXTRA_TEMPLATE)

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)

    @Test
    fun `a text alert posts on its channel with the id as tag and a big text style`() = runTest {
        assertTrue(notifier().post(testAlert(channel = com.gekkotron.oikos.alerts.AlertChannel.ALARM)))
        val n = posted("frigate-1")!!
        assertEquals("alarm", n.channelId)
        assertEquals("Front door", n.title())
        assertEquals("Person detected", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("android.app.Notification\$BigTextStyle", n.template())
    }

    @Test
    fun `a picture gives a big picture style and a large icon`() = runTest {
        assertTrue(notifier().post(testAlert(imageUrl = "http://192.168.1.20:5000/s.jpg")))
        val n = posted("frigate-1")!!
        assertEquals("android.app.Notification\$BigPictureStyle", n.template())
        assertNotNull(n.getLargeIcon())
    }

    @Test
    fun `a failed picture falls back to text only`() = runTest {
        assertTrue(notifier(NotificationImages { null }).post(testAlert(imageUrl = "http://h/s.jpg")))
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `a picture that hangs gets 8 seconds in total, then the text notification is posted`() = runTest {
        val notifier = notifier(NotificationImages { awaitCancellation() })
        val result = async { notifier.post(testAlert(imageUrl = "http://h/s.jpg")) }
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(result.isCompleted)
        assertNull(posted("frigate-1"))
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `the budget bounds a slow chain of attempts, not each attempt`() = runTest {
        // A LAN attempt, a gateway attempt and a decode, 5 s each, are one budget of 8 s.
        val notifier = notifier(NotificationImages { delay(5_000); delay(5_000); delay(5_000); picture })
        val result = async { notifier.post(testAlert(imageUrl = "http://h/s.jpg")) }
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(result.await())
        assertEquals(8_000, testScheduler.currentTime)
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `a picture that throws does not lose the alert`() = runTest {
        assertTrue(notifier(NotificationImages { error("decoder crashed") }).post(testAlert(imageUrl = "http://h/s.jpg")))
        assertNotNull(posted("frigate-1"))
    }

    @Test
    fun `at most three action buttons are shown`() = runTest {
        val actions = (1..4).map { AlertAction("a$it", "Action $it") }
        notifier().post(testAlert(actions = actions))
        assertEquals(listOf("Action 1", "Action 2", "Action 3"), posted("frigate-1")!!.actions.map { it.title.toString() })
    }

    @Test
    fun `tapping opens the alert deep link in this app`() = runTest {
        val notifier = notifier()
        val intent = shadowOf(notifier.openIntent("frigate-1")).savedIntent
        assertEquals("oikos://alert/frigate-1", intent.data.toString())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        notifier.post(testAlert())
        assertNotNull(posted("frigate-1")!!.contentIntent)
    }

    @Test
    fun `an id with path characters stays one segment of the deep link`() = runTest {
        val intent = shadowOf(notifier().openIntent("a/b c")).savedIntent
        assertEquals(listOf("a/b c"), intent.data!!.pathSegments)
    }

    @Test
    fun `two alerts with the same action id get distinct reply intents`() = runTest {
        val notifier = notifier()
        val disarm = AlertAction("disarm", "Disarm")
        val a = shadowOf(notifier.replyIntent("alert-a", disarm)).savedIntent
        val b = shadowOf(notifier.replyIntent("alert-b", disarm)).savedIntent
        // PendingIntent matching ignores extras: only action, data, type, class and categories keep two apart.
        assertFalse(a.filterEquals(b))
        assertNotEquals(a.data, b.data)
        assertEquals("alert-a", a.getStringExtra(ReplyReceiver.EXTRA_ALERT_ID))
        assertEquals("alert-b", b.getStringExtra(ReplyReceiver.EXTRA_ALERT_ID))
        assertEquals("disarm", a.getStringExtra(ReplyReceiver.EXTRA_ACTION_ID))
        assertEquals("Disarm", a.getStringExtra(ReplyReceiver.EXTRA_LABEL))
        assertEquals(ReplyReceiver.ACTION_REPLY, a.action)
        assertEquals(ReplyReceiver::class.java.name, a.component!!.className)
        // The same button of the same alert is the same PendingIntent, so a re-post updates instead of piling up.
        assertTrue(a.filterEquals(shadowOf(notifier.replyIntent("alert-a", disarm)).savedIntent))
        // Two different buttons of one alert differ too.
        assertFalse(a.filterEquals(shadowOf(notifier.replyIntent("alert-a", AlertAction("silence", "Silence"))).savedIntent))
    }

    @Test
    fun `a silent alert is posted without sound or vibration`() = runTest {
        notifier().post(testAlert(silent = true))
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, posted("frigate-1")!!.groupAlertBehavior)
    }

    @Test
    fun `when notifications are off nothing is fetched or posted`() = runTest {
        allowed = false
        var fetched = false
        val result = notifier(NotificationImages { fetched = true; picture }).post(testAlert(imageUrl = "http://h/s.jpg"))
        assertFalse(result)
        assertFalse(fetched)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a later alert with the same id replaces the notification`() = runTest {
        val notifier = notifier()
        notifier.post(testAlert(title = "First"))
        notifier.post(testAlert(title = "Second"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Second", posted("frigate-1")!!.title())
    }

    @Test
    fun `cancel removes the notification`() = runTest {
        val notifier = notifier()
        notifier.post(testAlert())
        notifier.cancel("frigate-1")
        assertNull(posted("frigate-1"))
    }

    @Test
    fun `a cancel that arrives while the picture loads is not undone by the late post`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val notifier = notifier(NotificationImages { gate.await() })
        val late = async { notifier.post(testAlert(imageUrl = "http://h/s.jpg")) }
        runCurrent()
        notifier.cancel("frigate-1")
        gate.complete(picture)
        assertFalse(late.await())
        assertNull(posted("frigate-1"))
    }

    @Test
    fun `an older alert finishing late does not replace a newer one with the same id`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        var calls = 0
        val notifier = notifier(NotificationImages { if (calls++ == 0) gate.await() else null })
        val older = async { notifier.post(testAlert(title = "Older", imageUrl = "http://h/old.jpg")) }
        runCurrent()
        assertTrue(notifier.post(testAlert(title = "Newer", imageUrl = "http://h/new.jpg")))
        gate.complete(picture)
        assertFalse(older.await())
        assertEquals("Newer", posted("frigate-1")!!.title())
        assertEquals("android.app.Notification\$BigTextStyle", posted("frigate-1")!!.template())
    }

    @Test
    fun `an alert for another id is not affected by a cancel`() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val notifier = notifier(NotificationImages { gate.await() })
        val other = async { notifier.post(testAlert(id = "other", imageUrl = "http://h/s.jpg")) }
        runCurrent()
        notifier.cancel("frigate-1")
        gate.complete(picture)
        assertTrue(other.await())
        assertNotNull(posted("other"))
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/FetchedNotificationImagesTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.disk.DiskCache
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaException
import com.gekkotron.oikos.alerts.media.MediaFailure
import com.gekkotron.oikos.alerts.media.MediaSource
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class FetchedNotificationImagesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = AlertImageCache(DiskCache.Builder().directory(tmp.newFolder().toOkioPath()).maxSizeBytes(1_000_000).build())

    private class Source(val answer: Result<Media>) : MediaSource {
        var limit = 0L
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
            limit = maxBytes
            return answer
        }
    }

    @Test
    fun `a fetched picture is stored in the shared cache under its url`() = runBlocking {
        val cache = cache()
        val source = Source(Result.success(Media(byteArrayOf(1, 2, 3), "image/jpeg")))
        FetchedNotificationImages(source, cache).load("http://h/s.jpg")
        assertNotNull(cache.open("http://h/s.jpg"))
        assertEquals(5L * 1024 * 1024, source.limit)
    }

    @Test
    fun `a failed fetch gives no picture and caches nothing`() = runBlocking {
        val cache = cache()
        val source = Source(Result.failure(MediaException(MediaFailure.Gone)))
        assertNull(FetchedNotificationImages(source, cache).load("http://h/s.jpg"))
        assertNull(cache.open("http://h/s.jpg"))
    }

    @Test
    fun `big pictures are subsampled to about the target size, small ones are left alone`() {
        assertEquals(1, sampleSizeFor(800, 600, 1024))
        assertEquals(1, sampleSizeFor(1500, 1000, 1024))
        assertEquals(2, sampleSizeFor(4000, 3000, 1024))
        assertEquals(4, sampleSizeFor(8000, 6000, 1024))
        assertEquals(1, sampleSizeFor(4000, 900, 1024))
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.AlertNotifierTest' --tests 'com.gekkotron.oikos.push.FetchedNotificationImagesTest'`
Expected: compilation FAIL (`Unresolved reference: AlertNotifier`, `NotificationImages`, `sampleSizeFor`).

- [ ] **Step 3: Implement**

`core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertNotifier.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The notification's picture, already decoded; null means text only. */
fun interface NotificationImages {
    suspend fun load(url: String): Bitmap?
}

/** Fetches through [MediaSource], keeps the bytes in the cache Coil reads, and decodes a bitmap small enough for a notification. */
class FetchedNotificationImages(
    private val source: MediaSource,
    private val cache: AlertImageCache,
    private val targetPx: Int = 1024,
) : NotificationImages {
    override suspend fun load(url: String): Bitmap? {
        val media = source.bytes(url, MAX_BYTES).getOrNull() ?: return null
        cache.put(url, media.bytes)
        return decodeSampled(media.bytes, targetPx)
    }

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}

internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
    return sample
}

internal fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Posts alerts as notifications (push alerts spec §5.2). */
class AlertNotifier(
    private val context: Context,
    private val images: NotificationImages,
    private val imageBudget: Duration = 8.seconds,
    private val canPost: () -> Boolean = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
    private val show: (tag: String, notification: Notification) -> Unit = { tag, notification -> defaultShow(context, tag, notification) },
) {
    private val lock = Any()
    private val counter = AtomicLong()
    private val latest = HashMap<String, Long>()

    /**
     * True when the notification was posted. False when notifications are off, or when a newer post or a cancel for
     * this id arrived while the picture was loading: the last call for an id wins.
     */
    suspend fun post(alert: Alert): Boolean {
        if (!canPost()) return false
        val ticket = synchronized(lock) { counter.incrementAndGet().also { latest[alert.id] = it } }
        val picture = alert.imageUrl?.let { loadPicture(it) }
        synchronized(lock) {
            if (latest[alert.id] != ticket) return false
            show(alert.id, build(alert, picture))
        }
        return true
    }

    /** Dismisses the notification and voids any post still waiting for its picture. */
    fun cancel(id: String) = synchronized(lock) {
        latest[id] = counter.incrementAndGet()
        NotificationManagerCompat.from(context).cancel(id, NotificationIds.ALERT)
    }

    /** One budget for the whole load (LAN attempt, gateway fallback, decode); any failure means no picture. */
    private suspend fun loadPicture(url: String): Bitmap? = try {
        withTimeoutOrNull(imageBudget) { images.load(url) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal fun build(alert: Alert, picture: Bitmap?): Notification {
        val builder = NotificationCompat.Builder(context, alert.channel.id)
            .setSmallIcon(R.drawable.ic_stat_oikos)
            .setColor(AlertChannels.accent(alert.channel))
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setWhen(alert.sentAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setSilent(alert.silent)
            .setContentIntent(openIntent(alert.id))
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(alert.body))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
        }
        alert.shownActions.forEach { builder.addAction(0, it.label, replyIntent(alert.id, it)) }
        return builder.build()
    }

    internal fun openIntent(alertId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("oikos").authority("alert").appendPath(alertId).build())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Android matches PendingIntents on action, data, type, class and categories, never on extras. Every (alert, action)
     * pair therefore gets its own `data` URI; without it all "Disarm" buttons would be one PendingIntent.
     */
    internal fun replyIntent(alertId: String, action: AlertAction): PendingIntent {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION_REPLY)
            .setData(Uri.Builder().scheme("oikos").authority("reply").appendPath(alertId).appendPath(action.id).build())
            .putExtra(ReplyReceiver.EXTRA_ALERT_ID, alertId)
            .putExtra(ReplyReceiver.EXTRA_ACTION_ID, action.id)
            .putExtra(ReplyReceiver.EXTRA_LABEL, action.label)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        @SuppressLint("MissingPermission") // post() checks areNotificationsEnabled() first
        fun defaultShow(context: Context, tag: String, notification: Notification) {
            NotificationManagerCompat.from(context).notify(tag, NotificationIds.ALERT, notification)
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:push:testDebugUnitTest`
Expected: PASS. The 8 s tests use virtual time because `withTimeoutOrNull` runs on the `runTest` scheduler.

- [ ] **Step 5: Commit**

```bash
git add core/push
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): AlertNotifier with picture budget, unique reply intents and supersession" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: `AlertIngestor`, `OikosMessagingService` and the app wiring

**Files:**
- Modify: `core/push/src/main/AndroidManifest.xml`, `core/push/src/main/kotlin/com/gekkotron/oikos/push/PushComponent.kt`, `core/push/src/test/kotlin/com/gekkotron/oikos/push/ReplyReceiverTest.kt`
- Create: `core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertIngestor.kt`, `OikosMessagingService.kt`
- Test: `core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertIngestorTest.kt`, `OikosMessagingServiceTest.kt`
- Modify: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/di/AlertsModule.kt`, `PushModule.kt`, `WorkModule.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/MediaRoutes.kt`, `PushTargets.kt`, `PushWiring.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/MediaRoutesTest.kt`, `PushTargetsTest.kt`, `PushWiringTest.kt`

**Interfaces:**
- Consumes: Tasks 1-8.
- Produces:
  - `class AlertIngestor(repository: AlertRepository, notifier: AlertNotifier, log: (String) -> Unit = ...)` with `suspend fun onMessage(data: Map<String, String>): Outcome` and `sealed interface Outcome { Dropped; Cancelled(id); Shown(id); StoredOnly(id) }`
  - `class OikosMessagingService : FirebaseMessagingService` (`onMessageReceived` -> `ingestor.onMessage`, `onNewToken` -> `registrar.request()`)
  - `PushComponent.ingestor: AlertIngestor`
  - (`:app`) `fun mediaRouteFor(profile: ConnectionProfile?, route: RoutePreference, http: OkHttpClient): MediaRoute`, `class ProfileMediaRoutes(profiles: ProfileRepository, http: OkHttpClient) : MediaRoutes`; `fun pushTargetFor(state: ProfileState, installId: String): PushTarget`, `class ProfilePushTargets(profiles: ProfileRepository) : PushTargets`, `suspend fun ProfileRepository.oneShotSpec(): TransportSpec?`; `class PushWiring(link: StateFlow<LinkState>, onConnected: suspend () -> Unit, scope: CoroutineScope) { fun start() }`

Semantics (spec §5.2): parse (null is logged by key names only and dropped); a cancel dismisses the notification and marks the stored alert resolved, and **never creates a row** (a cancel for an id we never stored must not leave a ghost, and a later alert with that id starts unresolved); otherwise upsert (trims to 200) and then post. With notifications off the alert is still stored (`StoredOnly`). Hilt graph: `AlertsModule` (Room, media, caches, Coil loader), `PushModule` (publisher, registrar, notifier, ingestor, component, worker factory, channels, status later), `WorkModule` (the two WorkManager-backed queues, kept apart so tests can replace just them). `OikosApplication` is `PushComponentProvider`, WorkManager `Configuration.Provider` and Coil `SingletonImageLoader.Factory`; WorkManager's default initializer is removed from the manifest. The live link connecting calls `registrar.onLinkConnected()` through `PushWiring`.

- [ ] **Step 1: Let the app see Room through `:core:alerts`**

In `core/alerts/build.gradle.kts` add to `dependencies { }`:

```kotlin
    // AlertDatabase extends RoomDatabase: every module that builds it must see the supertype.
    api(libs.androidx.room.runtime)
```

- [ ] **Step 2: Write the failing push tests**

`core/push/src/test/kotlin/com/gekkotron/oikos/push/AlertIngestorTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.app.Notification
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertIngestorTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val shown = mutableMapOf<String, Notification>()
    private var allowed = true
    private val logged = mutableListOf<String>()
    private lateinit var ingestor: AlertIngestor

    @Before
    fun setUp() {
        AlertChannels(context).ensure()
        db = Room.inMemoryDatabaseBuilder(context, AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts())
        val notifier = AlertNotifier(context, NotificationImages { null }, canPost = { allowed }, show = { tag, n -> shown[tag] = n })
        ingestor = AlertIngestor(repo, notifier) { logged += it }
    }

    @After
    fun tearDown() = db.close()

    private fun data(vararg extra: Pair<String, String>) = mapOf("v" to "1", "id" to "frigate-1", "title" to "Front door", "channel" to "camera") + extra

    @Test
    fun `a valid alert is stored and posted`() = runBlocking {
        assertEquals(AlertIngestor.Outcome.Shown("frigate-1"), ingestor.onMessage(data("body" to "Person")))
        assertEquals("Front door", repo.byId("frigate-1").first()!!.title)
        assertTrue("frigate-1" in shown)
    }

    @Test
    fun `malformed data is dropped, logged by key names only, and stores nothing`() = runBlocking {
        assertEquals(AlertIngestor.Outcome.Dropped, ingestor.onMessage(mapOf("v" to "2", "id" to "x", "title" to "Secret title")))
        assertTrue(repo.all(null).first().isEmpty())
        assertTrue(shown.isEmpty())
        assertTrue("Secret title" !in logged.single())
    }

    @Test
    fun `a cancel dismisses the notification and resolves the stored alert`() = runBlocking {
        ingestor.onMessage(data())
        assertEquals(AlertIngestor.Outcome.Cancelled("frigate-1"), ingestor.onMessage(mapOf("v" to "1", "id" to "frigate-1", "cancel" to "true")))
        assertTrue(repo.byId("frigate-1").first()!!.resolved)
    }

    @Test
    fun `a cancel for an unknown id stores nothing and a later alert with that id is not resolved`() = runBlocking {
        ingestor.onMessage(mapOf("v" to "1", "id" to "frigate-9", "cancel" to "true"))
        assertTrue(repo.all(null).first().isEmpty())
        ingestor.onMessage(data("id" to "frigate-9"))
        assertFalse(repo.byId("frigate-9").first()!!.resolved)
    }

    @Test
    fun `a re-sent alert after a cancel is shown again and no longer resolved`() = runBlocking {
        ingestor.onMessage(data())
        ingestor.onMessage(mapOf("v" to "1", "id" to "frigate-1", "cancel" to "true"))
        assertIs<AlertIngestor.Outcome.Shown>(ingestor.onMessage(data("title" to "Front door again")))
        val stored = repo.byId("frigate-1").first()!!
        assertFalse(stored.resolved)
        assertEquals("Front door again", stored.title)
    }

    @Test
    fun `with notifications off the alert is still stored`() = runBlocking {
        allowed = false
        assertEquals(AlertIngestor.Outcome.StoredOnly("frigate-1"), ingestor.onMessage(data()))
        assertEquals("Front door", repo.byId("frigate-1").first()!!.title)
        assertNull(shown["frigate-1"])
    }
}
```

`core/push/src/test/kotlin/com/gekkotron/oikos/push/OikosMessagingServiceTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
@Config(application = TestPushApp::class)
class OikosMessagingServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication() as TestPushApp
    private val queue = FakeQueue()

    private fun install(): AlertRepository {
        val db = Room.inMemoryDatabaseBuilder(app, AlertDatabase::class.java).allowMainThreadQueries().build()
        val repo = AlertRepository(db.alerts())
        val theIngestor = AlertIngestor(repo, AlertNotifier(app, NotificationImages { null }, canPost = { false }))
        val theRegistrar = PushRegistrar(
            FakeTargets(), FakeFcmTokens(), RecordingPublisher(),
            RegistrationStore.create(tmp.newFile().also { it.delete() }, CoroutineScope(Dispatchers.Unconfined)), queue, { PushRegistrar.DeviceInfo("p", "en") },
        )
        app.component = object : PushComponent {
            override val registrar = theRegistrar
            override val replies: ReplyQueue get() = error("not used")
            override val ingestor = theIngestor
        }
        return repo
    }

    @Test
    fun `a data message is ingested`() = runBlocking {
        val repo = install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onMessageReceived(
            RemoteMessage.Builder("to").setData(mapOf("v" to "1", "id" to "frigate-1", "title" to "Front door", "channel" to "camera")).build(),
        )
        assertNotNull(repo.byId("frigate-1").first())
    }

    @Test
    fun `a malformed message does not crash the service`() {
        install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("to").setData(mapOf("garbage" to "1")).build())
    }

    @Test
    fun `a new token asks for a registration`() {
        install()
        val service = Robolectric.buildService(OikosMessagingService::class.java).create().get()
        service.onNewToken("fresh-token")
        assertEquals(1, queue.enqueued)
    }
}
```

In `ReplyReceiverTest.component()` add the third member so it still implements `PushComponent`:

```kotlin
        override val ingestor: AlertIngestor get() = error("not used")
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.AlertIngestorTest' --tests 'com.gekkotron.oikos.push.OikosMessagingServiceTest'`
Expected: compilation FAIL (`Unresolved reference: AlertIngestor`, `OikosMessagingService`; `PushComponent.ingestor`).

- [ ] **Step 4: Implement the ingestor and the service**

`core/push/src/main/kotlin/com/gekkotron/oikos/push/AlertIngestor.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.util.Log
import com.gekkotron.oikos.alerts.AlertParser
import com.gekkotron.oikos.alerts.AlertRepository

/** What `onMessageReceived` does with an FCM data map (push alerts spec §5.2), apart from the Android plumbing. */
class AlertIngestor(
    private val repository: AlertRepository,
    private val notifier: AlertNotifier,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    sealed interface Outcome {
        data object Dropped : Outcome

        data class Cancelled(val id: String) : Outcome

        data class Shown(val id: String) : Outcome

        /** Stored for the history but not posted: notifications are off, or a newer message superseded this one. */
        data class StoredOnly(val id: String) : Outcome
    }

    suspend fun onMessage(data: Map<String, String>): Outcome {
        val alert = AlertParser.fromData(data) ?: run {
            // Key names only: titles and bodies are the user's private data.
            log("dropped a malformed alert (keys: ${data.keys.sorted().joinToString()})")
            return Outcome.Dropped
        }
        if (alert.resolved) {
            notifier.cancel(alert.id)
            repository.markResolved(alert.id)
            return Outcome.Cancelled(alert.id)
        }
        repository.upsert(alert)
        return if (notifier.post(alert)) Outcome.Shown(alert.id) else Outcome.StoredOnly(alert.id)
    }

    private companion object {
        const val TAG = "OikosPush"
    }
}
```

`core/push/src/main/kotlin/com/gekkotron/oikos/push/OikosMessagingService.kt`:

```kotlin
package com.gekkotron.oikos.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Receives OikosPush's data messages (push alerts spec §5.2). FCM runs this off the main thread, so it may block. */
class OikosMessagingService : FirebaseMessagingService() {
    private val component: PushComponent get() = (applicationContext as PushComponentProvider).pushComponent()

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            runBlocking(Dispatchers.IO) { component.ingestor.onMessage(message.data) }
        } catch (e: Exception) {
            Log.w(TAG, "an alert could not be handled: ${e.javaClass.simpleName}")
        }
    }

    override fun onNewToken(token: String) {
        component.registrar.request()
    }

    private companion object {
        const val TAG = "OikosPush"
    }
}
```

Replace `PushComponent.kt`:

```kotlin
package com.gekkotron.oikos.push

/**
 * What the Android entry points (service, receivers) need. `:core:push` has no Hilt: `:app` builds the objects and
 * `OikosApplication` hands them out through [PushComponentProvider].
 */
interface PushComponent {
    val ingestor: AlertIngestor
    val registrar: PushRegistrar
    val replies: ReplyQueue
}

interface PushComponentProvider {
    fun pushComponent(): PushComponent
}
```

`core/push/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application>
        <!-- FCM data messages from OikosPush (push alerts spec §5.2). Only Google Play services binds to it. -->
        <service
            android:name="com.gekkotron.oikos.push.OikosMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>

        <!-- Notification action buttons (spec §5.2). Not exported: only our own PendingIntents reach it. -->
        <receiver
            android:name="com.gekkotron.oikos.push.ReplyReceiver"
            android:exported="false" />
    </application>
</manifest>
```

- [ ] **Step 5: Run the push tests to verify they pass**

Run: `./gradlew :core:push:testDebugUnitTest`
Expected: PASS. The service tests call the two public callbacks directly; if Robolectric cannot instantiate the Firebase base class on the default SDK, add `sdk = [34]` to the test's `@Config`, which is the supported level for Firebase Messaging.

- [ ] **Step 6: Write the failing app tests**

`app/src/test/kotlin/com/gekkotron/oikos/connection/MediaRoutesTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.RoutePreference
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaRoutesTest {
    private val http = OkHttpClient()
    private val withGateway = ConnectionProfile.Direct(
        LocalBroker("192.168.1.91"),
        gateway = GatewayConfig("https://gw.example", ByteArray(32), "192.168.1.91"),
    )
    private val localOnly = ConnectionProfile.Direct(LocalBroker("192.168.1.91"))

    @Test
    fun `auto offers both the LAN probe and the gateway`() {
        val route = mediaRouteFor(withGateway, RoutePreference.AUTO, http)
        assertNotNull(route.local)
        assertNotNull(route.gateway)
    }

    @Test
    fun `direct-only has no gateway and gateway-only has no LAN probe`() {
        mediaRouteFor(withGateway, RoutePreference.DIRECT, http).let {
            assertNotNull(it.local)
            assertNull(it.gateway)
        }
        mediaRouteFor(withGateway, RoutePreference.GATEWAY, http).let {
            assertNull(it.local)
            assertNotNull(it.gateway)
        }
    }

    @Test
    fun `a profile without a gateway has only the LAN`() {
        val route = mediaRouteFor(localOnly, RoutePreference.AUTO, http)
        assertNotNull(route.local)
        assertNull(route.gateway)
    }

    @Test
    fun `the demo and no profile have no route`() {
        for (profile in listOf(ConnectionProfile.Demo, null)) {
            val route = mediaRouteFor(profile, RoutePreference.AUTO, http)
            assertNull(route.local)
            assertNull(route.gateway)
        }
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/connection/PushTargetsTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.push.PushTarget
import org.junit.Test
import kotlin.test.assertEquals

class PushTargetsTest {
    @Test
    fun `a direct profile registers on the default topic with its install id`() {
        val state = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))
        assertEquals(PushTarget.Ready("abc", "oikos-push/tokens/abc"), pushTargetFor(state, "abc"))
    }

    @Test
    fun `the profile's token topic overrides the default`() {
        val state = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91"), pushTokenTopic = "fcm-push/tokens/{installId}"))
        assertEquals(PushTarget.Ready("abc", "fcm-push/tokens/abc"), pushTargetFor(state, "abc"))
    }

    @Test
    fun `the demo, no profile and a locked one never register`() {
        assertEquals(PushTarget.Demo, pushTargetFor(ProfileState.Ready(ConnectionProfile.Demo), "abc"))
        for (state in listOf(ProfileState.None, ProfileState.Locked, ProfileState.Loading)) {
            assertEquals(PushTarget.None, pushTargetFor(state, "abc"), state.toString())
        }
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/connection/PushWiringTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PushWiringTest {
    @Test
    fun `every time the link connects the registrar is told, other states are ignored`() = runTest(UnconfinedTestDispatcher()) {
        val link = MutableStateFlow<LinkState>(LinkState.Connecting)
        var connects = 0
        PushWiring(link, { connects++ }, backgroundScope).start()
        assertEquals(0, connects)
        link.value = LinkState.Connected(TransportMode.DIRECT)
        assertEquals(1, connects)
        link.value = LinkState.Reconnecting(1)
        link.value = LinkState.Connected(TransportMode.GATEWAY)
        assertEquals(2, connects)
        link.value = LinkState.Offline(LinkState.Offline.PAUSED)
        assertEquals(2, connects)
    }

    @Test
    fun `a failing callback does not stop the wiring`() = runTest(UnconfinedTestDispatcher()) {
        val link = MutableStateFlow<LinkState>(LinkState.Connecting)
        var connects = 0
        PushWiring(link, { connects++; error("boom") }, backgroundScope).start()
        link.value = LinkState.Connected(TransportMode.DIRECT)
        link.value = LinkState.Reconnecting(1)
        link.value = LinkState.Connected(TransportMode.DIRECT)
        assertEquals(2, connects)
    }
}
```

- [ ] **Step 7: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.gekkotron.oikos.connection.MediaRoutesTest' --tests 'com.gekkotron.oikos.connection.PushTargetsTest' --tests 'com.gekkotron.oikos.connection.PushWiringTest'`
Expected: compilation FAIL (`Unresolved reference: mediaRouteFor`, `pushTargetFor`, `PushWiring`).

- [ ] **Step 8: Implement the app glue**

`app/build.gradle.kts` `dependencies { }` gains (after `implementation(project(":core:data"))`):

```kotlin
    implementation(project(":core:alerts"))
    implementation(project(":core:push"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.core)
    // Coil's own HTTP fetcher, for any future non-alert image. Alert pictures never use it: they are AlertMedia requests.
    implementation(libs.coil.network.okhttp)
```

`app/src/main/kotlin/com/gekkotron/oikos/connection/MediaRoutes.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.alerts.media.MediaRoute
import com.gekkotron.oikos.alerts.media.MediaRoutes
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.transport.AthenaGatewayDownloader
import com.gekkotron.oikos.transport.TcpProbe
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/** How alert media can be fetched for [profile] under the user's route choice (push alerts spec §6.3). */
fun mediaRouteFor(profile: ConnectionProfile?, route: RoutePreference, http: OkHttpClient): MediaRoute {
    val direct = profile as? ConnectionProfile.Direct ?: return MediaRoute(null, null)
    val local = if (route != RoutePreference.GATEWAY) TcpProbe(direct.local.host, direct.local.port) else null
    val gateway = if (route != RoutePreference.DIRECT) {
        direct.gateway?.let { AthenaGatewayDownloader(it.toSpec(direct.local), http) }
    } else {
        null
    }
    return MediaRoute(local, gateway)
}

class ProfileMediaRoutes(private val profiles: ProfileRepository, private val http: OkHttpClient) : MediaRoutes {
    override suspend fun current(): MediaRoute {
        val state = profiles.state.first { it !is ProfileState.Loading }
        return mediaRouteFor((state as? ProfileState.Ready)?.profile, profiles.route.value, http)
    }
}
```

`app/src/main/kotlin/com/gekkotron/oikos/connection/PushTargets.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.alerts.PushTopics
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.push.PushTarget
import com.gekkotron.oikos.push.PushTargets
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

fun pushTargetFor(state: ProfileState, installId: String): PushTarget = when (state) {
    is ProfileState.Ready -> when (val profile = state.profile) {
        ConnectionProfile.Demo -> PushTarget.Demo
        is ConnectionProfile.Direct -> PushTarget.Ready(installId, PushTopics.tokenTopic(profile.pushTokenTopic, installId))
    }
    ProfileState.Loading, ProfileState.None, ProfileState.Locked -> PushTarget.None
}

class ProfilePushTargets(private val profiles: ProfileRepository) : PushTargets {
    override suspend fun current(): PushTarget {
        val state = profiles.state.first { it !is ProfileState.Loading }
        return pushTargetFor(state, profiles.installId())
    }

    override val changes: Flow<PushTarget> = flow {
        val id = profiles.installId()
        emitAll(profiles.state.filter { it !is ProfileState.Loading }.map { pushTargetFor(it, id) }.distinctUntilChanged())
    }
}

/**
 * The spec a background publish uses: the live one, but with its own MQTT client id. A second client with the live
 * id would make the broker disconnect the first.
 */
suspend fun ProfileRepository.oneShotSpec(): TransportSpec? {
    val ready = state.first { it !is ProfileState.Loading }
    return ready.toSpec(clientIdFor(installId()) + "-push", route.value)
}
```

`app/src/main/kotlin/com/gekkotron/oikos/connection/PushWiring.kt`:

```kotlin
package com.gekkotron.oikos.connection

import android.util.Log
import com.gekkotron.oikos.transport.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/** Tells the registrar each time the live link connects (push alerts spec §5.4 trigger 3). */
class PushWiring(
    private val link: StateFlow<LinkState>,
    private val onConnected: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            link.filterIsInstance<LinkState.Connected>().collect {
                try {
                    onConnected()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Oikos", "push registration check failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
```

`app/src/main/kotlin/com/gekkotron/oikos/di/AlertsModule.kt`:

```kotlin
package com.gekkotron.oikos.di

import android.content.Context
import coil3.ImageLoader
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.AlertMediaFetcher
import com.gekkotron.oikos.alerts.media.AlertMediaKeyer
import com.gekkotron.oikos.alerts.media.MediaFetcher
import com.gekkotron.oikos.alerts.media.MediaRoutes
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.connection.ProfileMediaRoutes
import com.gekkotron.oikos.data.ProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AlertsModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AlertDatabase = AlertDatabase.create(context)

    @Provides
    @Singleton
    fun repository(db: AlertDatabase): AlertRepository = AlertRepository(db.alerts())

    @Provides
    @Singleton
    fun mediaRoutes(profiles: ProfileRepository, http: OkHttpClient): MediaRoutes = ProfileMediaRoutes(profiles, http)

    @Provides
    @Singleton
    fun mediaFetcher(routes: MediaRoutes, http: OkHttpClient): MediaFetcher = MediaFetcher(routes, http)

    @Provides
    @Singleton
    fun mediaSource(fetcher: MediaFetcher): MediaSource = fetcher

    @Provides
    @Singleton
    fun imageCache(@ApplicationContext context: Context): AlertImageCache = AlertImageCache.create(context)

    @Provides
    @Singleton
    fun videoCache(@ApplicationContext context: Context, source: MediaSource): VideoCache = VideoCache.create(context, source)

    /** One loader, the shared disk cache, and the fetcher for `AlertMedia` only (spec §6.3). */
    @Provides
    @Singleton
    fun imageLoader(@ApplicationContext context: Context, source: MediaSource, cache: AlertImageCache): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache(cache.diskCache)
            .components {
                add(AlertMediaKeyer)
                add(AlertMediaFetcher.Factory(source, cache))
            }
            .build()
}
```

`app/src/main/kotlin/com/gekkotron/oikos/di/WorkModule.kt`:

```kotlin
package com.gekkotron.oikos.di

import android.content.Context
import androidx.work.WorkManager
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.WorkManagerRegistrationQueue
import com.gekkotron.oikos.push.WorkManagerReplyQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The WorkManager-backed queues, apart from `PushModule` so a test can replace just them. */
@Module
@InstallIn(SingletonComponent::class)
object WorkModule {
    @Provides
    @Singleton
    fun registrationQueue(@ApplicationContext context: Context): RegistrationQueue =
        WorkManagerRegistrationQueue { WorkManager.getInstance(context) }

    @Provides
    @Singleton
    fun replyQueue(@ApplicationContext context: Context): ReplyQueue = WorkManagerReplyQueue({ WorkManager.getInstance(context) })
}
```

`app/src/main/kotlin/com/gekkotron/oikos/di/PushModule.kt`:

```kotlin
package com.gekkotron.oikos.di

import android.content.Context
import android.os.Build
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.connection.PushWiring
import com.gekkotron.oikos.connection.ProfilePushTargets
import com.gekkotron.oikos.connection.oneShotSpec
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.push.AlertChannels
import com.gekkotron.oikos.push.AlertIngestor
import com.gekkotron.oikos.push.AlertNotifier
import com.gekkotron.oikos.push.FcmTokens
import com.gekkotron.oikos.push.FetchedNotificationImages
import com.gekkotron.oikos.push.FirebaseFcmTokens
import com.gekkotron.oikos.push.OneShotPublisher
import com.gekkotron.oikos.push.PushComponent
import com.gekkotron.oikos.push.PushRegistrar
import com.gekkotron.oikos.push.PushTargets
import com.gekkotron.oikos.push.PushWorkerFactory
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.RegistrationStore
import com.gekkotron.oikos.push.ReplyFailureNotifier
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.TopicPublisher
import com.gekkotron.oikos.transport.ActiveTransport
import com.gekkotron.oikos.transport.AthenaGatewayApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.Locale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PushModule {
    @Provides
    @Singleton
    fun pushTargets(profiles: ProfileRepository): PushTargets = ProfilePushTargets(profiles)

    @Provides
    @Singleton
    fun registrationStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): RegistrationStore =
        RegistrationStore.create(context, scope)

    @Provides
    @Singleton
    fun fcmTokens(@ApplicationContext context: Context): FcmTokens = FirebaseFcmTokens(context)

    @Provides
    @Singleton
    fun oneShotPublisher(active: ActiveTransport, profiles: ProfileRepository, http: OkHttpClient): OneShotPublisher =
        OneShotPublisher(live = active, specs = { profiles.oneShotSpec() }, gatewayApi = { AthenaGatewayApi(it, http) })

    @Provides
    fun topicPublisher(publisher: OneShotPublisher): TopicPublisher = publisher

    @Provides
    @Singleton
    fun registrar(
        targets: PushTargets,
        tokens: FcmTokens,
        publisher: TopicPublisher,
        store: RegistrationStore,
        queue: RegistrationQueue,
    ): PushRegistrar = PushRegistrar(
        targets, tokens, publisher, store, queue,
        device = { PushRegistrar.DeviceInfo(Build.MODEL, Locale.getDefault().toLanguageTag()) },
    )

    @Provides
    @Singleton
    fun channels(@ApplicationContext context: Context): AlertChannels = AlertChannels(context)

    @Provides
    @Singleton
    fun notifier(@ApplicationContext context: Context, source: MediaSource, cache: AlertImageCache): AlertNotifier =
        AlertNotifier(context, FetchedNotificationImages(source, cache))

    @Provides
    @Singleton
    fun ingestor(repository: AlertRepository, notifier: AlertNotifier): AlertIngestor = AlertIngestor(repository, notifier)

    @Provides
    @Singleton
    fun replyFailures(@ApplicationContext context: Context): ReplyFailureNotifier = ReplyFailureNotifier(context)

    @Provides
    @Singleton
    fun pushComponent(alertIngestor: AlertIngestor, pushRegistrar: PushRegistrar, replyQueue: ReplyQueue): PushComponent = object : PushComponent {
        override val ingestor = alertIngestor
        override val registrar = pushRegistrar
        override val replies = replyQueue
    }

    @Provides
    @Singleton
    fun workerFactory(registrar: PushRegistrar, publisher: TopicPublisher, targets: PushTargets, failures: ReplyFailureNotifier): PushWorkerFactory =
        PushWorkerFactory(registrar, publisher, targets, failures)

    @Provides
    @Singleton
    fun pushWiring(active: ActiveTransport, registrar: PushRegistrar, @ApplicationScope scope: CoroutineScope): PushWiring =
        PushWiring(active.link, { registrar.onLinkConnected() }, scope)
}
```

`app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt`:

```kotlin
package com.gekkotron.oikos

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.gekkotron.oikos.connection.PushWiring
import com.gekkotron.oikos.push.AlertChannels
import com.gekkotron.oikos.push.PushComponent
import com.gekkotron.oikos.push.PushComponentProvider
import com.gekkotron.oikos.push.PushWorkerFactory
import com.gekkotron.oikos.transport.ActiveTransport
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OikosApplication : Application(), PushComponentProvider, Configuration.Provider, SingletonImageLoader.Factory {
    @Inject
    lateinit var transport: ActiveTransport

    @Inject
    lateinit var component: PushComponent

    @Inject
    lateinit var channels: AlertChannels

    @Inject
    lateinit var workerFactory: PushWorkerFactory

    @Inject
    lateinit var imageLoader: Lazy<ImageLoader>

    @Inject
    lateinit var pushWiring: PushWiring

    override fun pushComponent(): PushComponent = component

    /** On-demand WorkManager with our worker factory; the default initializer is removed from the manifest. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()

    override fun onCreate() {
        super.onCreate()
        channels.ensure()
        pushWiring.start()
        // Connected only in the foreground; ActiveTransport closes 30 s after onStop (spec §3.3).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = transport.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = transport.setForeground(false)
        })
    }
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Default-network changes for the route selector; no SSID, so no location permission. -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <!-- Expedited reply work runs as a foreground service on Android 9 to 11 (push alerts spec §5.5). -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application
        android:name=".OikosApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.Oikos">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- WorkManager starts on demand with PushWorkerFactory (OikosApplication.workManagerConfiguration). -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

- [ ] **Step 9: Run the app and push test suites**

Run: `./gradlew :core:push:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, including `MainActivityTest` (the Hilt graph now has the new modules; nothing in that test injects a push object yet, and `OikosApplication.onCreate` does not run under `HiltTestApplication`).

- [ ] **Step 10: Commit**

```bash
git add core app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): ingest FCM alerts, messaging service and app wiring" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `PushStatus`

**Files:**
- Create: `core/push/src/main/kotlin/com/gekkotron/oikos/push/PushStatus.kt`
- Test: `core/push/src/test/kotlin/com/gekkotron/oikos/push/PushStatusTest.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/PushModule.kt`

**Interfaces:**
- Consumes: Task 6 `LastRegistration`, `RegistrationStore.observe()`, `RegistrationQueue.active`, `PushRegistrar.lastError`; Task 5 `PushTarget`, `PushTargets.changes`.
- Produces:
  - `sealed interface PushStatus { data object NotificationsOff; data class Registered(val at: Instant); data object Waiting; data class Failed(val reason: String); data object Demo }`
  - `internal fun pushStatus(target: PushTarget, notificationsEnabled: Boolean, last: LastRegistration?, workActive: Boolean, lastError: String?): PushStatus`
  - `interface PushStatusSource { val status: StateFlow<PushStatus>; fun refresh(); fun registerAgain() }`
  - `class DefaultPushStatusSource(targets: PushTargets, store: RegistrationStore, queue: RegistrationQueue, registrar: PushRegistrar, notificationsEnabled: () -> Boolean, scope: CoroutineScope) : PushStatusSource`

Rules (spec §5.6), first match wins: demo target is `Demo`; notifications not allowed is `NotificationsOff`; a last error is `Failed(reason)` (also while a retry waits in its backoff); registration work pending is `Waiting`; a last registration on the *current* token topic is `Registered(at)`; anything else (no registration yet, or one for an older topic) is `Waiting`. `refresh()` re-reads the notification permission and is called when a screen resumes.

- [ ] **Step 1: Write the failing test**

`core/push/src/test/kotlin/com/gekkotron/oikos/push/PushStatusTest.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals

class PushStatusTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val at = Instant.ofEpochSecond(1_790_000_000)
    private val ready = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")
    private val registered = LastRegistration("t", "oikos-push/tokens/install-1", at)

    private fun status(
        target: PushTarget = ready,
        enabled: Boolean = true,
        last: LastRegistration? = registered,
        work: Boolean = false,
        error: String? = null,
    ) = pushStatus(target, enabled, last, work, error)

    @Test
    fun `a registration on the current topic is Registered`() {
        assertEquals(PushStatus.Registered(at), status())
    }

    @Test
    fun `the demo is Demo whatever else is true`() {
        assertEquals(PushStatus.Demo, status(target = PushTarget.Demo, enabled = false, error = "x"))
    }

    @Test
    fun `notifications off wins over a registration, a failure and pending work`() {
        assertEquals(PushStatus.NotificationsOff, status(enabled = false))
        assertEquals(PushStatus.NotificationsOff, status(enabled = false, error = "x", work = true))
    }

    @Test
    fun `an error is Failed even while a retry is waiting`() {
        assertEquals(PushStatus.Failed("broker did not answer in time"), status(error = "broker did not answer in time", work = true, last = null))
    }

    @Test
    fun `pending work is Waiting even over an older registration`() {
        assertEquals(PushStatus.Waiting, status(work = true))
    }

    @Test
    fun `no registration yet, or one for another topic, is Waiting`() {
        assertEquals(PushStatus.Waiting, status(last = null))
        assertEquals(PushStatus.Waiting, status(last = registered.copy(topic = "old/tokens/install-1")))
    }

    @Test
    fun `no profile is Waiting`() {
        assertEquals(PushStatus.Waiting, status(target = PushTarget.None, last = null))
    }

    @Test
    fun `the source follows the registration, the queue, the error and a refreshed permission`() = runTest {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope)
        val queue = FakeQueue()
        val publisher = RecordingPublisher()
        val registrar = PushRegistrar(FakeTargets(), FakeFcmTokens(), publisher, store, queue, { PushRegistrar.DeviceInfo("p", "en") }, { at })
        var enabled = true
        val source = DefaultPushStatusSource(FakeTargets(), store, queue, registrar, { enabled }, backgroundScope)

        assertEquals(PushStatus.Waiting, source.status.first { it == PushStatus.Waiting })
        registrar.register()
        assertEquals(PushStatus.Registered(at), source.status.first { it is PushStatus.Registered })

        publisher.result = Result.failure(IOException("offline"))
        registrar.register()
        assertEquals(PushStatus.Failed("offline"), source.status.first { it is PushStatus.Failed })

        enabled = false
        source.refresh()
        assertEquals(PushStatus.NotificationsOff, source.status.first { it == PushStatus.NotificationsOff })
    }

    @Test
    fun `register again asks the registrar to enqueue`() = runTest {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, backgroundScope)
        val queue = FakeQueue()
        val registrar = PushRegistrar(FakeTargets(), FakeFcmTokens(), RecordingPublisher(), store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        DefaultPushStatusSource(FakeTargets(), store, queue, registrar, { true }, backgroundScope).registerAgain()
        assertEquals(1, queue.enqueued)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:push:testDebugUnitTest --tests 'com.gekkotron.oikos.push.PushStatusTest'`
Expected: compilation FAIL (`Unresolved reference: pushStatus`, `PushStatus`, `DefaultPushStatusSource`).

- [ ] **Step 3: Implement**

`core/push/src/main/kotlin/com/gekkotron/oikos/push/PushStatus.kt`:

```kotlin
package com.gekkotron.oikos.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant

/** What Settings and the Security banner say about push (push alerts spec §5.6, §7.4). */
sealed interface PushStatus {
    data object NotificationsOff : PushStatus

    data class Registered(val at: Instant) : PushStatus

    data object Waiting : PushStatus

    /** [reason] is the last attempt's short, secret-free message. */
    data class Failed(val reason: String) : PushStatus

    data object Demo : PushStatus
}

internal fun pushStatus(
    target: PushTarget,
    notificationsEnabled: Boolean,
    last: LastRegistration?,
    workActive: Boolean,
    lastError: String?,
): PushStatus = when {
    target == PushTarget.Demo -> PushStatus.Demo
    !notificationsEnabled -> PushStatus.NotificationsOff
    lastError != null -> PushStatus.Failed(lastError)
    workActive -> PushStatus.Waiting
    target is PushTarget.Ready && last != null && last.topic == target.tokenTopic -> PushStatus.Registered(last.at)
    else -> PushStatus.Waiting
}

/** A seam so screens and their tests need no WorkManager. */
interface PushStatusSource {
    val status: StateFlow<PushStatus>

    /** Re-reads the notification permission; call when a screen resumes. */
    fun refresh()

    /** "Register again" in Settings. */
    fun registerAgain()
}

class DefaultPushStatusSource(
    targets: PushTargets,
    store: RegistrationStore,
    queue: RegistrationQueue,
    private val registrar: PushRegistrar,
    notificationsEnabled: () -> Boolean,
    scope: CoroutineScope,
) : PushStatusSource {
    private val refreshes = MutableStateFlow(0)

    override val status: StateFlow<PushStatus> =
        combine(targets.changes, store.observe(), queue.active, registrar.lastError, refreshes) { target, last, active, error, _ ->
            pushStatus(target, notificationsEnabled(), last, active, error)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PushStatus.Waiting)

    override fun refresh() = refreshes.update { it + 1 }

    override fun registerAgain() = registrar.request()
}
```

In `app/src/main/kotlin/com/gekkotron/oikos/di/PushModule.kt` add the imports `androidx.core.app.NotificationManagerCompat`, `com.gekkotron.oikos.push.DefaultPushStatusSource`, `com.gekkotron.oikos.push.PushStatusSource` and this provider at the end of the object:

```kotlin
    @Provides
    @Singleton
    fun pushStatusSource(
        @ApplicationContext context: Context,
        targets: PushTargets,
        store: RegistrationStore,
        queue: RegistrationQueue,
        registrar: PushRegistrar,
        @ApplicationScope scope: CoroutineScope,
    ): PushStatusSource = DefaultPushStatusSource(
        targets, store, queue, registrar,
        notificationsEnabled = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
        scope = scope,
    )
```

`:app` needs core-ktx for `NotificationManagerCompat`: add `implementation(libs.androidx.core.ktx)` to `app/build.gradle.kts`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:push:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/push app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): PushStatus from permission, registration and work state" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: `HistorySync`, shared subscriptions and its app wiring

**Files:**
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/sync/HistorySync.kt`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/sync/HistorySyncTest.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/SharedSubscriptions.kt`, `HistoryWiring.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`, `PushModule.kt` (or `AlertsModule.kt`), `OikosApplication.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/SharedSubscriptionsTest.kt`, `HistoryWiringTest.kt`

**Interfaces:**
- Consumes: Task 2 `AlertRepository.insertIfMissing/markResolved`, Task 1 `AlertParser.parseHistory`, `PushTopics.HISTORY`; `MqttMessage`.
- Produces:
  - `class HistorySync(messages: Flow<MqttMessage>, repository: AlertRepository, log: (String) -> Unit = ...)` with `val catchUp: StateFlow<Int>`, `fun acknowledge()`, `fun start(scope: CoroutineScope): Job`, `suspend fun handle(payload: String): Int`
  - `class SharedSubscriptions(delegate: MqttTransport) : MqttTransport` with `suspend fun setExtra(filters: Set<String>)`
  - `internal fun historyFilters(state: ProfileState): Set<String>`, `class HistoryWiring(states: Flow<ProfileState>, subscriptions: SharedSubscriptions, sync: HistorySync, scope: CoroutineScope) { fun start() }`

Semantics (spec §6.2): on each retained history message, every entry whose id is not in Room is inserted (never posting a notification), a `cancel` entry marks the alert resolved, then Room trims. A malformed entry is skipped on its own; a message that is not an array is skipped whole. The returned count is what the Security tab turns into "*n* alerts arrived while you were offline"; `catchUp` accumulates until the tab acknowledges it. Because the broker redelivers the retained message at every (re)subscribe, `insertIfMissing` (Task 2) is what keeps "Clear all" from being undone and an alert that trimming immediately deletes from being counted. A history entry with no title is a cancel stored on its own by OikosPush: it only resolves an alert we already have and never becomes a row.

`ActiveTransport.setSubscriptions` is declarative: the discovery source owns the whole set. `SharedSubscriptions` merges that set with the history topic, otherwise each would erase the other.

- [ ] **Step 1: Write the failing `HistorySync` test**

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/sync/HistorySyncTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.Severity
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class HistorySyncTest {
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private lateinit var sync: HistorySync
    private val messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 16)
    private val logged = mutableListOf<String>()

    /** "Now" for the clear marker: after every alert below, so a clear covers them all. */
    private val clearTime = Instant.ofEpochSecond(1_790_001_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { clearTime }
        sync = HistorySync(messages, repo) { logged += it }
    }

    @After
    fun tearDown() = db.close()

    private fun entry(id: String, sentAt: Long = 1_790_000_000, extra: String = "") =
        """{"v":1,"id":"$id","channel":"camera","severity":"info","title":"Title $id","body":"","silent":false,"sent_at":$sentAt$extra}"""

    private fun payload(vararg entries: String) = entries.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun existing(id: String, sent: Long = 1_790_000_000) = Alert(
        id, AlertChannel.CAMERA, Severity.INFO, "Stored $id", "", null, null, null, emptyList(), null, false, false,
        Instant.ofEpochSecond(sent), Instant.ofEpochSecond(sent),
    )

    @Test
    fun `only missing alerts are inserted and counted, existing ones are left as they are`() = runBlocking {
        repo.upsert(existing("a"))
        assertEquals(2, sync.handle(payload(entry("a"), entry("b", 1_790_000_001), entry("c", 1_790_000_002))))
        assertEquals("Stored a", repo.byId("a").first()!!.title)
        assertEquals("Title b", repo.byId("b").first()!!.title)
        assertEquals(2, sync.catchUp.value)
    }

    @Test
    fun `a replayed history adds nothing`() = runBlocking {
        val message = payload(entry("a"), entry("b", 1_790_000_001))
        assertEquals(2, sync.handle(message))
        assertEquals(0, sync.handle(message))
        assertEquals(2, sync.catchUp.value)
    }

    @Test
    fun `a cancel entry resolves an existing alert and arrives resolved when new`() = runBlocking {
        repo.upsert(existing("a"))
        sync.handle(payload(entry("a", extra = ""","cancel":true"""), entry("b", 1_790_000_001, extra = ""","cancel":true""")))
        assertTrue(repo.byId("a").first()!!.resolved)
        assertTrue(repo.byId("b").first()!!.resolved)
    }

    @Test
    fun `a cancel stored on its own for an unknown id never becomes a row`() = runBlocking {
        assertEquals(0, sync.handle("""[{"v":1,"id":"ghost","cancel":true,"sent_at":1790000000}]"""))
        assertNull(repo.byId("ghost").first())
        assertEquals(0, sync.catchUp.value)
    }

    @Test
    fun `bad entries are skipped one by one and a non array message whole`() = runBlocking {
        assertEquals(1, sync.handle("""[{"v":1,"title":"no id"},"junk",${entry("ok")},{"v":9,"id":"x","title":"t"}]"""))
        assertNotNull(repo.byId("ok").first())
        assertEquals(0, sync.handle("""{"not":"an array"}"""))
        assertEquals(0, sync.handle("garbage"))
        assertEquals(2, logged.size)
    }

    @Test
    fun `a cleared history is not resurrected by the retained history message`() = runBlocking {
        val message = payload(entry("a"), entry("b", 1_790_000_001))
        assertEquals(2, sync.handle(message))
        sync.acknowledge()
        repo.clearAll()
        // The broker redelivers the retained message on the next reconnect.
        assertEquals(0, sync.handle(message))
        assertTrue(repo.all(null).first().isEmpty())
        assertEquals(0, sync.catchUp.value)
        // An alert sent after the clear still arrives.
        assertEquals(1, sync.handle(payload(entry("a"), entry("fresh", clearTime.epochSecond + 5))))
        assertEquals(listOf("fresh"), repo.all(null).first().map { it.id })
    }

    @Test
    fun `an alert trimmed away in the same sync is not counted`() = runBlocking {
        for (i in 1..200) repo.upsert(existing("n$i", sent = 1_790_000_100L + i))
        assertEquals(0, sync.handle(payload(entry("ancient", sentAt = 1_000))))
        assertNull(repo.byId("ancient").first())
        assertEquals(0, sync.catchUp.value)
    }

    @Test
    fun `acknowledge resets the counter and a later sync starts again from zero`() = runBlocking {
        sync.handle(payload(entry("a")))
        assertEquals(1, sync.catchUp.value)
        sync.acknowledge()
        assertEquals(0, sync.catchUp.value)
        sync.handle(payload(entry("b", 1_790_000_001)))
        assertEquals(1, sync.catchUp.value)
    }

    @Test
    fun `start reads only the history topic`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        sync.start(scope)
        messages.emit(MqttMessage("homeassistant/switch/x/config", payload(entry("wrong")).encodeToByteArray(), retained = true))
        messages.emit(MqttMessage("oikos-push/history", payload(entry("right")).encodeToByteArray(), retained = true))
        // Room writes on its own executor: wait for the row instead of reading it the instant emit returns.
        assertNotNull(withTimeout(5_000) { repo.byId("right").first { it != null } })
        assertNull(repo.byId("wrong").first())
        scope.cancel()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.sync.HistorySyncTest'`
Expected: compilation FAIL (`Unresolved reference: HistorySync`).

- [ ] **Step 3: Implement `HistorySync`**

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/sync/HistorySync.kt`:

```kotlin
package com.gekkotron.oikos.alerts.sync

import android.util.Log
import com.gekkotron.oikos.alerts.AlertParser
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.PushTopics
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Catches the history up from the retained `oikos-push/history` message; it never posts a notification (spec §6.2). */
class HistorySync(
    private val messages: Flow<MqttMessage>,
    private val repository: AlertRepository,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val _catchUp = MutableStateFlow(0)

    /** Alerts that arrived through history and have not been announced yet ("*n* alerts arrived while you were offline"). */
    val catchUp: StateFlow<Int> = _catchUp.asStateFlow()

    fun acknowledge() {
        _catchUp.value = 0
    }

    /** Subscribes before returning, so a retained message that arrives at once is not missed. */
    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        messages.filter { it.topic == PushTopics.HISTORY }.collect { handle(it.payloadString()) }
    }

    /** Applies one history message; returns how many alerts are new and still stored. */
    suspend fun handle(payload: String): Int {
        val alerts = AlertParser.parseHistory(payload) ?: run {
            log("the history message is not a JSON array")
            return 0
        }
        var added = 0
        for (alert in alerts) {
            // No title: a cancel OikosPush stored on its own. It resolves what we have; it is never a row.
            if (alert.title.isNotBlank() && repository.insertIfMissing(alert)) added++
            if (alert.resolved) repository.markResolved(alert.id)
        }
        if (added > 0) _catchUp.update { it + added }
        return added
    }

    private companion object {
        const val TAG = "OikosHistory"
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:alerts:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Write the failing app tests**

`app/src/test/kotlin/com/gekkotron/oikos/connection/SharedSubscriptionsTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SharedSubscriptionsTest {
    private val fake = FakeMqttTransport()
    private val shared = SharedSubscriptions(fake)

    @Test
    fun `the source's filters and the extra ones are subscribed together`() = runTest {
        shared.setSubscriptions(setOf("homeassistant/#"))
        shared.setExtra(setOf("oikos-push/history"))
        assertEquals(setOf("homeassistant/#", "oikos-push/history"), fake.subscriptions)
    }

    @Test
    fun `the source replacing its filters keeps the extra ones`() = runTest {
        shared.setExtra(setOf("oikos-push/history"))
        shared.setSubscriptions(setOf("homeassistant/#", "z2m/#"))
        shared.setSubscriptions(emptySet())
        assertEquals(setOf("oikos-push/history"), fake.subscriptions)
    }

    @Test
    fun `clearing the extra ones leaves the source's`() = runTest {
        shared.setSubscriptions(setOf("homeassistant/#"))
        shared.setExtra(setOf("oikos-push/history"))
        shared.setExtra(emptySet())
        assertEquals(setOf("homeassistant/#"), fake.subscriptions)
    }

    @Test
    fun `publish and messages pass through`() = runTest {
        shared.publish("a/b", "x".encodeToByteArray(), 1, false)
        assertEquals(listOf("a/b" to "x"), fake.publishedStrings())
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/connection/HistoryWiringTest.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import org.junit.Test
import kotlin.test.assertEquals

class HistoryWiringTest {
    @Test
    fun `a direct profile subscribes to the history topic`() {
        assertEquals(setOf("oikos-push/history"), historyFilters(ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))))
    }

    @Test
    fun `the demo, no profile and a locked one subscribe to nothing`() {
        for (state in listOf(ProfileState.Ready(ConnectionProfile.Demo), ProfileState.None, ProfileState.Locked, ProfileState.Loading)) {
            assertEquals(emptySet(), historyFilters(state), state.toString())
        }
    }
}
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.gekkotron.oikos.connection.SharedSubscriptionsTest' --tests 'com.gekkotron.oikos.connection.HistoryWiringTest'`
Expected: compilation FAIL (`Unresolved reference: SharedSubscriptions`, `historyFilters`).

- [ ] **Step 7: Implement the app wiring**

`app/src/main/kotlin/com/gekkotron/oikos/connection/SharedSubscriptions.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `setSubscriptions` is declarative: the last caller's set is the set. The discovery source owns it, so features that
 * need an extra topic (the alert history) register it here and it is merged into whatever the source asks for.
 */
class SharedSubscriptions(private val delegate: MqttTransport) : MqttTransport by delegate {
    private val mutex = Mutex()
    private var owned = emptySet<String>()
    private var extra = emptySet<String>()

    override suspend fun setSubscriptions(filters: Set<String>) = mutex.withLock {
        owned = filters
        delegate.setSubscriptions(owned + extra)
    }

    suspend fun setExtra(filters: Set<String>) = mutex.withLock {
        extra = filters
        delegate.setSubscriptions(owned + extra)
    }
}
```

`app/src/main/kotlin/com/gekkotron/oikos/connection/HistoryWiring.kt`:

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.alerts.PushTopics
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The history topic is subscribed while a real (direct) profile is live; the demo has no OikosPush (spec §6.2, §7.6). */
internal fun historyFilters(state: ProfileState): Set<String> =
    if ((state as? ProfileState.Ready)?.profile is ConnectionProfile.Direct) setOf(PushTopics.HISTORY) else emptySet()

class HistoryWiring(
    private val states: Flow<ProfileState>,
    private val subscriptions: SharedSubscriptions,
    private val sync: HistorySync,
    private val scope: CoroutineScope,
) {
    fun start() {
        sync.start(scope)
        scope.launch { states.map(::historyFilters).distinctUntilChanged().collect { subscriptions.setExtra(it) } }
    }
}
```

In `AppModule.kt` replace the `transport` provider and add the shared one:

```kotlin
    @Provides
    @Singleton
    fun sharedSubscriptions(active: ActiveTransport): SharedSubscriptions = SharedSubscriptions(active)

    @Provides
    fun transport(shared: SharedSubscriptions): MqttTransport = shared
```

(adding `import com.gekkotron.oikos.connection.SharedSubscriptions`). In `AlertsModule.kt` add:

```kotlin
    @Provides
    @Singleton
    fun historySync(active: ActiveTransport, repository: AlertRepository): HistorySync = HistorySync(active.messages(), repository)

    @Provides
    @Singleton
    fun historyWiring(
        profiles: ProfileRepository,
        shared: SharedSubscriptions,
        sync: HistorySync,
        @ApplicationScope scope: CoroutineScope,
    ): HistoryWiring = HistoryWiring(profiles.state, shared, sync, scope)
```

with imports `com.gekkotron.oikos.alerts.sync.HistorySync`, `com.gekkotron.oikos.connection.HistoryWiring`, `com.gekkotron.oikos.connection.SharedSubscriptions`, `com.gekkotron.oikos.transport.ActiveTransport`, `kotlinx.coroutines.CoroutineScope`. In `OikosApplication` add `@Inject lateinit var historyWiring: HistoryWiring` and call `historyWiring.start()` right after `pushWiring.start()`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:alerts:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. `MainActivityTest` still passes: `EntitySourceSession` receives the same `MqttTransport` interface, now through `SharedSubscriptions`.

- [ ] **Step 9: Commit**

```bash
git add core app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): HistorySync catch-up and shared subscriptions" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: `:feature:alerts` — rows, detail sheet, demo alerts, "Open device"

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts` (Kover), `gradle/libs.versions.toml`
- Create: `feature/alerts/build.gradle.kts`, `feature/alerts/src/main/res/values/strings.xml`, `values-fr/strings.xml`, `feature/alerts/src/main/res/drawable/demo_alert_front_door.xml`, `demo_alert_garden.xml`, `demo_alert_garage.xml`
- Create: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertTimes.kt`, `EntityMatch.kt`, `AlertDetailViewModel.kt`
- Create: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertImage.kt`, `AlertRow.kt`, `AlertDetailSheet.kt`, `VideoPlayer.kt`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/demo/DemoAlerts.kt`, test `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/demo/DemoAlertsTest.kt`
- Modify (`:feature:home`): `presentation/CustomizeViewModel.kt` (add `send`), create `ui/EntityControlSheet.kt`, test in `CustomizeViewModelTest.kt`
- Test: `feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/EntityMatchTest.kt`, `AlertTimesTest.kt`, `AlertDetailViewModelTest.kt`, `ui/AlertDetailSheetTest.kt`, `ui/AlertRowTest.kt`, `ui/AlertDetailScreenshotTest.kt`, `FakeEntitySource.kt`, `ScreenshotSupport.kt`

**Interfaces:**
- Consumes: Tasks 1-11 (`Alert`, `AlertRepository`, `VideoCache`, `VideoState`, `MediaFailure`, `MediaException`, `AlertMedia`, `ReplyQueue`, `ReplyState`, `AlertChannels.nameRes`, `EntitySource`).
- Produces:
  - `object DemoAlerts { fun all(now: Instant): List<Alert> }` (six alerts, one per main channel, local `demo://` pictures)
  - `object AlertTimes { fun relative(context: Context, then: Instant, now: Instant): String }`
  - `object EntityMatch { fun find(entityId: String?, entities: Map<String, Entity>): String? }`
  - `sealed interface DetailState { Loading; Missing; data class Found(val alert: Alert, val deviceId: String?) }`, `sealed interface DetailEvent { Sent; data class CouldNotSend(val label: String) }`
  - `class AlertDetailViewModel @Inject constructor(repository, videos: VideoCache, replies: ReplyQueue, source: EntitySource)` with `fun state(id: String): Flow<DetailState>`, `fun video(alert: Alert): Flow<VideoState>`, `val events: Flow<DetailEvent>`, `fun send(alertId: String, action: AlertAction)`
  - `@Composable fun AlertRow(alert: Alert, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier)`, `@Composable fun AlertImage(url: String, modifier: Modifier, contentDescription: String?, error: @Composable (Throwable?) -> Unit)`, `fun AlertChannel.glyph(): OikosGlyph`
  - `@Composable fun AlertDetailRoute(alertId: String, onDismiss: () -> Unit, deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null, viewModel: AlertDetailViewModel = hiltViewModel())`; stateless `AlertDetailSheet(state, video, onPlay, onAction, onOpenDevice, onDismiss, snackbarHostState, playerContent)` and `AlertDetailContent(...)` (the sheet's body, also what the screenshot test renders)
  - (`:feature:home`) `CustomizeViewModel.send(id: String, command: Command)`, `@Composable fun EntityControlSheet(id: String, onDismiss: () -> Unit, onSaveFailed: () -> Unit, viewModel: CustomizeViewModel = hiltViewModel())`

Spec §7.3: a `ModalBottomSheet` with a full-width 16:9 rounded picture (a placeholder while loading, "Media no longer available" for `Gone` and `NotAllowed`), a play button when there is a `videoUrl` that turns into a progress ring while the clip downloads and then an inline Media3 player, the title, body, a channel chip, the full date and time, a "Resolved" chip, the action buttons (the same `ReplyQueue` as the notification, with a *Sent* / *Couldn't send* snackbar), and "Open device" when `entityId` matches a known entity. If the alert is not in Room the sheet says "This alert is no longer available". `AlertImage` shows `demo://` pictures from bundled drawables and everything else through Coil with the `AlertMedia` type only. `EntityMatch` accepts the exact unique id, the object id after the dot of a Home-Assistant-style `entity_id` (`binary_sensor.front_door` gives `front_door`), or a slug of the entity's name. `DemoAlerts` lives here because `:core:demo` is a plain JVM module that cannot depend on this Android module (spec §7.6 says `:core:demo`).

- [ ] **Step 1: Module, catalog, resources**

`settings.gradle.kts`, after `include(":feature:settings")`: `include(":feature:alerts")`. Root `build.gradle.kts`, in the Kover list: `kover(project(":feature:alerts"))`.

`gradle/libs.versions.toml` `[versions]`: `media3 = "1.11.1"`; `[libraries]`:

```toml
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
androidx-media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
```

Media3 1.11.1 needs `compileSdk` 36 (checked in its AAR metadata), which the project has.

`feature/alerts/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.alerts"
}

dependencies {
    implementation(project(":core:alerts"))
    implementation(project(":core:push"))
    implementation(project(":core:source"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
```

`feature/alerts/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="alerts_title">Alerts</string>
    <string name="alerts_back">Back</string>
    <string name="alerts_recent">Recent alerts</string>
    <string name="alerts_see_all">See all</string>
    <string name="alerts_empty">No alerts yet</string>
    <string name="alerts_empty_filter">No alerts in this category</string>
    <string name="alerts_resolved">Resolved</string>
    <string name="alerts_just_now">Just now</string>
    <string name="alerts_today">Today</string>
    <string name="alerts_yesterday">Yesterday</string>
    <string name="alerts_filter_all">All</string>
    <string name="alerts_more">More options</string>
    <string name="alerts_clear_all">Clear all</string>
    <string name="alerts_clear_title">Clear all alerts?</string>
    <string name="alerts_clear_body">This only clears this phone.</string>
    <string name="alerts_clear_confirm">Clear</string>
    <string name="alerts_cancel">Cancel</string>
    <string name="alerts_notifications_off">Notifications are off</string>
    <string name="alerts_turn_on">Turn on</string>
    <plurals name="alerts_catch_up">
        <item quantity="one">%d alert arrived while you were offline</item>
        <item quantity="other">%d alerts arrived while you were offline</item>
    </plurals>
    <string name="alert_gone">This alert is no longer available</string>
    <string name="alert_media_gone">Media no longer available</string>
    <string name="alert_media_failed">Couldn\'t load the media</string>
    <string name="alert_picture">Picture: %1$s</string>
    <string name="alert_play_video">Play video</string>
    <string name="alert_downloading_video">Downloading the clip</string>
    <string name="alert_open_device">Open device</string>
    <string name="alert_sent">Sent</string>
    <string name="alert_send_failed">Couldn\'t send %1$s</string>
</resources>
```

`feature/alerts/src/main/res/values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="alerts_title">Alertes</string>
    <string name="alerts_back">Retour</string>
    <string name="alerts_recent">Alertes récentes</string>
    <string name="alerts_see_all">Tout voir</string>
    <string name="alerts_empty">Aucune alerte pour l\'instant</string>
    <string name="alerts_empty_filter">Aucune alerte dans cette catégorie</string>
    <string name="alerts_resolved">Résolue</string>
    <string name="alerts_just_now">À l\'instant</string>
    <string name="alerts_today">Aujourd\'hui</string>
    <string name="alerts_yesterday">Hier</string>
    <string name="alerts_filter_all">Toutes</string>
    <string name="alerts_more">Plus d\'options</string>
    <string name="alerts_clear_all">Tout effacer</string>
    <string name="alerts_clear_title">Effacer toutes les alertes ?</string>
    <string name="alerts_clear_body">Cela n\'efface que ce téléphone.</string>
    <string name="alerts_clear_confirm">Effacer</string>
    <string name="alerts_cancel">Annuler</string>
    <string name="alerts_notifications_off">Les notifications sont désactivées</string>
    <string name="alerts_turn_on">Activer</string>
    <plurals name="alerts_catch_up">
        <item quantity="one">%d alerte reçue pendant que vous étiez hors ligne</item>
        <item quantity="other">%d alertes reçues pendant que vous étiez hors ligne</item>
    </plurals>
    <string name="alert_gone">Cette alerte n\'est plus disponible</string>
    <string name="alert_media_gone">Média plus disponible</string>
    <string name="alert_media_failed">Impossible de charger le média</string>
    <string name="alert_picture">Image : %1$s</string>
    <string name="alert_play_video">Lire la vidéo</string>
    <string name="alert_downloading_video">Téléchargement de la vidéo</string>
    <string name="alert_open_device">Ouvrir l\'appareil</string>
    <string name="alert_sent">Envoyé</string>
    <string name="alert_send_failed">Impossible d\'envoyer %1$s</string>
</resources>
```

The three demo pictures are vector scenes (no binary assets). `feature/alerts/src/main/res/drawable/demo_alert_front_door.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp"
    android:height="180dp"
    android:viewportWidth="320"
    android:viewportHeight="180">
    <path android:fillColor="#FF1B2240" android:pathData="M0,0h320v180h-320z" />
    <path android:fillColor="#FF2A3358" android:pathData="M0,128h320v52h-320z" />
    <path android:fillColor="#FF0B1020" android:pathData="M118,36h84v100h-84z" />
    <path android:fillColor="#FFFFC457" android:pathData="M126,44h68v92h-68z" />
    <path android:fillColor="#FF0B1020" android:pathData="M180,92a5,5 0 1,1 -10,0a5,5 0 1,1 10,0z" />
</vector>
```

`demo_alert_garden.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp"
    android:height="180dp"
    android:viewportWidth="320"
    android:viewportHeight="180">
    <path android:fillColor="#FF18302B" android:pathData="M0,0h320v180h-320z" />
    <path android:fillColor="#FF2F6F5B" android:pathData="M0,110h320v70h-320z" />
    <path android:fillColor="#FF6FF0BF" android:pathData="M60,110c0,-30 36,-30 36,0z" />
    <path android:fillColor="#FF6FF0BF" android:pathData="M220,110c0,-40 48,-40 48,0z" />
    <path android:fillColor="#FFE8ECFF" android:pathData="M154,64a8,8 0 1,1 -16,0a8,8 0 1,1 16,0z" />
    <path android:fillColor="#FFE8ECFF" android:pathData="M138,76h16v38h-16z" />
</vector>
```

`demo_alert_garage.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp"
    android:height="180dp"
    android:viewportWidth="320"
    android:viewportHeight="180">
    <path android:fillColor="#FF2B1A25" android:pathData="M0,0h320v180h-320z" />
    <path android:fillColor="#FF4A2A3A" android:pathData="M70,40h180v110h-180z" />
    <path android:fillColor="#FFFF7A9A" android:pathData="M82,52h156v22h-156z" />
    <path android:fillColor="#FF6B3A50" android:pathData="M82,80h156v22h-156z" />
    <path android:fillColor="#FF6B3A50" android:pathData="M82,108h156v30h-156z" />
</vector>
```

- [ ] **Step 2: Write the failing tests for the pure parts**

`core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/demo/DemoAlertsTest.kt`:

```kotlin
package com.gekkotron.oikos.alerts.demo

import com.gekkotron.oikos.alerts.AlertChannel
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoAlertsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    @Test
    fun `six alerts, one per main channel, newest first, with unique ids`() {
        val alerts = DemoAlerts.all(now)
        assertEquals(6, alerts.size)
        assertEquals(
            setOf(AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA, AlertChannel.ENERGY, AlertChannel.CLIMATE, AlertChannel.SYSTEM),
            alerts.map { it.channel }.toSet(),
        )
        assertEquals(6, alerts.map { it.id }.toSet().size)
        assertEquals(alerts.sortedByDescending { it.sentAt }, alerts)
        assertTrue(alerts.all { it.sentAt <= now })
    }

    @Test
    fun `pictures are local demo resources, never network urls, and there is no clip to fetch`() {
        val alerts = DemoAlerts.all(now)
        val pictures = alerts.mapNotNull { it.imageUrl }
        assertTrue(pictures.isNotEmpty() && pictures.all { it.startsWith("demo://") })
        assertTrue(alerts.all { it.videoUrl == null })
    }

    @Test
    fun `the set shows a resolved alert and an alert with action buttons`() {
        val alerts = DemoAlerts.all(now)
        assertTrue(alerts.any { it.resolved })
        assertTrue(alerts.any { it.actions.isNotEmpty() })
    }

    @Test
    fun `the same moment gives the same alerts`() {
        assertEquals(DemoAlerts.all(now), DemoAlerts.all(now))
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/EntityMatchTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntityMatchTest {
    private object NoBindings : Bindings

    private fun entity(id: String, name: String = id) =
        Entity(id, null, name, Kind.BINARY_SENSOR, "door", null, null, Capabilities(), NoBindings)

    private val entities = listOf(entity("front_door", "Front door"), entity("0xa4c138", "Garage sensor")).associateBy { it.uniqueId }

    @Test
    fun `the exact unique id matches`() {
        assertEquals("0xa4c138", EntityMatch.find("0xa4c138", entities))
    }

    @Test
    fun `an entity_id matches on its object id`() {
        assertEquals("front_door", EntityMatch.find("binary_sensor.front_door", entities))
    }

    @Test
    fun `an entity_id matches the entity's name slug`() {
        assertEquals("0xa4c138", EntityMatch.find("binary_sensor.garage_sensor", entities))
    }

    @Test
    fun `unknown, blank and null match nothing`() {
        assertNull(EntityMatch.find("binary_sensor.window", entities))
        assertNull(EntityMatch.find("  ", entities))
        assertNull(EntityMatch.find(null, entities))
        assertNull(EntityMatch.find("binary_sensor.front_door", emptyMap()))
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertTimesTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertTimesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    @Test
    fun `under a minute is just now, also for a clock that is a little ahead`() {
        assertEquals("Just now", AlertTimes.relative(context, now.minusSeconds(30), now))
        assertEquals("Just now", AlertTimes.relative(context, now.plusSeconds(20), now))
    }

    @Test
    fun `minutes and hours are relative`() {
        assertTrue("2" in AlertTimes.relative(context, now.minusSeconds(120), now))
        assertTrue("3" in AlertTimes.relative(context, now.minusSeconds(3 * 3600L), now))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the words follow the locale`() {
        assertEquals("À l'instant", AlertTimes.relative(context, now.minusSeconds(5), now))
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/FakeEntitySource.kt` (a copy of the one in `:feature:security`, trimmed to what these tests use):

```kotlin
package com.gekkotron.oikos.feature.alerts

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    private val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())

    override fun states() = stateMap
    override suspend fun send(uniqueId: String, command: Command): CommandResult = CommandResult.Accepted
    override suspend fun setOverride(uniqueId: String, override: UiOverride) = Unit
    override suspend fun setAreas(areas: List<Area>) = Unit
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertDetailViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.Severity
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.feature.alerts.FakeEntitySource
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlertDetailViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val source = FakeEntitySource()
    private val replyState = MutableStateFlow(ReplyState.PENDING)
    private val enqueued = mutableListOf<Triple<String, String, String>>()

    private val replies = object : ReplyQueue {
        override fun enqueue(alertId: String, actionId: String, label: String): UUID {
            enqueued += Triple(alertId, actionId, label)
            return UUID.randomUUID()
        }

        override fun state(id: UUID): Flow<ReplyState> = replyState

        override fun cancelAll() = Unit
    }

    private object NoBindings : Bindings

    private val clipSource = object : MediaSource {
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit) = Result.success(Media(ByteArray(3), "video/mp4"))
    }

    private fun alert(entityId: String? = null, videoUrl: String? = null) = Alert(
        "frigate-1", AlertChannel.CAMERA, Severity.WARNING, "Front door", "Person", null, videoUrl, entityId,
        listOf(AlertAction("disarm", "Disarm")), "frigate", false, false, Instant.EPOCH, Instant.EPOCH,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun vm() = AlertDetailViewModel(repo, VideoCache(tmp.newFolder("video"), clipSource), replies, source)

    @Test
    fun `an alert in Room is Found with its matching device`() = runTest(dispatcher) {
        source.entities.value = mapOf("front_door" to Entity("front_door", null, "Front door", Kind.BINARY_SENSOR, "door", null, null, Capabilities(), NoBindings))
        repo.upsert(alert(entityId = "binary_sensor.front_door"))
        val found = assertIs<DetailState.Found>(vm().state("frigate-1").first())
        assertEquals("front_door", found.deviceId)
        assertEquals("Front door", found.alert.title)
    }

    @Test
    fun `no matching entity means no Open device`() = runTest(dispatcher) {
        repo.upsert(alert(entityId = "binary_sensor.window"))
        assertEquals(null, assertIs<DetailState.Found>(vm().state("frigate-1").first()).deviceId)
    }

    @Test
    fun `an alert that is not in Room is Missing`() = runTest(dispatcher) {
        assertEquals(DetailState.Missing, vm().state("nope").first())
    }

    @Test
    fun `a clip is downloaded and ready, an alert without a clip has no video`() = runTest(dispatcher) {
        val states = vm().video(alert(videoUrl = "http://h/c.mp4")).toList()
        assertIs<VideoState.Ready>(states.last())
        assertEquals(emptyList(), vm().video(alert()).toList())
    }

    @Test
    fun `an action is queued and the outcome is reported as Sent`() = runTest(dispatcher) {
        val vm = vm()
        vm.send("frigate-1", AlertAction("disarm", "Disarm"))
        assertEquals(listOf(Triple("frigate-1", "disarm", "Disarm")), enqueued)
        replyState.value = ReplyState.SENT
        assertEquals(DetailEvent.Sent, vm.events.first())
    }

    @Test
    fun `a reply that gives up is reported with its label`() = runTest(dispatcher) {
        val vm = vm()
        vm.send("frigate-1", AlertAction("disarm", "Disarm"))
        replyState.value = ReplyState.FAILED
        assertEquals(DetailEvent.CouldNotSend("Disarm"), vm.events.first())
    }
}
```

Home's `CustomizeViewModelTest` gets a new test (append inside the class):

```kotlin
    @Test
    fun `send forwards the command to the source`() = runTest(dispatcher) {
        val vm = CustomizeViewModel(source, LabelText { null })
        vm.send("door", com.gekkotron.oikos.model.Command.Press)
        assertEquals(listOf("door" to com.gekkotron.oikos.model.Command.Press), source.sent)
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.alerts.demo.DemoAlertsTest' :feature:alerts:testDebugUnitTest :feature:home:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.home.presentation.CustomizeViewModelTest'`
Expected: compilation FAIL (`DemoAlerts`, `EntityMatch`, `AlertTimes`, `AlertDetailViewModel`, `CustomizeViewModel.send` unresolved).

- [ ] **Step 4: Implement the non-UI parts**

`core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/demo/DemoAlerts.kt`:

```kotlin
package com.gekkotron.oikos.alerts.demo

import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.Severity
import java.time.Duration
import java.time.Instant

/** Six fixed alerts for the demo house, previews and screenshots (push alerts spec §7.6). Pictures are `demo://` resources. */
object DemoAlerts {
    const val FRONT_DOOR_IMAGE = "demo://front_door"
    const val GARDEN_IMAGE = "demo://garden"
    const val GARAGE_IMAGE = "demo://garage"

    fun all(now: Instant): List<Alert> = listOf(
        demo("demo-security", AlertChannel.SECURITY, Severity.WARNING, "Front door opened", "The entrance door opened while the alarm was armed.", Duration.ofMinutes(2), now, image = FRONT_DOOR_IMAGE),
        demo("demo-camera", AlertChannel.CAMERA, Severity.INFO, "Person in the garden", "A person was detected near the back fence.", Duration.ofMinutes(18), now, image = GARDEN_IMAGE),
        demo("demo-alarm", AlertChannel.ALARM, Severity.CRITICAL, "Alarm triggered", "The garage door was forced. The alarm has been disarmed since.", Duration.ofHours(1), now, image = GARAGE_IMAGE, resolved = true, actions = listOf(AlertAction("silence", "Silence the siren"), AlertAction("disarm", "Disarm"))),
        demo("demo-energy", AlertChannel.ENERGY, Severity.INFO, "Solar peak", "Production reached 3.2 kW, the best of the week.", Duration.ofHours(3), now),
        demo("demo-climate", AlertChannel.CLIMATE, Severity.WARNING, "The living room is warm", "The temperature is 26.4 °C.", Duration.ofHours(26), now),
        demo("demo-system", AlertChannel.SYSTEM, Severity.INFO, "Gateway reconnected", "The remote gateway is back after a short outage.", Duration.ofHours(50), now),
    )

    private fun demo(
        id: String,
        channel: AlertChannel,
        severity: Severity,
        title: String,
        body: String,
        ago: Duration,
        now: Instant,
        image: String? = null,
        resolved: Boolean = false,
        actions: List<AlertAction> = emptyList(),
    ): Alert {
        val sent = now.minus(ago)
        return Alert(id, channel, severity, title, body, image, null, null, actions, "demo", false, resolved, sent, sent)
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertTimes.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import android.content.Context
import android.text.format.DateUtils
import com.gekkotron.oikos.feature.alerts.R
import java.time.Duration
import java.time.Instant

object AlertTimes {
    /** "Just now" under a minute (or a little in the future), then the platform's localized "2 min. ago". */
    fun relative(context: Context, then: Instant, now: Instant): String {
        if (Duration.between(then, now).seconds < 60) return context.getString(R.string.alerts_just_now)
        return DateUtils.getRelativeTimeSpanString(
            then.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/EntityMatch.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.model.Entity

/** Finds the entity an alert's `entity_id` points at, for "Open device" (push alerts spec §7.3). */
object EntityMatch {
    /** The unique id of the match, or null. Tries the exact id, the part after the dot, then name slugs. */
    fun find(entityId: String?, entities: Map<String, Entity>): String? {
        val wanted = entityId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (wanted in entities) return wanted
        val objectId = wanted.substringAfter('.', missingDelimiterValue = wanted)
        if (objectId in entities) return objectId
        val slug = slug(objectId)
        return entities.values.firstOrNull { slug(it.name) == slug || slug(it.uniqueId) == slug }?.uniqueId
    }

    private fun slug(text: String): String = text.lowercase().replace(NOT_ALNUM, "_").trim('_')

    private val NOT_ALNUM = Regex("[^a-z0-9]+")
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertDetailViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

sealed interface DetailState {
    data object Loading : DetailState

    /** "This alert is no longer available". */
    data object Missing : DetailState

    /** [deviceId] is the entity "Open device" opens, or null. */
    data class Found(val alert: Alert, val deviceId: String?) : DetailState
}

sealed interface DetailEvent {
    data object Sent : DetailEvent

    data class CouldNotSend(val label: String) : DetailEvent
}

@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    private val repository: AlertRepository,
    private val videos: VideoCache,
    private val replies: ReplyQueue,
    private val source: EntitySource,
) : ViewModel() {
    fun state(id: String): Flow<DetailState> = combine(repository.byId(id), source.entities) { alert, entities ->
        if (alert == null) DetailState.Missing else DetailState.Found(alert, EntityMatch.find(alert.entityId, entities))
    }

    /** Downloads the clip first, then hands the file to the player (spec §6.3). */
    fun video(alert: Alert): Flow<VideoState> = alert.videoUrl?.let { videos.file(alert.id, it) } ?: emptyFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()

    /** The same worker as the notification's buttons; the outcome comes back as a snackbar event. */
    fun send(alertId: String, action: AlertAction) {
        val id = replies.enqueue(alertId, action.id, action.label)
        viewModelScope.launch {
            val outcome = withTimeoutOrNull(REPLY_WAIT) { replies.state(id).first { it != ReplyState.PENDING } } ?: return@launch
            _events.send(if (outcome == ReplyState.SENT) DetailEvent.Sent else DetailEvent.CouldNotSend(action.label))
        }
    }

    private companion object {
        /** Five attempts with an exponential backoff from 10 s finish well within this. */
        val REPLY_WAIT = 10.minutes
    }
}
```

In `feature/home/.../presentation/CustomizeViewModel.kt` add imports `com.gekkotron.oikos.model.Command` and this method:

```kotlin
    /** The control sheet opened from another screen sends through the same source as Home. */
    fun send(id: String, command: Command) {
        viewModelScope.launch { source.send(id, command) }
    }
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityControlSheet.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.home.presentation.CustomizeLoad
import com.gekkotron.oikos.feature.home.presentation.CustomizeViewModel

/**
 * Home's control sheet for [id], for screens that show a device Home does not own (the alert sheet's "Open device",
 * push alerts spec §7.3). Its Customize link opens the same override sheet Home uses.
 */
@Composable
fun EntityControlSheet(
    id: String,
    onDismiss: () -> Unit,
    onSaveFailed: () -> Unit,
    viewModel: CustomizeViewModel = hiltViewModel(),
) {
    val load by remember(id) { viewModel.load(id) }.collectAsStateWithLifecycle(CustomizeLoad.Loading)
    var customizing by rememberSaveable(id) { mutableStateOf(false) }
    when (val l = load) {
        CustomizeLoad.Loading -> Unit
        CustomizeLoad.Gone -> LaunchedEffect(id) { onDismiss() }
        is CustomizeLoad.Ready ->
            if (customizing) {
                CustomizeSheet(
                    l.card,
                    l.rooms,
                    onSave = { rooms, override -> viewModel.save(id, rooms, override, onSaveFailed) },
                    onDismiss = { customizing = false },
                )
            } else {
                ControlSheet(l.card, onSend = viewModel::send, onCustomize = { customizing = true }, onDismiss = onDismiss)
            }
    }
}
```

- [ ] **Step 5: Run the non-UI tests to verify they pass**

Run: `./gradlew :core:alerts:testDebugUnitTest :feature:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.alerts.presentation.*' :feature:home:testDebugUnitTest`
Expected: PASS. (The `:feature:alerts` UI tests below do not exist yet.)

- [ ] **Step 6: Write the failing UI tests and the screenshot support**

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ScreenshotSupport.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.io.File

typealias ActivityRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * Renders the current content with Robolectric's native graphics and writes `build/screenshots/<name>.png`. The
 * clock is frozen first: `captureToImage` can time out on content that animates (the same trick as the energy flow
 * screenshots). There is no golden image to diff against; the callers assert the picture is not blank and follows the theme.
 */
fun ActivityRule.renderPng(name: String): Bitmap {
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(300)
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    File("build/screenshots/$name.png").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bitmap
}

/** How many different colours a sparse sample of [this] holds: a blank render has one. */
fun Bitmap.distinctColors(): Int {
    val seen = HashSet<Int>()
    for (x in 0 until width step 7) for (y in 0 until height step 7) seen += getPixel(x, y)
    return seen.size
}

fun Bitmap.luminanceAt(x: Int, y: Int): Float {
    val p = getPixel(x, y)
    return (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertRowTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlertRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val alerts = DemoAlerts.all(now)
    private val clicks = mutableListOf<String>()

    private fun show(index: Int) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { AlertRow(alerts[index], now, onClick = { clicks += alerts[index].id }) }
    }

    @Test
    fun `a row shows the title, the body and the relative time`() {
        show(0)
        compose.onNodeWithText("Front door opened").assertExists()
        compose.onNodeWithText("The entrance door opened while the alarm was armed.").assertExists()
        compose.onNodeWithText("2", substring = true).assertExists()
    }

    @Test
    fun `a resolved alert is labelled Resolved`() {
        show(2)
        compose.onNodeWithText("Resolved", substring = true).assertExists()
    }

    @Test
    fun `an unresolved alert has no Resolved label`() {
        show(0)
        compose.onNodeWithText("Resolved", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a click is reported`() {
        show(1)
        compose.onNodeWithText("Person in the garden").performClick()
        assertEquals(listOf("demo-camera"), clicks)
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertDetailSheetTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.MediaException
import com.gekkotron.oikos.alerts.media.MediaFailure
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.DetailState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlertDetailSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val camera = DemoAlerts.all(now)[1]
    private val alarm = DemoAlerts.all(now)[2]
    private val played = mutableListOf<Boolean>()
    private val actions = mutableListOf<AlertAction>()
    private var openedDevice = 0
    private var dismissed = 0

    private fun show(state: DetailState, video: VideoState? = null) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            AlertDetailSheet(
                state, video,
                onPlay = { played += true }, onAction = { actions += it }, onOpenDevice = { openedDevice++ }, onDismiss = { dismissed++ },
                playerContent = { file -> Text("player ${file.name}") },
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun withClip(device: String? = null) = DetailState.Found(camera.copy(videoUrl = "http://192.168.1.20:5000/clip.mp4"), device)

    @Test
    fun `an alert shows its title, body, channel, resolved flag and full date`() {
        show(DetailState.Found(alarm, null))
        compose.onNodeWithText("Alarm triggered").assertExists()
        compose.onNodeWithText("The garage door was forced. The alarm has been disarmed since.").assertExists()
        compose.onNodeWithText("Alarm").assertExists()
        compose.onNodeWithText("Resolved").assertExists()
        compose.onNodeWithText("Oct 7, 2026", substring = true).assertExists()
    }

    @Test
    fun `an alert that is not in Room says so`() {
        show(DetailState.Missing)
        compose.onNodeWithText("This alert is no longer available").assertExists()
    }

    @Test
    fun `action buttons report the tapped action`() {
        show(DetailState.Found(alarm, null))
        compose.onNodeWithText("Disarm").performClick()
        assertEquals(listOf(AlertAction("disarm", "Disarm")), actions)
    }

    @Test
    fun `at most three action buttons are shown`() {
        val many = alarm.copy(actions = (1..5).map { AlertAction("a$it", "Button $it") })
        show(DetailState.Found(many, null))
        compose.onNodeWithText("Button 3").assertExists()
        compose.onNodeWithText("Button 4").assertDoesNotExist()
    }

    @Test
    fun `Open device only shows when a device matches`() {
        show(DetailState.Found(camera, null))
        compose.onNodeWithText("Open device").assertDoesNotExist()
    }

    @Test
    fun `Open device opens the matched device`() {
        show(DetailState.Found(camera, "front_door"))
        compose.onNodeWithText("Open device").performClick()
        assertEquals(1, openedDevice)
    }

    @Test
    fun `a clip shows a play button that asks to download`() {
        show(withClip())
        compose.onNodeWithContentDescription("Play video").performClick()
        assertEquals(listOf(true), played)
    }

    @Test
    fun `an alert without a clip has no play button`() {
        show(DetailState.Found(camera, null))
        compose.onNodeWithContentDescription("Play video").assertDoesNotExist()
    }

    @Test
    fun `a downloading clip shows a progress ring with the fraction`() {
        show(withClip(), VideoState.Downloading(2, 4))
        compose.onNode(
            SemanticsMatcher("progress 0.5") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == 0.5f },
        ).assertExists()
        compose.onNodeWithContentDescription("Play video").assertDoesNotExist()
    }

    @Test
    fun `a downloading clip of unknown size shows an indeterminate ring`() {
        show(withClip(), VideoState.Downloading(0, null))
        compose.onNode(
            SemanticsMatcher("indeterminate") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate },
        ).assertExists()
    }

    @Test
    fun `a clip that is gone or refused says the media is no longer available`() {
        show(withClip(), VideoState.Failed(MediaFailure.Gone))
        compose.onNodeWithText("Media no longer available").assertExists()
    }

    @Test
    fun `a clip that failed for another reason says it could not load`() {
        show(withClip(), VideoState.Failed(MediaFailure.Unavailable("http 500")))
        compose.onNodeWithText("Couldn't load the media").assertExists()
    }

    @Test
    fun `a ready clip hands its file to the player`() {
        show(withClip(), VideoState.Ready(File("abc.mp4")))
        compose.onNodeWithText("player abc.mp4").assertExists()
    }

    @Test
    fun `a picture error maps to its message`() {
        assertEquals(R.string.alert_media_gone, mediaErrorRes(MediaException(MediaFailure.Gone).mediaFailure()))
        assertEquals(R.string.alert_media_gone, mediaErrorRes(MediaException(MediaFailure.NotAllowed).mediaFailure()))
        assertEquals(R.string.alert_media_failed, mediaErrorRes(MediaException(MediaFailure.Unavailable("x")).mediaFailure()))
        assertEquals(R.string.alert_media_failed, mediaErrorRes(RuntimeException("decoder").mediaFailure()))
        assertEquals(R.string.alert_media_failed, mediaErrorRes(null.mediaFailure()))
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertDetailScreenshotTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.distinctColors
import com.gekkotron.oikos.feature.alerts.luminanceAt
import com.gekkotron.oikos.feature.alerts.presentation.DetailState
import com.gekkotron.oikos.feature.alerts.renderPng
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class AlertDetailScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun render(theme: ThemeChoice, name: String, state: DetailState, video: VideoState? = null) = run {
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground {
                    AlertDetailContent(state, video, onPlay = {}, onAction = {}, onOpenDevice = {}, zone = ZoneOffset.UTC)
                }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `picture, clip button and action buttons in Night Glass`() {
        val alert = DemoAlerts.all(now)[2].copy(videoUrl = "http://192.168.1.20:5000/clip.mp4")
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "alert-detail-night", DetailState.Found(alert, "garage_door"))
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `resolved alert in Warm Minimal`() {
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "alert-detail-warm", DetailState.Found(DemoAlerts.all(now)[2], null))
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `gone media in Night Glass`() {
        val alert = DemoAlerts.all(now)[1].copy(videoUrl = "http://192.168.1.20:5000/clip.mp4")
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "alert-detail-gone", DetailState.Found(alert, null), VideoState.Failed(com.gekkotron.oikos.alerts.media.MediaFailure.Gone))
        assertTrue(bitmap.distinctColors() > 8)
    }
}
```

- [ ] **Step 7: Run them to verify they fail**

Run: `./gradlew :feature:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.alerts.ui.*'`
Expected: compilation FAIL (`AlertRow`, `AlertDetailSheet`, `AlertDetailContent`, `mediaErrorRes` unresolved).

- [ ] **Step 8: Implement the UI**

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertImage.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.SubcomposeAsyncImage
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.alerts.media.AlertMedia

@DrawableRes
internal fun demoImageRes(url: String): Int? = when (url) {
    DemoAlerts.FRONT_DOOR_IMAGE -> R.drawable.demo_alert_front_door
    DemoAlerts.GARDEN_IMAGE -> R.drawable.demo_alert_garden
    DemoAlerts.GARAGE_IMAGE -> R.drawable.demo_alert_garage
    else -> null
}

/** Channel glyphs from the existing icon set: no new drawing for the alerts screens. */
fun AlertChannel.glyph(): OikosGlyph = when (this) {
    AlertChannel.ALARM -> OikosGlyph.SHIELD
    AlertChannel.SECURITY -> OikosGlyph.WALKER
    AlertChannel.CAMERA -> OikosGlyph.PLAY
    AlertChannel.ENERGY -> OikosGlyph.BOLT
    AlertChannel.CLIMATE -> OikosGlyph.THERMOMETER
    AlertChannel.SYSTEM -> OikosGlyph.GEAR
    AlertChannel.MISC -> OikosGlyph.DOT
}

/**
 * A picture of an alert. `demo://` urls are bundled drawables; everything else goes through Coil as an [AlertMedia],
 * the only request type the alert fetcher (and so the gateway) handles. [error] gets the failure to explain.
 */
@Composable
fun AlertImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    error: @Composable (Throwable?) -> Unit = {},
) {
    val demo = demoImageRes(url)
    if (demo != null) {
        Image(painterResource(demo), contentDescription, modifier, contentScale = ContentScale.Crop)
        return
    }
    SubcomposeAsyncImage(
        model = AlertMedia(url),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = { Box(Modifier.fillMaxSize().background(OikosTheme.colors.chip)) },
        error = { state -> error(state.result.throwable) },
    )
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertRow.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.AlertTimes
import java.time.Instant

/** One alert in a list (push alerts spec §7.1): a 56 dp thumbnail or the channel glyph, title, one line of body, the time. */
@Composable
fun AlertRow(alert: Alert, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OikosTheme.colors
    val context = LocalContext.current
    OikosCard(modifier.fillMaxWidth().testTag("alert-${alert.id}"), onClick = onClick, minHeight = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(c.chip),
                contentAlignment = Alignment.Center,
            ) {
                val image = alert.imageUrl
                if (image != null) {
                    AlertImage(image, Modifier.size(56.dp))
                } else {
                    StaticIcon(alert.channel.glyph(), modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(alert.title, style = OikosTheme.type.cardName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (alert.body.isNotBlank()) {
                    Text(alert.body, style = OikosTheme.type.label, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val time = AlertTimes.relative(context, alert.sentAt, now)
                Text(
                    if (alert.resolved) "$time · ${stringResource(R.string.alerts_resolved)}" else time,
                    style = OikosTheme.type.label,
                    color = c.contentMuted,
                )
            }
        }
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/VideoPlayer.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** Inline Media3 playback of a downloaded clip (push alerts spec §6.3). The player is released with the composable. */
@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
        modifier = modifier.fillMaxSize(),
    )
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertDetailSheet.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.media.MediaException
import com.gekkotron.oikos.alerts.media.MediaFailure
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.AlertDetailViewModel
import com.gekkotron.oikos.feature.alerts.presentation.DetailEvent
import com.gekkotron.oikos.feature.alerts.presentation.DetailState
import com.gekkotron.oikos.push.AlertChannels
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@StringRes
internal fun mediaErrorRes(failure: MediaFailure): Int = when (failure) {
    MediaFailure.Gone, MediaFailure.NotAllowed -> R.string.alert_media_gone
    is MediaFailure.Unavailable -> R.string.alert_media_failed
}

internal fun Throwable?.mediaFailure(): MediaFailure =
    (this as? MediaException)?.failure ?: MediaFailure.Unavailable(this?.message ?: "error")

/**
 * The detail sheet for [alertId], from a row or from the `oikos://alert/<id>` link (push alerts spec §7.3).
 * [deviceSheet] is Home's control sheet, passed in by the app so this module does not depend on Home.
 */
@Composable
fun AlertDetailRoute(
    alertId: String,
    onDismiss: () -> Unit,
    deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    viewModel: AlertDetailViewModel = hiltViewModel(),
) {
    val loaded by remember(alertId) { viewModel.state(alertId) }.collectAsStateWithLifecycle(DetailState.Loading)
    val state = (loaded as? DetailState.Found)?.takeIf { deviceSheet == null }?.copy(deviceId = null) ?: loaded
    var playing by rememberSaveable(alertId) { mutableStateOf(false) }
    val found = state as? DetailState.Found
    val video by produceState<VideoState?>(null, found?.alert?.id, found?.alert?.videoUrl, playing) {
        value = null
        if (playing && found != null) viewModel.video(found.alert).collect { value = it }
    }
    var deviceId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            when (event) {
                DetailEvent.Sent -> snackbar.showSnackbar(resources.getString(R.string.alert_sent))
                is DetailEvent.CouldNotSend -> snackbar.showSnackbar(resources.getString(R.string.alert_send_failed, event.label))
            }
        }
    }
    AlertDetailSheet(
        state, video,
        onPlay = { playing = true },
        onAction = { action -> found?.let { viewModel.send(it.alert.id, action) } },
        onOpenDevice = { deviceId = found?.deviceId },
        onDismiss = onDismiss,
        snackbarHostState = snackbar,
    )
    deviceId?.let { id -> deviceSheet?.invoke(id) { deviceId = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailSheet(
    state: DetailState,
    video: VideoState?,
    onPlay: () -> Unit,
    onAction: (AlertAction) -> Unit,
    onOpenDevice: () -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    playerContent: @Composable (File) -> Unit = { VideoPlayer(it) },
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // Fully expanded and scrollable, so the picture, text and buttons stay reachable at 200 % font (spec §5.3 of the parent).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AlertDetailContent(state, video, onPlay, onAction, onOpenDevice, snackbarHostState, playerContent, zone)
    }
}

@Composable
fun AlertDetailContent(
    state: DetailState,
    video: VideoState?,
    onPlay: () -> Unit,
    onAction: (AlertAction) -> Unit,
    onOpenDevice: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    playerContent: @Composable (File) -> Unit = { VideoPlayer(it) },
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            DetailState.Loading -> Unit
            DetailState.Missing -> Text(stringResource(R.string.alert_gone), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
            is DetailState.Found -> Found(state, video, onPlay, onAction, onOpenDevice, playerContent, zone)
        }
        SnackbarHost(snackbarHostState)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Found(
    state: DetailState.Found,
    video: VideoState?,
    onPlay: () -> Unit,
    onAction: (AlertAction) -> Unit,
    onOpenDevice: () -> Unit,
    playerContent: @Composable (File) -> Unit,
    zone: ZoneId,
) {
    val alert = state.alert
    if (alert.imageUrl != null || alert.videoUrl != null) MediaBlock(alert, video, onPlay, playerContent)
    Text(alert.title, style = OikosTheme.type.display.copy(fontSize = 22.sp))
    if (alert.body.isNotBlank()) Text(alert.body, style = OikosTheme.type.body)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoChip(stringResource(AlertChannels.nameRes(alert.channel)))
        if (alert.resolved) InfoChip(stringResource(R.string.alerts_resolved))
    }
    Text(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(zone).format(alert.sentAt),
        style = OikosTheme.type.label,
        color = OikosTheme.colors.contentMuted,
    )
    if (alert.shownActions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            alert.shownActions.forEach { action -> FilledTonalButton(onClick = { onAction(action) }) { Text(action.label) } }
        }
    }
    if (state.deviceId != null) {
        TextButton(onClick = onOpenDevice, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alert_open_device)) }
    }
}

@Composable
private fun MediaBlock(alert: Alert, video: VideoState?, onPlay: () -> Unit, playerContent: @Composable (File) -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(OikosTheme.colors.chip),
        contentAlignment = Alignment.Center,
    ) {
        if (video is VideoState.Ready) {
            playerContent(video.file)
            return@Box
        }
        val image = alert.imageUrl
        if (image != null) {
            AlertImage(
                image, Modifier.fillMaxSize(), contentDescription = stringResource(R.string.alert_picture, alert.title),
                error = { cause -> MediaMessage(stringResource(mediaErrorRes(cause.mediaFailure()))) },
            )
        } else {
            StaticIcon(alert.channel.glyph(), modifier = Modifier.size(48.dp))
        }
        when {
            video is VideoState.Downloading -> ProgressRing(video.fraction)
            video is VideoState.Failed -> MediaMessage(stringResource(mediaErrorRes(video.failure)))
            alert.videoUrl != null -> {
                val label = stringResource(R.string.alert_play_video)
                FilledTonalIconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = label }) {
                    StaticIcon(OikosGlyph.PLAY, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float?) {
    val label = stringResource(R.string.alert_downloading_video)
    val modifier = Modifier.size(48.dp).semantics { contentDescription = label }
    if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = modifier) else CircularProgressIndicator(modifier = modifier)
}

@Composable
private fun MediaMessage(text: String) {
    Text(
        text,
        style = OikosTheme.type.label,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxSize().background(OikosTheme.colors.chip).padding(16.dp),
    )
}

@Composable
private fun InfoChip(text: String) {
    val c = OikosTheme.colors
    Text(
        text,
        style = OikosTheme.type.label,
        color = c.content,
        modifier = Modifier.clip(CircleShape).background(c.chip).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :core:alerts:testDebugUnitTest :feature:alerts:testDebugUnitTest :feature:home:testDebugUnitTest`
Expected: PASS. PNGs appear under `feature/alerts/build/screenshots/`; open one to eyeball it once.


- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml core feature
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): alert rows, detail sheet with picture, clip and actions, demo alerts" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Alerts screen, `AlertsKey`, and the `oikos://alert/<id>` deep link

**Files:**
- Create: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/DayGroups.kt`, `AlertsViewModel.kt`
- Create: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertsScreen.kt`, `Now.kt`
- Test: `feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/DayGroupsTest.kt`, `AlertsViewModelTest.kt`, `ui/AlertsScreenTest.kt`, `ui/AlertsScreenshotTest.kt`
- Modify: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/gekkotron/oikos/MainActivity.kt`, `AppViewModel.kt`, `navigation/OikosNavHost.kt`, `app/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/DeepLinks.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/DeepLinksTest.kt`, `app/src/test/kotlin/com/gekkotron/oikos/navigation/AlertLinkTest.kt`, modify `navigation/MainTabsTest.kt`

**Interfaces:**
- Consumes: Task 12 `AlertRow`, `AlertDetailRoute`, `AlertTimes`; Task 2 `AlertRepository.all/clearAll`; designsystem `RoomChips`/`ChipItem`.
- Produces:
  - `sealed interface DayLabel { Today; Yesterday; data class On(val date: LocalDate) }`, `data class DaySection(val label: DayLabel, val alerts: List<Alert>)`, `fun groupByDay(alerts: List<Alert>, zone: ZoneId, today: LocalDate): List<DaySection>`
  - `data class AlertsUi(val filter: AlertChannel? = null, val sections: List<DaySection> = emptyList(), val loading: Boolean = true)`; `class AlertsViewModel` with `val state: StateFlow<AlertsUi>`, `fun select(channel: AlertChannel?)`, `fun clearAll()`
  - `@Composable fun AlertsRoute(onBack: () -> Unit, windowInsets: WindowInsets = ..., deviceSheet: (@Composable (String, () -> Unit) -> Unit)? = null, viewModel: AlertsViewModel = hiltViewModel())`, `@Composable fun AlertsScreen(state: AlertsUi, now: Instant, onSelect: (AlertChannel?) -> Unit, onOpen: (String) -> Unit, onClearAll: () -> Unit, onBack: () -> Unit)`, `@Composable fun rememberNow(): State<Instant>`
  - (`:app`) `@Serializable data object AlertsKey : NavKey`, `@Serializable data class AlertDetailKey(val id: String) : NavKey`, `object DeepLinks { fun alertId(uri: Uri?): String? }`, `AppViewModel.pendingAlert: StateFlow<String?>`, `fun openAlert(id: String)`, `fun alertOpened()`, `internal enum class AlertLinkAction { NONE, OPEN, DROP, WAIT }` with `internal fun alertLinkAction(root: NavKey, pending: String?): AlertLinkAction`

Spec §7.2: header with a back arrow, "Alerts", and an overflow menu with "Clear all" (confirm dialog "Clear all alerts? This only clears this phone."); filter chips All, Alarm, Security, Camera, Energy, Climate, System, Misc; a `LazyColumn` with day headers (Today, Yesterday, then a localized date) and the same rows as the Security tab; an empty state for each filter. Tapping a row opens the detail sheet. Spec §5.2 and §7.3: the notification's tap opens `oikos://alert/<id>`; the activity becomes `singleTop` so a tap while the app is open reaches `onNewIntent` instead of stacking a second activity. The link is held in `AppViewModel.pendingAlert` until the back stack root is Home (Rooms or the notification step first), and dropped when the root is onboarding (no house to show it in).

- [ ] **Step 1: Write the failing `:feature:alerts` tests**

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/DayGroupsTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.alerts.demo.DemoAlerts
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DayGroupsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 7)

    @Test
    fun `alerts are grouped under Today, Yesterday and a date, newest first`() {
        val sections = groupByDay(DemoAlerts.all(now), ZoneOffset.UTC, today)
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.On(LocalDate.of(2026, 10, 5))), sections.map { it.label })
        assertEquals(listOf("demo-security", "demo-camera", "demo-alarm", "demo-energy"), sections[0].alerts.map { it.id })
        assertEquals(listOf("demo-climate"), sections[1].alerts.map { it.id })
        assertEquals(listOf("demo-system"), sections[2].alerts.map { it.id })
    }

    @Test
    fun `the day is the viewer's, not UTC`() {
        val lateEvening = DemoAlerts.all(now).first().copy(sentAt = Instant.parse("2026-10-06T23:30:00Z"))
        val paris = groupByDay(listOf(lateEvening), java.time.ZoneId.of("Europe/Paris"), today)
        assertEquals(DayLabel.Today, paris.single().label)
        assertEquals(DayLabel.Yesterday, groupByDay(listOf(lateEvening), ZoneOffset.UTC, today).single().label)
    }

    @Test
    fun `an out of order list still makes one section per day`() {
        val a = DemoAlerts.all(now)
        val shuffled = listOf(a[0], a[4], a[1])
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday), groupByDay(shuffled, ZoneOffset.UTC, today).map { it.label })
        assertEquals(listOf("demo-security", "demo-camera"), groupByDay(shuffled, ZoneOffset.UTC, today)[0].alerts.map { it.id })
    }

    @Test
    fun `no alerts, no sections`() {
        assertEquals(emptyList(), groupByDay(emptyList(), ZoneOffset.UTC, today))
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertsViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlertsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun seed() = DemoAlerts.all(now).forEach { repo.upsert(it) }

    @Test
    fun `all alerts are grouped by day, and the screen is no longer loading`() = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        val ui = vm.state.first { !it.loading }
        assertEquals(null, ui.filter)
        assertEquals(6, ui.sections.sumOf { it.alerts.size })
    }

    @Test
    fun `a filter shows only its channel`() = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        vm.select(AlertChannel.CAMERA)
        val ui = vm.state.first { it.filter == AlertChannel.CAMERA && !it.loading }
        assertEquals(listOf("demo-camera"), ui.sections.flatMap { it.alerts }.map { it.id })
        vm.select(null)
        assertEquals(6, vm.state.first { it.filter == null && !it.loading }.sections.sumOf { it.alerts.size })
    }

    @Test
    fun `an empty filter has no sections`() = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        vm.select(AlertChannel.MISC)
        assertTrue(vm.state.first { it.filter == AlertChannel.MISC && !it.loading }.sections.isEmpty())
    }

    @Test
    fun `clear all empties the list`() = runTest(dispatcher) {
        seed()
        val vm = AlertsViewModel(repo, { now }, ZoneOffset.UTC)
        backgroundScope.launch { vm.state.collect {} }
        vm.clearAll()
        assertTrue(vm.state.first { it.sections.isEmpty() && !it.loading }.sections.isEmpty())
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertsScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.presentation.AlertsUi
import com.gekkotron.oikos.feature.alerts.presentation.groupByDay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlertsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val all = AlertsUi(null, groupByDay(DemoAlerts.all(now), ZoneOffset.UTC, LocalDate.of(2026, 10, 7)), loading = false)
    private val selected = mutableListOf<AlertChannel?>()
    private val opened = mutableListOf<String>()
    private var cleared = 0
    private var backs = 0

    private fun show(state: AlertsUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            AlertsScreen(state, now, onSelect = { selected += it }, onOpen = { opened += it }, onClearAll = { cleared++ }, onBack = { backs++ })
        }
    }

    @Test
    fun `the title, the chips and the day headers`() {
        show(all)
        compose.onNodeWithText("Alerts").assertExists()
        listOf("All", "Alarm", "Security", "Camera", "Energy", "Climate", "System", "Other").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
        compose.onNodeWithText("Oct 5, 2026").assertExists()
    }

    @Test
    fun `a chip reports its channel and All reports null`() {
        show(all)
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("All").performClick()
        assertEquals(listOf<AlertChannel?>(AlertChannel.CAMERA, null), selected)
    }

    @Test
    fun `a row opens its alert`() {
        show(all)
        compose.onNodeWithText("Person in the garden").performClick()
        assertEquals(listOf("demo-camera"), opened)
    }

    @Test
    fun `no alerts at all and no alerts in a filter have their own words`() {
        show(AlertsUi(null, emptyList(), loading = false))
        compose.onNodeWithText("No alerts yet").assertExists()
    }

    @Test
    fun `an empty filter says no alerts in this category`() {
        show(AlertsUi(AlertChannel.MISC, emptyList(), loading = false))
        compose.onNodeWithText("No alerts in this category").assertExists()
    }

    @Test
    fun `while loading neither the empty state nor rows show`() {
        show(AlertsUi())
        compose.onNodeWithText("No alerts yet").assertDoesNotExist()
    }

    @Test
    fun `clear all asks first and only the confirm clears`() {
        show(all)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Clear all alerts?").assertExists()
        compose.onNodeWithText("This only clears this phone.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, cleared)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `the back arrow goes back`() {
        show(all)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertsScreenshotTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.distinctColors
import com.gekkotron.oikos.feature.alerts.luminanceAt
import com.gekkotron.oikos.feature.alerts.presentation.AlertsUi
import com.gekkotron.oikos.feature.alerts.presentation.groupByDay
import com.gekkotron.oikos.feature.alerts.renderPng
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class AlertsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 7)

    private fun render(theme: ThemeChoice, name: String, filter: AlertChannel?) = run {
        val alerts = DemoAlerts.all(now).filter { filter == null || it.channel == filter }
        val state = AlertsUi(filter, groupByDay(alerts, ZoneOffset.UTC, today), loading = false)
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground { AlertsScreen(state, now, onSelect = {}, onOpen = {}, onClearAll = {}, onBack = {}) }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `All in Night Glass`() {
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "alerts-all-night", null)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `All in Warm Minimal`() {
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "alerts-all-warm", null)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `one filter in Night Glass`() {
        assertTrue(render(ThemeChoice.NIGHT_GLASS, "alerts-camera-night", AlertChannel.CAMERA).distinctColors() > 8)
    }

    @Test
    fun `an empty filter in Warm Minimal`() {
        assertTrue(render(ThemeChoice.WARM_MINIMAL, "alerts-empty-warm", AlertChannel.MISC).distinctColors() > 4)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.alerts.presentation.DayGroupsTest' --tests 'com.gekkotron.oikos.feature.alerts.presentation.AlertsViewModelTest' --tests 'com.gekkotron.oikos.feature.alerts.ui.AlertsScreenTest'`
Expected: compilation FAIL (`groupByDay`, `AlertsViewModel`, `AlertsScreen`, `AlertsUi` unresolved).

- [ ] **Step 3: Implement the screen**

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/DayGroups.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import com.gekkotron.oikos.alerts.Alert
import java.time.LocalDate
import java.time.ZoneId

sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class On(val date: LocalDate) : DayLabel
}

data class DaySection(val label: DayLabel, val alerts: List<Alert>)

/** One section per local day of `sentAt`, in order of first appearance, so a newest-first list stays newest first. */
fun groupByDay(alerts: List<Alert>, zone: ZoneId, today: LocalDate): List<DaySection> {
    val byDate = LinkedHashMap<LocalDate, MutableList<Alert>>()
    alerts.forEach { byDate.getOrPut(LocalDate.ofInstant(it.sentAt, zone)) { mutableListOf() } += it }
    return byDate.map { (date, list) ->
        val label = when (date) {
            today -> DayLabel.Today
            today.minusDays(1) -> DayLabel.Yesterday
            else -> DayLabel.On(date)
        }
        DaySection(label, list)
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/AlertsViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** [loading] is true only before the first read, so the empty state never flashes. */
data class AlertsUi(
    val filter: AlertChannel? = null,
    val sections: List<DaySection> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel internal constructor(
    private val repository: AlertRepository,
    private val clock: () -> Instant,
    private val zone: ZoneId,
) : ViewModel() {
    @Inject
    constructor(repository: AlertRepository) : this(repository, Instant::now, ZoneId.systemDefault())

    private val filter = MutableStateFlow<AlertChannel?>(null)

    val state: StateFlow<AlertsUi> = filter
        .flatMapLatest { channel ->
            repository.all(channel).map { alerts ->
                AlertsUi(channel, groupByDay(alerts, zone, LocalDate.ofInstant(clock(), zone)), loading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUi())

    fun select(channel: AlertChannel?) {
        filter.value = channel
    }

    /** "Clear all" on this phone only (spec §7.2). */
    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/Now.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant

/** The current time, refreshed every minute so "2 min ago" does not freeze on a screen left open. */
@Composable
fun rememberNow(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(60_000)
        value = Instant.now()
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertsScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.RoomChips
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.AlertsUi
import com.gekkotron.oikos.feature.alerts.presentation.AlertsViewModel
import com.gekkotron.oikos.feature.alerts.presentation.DayLabel
import com.gekkotron.oikos.push.AlertChannels
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AlertsRoute(
    onBack: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberNow()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    AlertsScreen(
        state, now,
        onSelect = viewModel::select, onOpen = { openId = it }, onClearAll = viewModel::clearAll, onBack = onBack,
        windowInsets = windowInsets,
    )
    openId?.let { id -> AlertDetailRoute(id, onDismiss = { openId = null }, deviceSheet = deviceSheet) }
}

@Composable
fun AlertsScreen(
    state: AlertsUi,
    now: Instant,
    onSelect: (AlertChannel?) -> Unit,
    onOpen: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val allLabel = stringResource(R.string.alerts_filter_all)
    val chips = listOf(ChipItem(ALL_KEY, allLabel)) + AlertChannel.entries.map { ChipItem(it.id, stringResource(AlertChannels.nameRes(it))) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        contentWindowInsets = windowInsets,
        topBar = { AlertsTopBar(onBack, canClear = state.sections.isNotEmpty(), onClearAll = { confirm = true }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
        ) {
            item(key = "chips") {
                RoomChips(chips, state.filter?.id ?: ALL_KEY, onSelect = { key -> onSelect(AlertChannel.entries.firstOrNull { it.id == key }) })
            }
            when {
                state.loading -> Unit
                state.sections.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(if (state.filter == null) R.string.alerts_empty else R.string.alerts_empty_filter),
                        style = OikosTheme.type.body,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> state.sections.forEach { section ->
                    item(key = "day-${section.label}") {
                        Text(
                            dayTitle(section.label),
                            style = OikosTheme.type.label,
                            color = OikosTheme.colors.contentMuted,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                        )
                    }
                    items(section.alerts, key = { it.id }) { alert -> AlertRow(alert, now, onClick = { onOpen(alert.id) }) }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.alerts_clear_title)) },
            text = { Text(stringResource(R.string.alerts_clear_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onClearAll() }) { Text(stringResource(R.string.alerts_clear_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.alerts_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertsTopBar(onBack: () -> Unit, canClear: Boolean, onClearAll: () -> Unit) {
    val backLabel = stringResource(R.string.alerts_back)
    val moreLabel = stringResource(R.string.alerts_more)
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.alerts_title), style = OikosTheme.type.display) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Text("←", style = OikosTheme.type.cardValue, modifier = Modifier.clearAndSetSemantics {})
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = moreLabel }) {
                    Text("⋮", style = OikosTheme.type.cardValue, modifier = Modifier.clearAndSetSemantics {})
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alerts_clear_all)) },
                        enabled = canClear,
                        onClick = { menu = false; onClearAll() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = OikosTheme.colors.content,
            navigationIconContentColor = OikosTheme.colors.content,
            actionIconContentColor = OikosTheme.colors.content,
        ),
    )
}

@Composable
private fun dayTitle(label: DayLabel): String = when (label) {
    DayLabel.Today -> stringResource(R.string.alerts_today)
    DayLabel.Yesterday -> stringResource(R.string.alerts_yesterday)
    is DayLabel.On -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(label.date)
}

private const val ALL_KEY = "all"
```

- [ ] **Step 4: Run the feature tests to verify they pass**

Run: `./gradlew :feature:alerts:testDebugUnitTest`
Expected: PASS (all of `:feature:alerts`).

- [ ] **Step 5: Write the failing app tests**

`app/src/test/kotlin/com/gekkotron/oikos/DeepLinksTest.kt`:

```kotlin
package com.gekkotron.oikos

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DeepLinksTest {
    @Test
    fun `an alert link gives its id`() {
        assertEquals("frigate-front-1696", DeepLinks.alertId(Uri.parse("oikos://alert/frigate-front-1696")))
    }

    @Test
    fun `an id with path characters survives the notification's own encoding`() {
        val built = Uri.Builder().scheme("oikos").authority("alert").appendPath("a/b c").build()
        assertEquals("a/b c", DeepLinks.alertId(built))
    }

    @Test
    fun `other schemes, hosts and empty ids are not alert links`() {
        assertNull(DeepLinks.alertId(Uri.parse("https://alert/x")))
        assertNull(DeepLinks.alertId(Uri.parse("oikos://reply/x")))
        assertNull(DeepLinks.alertId(Uri.parse("oikos://alert")))
        assertNull(DeepLinks.alertId(Uri.parse("oikos://alert/")))
        assertNull(DeepLinks.alertId(null))
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/navigation/AlertLinkTest.kt`:

```kotlin
package com.gekkotron.oikos.navigation

import org.junit.Test
import kotlin.test.assertEquals

class AlertLinkTest {
    @Test
    fun `nothing pending does nothing`() {
        assertEquals(AlertLinkAction.NONE, alertLinkAction(HomeKey, null))
    }

    @Test
    fun `a pending link opens once Home is the root`() {
        assertEquals(AlertLinkAction.OPEN, alertLinkAction(HomeKey, "a1"))
    }

    @Test
    fun `a pending link waits while rooms or the notification step are still ahead`() {
        assertEquals(AlertLinkAction.WAIT, alertLinkAction(RoomsKey, "a1"))
        assertEquals(AlertLinkAction.WAIT, alertLinkAction(NotificationsKey, "a1"))
    }

    @Test
    fun `a pending link is dropped on onboarding, there is no house to show it in`() {
        assertEquals(AlertLinkAction.DROP, alertLinkAction(OnboardingKey, "a1"))
    }
}
```

`AlertLinkTest` uses `NotificationsKey`: it is declared in this task's nav code below and gets its screen in Task 16.

Append to `app/src/test/kotlin/com/gekkotron/oikos/navigation/MainTabsTest.kt`, inside the class:

```kotlin
    @Test
    fun `the Alerts screen and the alert sheet have no tab bar`() {
        assertEquals(false, showsTabBar(AlertsKey))
        assertEquals(false, showsTabBar(AlertDetailKey("a1")))
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.gekkotron.oikos.DeepLinksTest' --tests 'com.gekkotron.oikos.navigation.*'`
Expected: compilation FAIL (`DeepLinks`, `alertLinkAction`, `AlertsKey`, `NotificationsKey` unresolved).

- [ ] **Step 7: Implement the app side**

`app/build.gradle.kts` gains `implementation(project(":feature:alerts"))` after `implementation(project(":feature:settings"))`.

`app/src/main/kotlin/com/gekkotron/oikos/DeepLinks.kt`:

```kotlin
package com.gekkotron.oikos

import android.net.Uri

/** `oikos://alert/<id>`, the link every alert notification opens (push alerts spec §5.2). */
object DeepLinks {
    fun alertId(uri: Uri?): String? {
        if (uri == null || uri.scheme != "oikos" || uri.host != "alert") return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
```

In `app/src/main/AndroidManifest.xml` replace the `<activity>` element with:

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- A notification's tap (push alerts spec §5.2); the app's own PendingIntent names this package. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="oikos" android:host="alert" />
            </intent-filter>
        </activity>
```

`app/src/main/kotlin/com/gekkotron/oikos/MainActivity.kt` (whole file):

```kotlin
package com.gekkotron.oikos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.navigation.OikosNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** The same instance `hiltViewModel()` hands to the composition below: both use this activity's store. */
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            TesterUpdates.check(this)
            // A rotation re-delivers the same intent: only a fresh launch is a tap on a notification.
            handleIntent(intent)
        }
        setContent {
            val app: AppViewModel = hiltViewModel()
            val theme by app.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeChoice.NIGHT_GLASS -> true
                ThemeChoice.WARM_MINIMAL -> false
                ThemeChoice.SYSTEM, null -> isSystemInDarkTheme()
            }
            // The bar icons follow the chosen theme, not the system one (the default style would).
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            OikosTheme(theme ?: ThemeChoice.SYSTEM) {
                // Until the preferences have loaded only the background is drawn, so Home never flashes in the wrong theme.
                OikosBackground { if (theme != null) OikosNavHost(app) }
            }
        }
    }

    /** `singleTop`: a tap on a notification while the app is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        DeepLinks.alertId(intent?.data)?.let(appViewModel::openAlert)
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
```

In `AppViewModel.kt` add imports `kotlinx.coroutines.flow.MutableStateFlow`, and inside the class:

```kotlin
    private val _pendingAlert = MutableStateFlow<String?>(null)

    /** An `oikos://alert/<id>` tap waiting for the navigation host to show it (push alerts spec §7.3). */
    val pendingAlert: StateFlow<String?> = _pendingAlert

    fun openAlert(id: String) {
        _pendingAlert.value = id
    }

    fun alertOpened() {
        _pendingAlert.value = null
    }
```

`app/src/main/res/values/strings.xml` gains `<string name="device_save_failed">Couldn\'t save the change. Check the connection.</string>`; `values-fr/strings.xml` gains `<string name="device_save_failed">Impossible d\'enregistrer la modification. Vérifiez la connexion.</string>`.

`app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`: add imports `android.widget.Toast`, `androidx.compose.runtime.Composable` (already), `com.gekkotron.oikos.feature.alerts.ui.AlertDetailRoute`, `com.gekkotron.oikos.feature.alerts.ui.AlertsRoute`, `com.gekkotron.oikos.feature.home.ui.EntityControlSheet`; add the keys next to the others:

```kotlin
@Serializable
data object AlertsKey : NavKey

/** Opened by the notification's `oikos://alert/<id>` link; only the sheet shows, over the app background. */
@Serializable
data class AlertDetailKey(val id: String) : NavKey

@Serializable
data object NotificationsKey : NavKey

internal enum class AlertLinkAction { NONE, OPEN, DROP, WAIT }

/** Where a tapped notification may land: only on Home (Rooms and the notification step come first; onboarding has no house). */
internal fun alertLinkAction(root: NavKey, pending: String?): AlertLinkAction = when {
    pending == null -> AlertLinkAction.NONE
    root == HomeKey -> AlertLinkAction.OPEN
    root == OnboardingKey -> AlertLinkAction.DROP
    else -> AlertLinkAction.WAIT
}
```

Inside `OikosNavHost`, after `val route by ...` add `val pendingAlert by viewModel.pendingAlert.collectAsStateWithLifecycle()` and `val context = LocalContext.current`; after the `LaunchedEffect(root) { resetToRoot(backStack, root) }` line add:

```kotlin
    // Declared after resetToRoot's effect, so a cold start has its Home stack before the sheet is pushed on it.
    LaunchedEffect(pendingAlert, root) {
        val id = pendingAlert ?: return@LaunchedEffect
        when (alertLinkAction(root, id)) {
            AlertLinkAction.OPEN -> {
                push(backStack, AlertDetailKey(id))
                viewModel.alertOpened()
            }
            AlertLinkAction.DROP -> viewModel.alertOpened()
            AlertLinkAction.WAIT, AlertLinkAction.NONE -> Unit
        }
    }
    val deviceSheet: @Composable (String, () -> Unit) -> Unit = { id, dismiss ->
        EntityControlSheet(id, dismiss, onSaveFailed = { Toast.makeText(context, R.string.device_save_failed, Toast.LENGTH_SHORT).show() })
    }
```

and two entries inside `entryProvider`:

```kotlin
                entry<AlertsKey> { AlertsRoute(onBack = { pop(backStack, AlertsKey) }, windowInsets = tabInsets, deviceSheet = deviceSheet) }
                entry<AlertDetailKey> { key -> AlertDetailRoute(key.id, onDismiss = { pop(backStack, key) }, deviceSheet = deviceSheet) }
```

Change the root selection `is ProfileState.Ready -> if (p.roomsPending) RoomsKey else HomeKey` only in Task 16.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :feature:alerts:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add feature/alerts app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(alerts): Alerts screen, filters, deep link to the alert sheet" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Recent alerts on the Security tab — banner and catch-up snackbar

**Files:**
- Modify: `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/permissions/NotificationEnabler.kt`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/permissions/NotificationEnablerTest.kt`
- Create: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/RecentAlertsViewModel.kt`, `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/RecentAlertsSection.kt`
- Test: `feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/RecentAlertsViewModelTest.kt`, `ui/RecentAlertsSectionTest.kt`, `ui/RecentAlertsScreenshotTest.kt`, `FakePushStatusSource.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreen.kt`, `feature/security/src/main/res/values/strings.xml`, `values-fr/strings.xml`, `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreenTest.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`

**Interfaces:**
- Consumes: Task 12 `AlertRow`, `AlertDetailRoute`, `rememberNow` (Task 13); Task 10 `PushStatusSource`, `PushStatus`; Task 11 `HistorySync.catchUp/acknowledge`; Task 2 `AlertRepository.recent`.
- Produces:
  - (`:core:designsystem`) `fun shouldOpenSettings(granted: Boolean, showRationale: Boolean): Boolean`, `fun openNotificationSettings(context: Context)`, `@Composable fun rememberNotificationEnabler(onResult: () -> Unit): () -> Unit`
  - `data class RecentAlertsUi(val alerts: List<Alert> = emptyList(), val notificationsOff: Boolean = false)`; `class RecentAlertsViewModel @Inject constructor(repository, status: PushStatusSource, history: HistorySync)` with `val state: StateFlow<RecentAlertsUi>`, `val catchUp: StateFlow<Int>`, `fun acknowledgeCatchUp()`, `fun refreshPermission()`
  - `@Composable fun RecentAlertsSection(alerts: List<Alert>, now: Instant, notificationsOff: Boolean, onTurnOn: () -> Unit, onOpen: (String) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier)`; `@Composable fun RecentAlertsRoute(snackbar: SnackbarHostState, onOpenAll: () -> Unit, deviceSheet: ..., viewModel: RecentAlertsViewModel = hiltViewModel())`
  - (`:feature:security`) `SecurityScreen(..., alertsSection: (@Composable () -> Unit)? = null)`, `SecurityRoute(..., alertsSection: (@Composable (SnackbarHostState) -> Unit)? = null)`

Spec §7.1: the section replaces the placeholder under the sensor sections: title "Recent alerts" with a "See all" button (opens `AlertsKey`), up to 5 rows from `alarm`, `security` and `camera`, newest first, "No alerts yet" when empty, a slim banner "Notifications are off · Turn on" above the rows while `PushStatus` is `NotificationsOff` (it asks for the permission, or opens the app's notification settings once the system no longer shows the prompt), and a row opens the detail sheet. Spec §6.2: "*n* alerts arrived while you were offline" once per sync when *n* > 0. `:feature:security` hosts the section through a slot filled by `:app`, so it does not depend on `:feature:alerts`. The section is shown even when the house has no panel or sensor (a camera-only house still wants its alerts).

- [ ] **Step 1: Write the failing tests**

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/permissions/NotificationEnablerTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.permissions

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NotificationEnablerTest {
    @Test
    fun `settings open only when the prompt was refused and the system will not ask again`() {
        assertTrue(shouldOpenSettings(granted = false, showRationale = false))
        assertFalse(shouldOpenSettings(granted = false, showRationale = true))
        assertFalse(shouldOpenSettings(granted = true, showRationale = false))
        assertFalse(shouldOpenSettings(granted = true, showRationale = true))
    }

    @Test
    fun `the app's notification settings open for this package`() {
        val app = RuntimeEnvironment.getApplication()
        openNotificationSettings(app)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/FakePushStatusSource.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts

import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import kotlinx.coroutines.flow.MutableStateFlow

class FakePushStatusSource(initial: PushStatus = PushStatus.Waiting) : PushStatusSource {
    override val status = MutableStateFlow(initial)
    var refreshes = 0
    var registerAgains = 0

    override fun refresh() {
        refreshes++
    }

    override fun registerAgain() {
        registerAgains++
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/presentation/RecentAlertsViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.feature.alerts.FakePushStatusSource
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RecentAlertsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val status = FakePushStatusSource()
    private val history by lazy { HistorySync(MutableSharedFlow<MqttMessage>(), repo) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `only alarm, security and camera alerts show, newest first, at most five`() = runTest(dispatcher) {
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        val vm = RecentAlertsViewModel(repo, status, history)
        backgroundScope.launch { vm.state.collect {} }
        val ui = vm.state.first { it.alerts.isNotEmpty() }
        assertEquals(listOf("demo-security", "demo-camera", "demo-alarm"), ui.alerts.map { it.id })
    }

    @Test
    fun `the banner follows notifications being off`() = runTest(dispatcher) {
        val vm = RecentAlertsViewModel(repo, status, history)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(false, vm.state.value.notificationsOff)
        status.status.value = PushStatus.NotificationsOff
        assertTrue(vm.state.first { it.notificationsOff }.notificationsOff)
        status.status.value = PushStatus.Registered(now)
        assertEquals(false, vm.state.first { !it.notificationsOff }.notificationsOff)
    }

    @Test
    fun `the catch-up count comes from the history sync and is acknowledged once shown`() = runTest(dispatcher) {
        val vm = RecentAlertsViewModel(repo, status, history)
        history.handle("""[{"v":1,"id":"x","title":"X","sent_at":1790000000}]""")
        assertEquals(1, vm.catchUp.value)
        vm.acknowledgeCatchUp()
        assertEquals(0, vm.catchUp.value)
    }

    @Test
    fun `refreshing the permission asks the status source`() = runTest(dispatcher) {
        RecentAlertsViewModel(repo, status, history).refreshPermission()
        assertEquals(1, status.refreshes)
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/RecentAlertsSectionTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RecentAlertsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val alerts = DemoAlerts.all(now).take(3)
    private val opened = mutableListOf<String>()
    private var seeAll = 0
    private var turnedOn = 0

    private fun show(alerts: List<com.gekkotron.oikos.alerts.Alert> = this.alerts, off: Boolean = false) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            RecentAlertsSection(alerts, now, off, onTurnOn = { turnedOn++ }, onOpen = { opened += it }, onSeeAll = { seeAll++ })
        }
    }

    @Test
    fun `the title, See all and the rows`() {
        show()
        compose.onNodeWithText("Recent alerts").assertExists()
        compose.onNodeWithText("See all").assertExists()
        compose.onNodeWithText("Front door opened").assertExists()
        compose.onNodeWithText("Person in the garden").assertExists()
        compose.onNodeWithText("Alarm triggered").assertExists()
    }

    @Test
    fun `See all and a row report`() {
        show()
        compose.onNodeWithText("See all").performClick()
        compose.onNodeWithText("Person in the garden").performClick()
        assertEquals(1, seeAll)
        assertEquals(listOf("demo-camera"), opened)
    }

    @Test
    fun `no alerts shows the empty state`() {
        show(alerts = emptyList())
        compose.onNodeWithText("No alerts yet").assertExists()
    }

    @Test
    fun `the banner shows above the rows only while notifications are off`() {
        show(off = true)
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(1, turnedOn)
    }

    @Test
    fun `no banner while notifications are on`() {
        show(off = false)
        compose.onNodeWithText("Notifications are off").assertDoesNotExist()
    }

    @Test
    fun `the banner also shows with no alerts`() {
        show(alerts = emptyList(), off = true)
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("No alerts yet").assertExists()
    }
}
```

`feature/alerts/src/test/kotlin/com/gekkotron/oikos/feature/alerts/ui/RecentAlertsScreenshotTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.distinctColors
import com.gekkotron.oikos.feature.alerts.luminanceAt
import com.gekkotron.oikos.feature.alerts.renderPng
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class RecentAlertsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun render(theme: ThemeChoice, name: String, alerts: List<Alert>, off: Boolean) = run {
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground { RecentAlertsSection(alerts, now, off, onTurnOn = {}, onOpen = {}, onSeeAll = {}) }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `rows in Night Glass`() {
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "recent-rows-night", DemoAlerts.all(now).take(3), off = false)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `rows with the banner in Warm Minimal`() {
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "recent-banner-warm", DemoAlerts.all(now).take(3), off = true)
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `empty in both themes`() {
        assertTrue(render(ThemeChoice.NIGHT_GLASS, "recent-empty-night", emptyList(), off = false).distinctColors() > 3)
    }
}
```

Append to `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreenTest.kt`, inside the class, plus `import androidx.compose.material3.Text` is already there:

```kotlin
    @Test
    fun `the alerts slot shows under the sensors`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                SecurityScreen(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))), alertsSection = { Text("alerts slot") })
            }
        }
        compose.onNodeWithText("alerts slot").performScrollTo().assertExists()
    }

    @Test
    fun `the alerts slot shows even when the house has no panel or sensor`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { SecurityScreen(ui(emptyList()), alertsSection = { Text("alerts slot") }) }
        }
        compose.onNodeWithText("alerts slot").assertExists()
    }

    @Test
    fun `without a slot there is no alerts section`() {
        show(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))))
        compose.onNodeWithText("Recent alerts").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests 'com.gekkotron.oikos.designsystem.permissions.NotificationEnablerTest' :feature:alerts:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.alerts.presentation.RecentAlertsViewModelTest' :feature:security:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.security.ui.SecurityScreenTest'`
Expected: compilation FAIL (`shouldOpenSettings`, `RecentAlertsViewModel`, `RecentAlertsSection`, the `alertsSection` parameter unresolved).

- [ ] **Step 3: Implement**

`core/designsystem/build.gradle.kts` `dependencies { }` gains:

```kotlin
    implementation(libs.androidx.activity.compose) // the permission launcher
    implementation(libs.androidx.core.ktx)
```

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/permissions/NotificationEnabler.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** After a refused prompt: `showRationale` false means "don't ask again", so only the system settings can help. */
fun shouldOpenSettings(granted: Boolean, showRationale: Boolean): Boolean = !granted && !showRationale

fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * "Turn on" for notifications (push alerts spec §7.1, §7.5): asks for `POST_NOTIFICATIONS` on Android 13+, and opens
 * the app's notification settings when the system will no longer show the prompt, when notifications were switched
 * off in the settings with the permission still granted, and on older Android. [onResult] runs after the prompt.
 */
@Composable
fun rememberNotificationEnabler(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationale = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } ?: false
        if (shouldOpenSettings(granted, rationale)) openNotificationSettings(context)
        onResult()
    }
    return remember(launcher, context) {
        {
            val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (canAsk) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(context)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/presentation/RecentAlertsViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RecentAlertsUi(val alerts: List<Alert> = emptyList(), val notificationsOff: Boolean = false)

/** The Security tab's section: the five newest alarm, security and camera alerts (push alerts spec §7.1). */
@HiltViewModel
class RecentAlertsViewModel @Inject constructor(
    repository: AlertRepository,
    private val status: PushStatusSource,
    private val history: HistorySync,
) : ViewModel() {
    val state: StateFlow<RecentAlertsUi> = combine(repository.recent(CHANNELS, LIMIT), status.status) { alerts, push ->
        RecentAlertsUi(alerts, push == PushStatus.NotificationsOff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentAlertsUi())

    /** Alerts that arrived through the retained history and have not been announced yet. */
    val catchUp: StateFlow<Int> = history.catchUp

    fun acknowledgeCatchUp() = history.acknowledge()

    /** The permission may have changed in the system settings while the app was away. */
    fun refreshPermission() = status.refresh()

    private companion object {
        val CHANNELS = setOf(AlertChannel.ALARM, AlertChannel.SECURITY, AlertChannel.CAMERA)
        const val LIMIT = 5
    }
}
```

`feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/RecentAlertsSection.kt`:

```kotlin
package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.permissions.rememberNotificationEnabler
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.RecentAlertsViewModel
import kotlinx.coroutines.flow.filter
import java.time.Instant

/** The Security tab's "Recent alerts" (push alerts spec §7.1). Stateless; [RecentAlertsRoute] feeds it. */
@Composable
fun RecentAlertsSection(
    alerts: List<Alert>,
    now: Instant,
    notificationsOff: Boolean,
    onTurnOn: () -> Unit,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.alerts_recent),
                style = OikosTheme.type.label,
                color = OikosTheme.colors.contentMuted,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.alerts_see_all)) }
        }
        if (notificationsOff) NotificationsBanner(onTurnOn)
        if (alerts.isEmpty()) {
            Text(stringResource(R.string.alerts_empty), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
        } else {
            alerts.forEach { alert -> AlertRow(alert, now, onClick = { onOpen(alert.id) }) }
        }
    }
}

@Composable
private fun NotificationsBanner(onTurnOn: () -> Unit) {
    OikosCard(Modifier.fillMaxWidth(), accent = true, minHeight = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.alerts_notifications_off), style = OikosTheme.type.label, modifier = Modifier.weight(1f))
            TextButton(onClick = onTurnOn) { Text(stringResource(R.string.alerts_turn_on)) }
        }
    }
}

/**
 * Wires the section to Room and the push status. The catch-up message is shown on [snackbar] once per sync: it is
 * acknowledged first, so a second sync while the snackbar is up shows its own message after it.
 */
@Composable
fun RecentAlertsRoute(
    snackbar: SnackbarHostState,
    onOpenAll: () -> Unit,
    deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    viewModel: RecentAlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberNow()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val turnOn = rememberNotificationEnabler(onResult = viewModel::refreshPermission)
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPermission()
        onPauseOrDispose {}
    }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.catchUp.filter { it > 0 }.collect { count ->
            viewModel.acknowledgeCatchUp()
            snackbar.showSnackbar(resources.getQuantityString(R.plurals.alerts_catch_up, count, count))
        }
    }
    RecentAlertsSection(state.alerts, now, state.notificationsOff, onTurnOn = turnOn, onOpen = { openId = it }, onSeeAll = onOpenAll)
    openId?.let { id -> AlertDetailRoute(id, onDismiss = { openId = null }, deviceSheet = deviceSheet) }
}
```

`:feature:security` — in `SecurityScreen.kt`:

1. Add the parameter to `SecurityRoute`, after `customizeSheet`:

```kotlin
    alertsSection: (@Composable (snackbar: SnackbarHostState) -> Unit)? = null,
```

and pass it on in the `SecurityScreen(...)` call, after `customizeSheet = ...`:

```kotlin
        alertsSection = alertsSection?.let { section -> { section(snackbar) } },
```

2. Add the parameter to `SecurityScreen`, after `customizeSheet`:

```kotlin
    alertsSection: (@Composable () -> Unit)? = null,
```

3. Delete the whole placeholder `item(key = "alerts") { ... }` block inside the `else ->` branch, and add, right after the closing brace of that `when { ... }` (still inside the `LazyColumn`):

```kotlin
            // Even a house with no panel or sensor gets its alerts (a camera-only house, say).
            if (!state.loading) alertsSection?.let { section -> item(key = "alerts") { section() } }
```

4. In `feature/security/src/main/res/values/strings.xml` and `values-fr/strings.xml` delete the two unused strings `security_alerts` and `security_alerts_empty`.

`:app` — in `OikosNavHost.kt` change the `SecurityRoute` entry to:

```kotlin
                entry<SecurityKey> {
                    SecurityRoute(
                        windowInsets = tabInsets,
                        customizeSheet = { id, onDismiss, onSaveFailed -> CustomizeEntitySheet(id, onDismiss, onSaveFailed) },
                        alertsSection = { snackbar ->
                            RecentAlertsRoute(snackbar, onOpenAll = { push(backStack, AlertsKey) }, deviceSheet = deviceSheet)
                        },
                    )
                }
```

with the import `com.gekkotron.oikos.feature.alerts.ui.RecentAlertsRoute`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:designsystem:testDebugUnitTest :feature:alerts:testDebugUnitTest :feature:security:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core feature app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(security): Recent alerts section with notification banner and catch-up snackbar" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Settings — "Push alerts" row

**Files:**
- Modify: `feature/settings/build.gradle.kts`, `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModel.kt`, `ui/SettingsScreen.kt`, `feature/settings/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/PushSheet.kt`
- Test: modify `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModelTest.kt`, `ui/SettingsScreenTest.kt`; create `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/FakePushStatusSource.kt`

**Interfaces:**
- Consumes: Task 10 `PushStatus`, `PushStatusSource`; Task 14 `openNotificationSettings`.
- Produces: `SettingsUi.push: PushStatus = PushStatus.Waiting`; `SettingsViewModel(profiles, preferences, push: PushStatusSource)` with `fun registerAgain()`, `fun refreshPush()`; `SettingsScreen(..., onRegisterAgain: () -> Unit = {}, onOpenChannels: () -> Unit = {})`; `internal fun PushSheet(status, onRegisterAgain, onOpenChannels, onDismiss)`; `@Composable internal fun pushStatusText(status: PushStatus): String`.

Spec §7.4: the row shows the `PushStatus` text (*Registered · 2 min ago*, *Waiting for connection*, *Failed: reason*, *Notifications off*, *Not available in demo*); tapping it opens a small sheet with "Register again" and "Notification channels", which opens the system's channel settings. In the demo "Register again" is disabled. The status is re-read when the screen resumes, because the permission can change in the system settings.

- [ ] **Step 1: Write the failing tests**

`feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/FakePushStatusSource.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings

import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import kotlinx.coroutines.flow.MutableStateFlow

class FakePushStatusSource(initial: PushStatus = PushStatus.Waiting) : PushStatusSource {
    override val status = MutableStateFlow(initial)
    var refreshes = 0
    var registerAgains = 0

    override fun refresh() {
        refreshes++
    }

    override fun registerAgain() {
        registerAgains++
    }
}
```

In `SettingsViewModelTest` replace every `SettingsViewModel(profiles, prefs)` with `SettingsViewModel(profiles, prefs, FakePushStatusSource())` (add `import com.gekkotron.oikos.feature.settings.FakePushStatusSource`) and add inside the class:

```kotlin
    @Test
    fun `state carries the push status and follows it`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs2.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile2.json"), PassThrough, backgroundScope)
        val push = FakePushStatusSource(PushStatus.Waiting)
        val vm = SettingsViewModel(profiles, prefs, push)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(PushStatus.Waiting, vm.state.value.push)
        push.status.value = PushStatus.NotificationsOff
        assertEquals(PushStatus.NotificationsOff, vm.state.first { it.push == PushStatus.NotificationsOff }.push)
    }

    @Test
    fun `register again and refresh go to the push source`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs3.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile3.json"), PassThrough, backgroundScope)
        val push = FakePushStatusSource()
        val vm = SettingsViewModel(profiles, prefs, push)
        vm.registerAgain()
        vm.refreshPush()
        assertEquals(1, push.registerAgains)
        assertEquals(1, push.refreshes)
    }
```

with `import com.gekkotron.oikos.push.PushStatus`.

Append to `SettingsScreenTest` (inside the class; add imports `androidx.compose.ui.test.assertIsNotEnabled`, `com.gekkotron.oikos.push.PushStatus`, `java.time.Instant`):

```kotlin
    private var registerAgain = 0
    private var channels = 0

    private fun showPush(status: PushStatus) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SettingsScreen(
                SettingsUi(connection = direct, push = status), version = "0.12 · debug", route = Route.AUTO,
                onTheme = {}, onRouteChange = {}, onSetUpAgain = {}, onOpenRooms = {}, onOpenLicences = {}, onBack = {},
                onRegisterAgain = { registerAgain++ }, onOpenChannels = { channels++ },
            )
        }
    }

    @Test
    fun `the push row shows each status in words`() {
        showPush(PushStatus.Registered(Instant.now().minusSeconds(120)))
        compose.onNodeWithText("Registered · ", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("2", substring = true).assertExists()
    }

    @Test
    fun `waiting`() {
        showPush(PushStatus.Waiting)
        compose.onNodeWithText("Waiting for connection").performScrollTo().assertExists()
    }

    @Test
    fun `a failure carries its reason`() {
        showPush(PushStatus.Failed("broker did not answer in time"))
        compose.onNodeWithText("Failed: broker did not answer in time").performScrollTo().assertExists()
    }

    @Test
    fun `notifications off and the demo have their own words`() {
        showPush(PushStatus.NotificationsOff)
        compose.onNodeWithText("Notifications off").performScrollTo().assertExists()
    }

    @Test
    fun `the demo says it is not available`() {
        showPush(PushStatus.Demo)
        compose.onNodeWithText("Not available in demo").performScrollTo().assertExists()
    }

    @Test
    fun `tapping the row opens the sheet, Register again closes it and reports`() {
        showPush(PushStatus.Waiting)
        compose.onNodeWithText("Push alerts").performScrollTo().performClick()
        compose.onNodeWithText("Register again").performClick()
        assertEquals(1, registerAgain)
        compose.onNodeWithText("Notification channels").assertDoesNotExist()
    }

    @Test
    fun `Notification channels reports and closes`() {
        showPush(PushStatus.Waiting)
        compose.onNodeWithText("Push alerts").performScrollTo().performClick()
        compose.onNodeWithText("Notification channels").performClick()
        assertEquals(1, channels)
    }

    @Test
    fun `in the demo Register again is disabled`() {
        showPush(PushStatus.Demo)
        compose.onNodeWithText("Push alerts").performScrollTo().performClick()
        compose.onNodeWithText("Register again").assertIsNotEnabled()
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: compilation FAIL (`SettingsViewModel` has 2 parameters, `SettingsUi.push`, `onRegisterAgain` unresolved).

- [ ] **Step 3: Implement**

`feature/settings/build.gradle.kts` gains `implementation(project(":core:push"))`.

`SettingsViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val connection: ConnectionSummary? = null,
    val push: PushStatus = PushStatus.Waiting,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val preferences: AppPreferences,
    private val push: PushStatusSource,
) : ViewModel() {
    val state: StateFlow<SettingsUi> = combine(preferences.theme, profiles.state, push.status) { theme, profile, status ->
        SettingsUi(theme ?: ThemePreference.SYSTEM, ConnectionSummary.from(profile), status)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Seeded from the live values so the radio does not show System for a frame.
        SettingsUi(preferences.theme.value ?: ThemePreference.SYSTEM, ConnectionSummary.from(profiles.state.value), push.status.value),
    )

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    /** "Register again" in the push sheet (push alerts spec §7.4). */
    fun registerAgain() = push.registerAgain()

    /** The notification permission may have changed while the app was away. */
    fun refreshPush() = push.refresh()
}
```

`feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/PushSheet.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.push.PushStatus
import java.time.Duration
import java.time.Instant

/** The push status in words (push alerts spec §7.4). */
@Composable
internal fun pushStatusText(status: PushStatus): String = when (status) {
    is PushStatus.Registered -> stringResource(R.string.settings_push_registered, ageText(status.at))
    PushStatus.Waiting -> stringResource(R.string.settings_push_waiting)
    is PushStatus.Failed -> stringResource(R.string.settings_push_failed, status.reason)
    PushStatus.NotificationsOff -> stringResource(R.string.settings_push_off)
    PushStatus.Demo -> stringResource(R.string.settings_push_demo)
}

@Composable
private fun ageText(at: Instant): String {
    val now = Instant.now()
    if (Duration.between(at, now).seconds < 60) return stringResource(R.string.settings_push_just_now)
    return DateUtils.getRelativeTimeSpanString(
        at.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushSheet(status: PushStatus, onRegisterAgain: () -> Unit, onOpenChannels: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_push), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(pushStatusText(status), style = OikosTheme.type.body)
            FilledTonalButton(
                onClick = { onRegisterAgain(); onDismiss() },
                enabled = status != PushStatus.Demo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_push_register_again)) }
            OutlinedButton(onClick = { onOpenChannels(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_push_channels))
            }
        }
    }
}
```

In `SettingsScreen.kt`:

1. imports: `androidx.lifecycle.compose.LifecycleResumeEffect`, `com.gekkotron.oikos.designsystem.permissions.openNotificationSettings`, `com.gekkotron.oikos.push.PushStatus` is not needed.
2. `SettingsRoute`: after `val version = ...` add

```kotlin
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPush()
        onPauseOrDispose {}
    }
```

and change the call to

```kotlin
    SettingsScreen(
        state, version, route, viewModel::setTheme, onRouteChange, onSetUpAgain, onOpenRooms, onOpenLicences, onBack,
        onRegisterAgain = viewModel::registerAgain,
        onOpenChannels = { openNotificationSettings(context) },
    )
```

3. `SettingsScreen` signature gains, after `onBack: () -> Unit,`:

```kotlin
    onRegisterAgain: () -> Unit = {},
    onOpenChannels: () -> Unit = {},
```

4. Inside, next to `var confirm`: `var pushSheet by rememberSaveable { mutableStateOf(false) }`; after the Rooms `NavRow(...)` line add:

```kotlin
            NavRow(stringResource(R.string.settings_push), pushStatusText(state.push), onClick = { pushSheet = true })
```

and after the `if (confirm) { ... }` block add:

```kotlin
    if (pushSheet) {
        PushSheet(state.push, onRegisterAgain, onOpenChannels, onDismiss = { pushSheet = false })
    }
```

`feature/settings/src/main/res/values/strings.xml` gains:

```xml
    <string name="settings_push">Push alerts</string>
    <string name="settings_push_registered">Registered · %1$s</string>
    <string name="settings_push_just_now">just now</string>
    <string name="settings_push_waiting">Waiting for connection</string>
    <string name="settings_push_failed">Failed: %1$s</string>
    <string name="settings_push_off">Notifications off</string>
    <string name="settings_push_demo">Not available in demo</string>
    <string name="settings_push_register_again">Register again</string>
    <string name="settings_push_channels">Notification channels</string>
```

`values-fr/strings.xml` gains:

```xml
    <string name="settings_push">Alertes push</string>
    <string name="settings_push_registered">Enregistré · %1$s</string>
    <string name="settings_push_just_now">à l\'instant</string>
    <string name="settings_push_waiting">En attente de connexion</string>
    <string name="settings_push_failed">Échec : %1$s</string>
    <string name="settings_push_off">Notifications désactivées</string>
    <string name="settings_push_demo">Indisponible dans la démo</string>
    <string name="settings_push_register_again">Enregistrer à nouveau</string>
    <string name="settings_push_channels">Canaux de notification</string>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:settings:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. In `MainActivityTest` the Settings screen now resolves a `PushStatusSource` from the Hilt graph (`PushModule`), so this also proves the graph builds.

- [ ] **Step 5: Commit**

```bash
git add feature/settings
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(settings): Push alerts row with status, register again and channel settings" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: Onboarding — the notification permission step

**Files:**
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt`, `ConnectionProfile.kt`, `ProfileRepository.kt`
- Modify tests: `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt`, `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/OnboardingViewModelTest.kt`
- Create: `feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/NotificationsScreen.kt`, strings (`feature/onboarding/src/main/res/values/strings.xml`, `values-fr/strings.xml`)
- Test: `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/ui/NotificationsScreenTest.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`, `navigation/OikosNavHost.kt`, `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt`
- Create: `app/src/test/kotlin/com/gekkotron/oikos/TestWorkModule.kt`

**Interfaces:**
- Consumes: Task 14 `rememberNotificationEnabler`; Task 6 `PushRegistrar.request()`; Task 13 `NotificationsKey`.
- Produces: `ProfileState.Ready(profile, roomsPending = false, notificationsPending = false)`; `ProfileRepository.finishNotifications()`; `@Composable fun NotificationsRoute(onDone: () -> Unit)`; `AppViewModel.finishNotifications()`.

Spec §7.5: the last step, after room assignment, explains alerts and asks for `POST_NOTIFICATIONS` on Android 13+; "Not now" skips it; the registration is enqueued either way. The profile is saved at the end of the Connect step and the app then leaves `OnboardingRoute`, so the step cannot live in the onboarding module's `Step` enum. It follows the existing Rooms pattern instead: a persisted `notificationsPending` flag, set when a direct profile is saved, drives the nav root (`RoomsKey`, then `NotificationsKey`, then `HomeKey`). The demo never gets the step (no push), and an installed app being upgraded never gets it either (the flag defaults to false); its Security banner offers "Turn on".

- [ ] **Step 1: Write the failing `:core:data` tests**

In `ProfileRepositoryTest.kt` replace, in all 8 occurrences, `roomsPending = true)` with `roomsPending = true, notificationsPending = true)` (every one of them asserts a freshly saved direct profile). The same replacement applies to the two occurrences in `OnboardingViewModelTest.kt`:

```bash
sed -i '' 's/roomsPending = true)/roomsPending = true, notificationsPending = true)/' \
  core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt \
  feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/OnboardingViewModelTest.kt
```

Append inside `ProfileRepositoryTest`:

```kotlin
    @Test
    fun `a saved direct profile asks for the notification step, the demo does not`() = runTest {
        val r = repo()
        r.save(direct)
        assertEquals(true, (r.state.first { it is ProfileState.Ready } as ProfileState.Ready).notificationsPending)
        r.save(ConnectionProfile.Demo)
        assertEquals(false, (r.state.first { it is ProfileState.Ready && it.profile == ConnectionProfile.Demo } as ProfileState.Ready).notificationsPending)
    }

    @Test
    fun `finishNotifications clears only its own flag`() = runTest {
        val r = repo()
        r.save(direct)
        r.finishNotifications()
        val ready = r.state.first { it is ProfileState.Ready && !it.notificationsPending } as ProfileState.Ready
        assertEquals(true, ready.roomsPending)
        r.finishRooms()
        assertEquals(ProfileState.Ready(direct, roomsPending = false, notificationsPending = false), r.state.first { it is ProfileState.Ready && !it.roomsPending })
    }

    @Test
    fun `a profile file from before this field reads as not pending`() = runTest {
        val f = file()
        repo(f).save(direct)
        val old = tmp.newFile().also { it.delete() }
        old.writeText(f.readText().replace(Regex(""",?"notificationsPending":(true|false)"""), ""))
        assertEquals(false, (repo(old).settled() as ProfileState.Ready).notificationsPending)
    }

    @Test
    fun `finishNotifications survives setting the route`() = runTest {
        val r = repo()
        r.save(direct)
        r.setRoute(com.gekkotron.oikos.data.RoutePreference.GATEWAY)
        assertEquals(true, (r.state.value as ProfileState.Ready).notificationsPending)
    }
```

(The old-format file is a copy: a second store over the same file in one process breaks DataStore's one-file-one-store rule; the existing old-file test does the same.)

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: compilation FAIL (`notificationsPending`, `finishNotifications` unresolved).

- [ ] **Step 3: Implement the data layer**

`StoredProfile.kt` — in `StoredProfile` add after `roomsPending`:

```kotlin
    /** A direct profile was just set up and the notification step has not been finished or skipped (push alerts spec §7.5). */
    val notificationsPending: Boolean = false,
```

`ConnectionProfile.kt` — change `Ready`:

```kotlin
    /**
     * [roomsPending]: show the Rooms step before Home (a direct profile that was just set up). [notificationsPending]: then the
     * notification step (push alerts spec §7.5).
     */
    data class Ready(
        val profile: ConnectionProfile,
        val roomsPending: Boolean = false,
        val notificationsPending: Boolean = false,
    ) : ProfileState
```

`ProfileRepository.kt`:

```kotlin
    /** The notification step was finished or skipped; it is not shown again for this profile. */
    suspend fun finishNotifications() {
        store.updateData { it.copy(notificationsPending = false) }
    }
```

in `toState`: `s.toDirect()?.let { ProfileState.Ready(it, s.roomsPending, s.notificationsPending) } ?: ProfileState.None`; in `toStored` for `Direct` add `notificationsPending = true,` after `roomsPending = true,`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :core:data:testDebugUnitTest :feature:onboarding:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Write the failing screen test**

`feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/ui/NotificationsScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class NotificationsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var enabled = 0
    private var skipped = 0

    private fun show(canAsk: Boolean) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { NotificationsScreen(canAsk, onEnable = { enabled++ }, onSkip = { skipped++ }) }
    }

    @Test
    fun `it explains the alerts`() {
        show(canAsk = true)
        compose.onNodeWithText("Stay informed").assertExists()
        compose.onNodeWithText("alarm", substring = true).assertExists()
    }

    @Test
    fun `on Android 13 and later the button asks for notifications`() {
        show(canAsk = true)
        compose.onNodeWithText("Turn on notifications").performClick()
        assertEquals(1, enabled)
    }

    @Test
    fun `before Android 13 there is nothing to ask and the button just continues`() {
        show(canAsk = false)
        compose.onNodeWithText("Turn on notifications").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, enabled)
    }

    @Test
    fun `Not now skips`() {
        show(canAsk = true)
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, skipped)
        assertEquals(0, enabled)
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :feature:onboarding:testDebugUnitTest --tests 'com.gekkotron.oikos.feature.onboarding.ui.NotificationsScreenTest'`
Expected: compilation FAIL (`NotificationsScreen` unresolved).

- [ ] **Step 7: Implement the screen and the navigation**

`feature/onboarding/src/main/kotlin/com/gekkotron/oikos/feature/onboarding/ui/NotificationsScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.onboarding.ui

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.permissions.rememberNotificationEnabler
import com.gekkotron.oikos.feature.onboarding.R

/** The last onboarding step, after rooms (push alerts spec §7.5). [onDone] runs whatever the user answers. */
@Composable
fun NotificationsRoute(onDone: () -> Unit) {
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val ask = rememberNotificationEnabler(onResult = onDone)
    NotificationsScreen(canAsk, onEnable = if (canAsk) ask else onDone, onSkip = onDone)
}

@Composable
internal fun NotificationsScreen(canAsk: Boolean, onEnable: () -> Unit, onSkip: () -> Unit) {
    OnboardingColumn {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.notifications_title), style = OikosTheme.type.display)
        Text(stringResource(R.string.notifications_body), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (canAsk) R.string.notifications_turn_on else R.string.notifications_continue))
        }
        if (canAsk) {
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.notifications_not_now)) }
        }
    }
}
```

`feature/onboarding/src/main/res/values/strings.xml` gains:

```xml
    <string name="notifications_title">Stay informed</string>
    <string name="notifications_body">Oikos can alert you about the alarm, doors, cameras, energy and more, with a picture and quick actions such as Disarm. You can change this any time in Settings.</string>
    <string name="notifications_turn_on">Turn on notifications</string>
    <string name="notifications_continue">Continue</string>
    <string name="notifications_not_now">Not now</string>
```

`values-fr/strings.xml` gains:

```xml
    <string name="notifications_title">Restez informé</string>
    <string name="notifications_body">Oikos peut vous prévenir pour l\'alarme, les portes, les caméras, l\'énergie et plus, avec une image et des actions rapides comme Désarmer. Vous pouvez changer cela à tout moment dans les réglages.</string>
    <string name="notifications_turn_on">Activer les notifications</string>
    <string name="notifications_continue">Continuer</string>
    <string name="notifications_not_now">Pas maintenant</string>
```

`OnboardingColumn` is the existing layout wrapper in `OnboardingLayout.kt`; it is `internal` in the same module, as the screen is.

`AppViewModel.kt`: constructor gains `private val registrar: PushRegistrar,` (import `com.gekkotron.oikos.push.PushRegistrar`) and the class gains:

```kotlin
    /** The notification step was answered or skipped: leave it, and register for push either way (push alerts spec §7.5). */
    fun finishNotifications() {
        viewModelScope.launch {
            profiles.finishNotifications()
            registrar.request()
        }
    }
```

`OikosNavHost.kt`: change the root selection to

```kotlin
        // A direct profile that was just set up first gets the Rooms step (spec §5.2 step 5), then the notification step.
        is ProfileState.Ready -> when {
            p.roomsPending -> RoomsKey
            p.notificationsPending -> NotificationsKey
            else -> HomeKey
        }
```

and add the entry, next to the Rooms one:

```kotlin
                entry<NotificationsKey> { NotificationsRoute(onDone = viewModel::finishNotifications) }
```

with `import com.gekkotron.oikos.feature.onboarding.ui.NotificationsRoute`.

The new graph needs queues that do not need WorkManager in tests (`HiltTestApplication` is not an `OikosApplication`, so WorkManager is never initialized). `app/src/test/kotlin/com/gekkotron/oikos/TestWorkModule.kt`:

```kotlin
package com.gekkotron.oikos

import com.gekkotron.oikos.di.WorkModule
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import javax.inject.Singleton

/** No WorkManager under Robolectric's Hilt application: the queues just remember what they were asked. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkModule::class])
object TestWorkModule {
    class RecordingRegistrationQueue : RegistrationQueue {
        var enqueued = 0
        var cancelled = 0
        override val active: Flow<Boolean> = MutableStateFlow(false)

        override fun enqueue() {
            enqueued++
        }

        override fun cancel() {
            cancelled++
        }
    }

    class RecordingReplyQueue : ReplyQueue {
        override fun enqueue(alertId: String, actionId: String, label: String): UUID = UUID.randomUUID()

        override fun state(id: UUID): Flow<ReplyState> = flowOf(ReplyState.PENDING)

        override fun cancelAll() = Unit
    }

    @Provides
    @Singleton
    fun registrationQueue(): RegistrationQueue = RecordingRegistrationQueue()

    @Provides
    @Singleton
    fun replyQueue(): ReplyQueue = RecordingReplyQueue()
}
```

In `MainActivityTest`, the Rooms helper now ends on the notification step; replace `finishRooms` with:

```kotlin
    /** The Rooms list is lazy: its last item may not be composed until the list scrolls to it. Then the notification step. */
    private fun finishRooms(label: String) {
        compose.waitUntilAtLeastOneExists(hasText("Which room is each device in?"), timeoutMillis = 10_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Stay informed"), timeoutMillis = 10_000)
        compose.onNodeWithText("Not now").performClick()
    }
```

and add this test (the notification step comes after rooms and "Not now" reaches Home):

```kotlin
    @Test
    fun `after rooms the notification step is offered once and Not now reaches Home`() {
        connectToBroker(kettle)
        compose.waitUntilAtLeastOneExists(hasText("Which room is each device in?"), timeoutMillis = 10_000)
        compose.onNodeWithText("Kitchen kettle").performClick()
        finishRooms("Skip")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Kettle", substring = true), timeoutMillis = 10_000)
        compose.onAllNodes(hasText("Stay informed")).assertCountEquals(0)
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :feature:onboarding:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, including every `MainActivityTest` that goes through Rooms.

- [ ] **Step 9: Commit**

```bash
git add core feature app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(onboarding): notification permission step after rooms" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 17: Demo alerts and "Set up again" cleanup

**Files:**
- Create: `app/src/main/kotlin/com/gekkotron/oikos/DemoAlertSeeder.kt`, `SetUpAgainCleanup.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`, `di/AlertsModule.kt`, `di/PushModule.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/DemoAlertSeederTest.kt`, `SetUpAgainCleanupTest.kt`, modify `MainActivityTest.kt`

**Interfaces:**
- Consumes: Task 12 `DemoAlerts`; Task 2 `AlertRepository.reset/upsert`; Task 4 `AlertImageCache.clear`, `VideoCache.clear`; Task 6 `PushRegistrar.unregister`; Task 7 `ReplyQueue.cancelAll`.
- Produces: `class DemoAlertSeeder(states: Flow<ProfileState>, repository: AlertRepository, clock: () -> Instant = Instant::now, scope: CoroutineScope) { fun start() }` (idempotent); `class SetUpAgainCleanup(registrar: PushRegistrar, repository: AlertRepository, replies: ReplyQueue, images: AlertImageCache, videos: VideoCache, dismissNotifications: () -> Unit) { suspend fun run() }`.

Spec §7.6: the demo has no registration (`PushTarget.Demo`, Task 10) and no media fetching (`demo://` pictures); its six alerts are fixed. They are written to Room each time the demo profile becomes current, replacing whatever was there. Spec §5.4 and §6.3: "Set up again" first unregisters (empty retained token payload, 5 s, then `deleteToken`), then clears both media caches and the `alerts` table (marker included, so a new house starts clean); this plan also cancels pending replies and dismisses the old house's notifications. It runs *before* the profile is wiped (the unregister needs the install id and the token topic) and is best effort per step: a failing step must never trap the user on the old profile.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/gekkotron/oikos/DemoAlertSeederTest.kt`:

```kotlin
package com.gekkotron.oikos

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DemoAlertSeederTest {
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val demo = ProfileState.Ready(ConnectionProfile.Demo)
    private val direct = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun ids() = repo.all(null).first().map { it.id }.sorted()

    @Test
    fun `entering the demo seeds its six alerts`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<ProfileState>(ProfileState.None)
        DemoAlertSeeder(states, repo, { now }, backgroundScope).start()
        assertTrue(ids().isEmpty())
        states.value = demo
        assertEquals(6, ids().size)
    }

    @Test
    fun `a real house never gets demo alerts`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<ProfileState>(direct)
        DemoAlertSeeder(states, repo, { now }, backgroundScope).start()
        states.value = ProfileState.Locked
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `starting twice seeds once and leftovers are replaced, not added to`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<ProfileState>(demo)
        val seeder = DemoAlertSeeder(states, repo, { now }, backgroundScope)
        seeder.start()
        seeder.start()
        assertEquals(6, ids().size)
        repo.upsert(com.gekkotron.oikos.alerts.demo.DemoAlerts.all(now)[0].copy(id = "stray"))
        states.value = ProfileState.None
        states.value = demo
        assertEquals(6, ids().size)
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/SetUpAgainCleanupTest.kt`:

```kotlin
package com.gekkotron.oikos

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.disk.DiskCache
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.push.FcmTokens
import com.gekkotron.oikos.push.PushRegistrar
import com.gekkotron.oikos.push.PushTarget
import com.gekkotron.oikos.push.PushTargets
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.RegistrationStore
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import com.gekkotron.oikos.push.TopicPublisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SetUpAgainCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val events = mutableListOf<String>()
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private val targets = object : PushTargets {
        override suspend fun current(): PushTarget = PushTarget.Ready("install-1", "oikos-push/tokens/install-1")
        override val changes: Flow<PushTarget> get() = flowOf(PushTarget.Ready("install-1", "oikos-push/tokens/install-1"))
    }
    private val tokens = object : FcmTokens {
        override suspend fun token() = "token-1"
        override suspend fun delete() {
            events += "deleteToken"
        }
    }
    private val queue = object : RegistrationQueue {
        override fun enqueue() = Unit
        override fun cancel() {
            events += "cancelRegister"
        }

        override val active: Flow<Boolean> get() = flowOf(false)
    }
    private val replies = object : ReplyQueue {
        override fun enqueue(alertId: String, actionId: String, label: String): UUID = UUID.randomUUID()
        override fun state(id: UUID): Flow<ReplyState> = flowOf(ReplyState.PENDING)
        override fun cancelAll() {
            events += "cancelReplies"
        }
    }
    private val clips = object : MediaSource {
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit) = Result.success(Media(ByteArray(2), null))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun cleanup(publisher: TopicPublisher, imageCache: AlertImageCache = newImageCache(), videos: VideoCache = VideoCache(tmp.newFolder(), clips)): SetUpAgainCleanup {
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val registrar = PushRegistrar(targets, tokens, publisher, store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        return SetUpAgainCleanup(registrar, repo, replies, imageCache, videos) { events += "dismissNotifications" }
    }

    private fun newImageCache() = AlertImageCache(DiskCache.Builder().directory(tmp.newFolder().toOkioPath()).maxSizeBytes(1_000_000).build())

    @Test
    fun `it unregisters, drops the alerts, the caches, the replies and the notifications`() = runBlocking {
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        repo.clearAll()
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        val imageCache = newImageCache().also { it.put("http://h/s.jpg", byteArrayOf(1)) }
        val videoDir = tmp.newFolder("v")
        val videos = VideoCache(videoDir, clips).also { cache -> cache.file("a", "http://h/c.mp4").first { it is com.gekkotron.oikos.alerts.media.VideoState.Ready } }
        val sent = mutableListOf<Triple<String, String, Boolean>>()
        cleanup({ topic, payload, retain -> sent += Triple(topic, payload.decodeToString(), retain); Result.success(Unit) }, imageCache, videos).run()

        assertEquals(listOf(Triple("oikos-push/tokens/install-1", "", true)), sent)
        assertTrue(repo.all(null).first().isEmpty())
        assertNull(imageCache.open("http://h/s.jpg"))
        assertEquals(0, videoDir.listFiles()!!.size)
        assertTrue("cancelReplies" in events && "dismissNotifications" in events && "deleteToken" in events)
    }

    @Test
    fun `the unregister happens first, while the profile is still there to read`() = runBlocking {
        repo.upsert(DemoAlerts.all(now)[0])
        cleanup({ _, _, _ -> events += "publish"; Result.success(Unit) }).run()
        assertTrue(events.indexOf("cancelRegister") < events.indexOf("publish"))
        assertTrue(events.indexOf("publish") < events.indexOf("dismissNotifications"))
    }

    @Test
    fun `the clear marker is forgotten so a new house starts clean`() = runBlocking {
        repo.upsert(DemoAlerts.all(now)[0])
        repo.clearAll()
        cleanup({ _, _, _ -> Result.success(Unit) }).run()
        assertTrue(repo.insertIfMissing(DemoAlerts.all(now)[5]))
        assertNotNull(repo.byId("demo-system").first())
    }

    @Test
    fun `a failing step never stops the next ones`() = runBlocking {
        DemoAlerts.all(now).forEach { repo.upsert(it) }
        val throwing = object : FcmTokens {
            override suspend fun token(): String = throw IOException("no")
            override suspend fun delete(): Unit = throw IOException("no")
        }
        val store = RegistrationStore.create(tmp.newFile().also { it.delete() }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val registrar = PushRegistrar(targets, throwing, TopicPublisher { _, _, _ -> throw IOException("broker down") }, store, queue, { PushRegistrar.DeviceInfo("p", "en") })
        SetUpAgainCleanup(registrar, repo, replies, newImageCache(), VideoCache(tmp.newFolder(), clips)) { throw IllegalStateException("no notification service") }.run()
        assertTrue(repo.all(null).first().isEmpty())
        assertTrue("cancelReplies" in events)
    }
}
```

Add to `MainActivityTest` (inside the class; the demo house is opened by the existing `openDemo()` helper):

```kotlin
    @Test
    fun `the demo shows its alerts on Security and Set up again forgets them`() {
        openDemo()
        compose.onNodeWithText("Security").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Front door opened"))
        compose.onNodeWithText("Front door opened").assertExists()
        compose.onNodeWithText("See all").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Person in the garden"), timeoutMillis = 10_000)
        compose.onNodeWithContentDescription("Back").performClick()
    }
```

with imports `androidx.compose.ui.test.onNodeWithContentDescription`; and in `set up connection again returns to welcome` nothing changes: it now also runs `SetUpAgainCleanup` against the Hilt graph (fake queues from `TestWorkModule`).

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.gekkotron.oikos.DemoAlertSeederTest' --tests 'com.gekkotron.oikos.SetUpAgainCleanupTest'`
Expected: compilation FAIL (`DemoAlertSeeder`, `SetUpAgainCleanup` unresolved).

- [ ] **Step 3: Implement**

`app/src/main/kotlin/com/gekkotron/oikos/DemoAlertSeeder.kt`:

```kotlin
package com.gekkotron.oikos

import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Fills Room with the six demo alerts each time the demo profile becomes current (push alerts spec §7.6). */
class DemoAlertSeeder(
    private val states: Flow<ProfileState>,
    private val repository: AlertRepository,
    private val clock: () -> Instant = Instant::now,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Safe to call from every `AppViewModel`: only the first call collects. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            states.map { (it as? ProfileState.Ready)?.profile == ConnectionProfile.Demo }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    repository.reset()
                    DemoAlerts.all(clock()).forEach { alert -> repository.upsert(alert) }
                }
        }
    }
}
```

`app/src/main/kotlin/com/gekkotron/oikos/SetUpAgainCleanup.kt`:

```kotlin
package com.gekkotron.oikos

import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.push.PushRegistrar
import com.gekkotron.oikos.push.ReplyQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything push-related that "Set up again" forgets, before the profile is wiped (push alerts spec §5.4, §6.3).
 * Each step is best effort: whatever fails, the next one still runs and the caller still resets the profile.
 */
class SetUpAgainCleanup(
    private val registrar: PushRegistrar,
    private val repository: AlertRepository,
    private val replies: ReplyQueue,
    private val images: AlertImageCache,
    private val videos: VideoCache,
    private val dismissNotifications: () -> Unit,
) {
    suspend fun run() {
        // First: the unregister needs the install id and the token topic of the profile that is about to go.
        step { registrar.unregister() }
        step { replies.cancelAll() }
        step { dismissNotifications() }
        step { repository.reset() }
        step {
            withContext(Dispatchers.IO) {
                images.clear()
                videos.clear()
            }
        }
    }

    private suspend fun step(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort by design: see the class comment.
        }
    }
}
```

`AppViewModel.kt`: constructor gains `private val cleanup: SetUpAgainCleanup, private val demoAlerts: DemoAlertSeeder,`; add `init { demoAlerts.start() }` before `val profile`, and change `setUpAgain` to:

```kotlin
    fun setUpAgain() {
        viewModelScope.launch {
            cleanup.run()
            profiles.clear()
            sources.reset()
        }
    }
```

`AlertsModule.kt` gains (with imports `com.gekkotron.oikos.DemoAlertSeeder`, `com.gekkotron.oikos.data.ProfileRepository` already, `kotlinx.coroutines.CoroutineScope`):

```kotlin
    @Provides
    @Singleton
    fun demoAlertSeeder(profiles: ProfileRepository, repository: AlertRepository, @ApplicationScope scope: CoroutineScope): DemoAlertSeeder =
        DemoAlertSeeder(profiles.state, repository, scope = scope)
```

`PushModule.kt` gains (imports `androidx.core.app.NotificationManagerCompat`, `com.gekkotron.oikos.SetUpAgainCleanup`, `com.gekkotron.oikos.alerts.media.VideoCache`):

```kotlin
    @Provides
    @Singleton
    fun setUpAgainCleanup(
        @ApplicationContext context: Context,
        registrar: PushRegistrar,
        repository: AlertRepository,
        replies: ReplyQueue,
        images: AlertImageCache,
        videos: VideoCache,
    ): SetUpAgainCleanup = SetUpAgainCleanup(registrar, repository, replies, images, videos, dismissNotifications = { NotificationManagerCompat.from(context).cancelAll() })
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including `MainActivityTest` through the real Room, Coil cache and fake queues.

- [ ] **Step 5: Commit**

```bash
git add app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(push): demo alerts and a best-effort cleanup on Set up again" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 18: Final check, device check list, rollout notes

**Files:** none new, unless a check turns something up.

- [ ] **Step 1: Run the same command CI runs**

Run: `./gradlew check koverXmlReport :app:assembleDebug --stacktrace`
Expected: BUILD SUCCESSFUL: every module's unit tests, lint, the Kover report and a debug APK. Fix what fails in the task it belongs to (a lint `MissingPermission` or `NewApi` on a notification call means a guard from Task 7 or 8 slipped; a `MissingTranslation` means a string in `values/strings.xml` has no `values-fr` twin).

- [ ] **Step 2: Check the strings are complete**

Run:

```bash
for m in core/push feature/alerts feature/settings feature/onboarding app; do
  diff <(grep -o 'name="[^"]*"' $m/src/main/res/values/strings.xml | sort) \
       <(grep -o 'name="[^"]*"' $m/src/main/res/values-fr/strings.xml | sort) && echo "$m: EN and FR match"
done
```

Expected: five "EN and FR match" lines and no diff output.

- [ ] **Step 3: Check no real name leaked**

Run: `git log feat/push-alerts --format='%an <%ae>' master..HEAD | sort -u` and `git diff master...HEAD | grep -n "huguel" || echo clean`
Expected: only `Gekkotron <60887050+Gekkotron@users.noreply.github.com>`, then `clean`.

- [ ] **Step 4: Device check (spec §9, on a phone with the tester build)**

The debug build has no Firebase configuration, so FCM cannot work in it: Settings shows "Failed: Firebase is not configured in this build" there, which is the intended degradation. Use the tester (release) build, whose `google-services.json` lists package `com.gekkotron.oikos`. Prerequisites on the house side (the OikosPush plan): the service is running with the `oikos-b8268` service-account key; the gateway's Oikos key has an HTTP rule for the Frigate host (spec §10 step 2, `server/add_secret.py`).

1. Install the build, finish onboarding (or open the existing install), grant notifications. Settings, "Push alerts" row: "Registered · just now". On the broker: `mosquitto_sub -h <broker> -u <user> -P <pass> -t 'oikos-push/tokens/#' -v` shows the retained `{"token":…,"device_name":…,"lang":…,"updated_at":…}`.
2. Publish a v1 alert with a snapshot and an action:

```bash
mosquitto_pub -h <broker> -u <user> -P <pass> -t 'oikos-push/notification/frigate' -m '{"v":1,"id":"frigate-front-1696","channel":"camera","severity":"critical","title":"Front door","body":"Person detected","image_url":"http://<frigate-host>:5000/api/events/<event-id>/snapshot.jpg","video_url":"http://<frigate-host>:5000/api/events/<event-id>/clip.mp4","entity_id":"binary_sensor.front_door","actions":[{"id":"disarm","label":"Disarm"}]}'
```

   and a legacy one from an existing producer's format:

```bash
mosquitto_pub -h <broker> -u <user> -P <pass> -t 'oikos-push/notification/jeedom' -m '{"title":"Alarme","body":"Alarme activée","data":{"id":4201,"channelId":"alarme","actions":"[{\"btn\":\"Désactiver\",\"action\":\"disarm\"}]"},"priority":"high"}'
```

3. Check: the notification arrives on the `camera` channel with the picture; the second one on `alarm`. Tap the first: the detail sheet opens with the picture, the Play button and "Disarm". Press "Disarm" in the notification (and again from the sheet on a second alert): `mosquitto_sub -t 'oikos-push/responses' -v` prints `{"id":"frigate-front-1696","action":"disarm","install_id":"…","ts":…}` once per tap, and the sheet shows "Sent".
4. Publish `{"v":1,"id":"frigate-front-1696","cancel":true}` to the same topic: the notification disappears and the row says "Resolved".
5. Airplane mode on, publish two alerts, airplane mode off, open the app on Security: the snackbar "2 alerts arrived while you were offline" appears once, and the two alerts are in the list without having posted notifications.
6. Leave the home Wi-Fi (mobile data), open a fresh alert's sheet and press Play: the clip downloads (progress ring) and plays; the picture also loads. Then publish an alert whose `image_url` host has no gateway rule: the notification is text-only and the sheet says "Media no longer available"; `adb logcat -s OikosMedia` shows one line naming the host.
7. Turn notifications off in the system settings: Settings shows "Notifications off", Security shows the banner; "Turn on" opens the system settings. Turn them back on and return: the banner is gone.
8. Settings, "Set up connection again": `mosquitto_sub -t 'oikos-push/tokens/#' -v -W 3` prints nothing (the retained registration was cleared), the alert list is empty after the next setup, and no old notification is left in the shade.
9. Optional, once per device: with Wi-Fi off, repeat step 8 to confirm the unregister goes through the gateway.

- [ ] **Step 5: Rollout notes (spec §10 steps 3 and 4)**

- If the onboarding QR carries `push.token_topic` = the old default `fcm-push/tokens/{installId}`, regenerate it with `tools/setup-qr.py` after removing `push.token_topic` from the setup JSON (or set it to `oikos-push/tokens/{installId}`); with the key left out the app uses `oikos-push/tokens/{installId}`. An already installed app keeps the topic stored in its profile: "Set up again" with the new QR is what changes it.
- Install the app, grant notifications, and confirm Settings shows "Registered". Move producers to `oikos-push/notification/<source>` one at a time; mute DomoticApp's matching channels on the phone when Oikos has proven itself.
- OikosPush (separate plan) and the gateway rule for the Frigate host are prerequisites, not part of this branch.

- [ ] **Step 6: Finish the branch**

Use `superpowers:finishing-a-development-branch` to decide how `feat/push-alerts` is integrated.

---

## Self-review notes (written while drafting)

- **Spec coverage, §4-§8, §9 Oikos tests, §10 steps 3-4:**
  - §4 modules and model: Tasks 1, 2, 5, 12 (`:feature:alerts`), 9 (`:app` wiring); dependencies: Tasks 2, 3, 4, 6, 12.
  - §5.1 channels: Task 7. §5.2 service and notifier: Tasks 8, 9. §5.3 `OneShotPublisher`: Task 5. §5.4 registration: Tasks 6, 9 (`PushWiring`), 16 (end of onboarding), 15 (Register again), 17 (unregister). §5.5 replies: Task 7 (+12 sheet). §5.6 `PushStatus`: Task 10.
  - §6.1 Room: Task 2. §6.2 `HistorySync`: Task 11. §6.3 `MediaFetcher`, Coil fetcher, `VideoCache`, caches cleared on reset: Tasks 3, 4, 17.
  - §7.1 Recent alerts, banner, catch-up snackbar: Task 14. §7.2 Alerts screen: Task 13. §7.3 detail sheet, deep link, Open device: Tasks 12, 13. §7.4 Settings: Task 15. §7.5 onboarding: Task 16. §7.6 demo: Tasks 12, 17.
  - §8 error table: malformed (Tasks 1, 9), image over budget (8), `Gone`/`NotAllowed` (3, 12), registration fails (6, 10, 15), reply fails 5 times (7), permission denied (9, 14, 15), no profile/demo (5, 6, 7), history malformed (11).
  - §9 Oikos tests: parser (1), `OneShotPublisher` (5), `PushRegistrar` and workers (6, 7), DAO (2), `HistorySync` (11), `MediaFetcher` (3), `PushStatus` (10), UI screenshots in both themes (12, 13, 14). Manual checks: Task 18.
- **Placeholder scan:** every step has code or an exact command; there is no deferred item. The Robolectric `sdk = [34]` remark in Task 9 is a concrete fallback, not a gap.
- **Signature consistency:** `ReplyQueue` is `enqueue(...): UUID` + `state(id)` + `cancelAll()` in Tasks 7, 9 (TestPushApp users), 12, 16, 17; `PushComponent` gains `ingestor` in Task 9 and every implementer is updated there; `PushWorkerFactory` takes four arguments from Task 7 on; `PushStatusSource` has `status`, `refresh`, `registerAgain` in Tasks 10, 14, 15.
