# Oikos History (app side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show recorded history in Oikos: a device detail screen with 24 h / 7 d / 30 d charts, kWh-per-day bars for energy, and "kWh today" on the Energy cards, fetched from the OikosHistory LAN service.

**Architecture:** A new JVM module `:core:history` turns an entity into a `HistoryRef(topic, key)` and fetches `/v1/series|daily|today` through `LanOrGatewayGet`, the LAN-first / gateway-raw-GET route extracted from `MediaFetcher` into `:core:transport`. The service URL is an optional field of the direct profile (QR, Settings). A new `:feature:history` full-screen destination draws Vico charts; Home, the control sheet and Energy open it. Energy-specific logic (meter pairing, derived house) stays in `:feature:energy` behind an `EnergyHistory` interface.

**Tech Stack:** Kotlin 2.4, Jetpack Compose + Material 3, Navigation3, Hilt, kotlinx.serialization, OkHttp (via the gateway SDK), Vico 2.1.3, JUnit5 (JVM modules) / JUnit4 + Robolectric (Android modules), MockWebServer.

**Spec:** `docs/superpowers/specs/2026-10-08-oikos-history-design.md` (§4–§8). The service side (§3) is built in a separate session; §3.3 is the contract this plan codes against.

## Deviations from the spec (found while planning)

1. **Settings has no connection edit screen today** (only a read-only summary). The history URL gets a tappable row + a small dialog (Task 6), saved in place by a new `ProfileRepository.setHistoryUrl`.
2. **`DemoHistoryClient` lives in `:core:history`**, not `:core:demo` (which is the in-process demo MQTT broker and does not know history types) — the same choice as `DemoAlerts` living in `:core:alerts`.
3. **The min/max "band" is drawn as two faint lines** around the mean: Vico 2.1 has no fill between two series.
4. **Energy:** tapping a node keeps opening its existing detail sheet; the sheet gains a History button per sensor (and one for the derived house) and the "today" line. "kWh today" shows on the Energy **cards** and in the sheet, not inside the diagram bubbles, whose label layout was tuned to avoid the flow edges.

## Global Constraints

- Service API (spec §3.3), exact: `GET /v1/series?topic=&key=&range=24h|7d|30d` → `{"window": <s>, "points": [[t, mean, min, max], …]}`; `GET /v1/daily?topic=&key=&days=7|30&kind=counter|power` → `{"days": [[t_midnight, kwh], …]}`; `GET /v1/today?topic=&key=&kind=counter|power` → `{"kwh": <float|null>, "since": t}`; errors `400 {"error":"bad_request",…}`, `404 {"error":"not_recorded"}`, `503 {"error":"influx_unavailable"}`. Timestamps are unix **seconds**.
- `power` daily/today assume watts; `counter` assumes the meter's own unit. The app converts with the entity unit: counter `kWh`×1, `Wh`×0.001; power `W`×1, `kW`×1000. Any other unit has no daily/today view.
- An entity has history only when its value template reads a `value_json` key (Athena-Core records only JSON objects). No key → no history UI.
- `historyUrl == null` (or the demo profile off) hides every history entry point and the "today" line; the demo profile uses `DemoHistoryClient`.
- Cache TTLs: 1 min for `24h` series and `today`; 10 min for `7d`/`30d` series and `daily`. Memory only.
- `historyUrl` format: `http://` or `https://`, a host, optional port 1–65535, no path, query or fragment (a trailing `/` is accepted and removed).
- Strings: English and French for every user-visible string. Keys `history_*` in `:feature:history`, `sheet_history` in `:feature:home`, `settings_history*` in `:feature:settings`, `energy_today*` in `:feature:energy`.
- Commits: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, message ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Work on branch `feat/history`.
- Full check before the final review: `./gradlew check` (all modules green).

## Review Focus

1. **A meter reset or a sensor that publishes rarely** — the 24 h chart gets very few points or `points: []`: show "No data in this period" (not the error state) and keep the chips usable. Pinned in Task 8 (`empty series is NoData, not NotRecorded`).
2. **The history URL typed with a path or a trailing slash** (`http://192.168.1.91:8090/`) — accepted, normalised to no trailing slash, so the client never builds `//v1/…`. Pinned in Task 2 (`isHistoryUrl` + `normalizeHistoryUrl` tests) and Task 4 (`base url trailing slash is not doubled`).
3. **French template keys with accents and dots** (`value_json['température_int.']`) — extracted intact and URL-encoded. Pinned in Task 3 (`bracket key with accents and a dot`) and Task 4 (`key is url encoded`).
4. **Switching range quickly / leaving the screen mid-request** — the last selected range wins; a late answer for an older range never replaces it. Pinned in Task 8 (`a late answer for an older range is dropped`).
5. **Away from home with a gateway key that lacks the history host** — "Add the history host to the gateway key", not a generic error; the Energy "today" line just hides. Pinned in Task 4 (`gateway refusal is NotAllowed`) and Task 8 (`NotAllowed state`).

---

## File map

**`:core:transport`** (JVM)
- Create `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/LanOrGatewayGet.kt` — `HttpRoute`, `HttpRoutes`, `HttpGetResult`, `LanOrGatewayGet`.
- Create `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/LanOrGatewayGetTest.kt`.

**`:core:alerts`**
- Modify `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcher.kt` — becomes a thin mapper over `LanOrGatewayGet`; `MediaRoute`/`MediaRoutes` become typealiases.

**`:core:data`**
- Modify `ConnectionProfile.kt` (`Direct.historyUrl`), `StoredProfile.kt`, `ProfileRepository.kt` (`setHistoryUrl`), `OnboardingPayload.kt` (`history.url`).
- Create `core/data/src/main/kotlin/com/gekkotron/oikos/data/HistoryUrls.kt`.
- Tests: `ProfileRepositoryTest.kt`, `OnboardingPayloadTest.kt`, new `HistoryUrlsTest.kt`.
- Modify `tools/setup-qr.py`.

**`:core:history`** (new, JVM)
- `core/history/build.gradle.kts`
- `src/main/kotlin/com/gekkotron/oikos/history/HistoryModels.kt` — `HistoryRef`, `HistoryRange`, `EnergyKind`, `EnergyScale`, `HistoryPoint`, `Series`, `DayTotal`, `DailyTotals`, `HistoryResult`.
- `…/HistoryRefs.kt` — `HistoryRefs.of(entity)`, `HistoryRefs.jsonKey(template)`.
- `…/HistoryApi.kt` — URL building + JSON parsing.
- `…/HistoryClient.kt` — `HistoryClient` interface, `HistoryClient.None`, `HttpHistoryClient` (+ cache).
- `…/EnergyHistory.kt` — interface implemented by `:feature:energy`.
- `…/DemoHistoryClient.kt`.
- Tests next to each under `src/test/kotlin/com/gekkotron/oikos/history/`.

**`:app`**
- Create `app/src/main/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClient.kt`, `app/src/main/kotlin/com/gekkotron/oikos/di/HistoryModule.kt`, test `app/src/test/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClientTest.kt`.
- Modify `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt` (`HistoryKey`, entries, callbacks), `app/build.gradle.kts`, `settings.gradle.kts`.

**`:core:designsystem`**
- Create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/charts/HistoryCharts.kt` (`ChartPoint`, `HistoryLineChart`, `DailyBarsChart`, `ChartSkeleton`).
- Modify `core/designsystem/build.gradle.kts`, `gradle/libs.versions.toml`.

**`:feature:history`** (new)
- `feature/history/build.gradle.kts`, `src/main/kotlin/com/gekkotron/oikos/feature/history/HistoryTarget.kt`, `presentation/HistoryViewModel.kt`, `presentation/HistoryUi.kt`, `ui/HistoryScreen.kt`, `src/main/res/values{,-fr}/strings.xml`.
- Tests: `presentation/HistoryViewModelTest.kt`, `ui/HistoryScreenshotTest.kt`, `ScreenshotSupport.kt`, `FakeHistoryClient.kt`, `src/test/resources/robolectric.properties`.

**`:feature:settings`**
- Modify `presentation/ConnectionSummary.kt`, `presentation/SettingsViewModel.kt`, `ui/SettingsScreen.kt`, strings; tests `ConnectionSummaryTest.kt`, `SettingsViewModelTest.kt`.

**`:feature:home`**
- Modify `presentation/HomeModels.kt` (`TapAction.OPEN_HISTORY`, `CardUi.hasHistory`), `presentation/HomeUiBuilder.kt`, `presentation/HomeViewModel.kt`, `ui/HomeScreen.kt`, `ui/ControlSheet.kt`, `ui/EntityControlSheet.kt`, strings; tests in `HomeUiBuilderTest.kt`.

**`:feature:energy`**
- Create `presentation/EnergyToday.kt`, `history/EnergyHistoryImpl.kt`, `di/EnergyHistoryModule.kt`.
- Modify `presentation/EnergyModels.kt` (`todayKwh`), `presentation/EnergyFlowBuilder.kt` (value lookup), `presentation/EnergyViewModel.kt`, `ui/EnergyScreen.kt`, `ui/EnergyDetailSheet.kt`, strings; tests.

---

### Task 1: `LanOrGatewayGet` in `:core:transport`, `MediaFetcher` on top of it

**Files:**
- Create: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/LanOrGatewayGet.kt`
- Create: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/LanOrGatewayGetTest.kt`
- Modify: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcher.kt`
- Test (unchanged, must stay green): `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcherTest.kt`, `app/src/test/kotlin/com/gekkotron/oikos/connection/MediaRoutesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class HttpRoute(val local: LocalProbe?, val gateway: GatewayDownloader?)
  fun interface HttpRoutes { suspend fun current(): HttpRoute }
  sealed interface HttpGetResult {
      class Response(val status: Int, val bytes: ByteArray, val contentType: String?) : HttpGetResult
      data object TooLarge : HttpGetResult
      data object NotAllowed : HttpGetResult
      data class Unavailable(val reason: String) : HttpGetResult
  }
  class LanOrGatewayGet(routes: HttpRoutes, http: OkHttpClient, isLanHost: (String) -> Boolean, log: (String) -> Unit, io: CoroutineDispatcher = Dispatchers.IO) {
      suspend fun get(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): HttpGetResult
  }
  ```
  In `:core:alerts`: `typealias MediaRoute = HttpRoute`, `typealias MediaRoutes = HttpRoutes` (same constructor and SAM shape as before).

Behaviour (moved verbatim from `MediaFetcher`, spec §4.3):
- Not an http(s) URL → `Unavailable("not an http url")`.
- LAN tried only when `route.local != null && isLanHost(host) && local.reachable()`: client with 5 s connect, 15 s read, no redirects. A non-2xx LAN answer is returned as `Response(status, ByteArray(0), null)` **without reading the body and without falling back**. `Content-Length > maxBytes` or more than `maxBytes` streamed → `TooLarge`. No body → `Unavailable("empty body")`. `IOException` → remember its message and fall back.
- Gateway: none → `Unavailable(lanReason ?: "no route")`. `gateway.download(url, 30)`; body over `maxBytes` → `TooLarge`; other statuses returned as `Response`. `DownloadNotAllowed` → `NotAllowed`, logged once per host as `"the gateway refused $host (${reason}); add the host to this key's rules"`. Other exceptions → `Unavailable(message ?: class simple name)`. `CancellationException` is rethrown.

- [ ] **Step 1: Write the failing tests**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/LanOrGatewayGetTest.kt` (JUnit5 — `:core:transport` is `oikos.jvm.library`):

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LanOrGatewayGetTest {
    private val server = MockWebServer()
    private val logged = mutableListOf<String>()

    @BeforeEach fun start() = server.start()
    @AfterEach fun stop() = server.shutdown()

    private class FakeGateway(var answer: () -> RawDownload = { RawDownload(200, "gw".encodeToByteArray(), "application/json") }) : GatewayDownloader {
        val urls = mutableListOf<String>()
        override suspend fun download(url: String, timeoutSeconds: Int): RawDownload {
            check(timeoutSeconds == 30)
            urls += url
            return answer()
        }
    }

    private fun get(local: Boolean?, gateway: GatewayDownloader?, lan: (String) -> Boolean = { true }) = LanOrGatewayGet(
        routes = { HttpRoute(local?.let { up -> LocalProbe { up } }, gateway) },
        http = OkHttpClient(),
        isLanHost = lan,
        log = { logged += it },
    )

    private fun url(path: String = "/v1/series") = server.url(path).toString()

    @Test
    fun `the LAN answers first and the gateway is not touched`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"points":[]}""").setHeader("Content-Type", "application/json"))
        val gateway = FakeGateway()
        val r = assertIs<HttpGetResult.Response>(get(local = true, gateway = gateway).get(url(), 1_000))
        assertEquals(200, r.status)
        assertContentEquals("""{"points":[]}""".encodeToByteArray(), r.bytes)
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a LAN 404 is returned as a response and does not fall back`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_recorded"}"""))
        val gateway = FakeGateway()
        val r = assertIs<HttpGetResult.Response>(get(local = true, gateway = gateway).get(url(), 1_000))
        assertEquals(404, r.status)
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a connection error falls back to the gateway`(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val gateway = FakeGateway()
        val r = assertIs<HttpGetResult.Response>(get(local = true, gateway = gateway).get(url(), 1_000))
        assertContentEquals("gw".encodeToByteArray(), r.bytes)
        assertEquals(listOf(url()), gateway.urls)
    }

    @Test
    fun `a host outside the private network never gets a direct request`(): Unit = runBlocking {
        val gateway = FakeGateway()
        get(local = true, gateway = gateway, lan = { false }).get(url(), 1_000)
        assertEquals(0, server.requestCount)
        assertEquals(1, gateway.urls.size)
    }

    @Test
    fun `a refused destination is NotAllowed and logged once per host`(): Unit = runBlocking {
        val gateway = FakeGateway { throw DownloadNotAllowed("destination not allowed for this secret") }
        val g = get(local = false, gateway = gateway)
        assertEquals(HttpGetResult.NotAllowed, g.get(url(), 1_000))
        assertEquals(HttpGetResult.NotAllowed, g.get(url("/v1/daily"), 1_000))
        assertEquals(1, logged.size)
    }

    @Test
    fun `bodies over maxBytes are TooLarge on both routes`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(50)))
        assertEquals(HttpGetResult.TooLarge, get(local = true, gateway = null).get(url(), 10))
        val gateway = FakeGateway { RawDownload(200, ByteArray(50), null) }
        assertEquals(HttpGetResult.TooLarge, get(local = false, gateway = gateway).get(url(), 10))
    }

    @Test
    fun `no route at all is Unavailable`(): Unit = runBlocking {
        assertIs<HttpGetResult.Unavailable>(get(local = null, gateway = null).get(url(), 1_000))
    }

    @Test
    fun `a url that is not http is Unavailable`(): Unit = runBlocking {
        assertEquals(HttpGetResult.Unavailable("not an http url"), get(local = true, gateway = FakeGateway()).get("ftp://x/y", 1_000))
    }
}
```

Add to `core/transport/build.gradle.kts` dependencies: nothing (MockWebServer is already `testImplementation`).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:transport:test --tests "*LanOrGatewayGetTest"`
Expected: FAIL — `Unresolved reference: LanOrGatewayGet`.

- [ ] **Step 3: Implement `LanOrGatewayGet.kt`**

```kotlin
package com.gekkotron.oikos.transport

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

/** How a LAN URL can be reached right now: a local probe when a LAN broker is allowed, a gateway when allowed. */
class HttpRoute(val local: LocalProbe?, val gateway: GatewayDownloader?)

fun interface HttpRoutes {
    suspend fun current(): HttpRoute
}

sealed interface HttpGetResult {
    /** Any HTTP answer; a non-2xx LAN answer has an empty body (it is never read). */
    class Response(val status: Int, val bytes: ByteArray, val contentType: String?) : HttpGetResult
    data object TooLarge : HttpGetResult
    /** The gateway key has no rule for this host or port. */
    data object NotAllowed : HttpGetResult
    data class Unavailable(val reason: String) : HttpGetResult
}

/** A GET of a LAN URL: direct when the LAN is reachable (5 s connect, 15 s read), the gateway's raw download otherwise (30 s). */
class LanOrGatewayGet(
    private val routes: HttpRoutes,
    http: OkHttpClient,
    private val isLanHost: (String) -> Boolean,
    private val log: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lan: OkHttpClient = http.newBuilder()
        .connectTimeout(LAN_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LAN_READ_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false) // a LAN host must not bounce the request to another (cleartext) host
        .followSslRedirects(false)
        .build()
    private val warnedHosts = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Lan {
        class Done(val result: HttpGetResult) : Lan
        class Down(val reason: String) : Lan
    }

    suspend fun get(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): HttpGetResult {
        val parsed = url.toHttpUrlOrNull() ?: return HttpGetResult.Unavailable("not an http url")
        val host = parsed.host
        val route = routes.current()
        var lanReason: String? = null
        val probe = route.local
        if (probe != null && isLanHost(host) && probe.reachable()) {
            when (val result = fromLan(url, maxBytes, onProgress)) {
                is Lan.Done -> return result.result
                is Lan.Down -> lanReason = result.reason
            }
        }
        val gateway = route.gateway ?: return HttpGetResult.Unavailable(lanReason ?: "no route")
        return fromGateway(gateway, url, host, maxBytes)
    }

    private suspend fun fromGateway(gateway: GatewayDownloader, url: String, host: String, maxBytes: Long): HttpGetResult = try {
        val download = gateway.download(url, GATEWAY_TIMEOUT_SECONDS)
        if (download.bytes.size > maxBytes) HttpGetResult.TooLarge else HttpGetResult.Response(download.status, download.bytes, download.contentType)
    } catch (e: CancellationException) {
        throw e
    } catch (e: DownloadNotAllowed) {
        if (warnedHosts.add(host)) log("the gateway refused $host (${e.reason}); add the host to this key's rules")
        HttpGetResult.NotAllowed
    } catch (e: Exception) {
        HttpGetResult.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun fromLan(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Lan = try {
        lan.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) Lan.Done(HttpGetResult.Response(response.code, ByteArray(0), null))
            else Lan.Done(readBody(response, maxBytes, onProgress))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Lan.Down(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun readBody(response: Response, maxBytes: Long, onProgress: (Long, Long?) -> Unit): HttpGetResult = withContext(io) {
        val body = response.body ?: return@withContext HttpGetResult.Unavailable("empty body")
        val total = body.contentLength().takeIf { it >= 0 }
        if (total != null && total > maxBytes) return@withContext HttpGetResult.TooLarge
        val out = ByteArrayOutputStream(total?.toInt()?.coerceIn(1, 1 shl 20) ?: INITIAL_BUFFER)
        val source = body.source()
        val chunk = ByteArray(CHUNK)
        var read = 0L
        while (true) {
            ensureActive()
            val n = source.read(chunk)
            if (n < 0) break
            read += n
            if (read > maxBytes) return@withContext HttpGetResult.TooLarge
            out.write(chunk, 0, n)
            onProgress(read, total)
        }
        HttpGetResult.Response(response.code, out.toByteArray(), body.contentType()?.toString())
    }

    private companion object {
        const val LAN_CONNECT_SECONDS = 5L
        const val LAN_READ_SECONDS = 15L
        const val GATEWAY_TIMEOUT_SECONDS = 30
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

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:transport:test --tests "*LanOrGatewayGetTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Rewrite `MediaFetcher` on top of it**

Replace the body of `MediaFetcher.kt` from the `MediaRoute` declaration to the end of the file (keep the package line, `Media`, `MediaFailure`, `MediaException`, `MediaSource`) with:

```kotlin
/** How media can be reached right now (spec §6.3): see [HttpRoute]. */
typealias MediaRoute = HttpRoute
typealias MediaRoutes = HttpRoutes

/** LAN first (5 s connect, 15 s read), the gateway's raw download otherwise (30 s); spec §6.3. */
class MediaFetcher(
    routes: MediaRoutes,
    http: OkHttpClient,
    io: CoroutineDispatcher = Dispatchers.IO,
    isLanHost: (String) -> Boolean = ::isLikelyLocalHost,
    log: (String) -> Unit = { Log.w(TAG, it) },
) : MediaSource {
    private val get = LanOrGatewayGet(routes, http, isLanHost, log, io)

    override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> =
        when (val r = get.get(url, maxBytes, onProgress)) {
            is HttpGetResult.Response -> when {
                r.status == 404 || r.status == 410 -> fail(MediaFailure.Gone)
                r.status !in 200..299 -> fail(MediaFailure.Unavailable("http ${r.status}"))
                else -> Result.success(Media(r.bytes, r.contentType))
            }
            HttpGetResult.TooLarge -> fail(MediaFailure.Unavailable(TOO_LARGE))
            HttpGetResult.NotAllowed -> fail(MediaFailure.NotAllowed)
            is HttpGetResult.Unavailable -> fail(MediaFailure.Unavailable(if (r.reason == "no route") "no route to the media" else r.reason))
        }

    private fun fail(failure: MediaFailure): Result<Media> = Result.failure(MediaException(failure))

    private companion object {
        const val TAG = "OikosMedia"
        const val TOO_LARGE = "too large"
    }
}
```

Imports now needed: `android.util.Log`, `com.gekkotron.oikos.data.isLikelyLocalHost`, `com.gekkotron.oikos.transport.HttpGetResult`, `com.gekkotron.oikos.transport.HttpRoute`, `com.gekkotron.oikos.transport.HttpRoutes`, `com.gekkotron.oikos.transport.LanOrGatewayGet`, `kotlinx.coroutines.CoroutineDispatcher`, `kotlinx.coroutines.Dispatchers`, `okhttp3.OkHttpClient`. Remove the now-unused ones (OkHttp `Call`/`Callback`/`Request`/`Response`, `ConcurrentHashMap`, `TimeUnit`, stream and coroutine-continuation imports).

Then check every other user of `MediaRoute(…)` / `MediaRoutes` still compiles (`grep -rn "MediaRoute" --include='*.kt' app core`): `app/.../connection/MediaRoutes.kt` (`mediaRouteFor`, `ProfileMediaRoutes`) keeps working through the typealiases.

- [ ] **Step 6: Run the regression tests**

Run: `./gradlew :core:alerts:testDebugUnitTest --tests "*MediaFetcherTest" :app:testDebugUnitTest --tests "*MediaRoutesTest" :core:transport:test`
Expected: PASS — every `MediaFetcherTest` case unchanged (15 tests), `MediaRoutesTest` (4).

- [ ] **Step 7: Commit**

```bash
git add core/transport core/alerts
git commit -m "refactor(transport): LanOrGatewayGet shared by media and history"
```

---

### Task 2: `historyUrl` in the profile, the QR and `setup-qr.py`

**Files:**
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/HistoryUrls.kt`, `core/data/src/test/kotlin/com/gekkotron/oikos/data/HistoryUrlsTest.kt`
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt`, `StoredProfile.kt`, `ProfileRepository.kt`, `OnboardingPayload.kt`
- Modify tests: `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt`, `OnboardingPayloadTest.kt`
- Modify: `tools/setup-qr.py`

**Interfaces:**
- Produces: `ConnectionProfile.Direct(local, prefix, gateway, pushTokenTopic, historyUrl: String? = null)`; `fun isHistoryUrl(text: String): Boolean`; `fun normalizeHistoryUrl(text: String): String?` (trimmed, trailing `/` removed, `null` when invalid); `suspend fun ProfileRepository.setHistoryUrl(url: String?)`.

`historyUrl` is not part of `cacheKey()` (`host:port:prefix`), so editing it keeps the entity cache. It is part of `Direct` equality, so the next Home rebuilds its entity source once — acceptable (same as editing the gateway).

- [ ] **Step 1: Write the failing tests**

`HistoryUrlsTest.kt` (JUnit4, `:core:data` is an Android library):

```kotlin
package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryUrlsTest {
    @Test
    fun `http and https hosts with an optional port are history urls`() {
        assertTrue(isHistoryUrl("http://192.168.1.91:8090"))
        assertTrue(isHistoryUrl("https://history.lan"))
        assertTrue(isHistoryUrl("http://geekom.local:8090/"))
    }

    @Test
    fun `anything else is not`() {
        listOf("", "192.168.1.91:8090", "ftp://x", "http://", "http://h:0", "http://h:70000", "http://h/v1", "http://h?x=1", "http://h#f", "http://a b")
            .forEach { assertFalse(isHistoryUrl(it), it) }
    }

    @Test
    fun `normalising trims and drops the trailing slash`() {
        assertEquals("http://192.168.1.91:8090", normalizeHistoryUrl("  http://192.168.1.91:8090/ "))
        assertNull(normalizeHistoryUrl("http://h/v1"))
    }
}
```

`ProfileRepositoryTest.kt` — add (reuse the existing `direct`, `repo()`, `settled()` helpers):

```kotlin
@Test
fun `history url round-trips and can be set and cleared in place`() = runTest {
    val r = repo()
    r.save(direct.copy(historyUrl = "http://192.168.1.91:8090"))
    assertEquals("http://192.168.1.91:8090", ((r.state.first { it is ProfileState.Ready } as ProfileState.Ready).profile as ConnectionProfile.Direct).historyUrl)
    r.finishRooms()
    r.setHistoryUrl("http://10.0.0.2:8090")
    val ready = r.state.first { ((it as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.historyUrl == "http://10.0.0.2:8090" } as ProfileState.Ready
    assertFalse(ready.roomsPending) // an in-place edit, not a new pairing
    r.setHistoryUrl(null)
    r.state.first { ((it as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.historyUrl == null }
}

@Test
fun `a profile file from before the history url has none`() = runTest {
    val f = file()
    f.writeText("""{"installId":"x","mode":"DIRECT","local":{"host":"h","port":1883,"tls":false,"ws":false},"prefix":"homeassistant"}""")
    assertNull(((repo(f).settled() as ProfileState.Ready).profile as ConnectionProfile.Direct).historyUrl)
}
```

`OnboardingPayloadTest.kt` — add:

```kotlin
@Test
fun `history url is read and normalised`() {
    val p = ok("""{"v":1,"local":{"host":"h","tls":false,"ws":false},"history":{"url":"http://192.168.1.91:8090/"}}""")
    assertEquals("http://192.168.1.91:8090", p.historyUrl)
}

@Test
fun `an invalid history url is named`() {
    assertEquals(PayloadError.InvalidField("history.url"), error("""{"v":1,"local":{"host":"h","tls":false,"ws":false},"history":{"url":"192.168.1.91"}}"""))
}
```
and in `minimal payload uses defaults` add `assertNull(p.historyUrl)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: isHistoryUrl`, `historyUrl`, `setHistoryUrl`.

- [ ] **Step 3: Implement**

`HistoryUrls.kt`:

```kotlin
package com.gekkotron.oikos.data

private val HISTORY_URL = Regex("""^https?://([A-Za-z0-9._-]+|\[[0-9A-Fa-f:]+])(?::(\d{1,5}))?/?$""")

/** `http(s)://host[:port]`, nothing after the host but an optional `/` (history spec §4.4). */
fun isHistoryUrl(text: String): Boolean {
    val m = HISTORY_URL.matchEntire(text.trim()) ?: return false
    val port = m.groupValues[2]
    return port.isEmpty() || port.toInt() in 1..65535
}

fun normalizeHistoryUrl(text: String): String? = text.trim().takeIf(::isHistoryUrl)?.removeSuffix("/")
```

`ConnectionProfile.kt` — `Direct` gains a last parameter, documented:
```kotlin
        /** The OikosHistory service, e.g. `http://192.168.1.91:8090`; null hides history (history spec §4.4). */
        val historyUrl: String? = null,
```

`StoredProfile.kt` — `StoredProfile` gains, after `pushTokenTopic`:
```kotlin
    val historyUrl: String? = null,
```

`ProfileRepository.kt`:
- `toDirect` passes `historyUrl = historyUrl`.
- `toStored` (Direct branch) passes `historyUrl = p.historyUrl`.
- New method next to `setRoute`:
```kotlin
    /** Edits the history URL in place: no new pairing, rooms and route are kept. */
    suspend fun setHistoryUrl(url: String?) {
        withContext(ioDispatcher) { store.updateData { it.copy(historyUrl = url) } }
    }
```
(Follow `setRoute`'s exact shape — same dispatcher field name and the same `store` property.)

`OnboardingPayload.kt` — before the `return ParseResult.Ok(...)`:
```kotlin
        val history = (root["history"] as? JsonObject)?.let { h ->
            h.string("url")?.let(::normalizeHistoryUrl) ?: return invalid("history.url")
        }
        return ParseResult.Ok(ConnectionProfile.Direct(broker, prefix, gateway, tokenTopic, history))
```

`tools/setup-qr.py` — after the gateway arguments:
```python
parser.add_argument("--history-url", help="OikosHistory service, e.g. http://192.168.1.91:8090")
parser.add_argument("--no-history", action="store_true", help="remove the history section")
```
and after the gateway block:
```python
if args.no_history:
    payload.pop("history", None)
elif args.history_url:
    if not re.fullmatch(r"https?://[A-Za-z0-9._-]+(:\d{1,5})?/?", args.history_url):
        fail("--history-url must be http(s)://host[:port]")
    payload["history"] = {"url": args.history_url.rstrip("/")}
```
(add `import re` at the top if missing) and document both flags in the module docstring.

Then fix every positional `ConnectionProfile.Direct(…)` call that breaks (`grep -rn "ConnectionProfile.Direct(" --include='*.kt' .`); named or 4-argument calls keep compiling thanks to the default.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:data:testDebugUnitTest :feature:onboarding:testDebugUnitTest :app:testDebugUnitTest && python3 tools/setup-qr.py --help`
Expected: PASS; the help text lists `--history-url` and `--no-history`.

- [ ] **Step 5: Commit**

```bash
git add core/data tools/setup-qr.py
git commit -m "feat(data): optional history service URL in the profile and the setup QR"
```

---

### Task 3: `:core:history` module, models and `HistoryRefs`

**Files:**
- Create: `core/history/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include(":core:history")` after `:core:alerts`)
- Create: `core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryModels.kt`, `HistoryRefs.kt`
- Create: `core/history/src/test/kotlin/com/gekkotron/oikos/history/HistoryRefsTest.kt`, `HistoryModelsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class HistoryRef(val topic: String, val key: String)
  enum class HistoryRange(val query: String, val ttlMillis: Long) { H24("24h", 60_000), D7("7d", 600_000), D30("30d", 600_000) }
  enum class EnergyKind(val query: String) { COUNTER("counter"), POWER("power") }
  data class EnergyScale(val kind: EnergyKind, val factor: Double) { companion object { fun of(unit: String?): EnergyScale? } }
  data class HistoryPoint(val t: Long, val mean: Double, val min: Double, val max: Double)
  data class Series(val windowSeconds: Long, val points: List<HistoryPoint>)
  data class DayTotal(val t: Long, val kwh: Double)
  data class DailyTotals(val days: List<DayTotal>)
  sealed interface HistoryResult<out T> {
      data class Ok<T>(val value: T) : HistoryResult<T>
      data object NotRecorded : HistoryResult<Nothing>
      data object NotAllowed : HistoryResult<Nothing>
      data class Unavailable(val reason: String) : HistoryResult<Nothing>
  }
  object HistoryRefs { fun of(entity: Entity): HistoryRef?; fun jsonKey(template: String?): String? }
  ```

- [ ] **Step 1: Module skeleton**

`core/history/build.gradle.kts`:
```kotlin
plugins {
    id("oikos.jvm.library")
    id("oikos.serialization")
}

dependencies {
    // Entity, MqttBindings (the discovery wiring a ref is read from).
    api(project(":core:state"))
    // HttpGetResult: what LanOrGatewayGet answers.
    api(project(":core:transport"))
}
```

- [ ] **Step 2: Write the failing tests**

`HistoryRefsTest.kt`:
```kotlin
package com.gekkotron.oikos.history

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryRefsTest {
    private fun entity(kind: Kind, unit: String?, vararg config: Pair<String, String>) = Entity(
        uniqueId = "e", deviceId = null, name = "E", kind = kind, deviceClass = null, unit = unit, icon = null,
        capabilities = Capabilities(), bindings = MqttBindings("sensor", JsonObject(config.associate { (k, v) -> k to JsonPrimitive(v) })),
    )

    @Test
    fun `dot key`() = assertEquals("temperature", HistoryRefs.jsonKey("{{ value_json.temperature }}"))

    @Test
    fun `bracket key with accents and a dot`() = assertEquals("température_int.", HistoryRefs.jsonKey("{{ value_json['température_int.'] }}"))

    @Test
    fun `double-quoted key and filters`() = assertEquals("power", HistoryRefs.jsonKey("""{{ value_json["power"] | float | round(1) }}"""))

    @Test
    fun `anything else has no key`() {
        listOf(null, "", "{{ value }}", "{{ value_json.a.b }}", "{{ value_json.a + 1 }}", "{% if value_json.a %}1{% endif %}")
            .forEach { assertNull(HistoryRefs.jsonKey(it), it.toString()) }
    }

    @Test
    fun `a numeric sensor gets its state topic and key`() {
        val e = entity(Kind.SENSOR, "°C", "state_topic" to "zigbee2mqtt/rooms/living/thermometer", "value_template" to "{{ value_json.temperature }}")
        assertEquals(HistoryRef("zigbee2mqtt/rooms/living/thermometer", "temperature"), HistoryRefs.of(e))
    }

    @Test
    fun `a sensor without a unit, a raw payload or another kind has none`() {
        assertNull(HistoryRefs.of(entity(Kind.SENSOR, null, "state_topic" to "t", "value_template" to "{{ value_json.state }}")))
        assertNull(HistoryRefs.of(entity(Kind.SENSOR, "W", "state_topic" to "t")))
        assertNull(HistoryRefs.of(entity(Kind.SWITCH, "W", "state_topic" to "t", "value_template" to "{{ value_json.state }}")))
    }

    @Test
    fun `a climate uses its current temperature`() {
        val e = entity(Kind.CLIMATE, null, "current_temperature_topic" to "jeedom/data/rooms/remi/climatisation", "current_temperature_template" to "{{ value_json['température_int.'] }}")
        assertEquals(HistoryRef("jeedom/data/rooms/remi/climatisation", "température_int."), HistoryRefs.of(e))
    }
}
```
If `Capabilities()` has required parameters, build it the way `feature/energy/src/test/.../TestEntities.kt` does.

`HistoryModelsTest.kt`:
```kotlin
package com.gekkotron.oikos.history

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryModelsTest {
    @Test
    fun `energy scale follows the unit`() {
        assertEquals(EnergyScale(EnergyKind.COUNTER, 1.0), EnergyScale.of("kWh"))
        assertEquals(EnergyScale(EnergyKind.COUNTER, 0.001), EnergyScale.of("Wh"))
        assertEquals(EnergyScale(EnergyKind.POWER, 1.0), EnergyScale.of("W"))
        assertEquals(EnergyScale(EnergyKind.POWER, 1000.0), EnergyScale.of("kW"))
        assertNull(EnergyScale.of("°C"))
        assertNull(EnergyScale.of(null))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:history:test`
Expected: FAIL — unresolved `HistoryRefs`, `EnergyScale`.

- [ ] **Step 4: Implement**

`HistoryModels.kt` — exactly the types in **Interfaces** above, with:
```kotlin
data class EnergyScale(val kind: EnergyKind, val factor: Double) {
    companion object {
        /** kWh = what the service returns × [factor]; the service integrates watts and sums the meter's own unit (spec §3.3). */
        fun of(unit: String?): EnergyScale? = when (unit?.trim()) {
            "kWh" -> EnergyScale(EnergyKind.COUNTER, 1.0)
            "Wh" -> EnergyScale(EnergyKind.COUNTER, 0.001)
            "W" -> EnergyScale(EnergyKind.POWER, 1.0)
            "kW" -> EnergyScale(EnergyKind.POWER, 1000.0)
            else -> null
        }
    }
}
```

`HistoryRefs.kt`:
```kotlin
package com.gekkotron.oikos.history

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings

/** Where Athena-Core recorded an entity: its state topic and the JSON key its template reads (history spec §2, §4.1). */
object HistoryRefs {
    private val KEY = Regex(
        """^\{\{\s*value_json(?:\.([A-Za-z_][A-Za-z0-9_]*)|\[\s*(['"])(.+?)\2\s*])\s*(?:\|[^{}]*)?}}$""",
    )

    fun jsonKey(template: String?): String? {
        val m = KEY.matchEntire(template?.trim() ?: return null) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[3] }.ifEmpty { null }
    }

    fun of(entity: Entity): HistoryRef? {
        val b = entity.bindings as? MqttBindings ?: return null
        val (topicKey, templateKey) = when (entity.kind) {
            Kind.SENSOR -> if (entity.unit.isNullOrBlank()) return null else "state_topic" to "value_template"
            Kind.CLIMATE -> "current_temperature_topic" to "current_temperature_template"
            else -> return null
        }
        val topic = b.str(topicKey) ?: return null
        val key = jsonKey(b.str(templateKey)) ?: return null
        return HistoryRef(topic, key)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:history:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/history
git commit -m "feat(history): core:history module with history refs and models"
```

---

### Task 4: `HistoryApi` and `HttpHistoryClient` (URL building, parsing, cache)

**Files:**
- Create: `core/history/src/main/kotlin/com/gekkotron/oikos/history/HistoryApi.kt`, `HistoryClient.kt`, `EnergyHistory.kt`
- Create: `core/history/src/test/kotlin/com/gekkotron/oikos/history/HttpHistoryClientTest.kt`

**Interfaces:**
- Consumes: Task 1 `HttpGetResult`; Task 3 models.
- Produces:
  ```kotlin
  interface HistoryClient {
      /** True when history can be asked for at all (a URL is set, or the demo). Entry points hide otherwise. */
      val available: StateFlow<Boolean>
      suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series>
      suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals>
      suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?>
      companion object { val None: HistoryClient }
  }
  class HttpHistoryClient(baseUrl: suspend () -> String?, get: suspend (url: String) -> HttpGetResult, clock: () -> Long = System::currentTimeMillis) : HistoryClient
  interface EnergyHistory {
      /** The kWh meter paired with a power entity by the Energy rules, or null. */
      fun meterFor(entityId: String): String?
      suspend fun houseSeries(range: HistoryRange): HistoryResult<Series>
      suspend fun houseDaily(days: Int): HistoryResult<DailyTotals>
      companion object { val None: EnergyHistory }
  }
  ```
  `daily`/`today` multiply every kWh value by `scale.factor`. `HttpHistoryClient.available` is a constant `MutableStateFlow(true)`; `None.available` is `false` and every call returns `Unavailable("history is off")`; `EnergyHistory.None.meterFor` returns null and its calls return `Unavailable("no energy")`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gekkotron.oikos.history

import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpHistoryClientTest {
    private val ref = HistoryRef("zigbee2mqtt/rooms/living/thermometer", "température_int.")
    private val urls = mutableListOf<String>()
    private var now = 0L
    private var answer: HttpGetResult = ok("""{"window":600,"points":[[1696000000,21.0,20.5,21.5],[1696000600,21.2,21.0,21.4]]}""")

    private fun ok(json: String) = HttpGetResult.Response(200, json.encodeToByteArray(), "application/json")
    private fun client(base: String? = "http://192.168.1.91:8090/") =
        HttpHistoryClient(baseUrl = { base }, get = { urls += it; answer }, clock = { now })

    @Test
    fun `series is parsed`(): Unit = runBlocking {
        val s = assertIs<HistoryResult.Ok<Series>>(client().series(ref, HistoryRange.H24)).value
        assertEquals(600, s.windowSeconds)
        assertEquals(listOf(HistoryPoint(1696000000, 21.0, 20.5, 21.5), HistoryPoint(1696000600, 21.2, 21.0, 21.4)), s.points)
    }

    @Test
    fun `key is url encoded and base url trailing slash is not doubled`(): Unit = runBlocking {
        client().series(ref, HistoryRange.D7)
        assertEquals(
            "http://192.168.1.91:8090/v1/series?topic=zigbee2mqtt%2Frooms%2Fliving%2Fthermometer&key=temp%C3%A9rature_int.&range=7d",
            urls.single(),
        )
    }

    @Test
    fun `daily and today apply the scale`(): Unit = runBlocking {
        answer = ok("""{"days":[[1695938400,3200.0],[1696024800,1500.0]]}""")
        val d = assertIs<HistoryResult.Ok<DailyTotals>>(client().daily(ref, 7, EnergyScale(EnergyKind.COUNTER, 0.001))).value
        assertEquals(listOf(DayTotal(1695938400, 3.2), DayTotal(1696024800, 1.5)), d.days)
        assertEquals("http://192.168.1.91:8090/v1/daily?topic=zigbee2mqtt%2Frooms%2Fliving%2Fthermometer&key=temp%C3%A9rature_int.&days=7&kind=counter", urls.last())
        answer = ok("""{"kwh":3.412,"since":1696024800}""")
        assertEquals(HistoryResult.Ok(3.412), client().today(ref, EnergyScale(EnergyKind.POWER, 1.0)))
        answer = ok("""{"kwh":null,"since":1696024800}""")
        assertEquals(HistoryResult.Ok(null), client().today(ref, EnergyScale(EnergyKind.POWER, 1.0)))
    }

    @Test
    fun `statuses map to results`(): Unit = runBlocking {
        answer = HttpGetResult.Response(404, """{"error":"not_recorded"}""".encodeToByteArray(), null)
        assertEquals(HistoryResult.NotRecorded, client().series(ref, HistoryRange.H24))
        answer = HttpGetResult.Response(404, ByteArray(0), null) // a LAN 404: body never read
        assertEquals(HistoryResult.NotRecorded, client().series(ref, HistoryRange.D7))
        answer = HttpGetResult.Response(503, """{"error":"influx_unavailable"}""".encodeToByteArray(), null)
        assertIs<HistoryResult.Unavailable>(client().series(ref, HistoryRange.D30))
        answer = ok("not json")
        assertIs<HistoryResult.Unavailable>(client().today(ref, EnergyScale(EnergyKind.COUNTER, 1.0)))
    }

    @Test
    fun `gateway refusal is NotAllowed and no url is Unavailable`(): Unit = runBlocking {
        answer = HttpGetResult.NotAllowed
        assertEquals(HistoryResult.NotAllowed, client().series(ref, HistoryRange.H24))
        assertIs<HistoryResult.Unavailable>(client(base = null).series(ref, HistoryRange.H24))
    }

    @Test
    fun `answers are cached per request for their ttl, failures are not`(): Unit = runBlocking {
        val c = client()
        c.series(ref, HistoryRange.H24)
        now += 59_000
        c.series(ref, HistoryRange.H24)
        assertEquals(1, urls.size)
        now += 2_000
        c.series(ref, HistoryRange.H24)
        assertEquals(2, urls.size)
        c.series(ref, HistoryRange.D7)
        now += 9 * 60_000
        c.series(ref, HistoryRange.D7)
        assertEquals(3, urls.size)
        answer = HttpGetResult.Unavailable("down")
        c.series(ref, HistoryRange.D30)
        c.series(ref, HistoryRange.D30)
        assertEquals(5, urls.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:history:test --tests "*HttpHistoryClientTest"`
Expected: FAIL — unresolved `HttpHistoryClient`.

- [ ] **Step 3: Implement**

`HistoryApi.kt`:
```kotlin
package com.gekkotron.oikos.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** The OikosHistory HTTP contract (history spec §3.3). */
internal object HistoryApi {
    fun seriesUrl(base: String, ref: HistoryRef, range: HistoryRange) = url(base, "series", ref, "range" to range.query)
    fun dailyUrl(base: String, ref: HistoryRef, days: Int, kind: EnergyKind) = url(base, "daily", ref, "days" to "$days", "kind" to kind.query)
    fun todayUrl(base: String, ref: HistoryRef, kind: EnergyKind) = url(base, "today", ref, "kind" to kind.query)

    private fun url(base: String, path: String, ref: HistoryRef, vararg extra: Pair<String, String>): String =
        (listOf("topic" to ref.topic, "key" to ref.key) + extra)
            .joinToString("&", prefix = "${base.trimEnd('/')}/v1/$path?") { (k, v) -> "$k=${encode(v)}" }

    private fun encode(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")

    fun parseSeries(text: String): Series {
        val o = Json.parseToJsonElement(text) as JsonObject
        val points = o.getValue("points").jsonArray.map { p ->
            val a = p.jsonArray
            HistoryPoint(a.long(0), a.double(1), a.double(2), a.double(3))
        }
        return Series(o.getValue("window").jsonPrimitive.longOrNull ?: 0, points)
    }

    fun parseDaily(text: String, factor: Double): DailyTotals {
        val o = Json.parseToJsonElement(text) as JsonObject
        return DailyTotals(o.getValue("days").jsonArray.map { d -> d.jsonArray.let { DayTotal(it.long(0), it.double(1) * factor) } })
    }

    fun parseToday(text: String, factor: Double): Double? {
        val kwh = (Json.parseToJsonElement(text) as JsonObject).getValue("kwh")
        return if (kwh is JsonNull) null else kwh.jsonPrimitive.doubleOrNull?.times(factor)
    }

    private fun JsonArray.long(i: Int) = get(i).jsonPrimitive.longOrNull ?: error("bad timestamp")
    private fun JsonArray.double(i: Int) = get(i).jsonPrimitive.doubleOrNull ?: error("bad value")
}
```
Floating-point: `3200.0 * 0.001` is `3.2` exactly enough for `assertEquals` on `DayTotal`? It is `3.2000000000000006`. Round in `parseDaily`/`parseToday`: `Math.round(x * factor * 1000) / 1000.0` (the service already rounds to 3 decimals). Apply the same rounding helper in both.

`HistoryClient.kt`:
```kotlin
package com.gekkotron.oikos.history

import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

interface HistoryClient {
    val available: StateFlow<Boolean>
    suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series>
    suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals>
    suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?>

    companion object {
        /** No history: entry points hide. */
        val None: HistoryClient = object : HistoryClient {
            override val available: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
            private val off = HistoryResult.Unavailable("history is off")
            override suspend fun series(ref: HistoryRef, range: HistoryRange) = off
            override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale) = off
            override suspend fun today(ref: HistoryRef, scale: EnergyScale) = off
        }
    }
}

/** Over OikosHistory (spec §3.3); answers are kept in memory for their TTL (spec §4.2). */
class HttpHistoryClient(
    private val baseUrl: suspend () -> String?,
    private val get: suspend (url: String) -> HttpGetResult,
    private val clock: () -> Long = System::currentTimeMillis,
) : HistoryClient {
    override val available: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    private val cache = HashMap<String, Pair<Long, HistoryResult<*>>>()
    private val lock = Mutex()

    override suspend fun series(ref: HistoryRef, range: HistoryRange) =
        fetch({ HistoryApi.seriesUrl(it, ref, range) }, range.ttlMillis, HistoryApi::parseSeries)

    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale) =
        fetch({ HistoryApi.dailyUrl(it, ref, days, scale.kind) }, LONG_TTL) { HistoryApi.parseDaily(it, scale.factor) }

    override suspend fun today(ref: HistoryRef, scale: EnergyScale) =
        fetch({ HistoryApi.todayUrl(it, ref, scale.kind) }, SHORT_TTL) { HistoryApi.parseToday(it, scale.factor) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> fetch(url: (String) -> String, ttl: Long, parse: (String) -> T): HistoryResult<T> {
        val base = baseUrl() ?: return HistoryResult.Unavailable("no history url")
        val u = url(base)
        lock.withLock { cache[u]?.takeIf { clock() - it.first < ttl }?.let { return it.second as HistoryResult<T> } }
        val result: HistoryResult<T> = when (val r = get(u)) {
            is HttpGetResult.Response -> when (r.status) {
                in 200..299 -> try {
                    HistoryResult.Ok(parse(r.bytes.decodeToString()))
                } catch (e: SerializationException) {
                    HistoryResult.Unavailable("unreadable answer")
                } catch (e: IllegalArgumentException) {
                    HistoryResult.Unavailable("unreadable answer")
                } catch (e: IllegalStateException) {
                    HistoryResult.Unavailable("unreadable answer")
                } catch (e: NoSuchElementException) {
                    HistoryResult.Unavailable("unreadable answer")
                }
                404 -> HistoryResult.NotRecorded
                else -> HistoryResult.Unavailable("http ${r.status}")
            }
            HttpGetResult.NotAllowed -> HistoryResult.NotAllowed
            HttpGetResult.TooLarge -> HistoryResult.Unavailable("answer too large")
            is HttpGetResult.Unavailable -> HistoryResult.Unavailable(r.reason)
        }
        if (result is HistoryResult.Ok) lock.withLock { cache[u] = clock() to result }
        return result
    }

    private companion object {
        const val SHORT_TTL = 60_000L
        const val LONG_TTL = 600_000L
    }
}
```
(`SerializationException` extends `IllegalArgumentException`; keep both catches only if the compiler does not flag the first as redundant — otherwise keep `IllegalArgumentException`, `IllegalStateException`, `NoSuchElementException`.)

`EnergyHistory.kt` — the interface from **Interfaces**, with `None`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:history:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/history
git commit -m "feat(history): HTTP history client with per-range cache"
```

---

### Task 5: `DemoHistoryClient` and app wiring (`ProfileHistoryClient`, Hilt)

**Files:**
- Create: `core/history/src/main/kotlin/com/gekkotron/oikos/history/DemoHistoryClient.kt`, test `DemoHistoryClientTest.kt`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClient.kt`, `app/src/main/kotlin/com/gekkotron/oikos/di/HistoryModule.kt`
- Create: `app/src/test/kotlin/com/gekkotron/oikos/connection/ProfileHistoryClientTest.kt`
- Modify: `app/build.gradle.kts` (`implementation(project(":core:history"))`)

**Interfaces:**
- Consumes: Task 1 `LanOrGatewayGet`, `HttpRoutes`; Task 2 `Direct.historyUrl`; Task 4 `HistoryClient`, `HttpHistoryClient`.
- Produces: `class DemoHistoryClient(clock: () -> Long = System::currentTimeMillis) : HistoryClient`; Hilt `@Provides @Singleton fun historyClient(...): HistoryClient`.

Demo shapes (deterministic: seeded by `ref.hashCode()`), with `step = range seconds / 150` and points ending at `clock()/1000` floored to `step`:
- key containing `solar` → `max(0, 4000·sin(π·(hour−7)/12))` for hours 7–19 else 0, plus noise ±5 %.
- key containing `kwh` or `energy` or unit counters → series of a rising counter; `daily`/`today`: 8–14 kWh per day.
- key containing `temperature` or `température` → `21 + 2·sin(2π·(hour−15)/24)`, noise ±0.2.
- anything else → `500 + 300·sin(2π·hour/24)`, noise ±5 %.
- `min`/`max` = mean ∓ 3 % (temperature ∓ 0.3).
- `daily(days)` → `days` totals (8–14 kWh × factor 1), oldest first, today last; `today` → 5.0–9.0.

- [ ] **Step 1: Write the failing tests**

`DemoHistoryClientTest.kt`:
```kotlin
package com.gekkotron.oikos.history

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DemoHistoryClientTest {
    private val demo = DemoHistoryClient(clock = { 1_696_000_000_000 })

    @Test
    fun `about 150 rising-in-time points per range, same answer twice`(): Unit = runBlocking {
        HistoryRange.entries.forEach { range ->
            val s = assertIs<HistoryResult.Ok<Series>>(demo.series(HistoryRef("demo/sensor", "temperature"), range)).value
            assertTrue(s.points.size in 140..151, "$range ${s.points.size}")
            assertTrue(s.points.zipWithNext().all { (a, b) -> a.t < b.t })
            assertTrue(s.points.all { it.min <= it.mean && it.mean <= it.max })
            assertEquals(s, (demo.series(HistoryRef("demo/sensor", "temperature"), range) as HistoryResult.Ok).value)
        }
    }

    @Test
    fun `solar is never negative and daily has one value per day`(): Unit = runBlocking {
        val s = (demo.series(HistoryRef("demo/energy", "solar_w"), HistoryRange.H24) as HistoryResult.Ok).value
        assertTrue(s.points.all { it.mean >= 0 })
        val d = (demo.daily(HistoryRef("demo/energy", "solar_kwh"), 7, EnergyScale(EnergyKind.COUNTER, 1.0)) as HistoryResult.Ok).value
        assertEquals(7, d.days.size)
        assertTrue(demo.available.value)
    }
}
```

`ProfileHistoryClientTest.kt` (JUnit4 + Robolectric is not needed; plain JUnit4):
```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HistoryResult
import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProfileHistoryClientTest {
    private val states = MutableStateFlow<ProfileState>(ProfileState.Loading)
    private val direct = ConnectionProfile.Direct(LocalBroker("192.168.1.91"))
    private val urls = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.client() = ProfileHistoryClient(
        states, get = { urls += it; HttpGetResult.Response(200, """{"window":600,"points":[]}""".encodeToByteArray(), null) }, scope = backgroundScope,
    )

    @Test
    fun `available only with a history url or the demo`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct)
        assertEquals(false, c.available.first { !it })
        states.value = ProfileState.Ready(direct.copy(historyUrl = "http://192.168.1.91:8090"))
        c.available.first { it }
        states.value = ProfileState.Ready(ConnectionProfile.Demo)
        c.available.first { it }
        states.value = ProfileState.None
        c.available.first { !it }
    }

    @Test
    fun `direct asks the service, the demo never does`() = runTest {
        val c = client()
        states.value = ProfileState.Ready(direct.copy(historyUrl = "http://192.168.1.91:8090"))
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t", "k"), HistoryRange.H24))
        assertEquals(1, urls.size)
        states.value = ProfileState.Ready(ConnectionProfile.Demo)
        assertIs<HistoryResult.Ok<*>>(c.series(HistoryRef("t", "k"), HistoryRange.D7))
        assertEquals(1, urls.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:history:test --tests "*DemoHistoryClientTest" :app:testDebugUnitTest --tests "*ProfileHistoryClientTest"`
Expected: FAIL — unresolved `DemoHistoryClient`, `ProfileHistoryClient`.

- [ ] **Step 3: Implement**

`DemoHistoryClient.kt`: implement the shapes above with `kotlin.random.Random(ref.hashCode() + range.ordinal)` and `java.time` (`Instant.ofEpochSecond(t).atZone(ZoneId.of("Europe/Paris")).hour + minute/60.0`) for the hour of day. `available = MutableStateFlow(true)`.

`ProfileHistoryClient.kt`:
```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.history.DemoHistoryClient
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HttpHistoryClient
import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The demo's fake history, or OikosHistory at the profile's URL (history spec §4.2, §4.5). */
class ProfileHistoryClient(
    private val states: Flow<ProfileState>,
    get: suspend (String) -> HttpGetResult,
    scope: CoroutineScope,
) : HistoryClient {
    private val demo = DemoHistoryClient()
    private val http = HttpHistoryClient(baseUrl = { (profile() as? ConnectionProfile.Direct)?.historyUrl }, get = get)

    override val available: StateFlow<Boolean> = states
        .map { (it as? ProfileState.Ready)?.profile.let { p -> p == ConnectionProfile.Demo || (p as? ConnectionProfile.Direct)?.historyUrl != null } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private suspend fun profile(): ConnectionProfile? = (states.first { it !is ProfileState.Loading } as? ProfileState.Ready)?.profile
    private suspend fun current(): HistoryClient = if (profile() == ConnectionProfile.Demo) demo else http

    override suspend fun series(ref: HistoryRef, range: HistoryRange) = current().series(ref, range)
    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale) = current().daily(ref, days, scale)
    override suspend fun today(ref: HistoryRef, scale: EnergyScale) = current().today(ref, scale)
}
```

`HistoryModule.kt`:
```kotlin
package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.connection.ProfileHistoryClient
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.isLikelyLocalHost
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.transport.LanOrGatewayGet
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import javax.inject.Singleton
import com.gekkotron.oikos.alerts.media.MediaRoutes

@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    /** History answers are small; anything bigger is not ours. */
    private const val MAX_BYTES = 1L shl 20

    @Provides
    @Singleton
    fun historyClient(profiles: ProfileRepository, routes: MediaRoutes, http: OkHttpClient, @ApplicationScope scope: CoroutineScope): HistoryClient {
        val get = LanOrGatewayGet(routes, http, ::isLikelyLocalHost, log = { Log.w("OikosHistory", it) })
        return ProfileHistoryClient(profiles.state, get = { get.get(it, MAX_BYTES) }, scope = scope)
    }
}
```
(`MediaRoutes` is already provided as a singleton by `AlertsModule`; reuse it — it is the same LAN/gateway route. Use the `@ApplicationScope` qualifier's real import path from `AppModule`.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:history:test :app:testDebugUnitTest --tests "*ProfileHistoryClientTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/history app
git commit -m "feat(history): demo history and the profile-aware history client"
```

---

### Task 6: Settings — show and edit the history service URL

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/ConnectionSummary.kt`, `presentation/SettingsViewModel.kt`, `ui/SettingsScreen.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Modify tests: `feature/settings/src/test/.../presentation/ConnectionSummaryTest.kt`, `SettingsViewModelTest.kt`, `ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: Task 2 `ProfileRepository.setHistoryUrl`, `normalizeHistoryUrl`.
- Produces: `ConnectionSummary.Direct.historyUrl: String?`; `SettingsViewModel.setHistoryUrl(text: String): Boolean` (returns false and saves nothing when `text` is not blank and not valid; blank clears).

UI: in `ConnectionRows`, for a direct profile, after the gateway row, a clickable `InfoRow(stringResource(R.string.settings_history), summary.historyUrl ?: stringResource(R.string.settings_history_none))` (make `InfoRow` accept an optional `onClick`, rendered with `Modifier.clickable` and `role = Role.Button`) that opens `HistoryUrlDialog`: an `AlertDialog` with one `OutlinedTextField` (label `settings_history_hint`, keyboard `KeyboardType.Uri`, error text `settings_history_error` shown after a failed save), buttons `settings_history_save` and `settings_cancel`. Save calls `onSaveHistoryUrl(text)`; the dialog closes only when it returns true.

Strings (en / fr):
- `settings_history` — "History service" / "Service d'historique"
- `settings_history_none` — "Not set" / "Non configuré"
- `settings_history_hint` — "http://192.168.1.91:8090" / same
- `settings_history_error` — "Use http(s)://host[:port], nothing after it" / "Format http(s)://hôte[:port], rien après"
- `settings_history_save` — "Save" / "Enregistrer"

- [ ] **Step 1: Write the failing tests**

`ConnectionSummaryTest.kt`:
```kotlin
@Test
fun `the history url is shown`() {
    val s = ConnectionSummary.from(ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("h"), historyUrl = "http://h:8090"))) as ConnectionSummary.Direct
    assertEquals("http://h:8090", s.historyUrl)
}
```
`SettingsViewModelTest.kt` (use the file's existing repository/fakes setup):
```kotlin
@Test
fun `a valid history url is saved normalised, an invalid one is refused, blank clears`() = runTest(dispatcher) {
    val vm = viewModel() // the file's existing factory/helper
    assertTrue(vm.setHistoryUrl(" http://192.168.1.91:8090/ "))
    profiles.awaitHistoryUrl("http://192.168.1.91:8090")
    assertFalse(vm.setHistoryUrl("192.168.1.91"))
    profiles.awaitHistoryUrl("http://192.168.1.91:8090")
    assertTrue(vm.setHistoryUrl("  "))
    profiles.awaitHistoryUrl(null)
}
```
where `awaitHistoryUrl` is a local helper: `suspend fun ProfileRepository.awaitHistoryUrl(u: String?) = state.first { ((it as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.historyUrl == u }`. If the test file uses a fake instead of a real `ProfileRepository`, add `setHistoryUrl` to that fake and assert on its recorded value instead.

`SettingsScreenTest.kt`: assert that a direct state with `historyUrl = null` shows "Not set", that clicking it shows the dialog, and that a refused save keeps the dialog open with the error text.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: FAIL — unresolved `historyUrl`, `setHistoryUrl`.

- [ ] **Step 3: Implement**

`ConnectionSummary.Direct` gains `val historyUrl: String?` (copied field by field like the others). `SettingsViewModel`:
```kotlin
    /** Blank clears; anything else must be a history URL (history spec §4.4). */
    fun setHistoryUrl(text: String): Boolean {
        val url = if (text.isBlank()) null else normalizeHistoryUrl(text) ?: return false
        viewModelScope.launch { profiles.setHistoryUrl(url) }
        return true
    }
```
(keep a `private val profiles` reference in the constructor if it is currently only used to build the state). `SettingsScreen` gets an `onSaveHistoryUrl: (String) -> Boolean` parameter, passed from `SettingsRoute` as `viewModel::setHistoryUrl`; the dialog as described above. Add the strings.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/settings
git commit -m "feat(settings): edit the history service URL"
```

---

### Task 7: Vico chart wrappers in `:core:designsystem`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/charts/HistoryCharts.kt`
- Create: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/charts/HistoryChartsTest.kt`, `core/designsystem/src/test/resources/robolectric.properties` (`sdk=34`) if missing

**Interfaces:**
- Produces:
  ```kotlin
  data class ChartPoint(val t: Long, val mean: Double, val min: Double, val max: Double)
  data class ChartBar(val t: Long, val value: Double)
  @Composable fun HistoryLineChart(points: List<ChartPoint>, color: Color, formatValue: (Double) -> String, formatTime: (Long) -> String, contentDescription: String, modifier: Modifier = Modifier)
  @Composable fun DailyBarsChart(bars: List<ChartBar>, color: Color, formatValue: (Double) -> String, formatDay: (Long) -> String, contentDescription: String, modifier: Modifier = Modifier)
  @Composable fun ChartSkeleton(modifier: Modifier = Modifier)
  ```
  `t` is unix seconds. Every Vico type stays inside `HistoryCharts.kt`.

Catalog additions:
```toml
[versions]
vico = "2.1.3"
[libraries]
vico-compose-m3 = { module = "com.patrykandpatrick.vico:compose-m3", version.ref = "vico" }
```
`core/designsystem/build.gradle.kts`: `implementation(libs.vico.compose.m3)`.

Rendering rules:
- Line chart: one `LineCartesianLayer` with three series in this order — `min`, `max`, `mean`. `min`/`max` lines: `color` at 25 % alpha, 1 dp; `mean`: `color`, 2 dp. (The "band" of spec §5.2 is drawn as the two faint lines; Vico 2.1 has no fill between two series.) X values are seconds since the first point; the bottom axis formats `first.t + x` with `formatTime`, about 4 labels; the start axis formats with `formatValue`. A default marker shows `formatTime(t)` and `formatValue(mean)` of the touched point.
- Bars: one `ColumnCartesianLayer`, `color`, 8 dp columns, bottom axis `formatDay`.
- Both have `Modifier.semantics { this.contentDescription = contentDescription }` and a fixed height of 220 dp.
- `ChartSkeleton`: a 220 dp rounded box filled with `OikosTheme.colors.card` (no animation — screenshots freeze the clock).
- Empty `points`/`bars`: the composables draw nothing but the skeleton-sized space (callers show their own empty message).

If a Vico 2.1.3 name does not resolve, read the library sources (Gradle cache, or `https://github.com/patrykandpatrick/vico/tree/v2.1.3`) and adapt inside this file only; the public signatures above must not change.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gekkotron.oikos.designsystem.charts

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryChartsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `line and bar charts render with their descriptions`() {
        val points = (0 until 150).map { ChartPoint(1_696_000_000L + it * 600L, 21.0 + it % 5, 20.5, 22.0) }
        compose.setContent {
            OikosTheme {
                HistoryLineChart(points, Color.Red, { "%.1f".format(it) }, { "$it" }, "line")
                DailyBarsChart(listOf(ChartBar(1_696_000_000, 3.2), ChartBar(1_696_086_400, 4.1)), Color.Blue, { "$it" }, { "$it" }, "bars")
            }
        }
        compose.onNodeWithContentDescription("line").assertExists()
        compose.onNodeWithContentDescription("bars").assertExists()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests "*HistoryChartsTest"`
Expected: FAIL — unresolved `HistoryLineChart`.

- [ ] **Step 3: Implement `HistoryCharts.kt`** following the rendering rules, with Vico 2.1.3's Compose API (`CartesianChartHost`, `rememberCartesianChart`, `rememberLineCartesianLayer` + `LineCartesianLayer.LineProvider.series(...)` + `LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(color)), stroke = ...)`, `rememberColumnCartesianLayer` + `ColumnCartesianLayer.ColumnProvider.series(rememberLineComponent(fill(color), 8.dp))`, `VerticalAxis.rememberStart(valueFormatter = …)`, `HorizontalAxis.rememberBottom(valueFormatter = …)`, `rememberDefaultCartesianMarker(...)`, a `CartesianChartModelProducer` filled in a `LaunchedEffect(points) { producer.runTransaction { lineSeries { series(xs, mins); series(xs, maxs); series(xs, means) } } }`).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests "*HistoryChartsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml core/designsystem
git commit -m "feat(designsystem): Vico line and daily bar chart wrappers"
```

---

### Task 8: `:feature:history` — target, view model and states

**Files:**
- Create: `feature/history/build.gradle.kts`; modify `settings.gradle.kts` (`include(":feature:history")`) and `app/build.gradle.kts` (`implementation(project(":feature:history"))`)
- Create: `feature/history/src/main/kotlin/com/gekkotron/oikos/feature/history/HistoryTarget.kt`, `presentation/HistoryUi.kt`, `presentation/HistoryViewModel.kt`
- Create tests: `feature/history/src/test/kotlin/com/gekkotron/oikos/feature/history/FakeHistoryClient.kt`, `FakeEntitySource.kt` (copy of `feature/energy/src/test/.../FakeEntitySource.kt`), `presentation/HistoryViewModelTest.kt`, `src/test/resources/robolectric.properties` (`sdk=34`)

**Interfaces:**
- Consumes: Task 3/4 `HistoryRefs`, `HistoryClient`, `EnergyHistory`, `EnergyScale`, models; `EntitySource` from `:core:source`.
- Produces:
  ```kotlin
  sealed interface HistoryTarget {
      data class Entity(val id: String) : HistoryTarget
      data object House : HistoryTarget
      fun encode(): String  // "entity:<id>" | "house"
      companion object { fun decode(text: String): HistoryTarget? }
  }
  enum class ChartMode { POWER, DAILY }
  sealed interface ChartContent {
      data object Loading : ChartContent
      data class Line(val series: Series, val min: Double, val avg: Double, val max: Double) : ChartContent
      data class Bars(val days: DailyTotals, val total: Double) : ChartContent
      data object NoData : ChartContent
      data object NotRecorded : ChartContent
      data object NotAllowed : ChartContent
      data object Unavailable : ChartContent
  }
  data class HistoryUi(
      val title: String? = null,          // null = the house (UI shows the localized "House")
      val room: String? = null,
      val live: Double? = null,
      val unit: String? = null,
      val range: HistoryRange = HistoryRange.H24,
      /** Non-null when kWh-per-day bars exist for this target; the toggle shows on 7 d / 30 d. */
      val energy: Boolean = false,
      val mode: ChartMode = ChartMode.POWER,
      val content: ChartContent = ChartContent.Loading,
      val missing: Boolean = false,       // the entity is gone or has no history ref
  )
  @HiltViewModel class HistoryViewModel internal constructor(source: EntitySource, history: HistoryClient, energy: EnergyHistory) : ViewModel() {
      @Inject constructor(...) : this(...)
      val state: StateFlow<HistoryUi>
      fun bind(target: HistoryTarget); fun selectRange(range: HistoryRange); fun selectMode(mode: ChartMode); fun retry()
  }
  ```

Rules:
- Title/room/live/unit come from `source.entities` + `source.states()` + `source.areas` (title = `entity.name`; room = area name or null; live = numeric state value, climate `current`). For `House`: title null, unit "W", live null.
- Content for an entity: `ref = HistoryRefs.of(entity)` (null → `missing = true`); `scale = EnergyScale.of(entity.unit)`; `energy = scale != null`.
  - `mode == DAILY && range != H24 && energy`: if `scale.kind == POWER` and `energy.meterFor(id)` gives a meter entity with a ref and a counter scale → `daily(meterRef, days, meterScale)`; else `daily(ref, days, scale)`. `days` = 7 for `D7`, 30 for `D30`.
  - otherwise `series(ref, range)`.
- `House`: `energy = true`; `houseSeries(range)` / `houseDaily(days)`.
- `Ok(series)` with no points → `NoData`; `Ok` → `Line` with `min` = min of mins, `max` = max of maxes, `avg` = mean of means; `Ok(daily)` with no days → `NoData`, else `Bars(total = sum)`.
- `NotRecorded`/`NotAllowed`/`Unavailable` map 1:1.
- Selecting `H24` forces `mode = POWER`. Every change of target/range/mode/retry restarts the fetch with `Loading` first; a fetch for an older selection never lands (use `flatMapLatest`).

- [ ] **Step 1: Module skeleton**

`feature/history/build.gradle.kts`:
```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.history"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:history"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
```

- [ ] **Step 2: Write the failing tests**

`FakeHistoryClient.kt`:
```kotlin
package com.gekkotron.oikos.feature.history

import com.gekkotron.oikos.history.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHistoryClient : HistoryClient {
    override val available = MutableStateFlow(true)
    val calls = mutableListOf<String>()
    var series: (HistoryRef, HistoryRange) -> HistoryResult<Series> = { _, _ -> HistoryResult.Ok(Series(600, listOf(HistoryPoint(1, 20.0, 19.0, 21.0), HistoryPoint(2, 22.0, 21.0, 23.0)))) }
    var daily: (HistoryRef, Int, EnergyScale) -> HistoryResult<DailyTotals> = { _, _, _ -> HistoryResult.Ok(DailyTotals(listOf(DayTotal(1, 3.0), DayTotal(2, 4.5)))) }
    /** When set, series() waits on it (to test late answers). */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series> {
        calls += "series ${ref.key} ${range.query}"
        gate?.takeIf { range == HistoryRange.H24 }?.await()
        return series(ref, range)
    }
    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals> { calls += "daily ${ref.key} $days ${scale.kind}"; return daily(ref, days, scale) }
    override suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?> = HistoryResult.Ok(null)
}
```

`HistoryViewModelTest.kt` (JUnit4 + `UnconfinedTestDispatcher`, as in `EnergyViewModelTest`). Build entities with `MqttBindings` as in Task 3's test helper: a thermometer (`°C`, `value_json.temperature`), a power sensor (`W`, `value_json.power`, id `plug_power`) and its meter (`kWh`, `value_json.energy`, id `plug_energy`).

```kotlin
@Test fun `a sensor shows its 24 h line with stats`() = runTest(dispatcher) {
    val vm = vm(); vm.bind(HistoryTarget.Entity("thermo"))
    backgroundScope.launch { vm.state.collect {} }
    val line = vm.state.first { it.content is ChartContent.Line }.content as ChartContent.Line
    assertEquals(Triple(19.0, 21.0, 23.0), Triple(line.min, line.avg, line.max))
    assertEquals(listOf("series temperature 24h"), history.calls)
    assertFalse(vm.state.value.energy)
}

@Test fun `empty series is NoData, not NotRecorded`() = runTest(dispatcher) {
    history.series = { _, _ -> HistoryResult.Ok(Series(600, emptyList())) }
    val vm = vm(); vm.bind(HistoryTarget.Entity("thermo")); backgroundScope.launch { vm.state.collect {} }
    vm.state.first { it.content == ChartContent.NoData }
}

@Test fun `results map to states`() = runTest(dispatcher) {
    listOf(HistoryResult.NotRecorded to ChartContent.NotRecorded, HistoryResult.NotAllowed to ChartContent.NotAllowed, HistoryResult.Unavailable("x") to ChartContent.Unavailable)
        .forEach { (r, c) ->
            history.series = { _, _ -> r }
            val vm = vm(); vm.bind(HistoryTarget.Entity("thermo")); backgroundScope.launch { vm.state.collect {} }
            vm.state.first { it.content == c }
        }
}

@Test fun `a power sensor's daily bars use its paired meter`() = runTest(dispatcher) {
    energy.meters["plug_power"] = "plug_energy"
    val vm = vm(); vm.bind(HistoryTarget.Entity("plug_power")); backgroundScope.launch { vm.state.collect {} }
    vm.selectRange(HistoryRange.D7); vm.selectMode(ChartMode.DAILY)
    val bars = vm.state.first { it.content is ChartContent.Bars }.content as ChartContent.Bars
    assertEquals(7.5, bars.total)
    assertEquals("daily energy 7 COUNTER", history.calls.last())
}

@Test fun `without a meter the daily bars integrate power`() = runTest(dispatcher) {
    val vm = vm(); vm.bind(HistoryTarget.Entity("plug_power")); backgroundScope.launch { vm.state.collect {} }
    vm.selectRange(HistoryRange.D30); vm.selectMode(ChartMode.DAILY)
    vm.state.first { it.content is ChartContent.Bars }
    assertEquals("daily power 30 POWER", history.calls.last())
}

@Test fun `24 h always shows power`() = runTest(dispatcher) {
    val vm = vm(); vm.bind(HistoryTarget.Entity("plug_power")); backgroundScope.launch { vm.state.collect {} }
    vm.selectRange(HistoryRange.D7); vm.selectMode(ChartMode.DAILY); vm.selectRange(HistoryRange.H24)
    assertEquals(ChartMode.POWER, vm.state.first { it.range == HistoryRange.H24 }.mode)
}

@Test fun `a late answer for an older range is dropped`() = runTest(dispatcher) {
    history.gate = CompletableDeferred()
    val vm = vm(); vm.bind(HistoryTarget.Entity("thermo")); backgroundScope.launch { vm.state.collect {} }
    vm.selectRange(HistoryRange.D7)
    vm.state.first { it.range == HistoryRange.D7 && it.content is ChartContent.Line }
    history.gate!!.complete(Unit)
    assertEquals(HistoryRange.D7, vm.state.value.range)
    assertTrue(vm.state.value.content is ChartContent.Line)
}

@Test fun `the house uses the energy history`() = runTest(dispatcher) {
    val vm = vm(); vm.bind(HistoryTarget.House); backgroundScope.launch { vm.state.collect {} }
    vm.state.first { it.content is ChartContent.Line && it.energy && it.title == null }
}

@Test fun `a gone entity or one without a ref is missing`() = runTest(dispatcher) {
    val vm = vm(); vm.bind(HistoryTarget.Entity("nope")); backgroundScope.launch { vm.state.collect {} }
    vm.state.first { it.missing }
}

@Test fun `target encoding round-trips`() {
    listOf(HistoryTarget.House, HistoryTarget.Entity("a:b")).forEach { assertEquals(it, HistoryTarget.decode(it.encode())) }
    assertNull(HistoryTarget.decode("x"))
}
```
with `FakeEnergyHistory(val meters: MutableMap<String, String> = mutableMapOf())` implementing `EnergyHistory` (`houseSeries` returns a 2-point Ok series, `houseDaily` an Ok of 2 days) and `vm() = HistoryViewModel(source, history, energy)`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :feature:history:testDebugUnitTest`
Expected: FAIL — unresolved `HistoryViewModel`.

- [ ] **Step 4: Implement** `HistoryTarget.kt` (`"entity:" + id` / `"house"`; `decode` returns null for anything else), `HistoryUi.kt` (types above) and `HistoryViewModel.kt`:
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val source: EntitySource,
    private val history: HistoryClient,
    private val energy: EnergyHistory,
) : ViewModel() {
```
(No clock or dispatcher is needed, so a single `@Inject` constructor serves both Hilt and the tests.)

Selection state: `private data class Selection(val target: HistoryTarget?, val range: HistoryRange, val mode: ChartMode, val attempt: Int)` in a `MutableStateFlow`. `state = combine(selection, source.entities, source.states(), source.areas) { … header … }` for the header fields, combined with `selection.flatMapLatest { s -> flow { emit(ChartContent.Loading); emit(load(s)) } }` for `content`, `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())`. `load(s)` reads `source.entities.value` for the entity (so a live state tick does not refetch). `selectRange(H24)` also sets `mode = POWER`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :feature:history:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts feature/history
git commit -m "feat(history): history view model with ranges, daily mode and states"
```

---

### Task 9: History screen, navigation key and screenshots

**Files:**
- Create: `feature/history/src/main/kotlin/com/gekkotron/oikos/feature/history/ui/HistoryScreen.kt`
- Create: `feature/history/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create tests: `feature/history/src/test/kotlin/com/gekkotron/oikos/feature/history/ScreenshotSupport.kt` (copy of `feature/alerts/src/test/.../ScreenshotSupport.kt`, package changed), `ui/HistoryScreenTest.kt`, `ui/HistoryScreenshotTest.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`

**Interfaces:**
- Consumes: Task 7 charts; Task 8 view model and types.
- Produces: `@Composable fun HistoryRoute(target: HistoryTarget, onBack: () -> Unit, windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets, viewModel: HistoryViewModel = hiltViewModel())`; `@Composable fun HistoryScreen(state: HistoryUi, onBack: () -> Unit, onRange: (HistoryRange) -> Unit, onMode: (ChartMode) -> Unit, onRetry: () -> Unit, windowInsets: WindowInsets = …, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault())`; app `@Serializable data class HistoryKey(val target: String) : NavKey`.

Layout (`Scaffold` with transparent container, a top bar with a back arrow `history_back`):
1. Header: title (`state.title ?: stringResource(DsR.string.energy_house)`), room (muted), live value + unit (`OikosTheme.type.display`).
2. Range chips 24 h / 7 d / 30 d (`FilterChip`, labels `history_range_24h|7d|30d`).
3. When `state.energy && state.range != H24`: a two-segment `SingleChoiceSegmentedButtonRow` **Power | kWh per day** (`history_mode_power`, `history_mode_daily`).
4. Content:
   - `Loading` → `ChartSkeleton`.
   - `Line` → `HistoryLineChart` (color `OikosTheme.colors.solar` when `state.energy`, else `OikosTheme.colors.water`), then a stats row min / avg / max with labels `history_min|avg|max` formatted with the unit (1 decimal; 0 decimals for W).
   - `Bars` → `DailyBarsChart` (color `solar`) and `history_total` ("%1$s kWh over %2$d days").
   - `NoData` → `history_no_data`; `NotRecorded` → `history_not_recorded`; `NotAllowed` → `history_not_allowed`; `Unavailable` → `history_unavailable` + a `history_retry` button calling `onRetry`.
   - `state.missing` → `history_not_recorded`.
5. Content descriptions: `history_cd_line` ("%1$s, %2$s, min %3$s, max %4$s") and `history_cd_bars` ("%1$s, kWh per day, %2$s"), with the range text `history_cd_range_24h|7d|30d` ("last 24 hours" / "last 7 days" / "last 30 days").
6. Time formats: 24 h → `HH:mm`; 7 d → `EEE HH:mm`; 30 d and days → `d MMM`, via `DateTimeFormatter.ofPattern(…, locale)` in `zone`.

Strings (en / fr):
| key | en | fr |
|---|---|---|
| history_back | Back | Retour |
| history_range_24h | 24 h | 24 h |
| history_range_7d | 7 d | 7 j |
| history_range_30d | 30 d | 30 j |
| history_mode_power | Power | Puissance |
| history_mode_daily | kWh per day | kWh par jour |
| history_min | Min | Min |
| history_avg | Avg | Moy |
| history_max | Max | Max |
| history_total | %1$s kWh over %2$d days | %1$s kWh sur %2$d jours |
| history_no_data | No data in this period | Aucune donnée sur cette période |
| history_not_recorded | No history recorded for this device | Aucun historique enregistré pour cet appareil |
| history_not_allowed | Add the history host to the gateway key | Ajoutez l’hôte de l’historique à la clé de la passerelle |
| history_unavailable | History unavailable | Historique indisponible |
| history_retry | Retry | Réessayer |
| history_cd_line | %1$s, %2$s, min %3$s, max %4$s | %1$s, %2$s, min %3$s, max %4$s |
| history_cd_bars | %1$s, kWh per day, %2$s | %1$s, kWh par jour, %2$s |
| history_cd_range_24h | last 24 hours | dernières 24 heures |
| history_cd_range_7d | last 7 days | 7 derniers jours |
| history_cd_range_30d | last 30 days | 30 derniers jours |

Navigation (`OikosNavHost.kt`):
```kotlin
@Serializable
data class HistoryKey(val target: String) : NavKey
```
and in the `entryProvider`:
```kotlin
entry<HistoryKey> { key ->
    val target = HistoryTarget.decode(key.target)
    if (target == null) LaunchedEffect(key) { pop(backStack, key) }
    else HistoryRoute(target, onBack = { pop(backStack, key) }, windowInsets = screenInsets(key, tabInsets, ScaffoldDefaults.contentWindowInsets))
}
```
plus `val openHistory: (HistoryTarget) -> Unit = { push(backStack, HistoryKey(it.encode())) }` next to `deviceSheet` (used by Tasks 10 and 12).

- [ ] **Step 1: Write the failing tests**

`HistoryScreenTest.kt` (Robolectric compose test): for each `ChartContent` show the matching text; the mode toggle is absent for `H24` and present for `D7` when `energy`; clicking `30 d` calls `onRange(D30)`; clicking Retry calls `onRetry`.

`HistoryScreenshotTest.kt` (pattern of `AlertsScreenshotTest`): render `HistoryScreen` with a 150-point `Line` (Night Glass and Warm Minimal), a `Bars` state with 7 days (Night Glass), and `NotRecorded` (Warm Minimal); assert `distinctColors() > 8` and the theme luminance at (4, 4) like the alerts test. Fixed `zone = ZoneOffset.UTC`, `locale = Locale.UK`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:history:testDebugUnitTest`
Expected: FAIL — unresolved `HistoryScreen`.

- [ ] **Step 3: Implement** `HistoryScreen.kt`, the strings and the nav entry as specified.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:history:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS; PNGs under `feature/history/build/screenshots/`. Open two of them and check they look right (chart visible, stats row, theme colours).

- [ ] **Step 5: Commit**

```bash
git add feature/history app
git commit -m "feat(history): history screen and its navigation key"
```

---

### Task 10: Home and control sheet entry points

**Files:**
- Modify: `feature/home/build.gradle.kts` (`implementation(project(":core:history"))`)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`, `HomeUiBuilder.kt`, `HomeViewModel.kt`, `CustomizeViewModel.kt`, `ui/HomeScreen.kt`, `ui/ControlSheet.kt`, `ui/EntityControlSheet.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml` (`sheet_history` — "History" / "Historique")
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Tests: `feature/home/src/test/.../presentation/HomeUiBuilderTest.kt`, `ui/HomeScreenTest.kt` (or the existing control sheet test)

**Interfaces:**
- Consumes: Task 3 `HistoryRefs`; Task 4 `HistoryClient`; Task 9 `openHistory`.
- Produces: `enum class TapAction { TOGGLE, PRESS, OPEN_SHEET, OPEN_HISTORY, NONE }`; `CardUi.hasHistory: Boolean = false`; `HomeUiBuilder.build(…, historyEnabled: Boolean = false)` and `cardFor(…, historyEnabled: Boolean = false)`; `HomeRoute(…, onOpenHistory: (String) -> Unit = {})`; `ControlSheet(card, onSend, onCustomize, onDismiss, onOpenHistory: (() -> Unit)? = null)`; `EntityControlSheet(id, onDismiss, onSaveFailed, onOpenHistory: ((String) -> Unit)? = null, viewModel)`; `HomeViewModel` gains `private val history: HistoryClient = HistoryClient.None` as the last constructor parameter.

Rules:
- `hasHistory = historyEnabled && HistoryRefs.of(entity) != null`.
- `tapFor(kind, hasHistory)`: `Kind.SENSOR` with `hasHistory` → `OPEN_HISTORY`; everything else unchanged.
- `HomeScreen` card `onClick`: `OPEN_SHEET` → sheet; `OPEN_HISTORY` → `onOpenHistory(card.id)`; else `onTap(card)`. `HomeViewModel.tap`: `OPEN_HISTORY` → `Unit`.
- `ControlSheet`: when `card.hasHistory && onOpenHistory != null`, a `TextButton` `sheet_history` before the Customize button; it dismisses the sheet then opens history.
- `HomeViewModel.state`: combine `history.available` in (nest it with `source.link` in a `Pair` so `combine` keeps 5 typed arguments) and pass it as `historyEnabled`.
- `CustomizeViewModel` (used by `EntityControlSheet`) passes `historyEnabled = history.available.value` to `cardFor`; give it the same `history: HistoryClient = HistoryClient.None` constructor parameter.
- App: `HomeRoute(…, onOpenHistory = { openHistory(HistoryTarget.Entity(it)) })` and `deviceSheet` → `EntityControlSheet(id, dismiss, onSaveFailed = …, onOpenHistory = { openHistory(HistoryTarget.Entity(it)) })`.
- Hilt: `HomeViewModel`/`CustomizeViewModel` get `HistoryClient` from `HistoryModule`. Their default parameter value is only for tests.

- [ ] **Step 1: Write the failing tests**

`HomeUiBuilderTest.kt` (reuse the file's entity helpers; give the thermometer `MqttBindings` with `state_topic` and `value_template = "{{ value_json.temperature }}"` and unit `°C`):
```kotlin
@Test
fun `a recorded sensor opens its history only when history is on`() {
    val on = HomeUiBuilder.build(entities, states, areas, RoomFilter.All, link, Locale.ROOT, historyEnabled = true).card("thermo")!!
    assertEquals(TapAction.OPEN_HISTORY, on.tap)
    assertTrue(on.hasHistory)
    val off = HomeUiBuilder.build(entities, states, areas, RoomFilter.All, link, Locale.ROOT).card("thermo")!!
    assertEquals(TapAction.NONE, off.tap)
    assertFalse(off.hasHistory)
}

@Test
fun `a sensor without a json key never opens history`() {
    // same thermometer but value_template = null
    assertEquals(TapAction.NONE, HomeUiBuilder.build(rawEntities, states, areas, RoomFilter.All, link, Locale.ROOT, historyEnabled = true).card("thermo")!!.tap)
}
```
Compose test: a `ControlSheet` for a climate card with `hasHistory = true` shows "History" and clicking it calls `onOpenHistory`; with `onOpenHistory = null` it is absent. A `HomeScreen` with an `OPEN_HISTORY` card calls `onOpenHistory("thermo")` on click.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: FAIL — unresolved `OPEN_HISTORY`, `historyEnabled`.

- [ ] **Step 3: Implement** the rules above.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS (existing Home tests unchanged: `historyEnabled` defaults to false).

- [ ] **Step 5: Commit**

```bash
git add feature/home app
git commit -m "feat(home): open history from sensor cards and the control sheet"
```

---

### Task 11: `EnergyHistory` in `:feature:energy` (meter pairing, derived house)

**Files:**
- Modify: `feature/energy/build.gradle.kts` (`implementation(project(":core:history"))`)
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/history/EnergyHistoryImpl.kt`, `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/di/EnergyHistoryModule.kt`
- Create: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/history/EnergyHistoryImplTest.kt`, `FakeHistoryClient.kt` (same as Task 8's, in this package)

**Interfaces:**
- Consumes: `EnergyMeters.meterFor`, `EnergyMeters.reverseFor`, `EnergyRole`, `EntitySource.entities`; Task 3/4 types.
- Produces: `class EnergyHistoryImpl @Inject constructor(source: EntitySource, history: HistoryClient) : EnergyHistory`; Hilt `@Binds abstract fun energyHistory(impl: EnergyHistoryImpl): EnergyHistory` in `@Module @InstallIn(SingletonComponent::class) abstract class EnergyHistoryModule`.

Rules (mirror `EnergyFlowBuilder`'s live and total formulas, spec §5.3):
- `meterFor(id)`: `entities[id]?.let { EnergyMeters.meterFor(it, entities.values) }?.uniqueId`.
- Derived house only when there is **no** `EnergyRole.HOUSE` entity; otherwise `houseSeries`/`houseDaily` use the HOUSE entity's own ref (series) and its meter (daily) like an entity.
- `houseSeries(range)`: inputs = every SOLAR, GRID and BATTERY power entity (`entity.ui.energyRole`). Fetch `series(ref, range)` for each (all must have a ref, else `NotRecorded`). Watts per input point = `mean × EnergyScale.of(unit).factor` (W→1, kW→1000) × (−1 when `ui.energyInvert`). Result points = timestamps present in **every** input; `mean = max(0, Σ)`, `min = max(0, Σ mins)`, `max = max(0, Σ maxes)`. Any input result not `Ok` → that result (first non-Ok wins). No inputs → `NotRecorded`.
- `houseDaily(days)`: the derived-total formula of `EnergyFlowBuilder.derivedTotal` per day: plus = meters of SOLAR + GRID entities; minus = grid reverse meters + GRID_EXPORT meters + solar draw (reverse) meters; with a BATTERY, or a solar without any export meter, or a missing meter → `NotRecorded`. Each meter → `daily(ref, days, EnergyScale.of(meter.unit))`; per day `max(0, Σplus − Σminus)` over days present in every series.

- [ ] **Step 1: Write the failing tests**

Using `TestEntities.kt` helpers (`energyEntity(...)`, extended with a `MqttBindings` carrying `state_topic = "zigbee2mqtt/<id>"` and `value_template = "{{ value_json.power }}"` or `.energy`):
```kotlin
@Test fun `house series is the clamped sum of solar and grid at shared timestamps`() = runBlocking {
    history.series = { ref, _ -> HistoryResult.Ok(Series(600, when (ref.topic) {
        "zigbee2mqtt/solar" -> listOf(HistoryPoint(1, 3000.0, 2900.0, 3100.0), HistoryPoint(2, 2000.0, 1900.0, 2100.0), HistoryPoint(3, 0.0, 0.0, 0.0))
        else -> listOf(HistoryPoint(1, -500.0, -600.0, -400.0), HistoryPoint(3, 400.0, 300.0, 500.0))
    })) }
    val s = (impl.houseSeries(HistoryRange.H24) as HistoryResult.Ok).value
    assertEquals(listOf(HistoryPoint(1, 2500.0, 2300.0, 2700.0), HistoryPoint(3, 400.0, 300.0, 500.0)), s.points)
}

@Test fun `house daily is plus meters minus export and draw meters`() = runBlocking { /* solar_energy 10, grid_energy 4, grid_energy_produced 3 per day -> 11 */ }

@Test fun `a battery or a missing meter makes the house daily NotRecorded`() = runBlocking { /* … */ }

@Test fun `an input failure is passed through`() = runBlocking {
    history.series = { _, _ -> HistoryResult.NotAllowed }
    assertEquals(HistoryResult.NotAllowed, impl.houseSeries(HistoryRange.H24))
}

@Test fun `meterFor follows the energy pairing`() { assertEquals("plug_energy", impl.meterFor("plug_power")) }
```
Write the bodies in full with concrete entities and numbers (the comments give the expected values).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests "*EnergyHistoryImplTest"`
Expected: FAIL — unresolved `EnergyHistoryImpl`.

- [ ] **Step 3: Implement** per the rules; `@Singleton` on `EnergyHistoryImpl` is not needed (it holds no state).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:energy:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS (Hilt graph compiles with the new binding).

- [ ] **Step 5: Commit**

```bash
git add feature/energy
git commit -m "feat(energy): energy history with meter pairing and the derived house"
```

---

### Task 12: Energy "kWh today" and history buttons

**Files:**
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyToday.kt`
- Modify: `presentation/EnergyModels.kt`, `presentation/EnergyFlowBuilder.kt`, `presentation/EnergyViewModel.kt`, `ui/EnergyScreen.kt`, `ui/EnergyDetailSheet.kt`, strings (en/fr)
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Tests: `presentation/EnergyTodayTest.kt`, `presentation/EnergyFlowBuilderTest.kt`, `presentation/EnergyViewModelTest.kt`, `ui/EnergyScreenTest.kt`

**Interfaces:**
- Consumes: Task 3/4 types, `HistoryClient`; Task 9 `openHistory`.
- Produces:
  - `EnergyNode.todayKwh: Double? = null`.
  - `object EnergyToday { fun requests(entities: Map<String, Entity>): Map<String, Pair<HistoryRef, EnergyScale>> }` — for every energy-role power entity: its meter (when paired and it has a ref and a counter scale) keyed by the **meter** id, else the power entity itself (power scale) keyed by its id.
  - `EnergyFlowBuilder.build(entities, states, link, nowMillis, previous, today: Map<String, Double> = emptyMap())`.
  - `EnergyViewModel` internal constructor gains `history: HistoryClient` and `refresh: Duration = 5.minutes`; the `@Inject` constructor takes `HistoryClient`.
  - `EnergyRoute(onOpenHome, windowInsets, onOpenHistory: (entityId: String?) -> Unit = {}, viewModel)` — `null` = the derived house.

Rules:
- `todayKwh` per node uses the **same** formulas as `totalKwh`, with the value of a meter read from `today[meter.uniqueId]` instead of its live counter: refactor `total()` and `derivedTotal()` to take `value: (Entity) -> Reading` and call them twice (live: the existing `kwh(it, states)`; today: `{ m -> Reading(today[m.uniqueId], NodeStatus.LIVE) }`). A node whose sensors have no meter: `todayKwh = Σ today[sensor.uniqueId]` over its power sensors, null if any is missing. Existing `totalKwh` tests must not change.
- View model: when `history.available` is true and the state is subscribed, every `refresh` fetch `history.today(ref, scale)` for each `EnergyToday.requests(entities)` entry (in parallel with `async`), keep the `Ok` non-null values, and feed the map to `build`. Unavailable values are simply absent (the line hides). The first fetch happens as soon as entities are known.
- `EnergyValueCard`: under the existing text, when `node.todayKwh != null`, `Text(stringResource(R.string.energy_today, EnergyFormat.energy(node.todayKwh, locale)), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)` — `energy_today` = "%1$s today" / "%1$s aujourd’hui". The diagram bubbles are left unchanged (their label layout is tuned to avoid the flow edges).
- `EnergyDetailSheet`: the same "today" line under the node value; an `energy_history` ("History" / "Historique") `TextButton` per sensor row calling `onOpenHistory(sensor.id)`; for the derived house (no HOUSE sensor) one button at the top calling `onOpenHistory(null)`. Buttons only when `historyAvailable` (pass `history.available` through `EnergyUiState.historyAvailable: Boolean = false`).
- App: `EnergyRoute(…, onOpenHistory = { id -> openHistory(id?.let(HistoryTarget::Entity) ?: HistoryTarget.House) })`.

- [ ] **Step 1: Write the failing tests**

`EnergyTodayTest.kt`: a power entity with a paired kWh meter → one request keyed by the meter id with `COUNTER`; a power entity without a meter → keyed by itself with `POWER`; an entity without a JSON key → no request.

`EnergyFlowBuilderTest.kt`:
```kotlin
@Test
fun `today uses the same formulas with today's meter values`() {
    // entities: solar power+meter, grid power+meter+produced twin, derived house
    val ui = EnergyFlowBuilder.build(entities, states, link, now, today = mapOf("solar_energy" to 10.0, "grid_energy" to 4.0, "grid_energy_produced" to 3.0))
    assertEquals(10.0, ui.node("solar")!!.todayKwh)
    assertEquals(11.0, ui.node("house")!!.todayKwh)
}

@Test
fun `without today values nothing changes`() {
    assertNull(EnergyFlowBuilder.build(entities, states, link, now).node("house")!!.todayKwh)
}
```
`EnergyViewModelTest.kt`: with a `FakeHistoryClient` whose `today` returns 5.0, `state.first { it.node("solar")?.todayKwh == 5.0 }`; with `available = false` no `today` call is made; advancing the test clock by 5 minutes makes a second round of calls (`advanceTimeBy(5.minutes)` with a `StandardTestDispatcher`).
`EnergyScreenTest.kt`: a card with `todayKwh = 3.4` shows "3.4 kWh today"; the detail sheet's History button calls `onOpenHistory("solar_power")`; the derived house's calls `onOpenHistory(null)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :feature:energy:testDebugUnitTest`
Expected: FAIL — unresolved `todayKwh`, `EnergyToday`.

- [ ] **Step 3: Implement** per the rules.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:energy:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, including every existing `EnergyFlowBuilderTest` case unchanged.

- [ ] **Step 5: Commit**

```bash
git add feature/energy app
git commit -m "feat(energy): kWh today and history from the energy screen"
```

---

### Task 13: Full check, demo run and device checklist

**Files:**
- Modify: `README.md` (a "History" paragraph: what it needs — OikosHistory URL in Settings or the QR `history.url`, the gateway key host:port rule for remote access)

- [ ] **Step 1: Full build and tests**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Fix anything red before continuing.

- [ ] **Step 2: Demo run**

Install the debug build, start in demo mode and check: a Home temperature card opens a 24 h chart; 7 d and 30 d chips switch; Energy cards show "x.x kWh today"; the solar detail sheet's History button opens power curves and the kWh-per-day toggle on 7 d; the derived house (if the demo has none, skip) opens its curve. Take one screenshot of the history screen per theme.

- [ ] **Step 3: README and commit**

```bash
git add README.md
git commit -m "docs: history setup in the README"
```

- [ ] **Step 4: Hand the device checklist to the user** (needs the real OikosHistory service, deployed by the other session):
  1. Settings → History service → `http://<geekom-ip>:8090`.
  2. A Zigbee thermometer: 24 h / 7 d / 30 d look right.
  3. Energy: "kWh today" on the solar card ≈ the meter's own counter delta since midnight.
  4. 7 d kWh-per-day bars on a plug with a meter.
  5. Off Wi-Fi: the same screens through the gateway (add the history host:port to the gateway key first); without the rule, "Add the history host to the gateway key".
  6. Regenerate the setup QR: `python3 tools/setup-qr.py --history-url http://<geekom-ip>:8090`.
