# Frigate login for alert media — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alert snapshots and clips from an authenticated Frigate (port 8971) load in Oikos on the LAN and through the gateway, with credentials set in Settings or the setup QR.

**Architecture:** Gateway v1.4 returns upstream response headers so the app can read Frigate's `Set-Cookie`. In Oikos, a `FrigateConfig` lives on the direct profile (password sealed). `FrigateAuth` (core/alerts) logs in through the same LAN-or-gateway route as media and hands `MediaFetcher` a `Cookie: frigate_token=…` header for URLs on the configured Frigate origin, re-logging once on a 401.

**Tech Stack:** Python 3 / Flask / requests / pytest (gateway); Kotlin, OkHttp 4.12, kotlinx.serialization, DataStore, Hilt, Compose, JUnit 4/5, MockWebServer (Oikos).

**Spec:** `docs/superpowers/specs/2026-10-08-frigate-auth-design.md`

## Global Constraints

- Commit identity on **every** commit, in both repos: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`. Never write the user's real name anywhere.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN
  ```
- Oikos branch: `feat/frigate-auth` (cut from `feat/history`). Do not push unless told to.
- Gateway repo remote: `git@github.com-gekkotron:Gekkotron/Athena-HttpMqttGateway.git` (the plain `github.com` host is the wrong account).
- Generic defaults only: default Frigate URL `http://frigate:8971`; nothing specific to one house is hard-coded in app code.
- Cookie name `frigate_token`; login `POST <url>/api/login` with JSON `{"user": …, "password": …}`.
- Response header names from the gateway are lower-case; repeated headers are joined with `\n`.
- Frigate URL shape = history URL shape: `http(s)://host[:port]`, no path, stored without a trailing slash.
- Never log or `toString` a password or token.
- Match the surrounding code's comment density, naming and KDoc style (short, spec-section references).

## Review Focus

1. Two pictures load at once on a cold start → exactly one login request (mutex) — pinned in Task 4.
2. Wrong password → "Frigate login failed — check Settings", and never more than one re-login per fetch (no loop) — pinned in Task 4.
3. Alert media on another host or port (or another camera service) never receives the cookie; `http://frigate` equals `http://frigate:80` — pinned in Task 4.
4. A gateway older than v1.4 (no headers in the login answer) → a clear "Unavailable" reason, not "login failed" — pinned in Task 4.
5. Changing the username in Settings with the password left blank must not reuse the old user's password — pinned in Task 5.

---

### Task 1: Gateway v1.4 — response headers (server + Kotlin SDK)

Work in a fresh clone **outside** Oikos:

```bash
cd /Users/julienhuguel/Documents/SourcesPerso
git clone git@github.com-gekkotron:Gekkotron/Athena-HttpMqttGateway.git
cd Athena-HttpMqttGateway
python3 -m venv .venv && .venv/bin/pip install -q -r requirements-dev.txt
```

**Files:**
- Modify: `server/services/http_service.py` (non-raw response payload)
- Modify: `server/app.py:45` (`/health` version `1.3.0` → `1.4.0`)
- Modify: `tests/test_app.py:341` (expects `1.4.0`)
- Test: `tests/test_services.py`
- Modify: `sdk/kotlin/src/main/kotlin/io/github/gekkotron/athena/gateway/client/Models.kt` (`GatewayResponse`)
- Modify: `sdk/kotlin/src/main/kotlin/io/github/gekkotron/athena/gateway/client/internal/ResponseDecoder.kt` (`http`)
- Test: `sdk/kotlin/src/test/kotlin/io/github/gekkotron/athena/gateway/client/ResponseDecoderTest.kt`
- Modify: `README.md` (request docs near line 800, `/health` near 863, SDK version near 401)

**Interfaces:**
- Produces (wire): non-raw `/gateway` HTTP answers carry `"headers": {"<lower-case name>": "<value>"}`; several `Set-Cookie` lines are joined with `\n`.
- Produces (SDK): `public data class GatewayResponse(public val status: Int, public val body: JsonElement, public val headers: Map<String, String> = emptyMap())`.

- [ ] **Step 1: Write the failing server tests** — append to `tests/test_services.py` after `test_http_non_json_response_returned_as_text`:

```python
def test_http_returns_response_headers_lower_cased(crypto, secret, fake_request):
    resp = _response(json_body={}, headers={"Content-Type": "application/json", "X-Thing": "1"})
    resp.raw = SimpleNamespace(headers=SimpleNamespace(getlist=lambda name: []))
    fake_request.return_value = resp
    out = _open(crypto, HttpService(crypto).handle_request({"url": "http://h/x"}, secret))
    assert out["headers"] == {"content-type": "application/json", "x-thing": "1"}


def test_http_keeps_every_set_cookie_line(crypto, secret, fake_request):
    resp = _response(json_body={}, headers={"Set-Cookie": "a=1; Path=/, frigate_token=jwt; HttpOnly"})
    lines = ["a=1; Path=/", "frigate_token=jwt; HttpOnly"]
    resp.raw = SimpleNamespace(headers=SimpleNamespace(getlist=lambda name: lines if name == "Set-Cookie" else []))
    fake_request.return_value = resp
    out = _open(crypto, HttpService(crypto).handle_request({"url": "http://h/api/login"}, secret))
    assert out["headers"]["set-cookie"] == "a=1; Path=/\nfrigate_token=jwt; HttpOnly"


def test_http_headers_without_a_raw_response(crypto, secret, fake_request):
    resp = _response(json_body={}, headers={"Set-Cookie": "frigate_token=jwt"})
    resp.raw = None
    fake_request.return_value = resp
    out = _open(crypto, HttpService(crypto).handle_request({"url": "http://h/x"}, secret))
    assert out["headers"] == {"set-cookie": "frigate_token=jwt"}
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/bin/pytest tests/test_services.py -k headers -v`
Expected: 3 FAIL with `KeyError: 'headers'`.

- [ ] **Step 3: Implement** — in `server/services/http_service.py`, add a module-level helper and use it in the non-raw payload:

```python
def _response_headers(resp) -> dict:
    """Upstream response headers, names lower-cased; every Set-Cookie line kept, joined with \\n."""
    headers = {str(k).lower(): str(v) for k, v in dict(resp.headers or {}).items()}
    raw_headers = getattr(getattr(resp, "raw", None), "headers", None)
    getlist = getattr(raw_headers, "getlist", None)
    if callable(getlist):
        cookies = getlist("Set-Cookie")
        if isinstance(cookies, list) and cookies:  # a bare test Mock returns a Mock, not a list
            headers["set-cookie"] = "\n".join(str(c) for c in cookies)
    return headers
```

and in `handle_request`, the non-raw `response_payload` becomes:

```python
        response_payload = {
            "status": resp.status_code,
            "body": body,
            "headers": _response_headers(resp),
            "timestamp": int(time.time())
        }
```

Bump `/health` in `server/app.py` to `"1.4.0"` and `tests/test_app.py:341` to expect `"1.4.0"`.

- [ ] **Step 4: Run the whole server suite**

Run: `.venv/bin/pytest -q`
Expected: all pass (the existing `test_http_non_raw_is_unchanged` must still pass; if it asserts the exact payload keys, add `"headers"` to its expectation — that is the intended v1.4 change).

- [ ] **Step 5: Write the failing SDK decoder tests** — in `ResponseDecoderTest.kt`, after `http success returns status and body as-is`:

```kotlin
    @Test fun `http answer headers are decoded, and absent headers are empty`() {
        val withHeaders = RawResponse(200, "application/octet-stream", sealed {
            put("status", 200); put("body", json); put("timestamp", 0)
            put("headers", buildJsonObject { put("set-cookie", "frigate_token=jwt; HttpOnly"); put("content-type", "application/json") })
        })
        val r = decoder.http(withHeaders, true)
        assertEquals("frigate_token=jwt; HttpOnly", r.headers["set-cookie"])
        assertEquals("application/json", r.headers["content-type"])
        assertEquals(emptyMap(), decoder.http(envelope(200, json), true).headers)
    }

    @Test fun `non-string header values are skipped`() {
        val raw = RawResponse(200, "application/octet-stream", sealed {
            put("status", 200); put("body", json); put("timestamp", 0)
            put("headers", buildJsonObject { put("x-num", 3); put("x-ok", "y") })
        })
        assertEquals(mapOf("x-ok" to "y"), decoder.http(raw, true).headers)
    }
```

(`3` is a JSON number, not a string, so it is dropped.)

- [ ] **Step 6: Run them to see them fail**

Run: `cd sdk/kotlin && ./gradlew test --tests '*ResponseDecoderTest*'`
Expected: compilation failure — `headers` is not a member of `GatewayResponse`.

- [ ] **Step 7: Implement in the SDK**

`Models.kt`:

```kotlin
/** Response of a request forwarded through `/gateway`. [headers]: upstream response headers, lower-case names (gateway v1.4+; empty before). */
public data class GatewayResponse(
    public val status: Int,
    public val body: JsonElement,
    public val headers: Map<String, String> = emptyMap(),
) {
    /** Decodes [body] into a `@Serializable` type. */
    public inline fun <reified T> bodyAs(json: Json = GatewayJson): T = json.decodeFromJsonElement(body)
}
```

`ResponseDecoder.http`:

```kotlin
    fun http(raw: RawResponse, throwOnUpstreamError: Boolean): GatewayResponse {
        val env = envelope(raw)
        if (env.status !in 200..299 && throwOnUpstreamError) throw GatewayException.Upstream(env.status, env.body)
        return GatewayResponse(env.status, env.body, headersOf(env.fields))
    }
```

and a private helper in the same file:

```kotlin
/** v1.4 `headers`: string values only; absent (older gateway) means empty. */
private fun headersOf(fields: JsonObject): Map<String, String> =
    (fields["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k.lowercase() to it }
    }?.toMap().orEmpty()
```

- [ ] **Step 8: Run the SDK suite**

Run: `cd sdk/kotlin && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: README** — under the request field list (near line 800) add after the `raw` bullet:

```markdown
**Response headers (v1.4+):** non-raw answers also carry `"headers"`: the upstream response headers with lower-case names; several `Set-Cookie` lines are joined with `\n`. The Kotlin SDK exposes them as `GatewayResponse.headers` (empty from an older gateway).
```

Change the `/health` example to `"version": "1.4.0"` and the SDK dependency example to `:v1.4`.

- [ ] **Step 10: Commit** (do **not** push or tag yet — that is the checkpoint below)

```bash
git add server/services/http_service.py server/app.py tests/test_services.py tests/test_app.py \
  sdk/kotlin/src/main/kotlin/io/github/gekkotron/athena/gateway/client/Models.kt \
  sdk/kotlin/src/main/kotlin/io/github/gekkotron/athena/gateway/client/internal/ResponseDecoder.kt \
  sdk/kotlin/src/test/kotlin/io/github/gekkotron/athena/gateway/client/ResponseDecoderTest.kt README.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat: return upstream response headers from /gateway http (v1.4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

- [ ] **Step 11: CHECKPOINT (controller, with user OK)** — push `main` and the tag, then warm JitPack:

```bash
git push origin main && git tag v1.4 && git push origin v1.4
curl -s -o /dev/null -w '%{http_code}\n' https://jitpack.io/com/github/Gekkotron/Athena-HttpMqttGateway/athena-gateway-client/v1.4/athena-gateway-client-v1.4.pom
```

Repeat the curl until it answers 200 (the first request triggers the build). The user deploys the server with `update.sh` on the Geekom.

---

### Task 2: Oikos data — `FrigateConfig` on the profile and in the setup payload

**Files:**
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/FrigateConfig.kt`
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt` (`Direct.frigate`)
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt` (`StoredFrigate`)
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ProfileRepository.kt` (`toDirect`, `toStored`, `setFrigate`)
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/OnboardingPayload.kt` (`frigate` section)
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/connection/EntitySourceSession.kt:115` (`sourceKey`)
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt`, `OnboardingPayloadTest.kt`, `app/src/test/kotlin/com/gekkotron/oikos/connection/EntitySourceSessionTest.kt`

**Interfaces:**
- Produces:
  - `data class FrigateConfig(val url: String, val username: String? = null, val password: String? = null)` with `val hasLogin: Boolean` (username and password both non-empty) and a masking `toString`.
  - `const val DEFAULT_FRIGATE_URL = "http://frigate:8971"`
  - `fun normalizeFrigateUrl(text: String): String?`
  - `ConnectionProfile.Direct(..., val frigate: FrigateConfig? = null)` (last parameter)
  - `suspend fun ProfileRepository.setFrigate(config: FrigateConfig?)` — in-place edit, keeps rooms/route flags.

- [ ] **Step 1: Write the failing tests**

`OnboardingPayloadTest.kt` — add:

```kotlin
    @Test
    fun `frigate section is read and normalised`() {
        val p = ok("""{"v":1,"local":{"host":"h"},"frigate":{"url":"http://192.168.1.91:8971/","user":"admin","pass":"pw"}}""")
        assertEquals(FrigateConfig("http://192.168.1.91:8971", "admin", "pw"), p.frigate)
    }

    @Test
    fun `frigate without a login is fine, and absent means none`() {
        assertEquals(FrigateConfig("http://frigate:5000"), ok("""{"v":1,"local":{"host":"h"},"frigate":{"url":"http://frigate:5000"}}""").frigate)
        assertNull(ok("""{"v":1,"local":{"host":"h"}}""").frigate)
    }

    @Test
    fun `invalid frigate fields are named`() {
        assertEquals(PayloadError.InvalidField("frigate.url"), error("""{"v":1,"local":{"host":"h"},"frigate":{}}"""))
        assertEquals(PayloadError.InvalidField("frigate.url"), error("""{"v":1,"local":{"host":"h"},"frigate":{"url":"frigate:8971"}}"""))
        assertEquals(PayloadError.InvalidField("frigate.user"), error("""{"v":1,"local":{"host":"h"},"frigate":{"url":"http://f:8971","pass":"pw"}}"""))
    }
```

and extend `toString never shows secrets` with a frigate password:

```kotlin
    @Test
    fun `the frigate password never shows in toString`() {
        val p = ok("""{"v":1,"local":{"host":"h"},"frigate":{"url":"http://f:8971","user":"admin","pass":"fr1gate-secret"}}""")
        assertFalse("fr1gate-secret" in p.toString())
    }
```

`ProfileRepositoryTest.kt` — add:

```kotlin
    @Test
    fun `frigate round-trips with a sealed password and can be edited and cleared in place`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct.copy(frigate = FrigateConfig("http://192.168.1.91:8971", "admin", "fr1gate-secret")))
        val saved = ((r.state.first { it is ProfileState.Ready } as ProfileState.Ready).profile as ConnectionProfile.Direct).frigate
        assertEquals(FrigateConfig("http://192.168.1.91:8971", "admin", "fr1gate-secret"), saved)
        assertFalse("fr1gate-secret" in f.readText())
        r.finishRooms()
        r.setFrigate(FrigateConfig("http://frigate:5000"))
        val ready = r.state.first { ((it as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.frigate == FrigateConfig("http://frigate:5000") } as ProfileState.Ready
        assertFalse(ready.roomsPending) // an in-place edit, not a new pairing
        r.setFrigate(null)
        r.state.first { it is ProfileState.Ready && (it.profile as ConnectionProfile.Direct).frigate == null }
    }

    @Test
    fun `a profile file from before frigate has none`() = runTest {
        val f = file()
        f.writeText("""{"installId":"x","mode":"DIRECT","local":{"host":"h","port":1883,"tls":false,"ws":false},"prefix":"homeassistant"}""")
        assertNull(((repo(f).settled() as ProfileState.Ready).profile as ConnectionProfile.Direct).frigate)
    }
```

`EntitySourceSessionTest.kt` — add next to the history case:

```kotlin
    @Test
    fun `a profile differing only by frigate keeps the source`() {
        val first = session.current(direct)
        val second = session.current(direct.copy(frigate = FrigateConfig("http://frigate:8971", "admin", "pw")))
        assertSame(first, second)
        assertEquals(1, scopes.size)
    }
```

(import `com.gekkotron.oikos.data.FrigateConfig`).

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*EntitySourceSessionTest*'`
Expected: compilation failure (`FrigateConfig` unresolved).

- [ ] **Step 3: Implement**

`FrigateConfig.kt`:

```kotlin
package com.gekkotron.oikos.data

/** Frigate's address and optional login, for alert media (Frigate auth spec §3). [password] is a secret; [toString] masks it. */
data class FrigateConfig(val url: String, val username: String? = null, val password: String? = null) {
    /** A username and a password: the app logs in. Anything less: media is fetched without a cookie. */
    val hasLogin: Boolean get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()

    override fun toString(): String = "FrigateConfig($url, user=$username, password=${if (password == null) "none" else "***"})"
}

/** The generic default shown in Settings: Frigate's authenticated port on a host named `frigate`. */
const val DEFAULT_FRIGATE_URL = "http://frigate:8971"

/** `http(s)://host[:port]` without a trailing slash, like the history URL (spec §3, §5); null when it is not one. */
fun normalizeFrigateUrl(text: String): String? = normalizeHistoryUrl(text)
```

`ConnectionProfile.Direct` — add after `historyUrl`:

```kotlin
        /** Frigate's address and login for alert media (Frigate auth spec §3); null = none configured. */
        val frigate: FrigateConfig? = null,
```

`StoredProfile.kt` — add the class and the field (after `historyUrl`):

```kotlin
@Serializable
internal data class StoredFrigate(val url: String, val username: String? = null, val passCipher: String? = null)
```

```kotlin
    val frigate: StoredFrigate? = null,
```

`ProfileRepository.kt`:
- in `toDirect()` add `frigate = frigate?.let { FrigateConfig(it.url, it.username, it.passCipher?.let { c -> open(c).decodeToString() }) },`
- in `toStored()`'s `Direct` branch add `frigate = p.frigate?.toStored(),`
- add:

```kotlin
    /** Edits the Frigate settings in place: no new pairing, rooms and route are kept. Null removes them. */
    suspend fun setFrigate(config: FrigateConfig?) {
        val stored = config?.toStored()
        store.updateData { it.copy(frigate = stored) }
    }

    private fun FrigateConfig.toStored() = StoredFrigate(url, username, password?.let { seal(it.encodeToByteArray()) })
```

`OnboardingPayload.parse` — before the `return ParseResult.Ok(...)`:

```kotlin
        val frigate = (root["frigate"] as? JsonObject)?.let { f ->
            val url = f.string("url")?.let(::normalizeFrigateUrl) ?: return invalid("frigate.url")
            val user = f.string("user")?.takeIf { it.isNotEmpty() }
            val pass = f.string("pass")?.takeIf { it.isNotEmpty() }
            if (pass != null && user == null) return invalid("frigate.user")
            FrigateConfig(url, user, pass)
        }
```

and pass it: `ConnectionProfile.Direct(broker, prefix, gateway, tokenTopic, history, frigate)`. Update the KDoc line of `OnboardingPayload` to mention the optional `frigate` section.

`EntitySourceSession.kt:115`:

```kotlin
    /** The profile as the source sees it: the history URL and Frigate settings have no bearing on discovery. */
    private fun ConnectionProfile?.sourceKey() = (this as? ConnectionProfile.Direct)?.copy(historyUrl = null, frigate = null) ?: this
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*EntitySourceSessionTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data app/src/main/kotlin/com/gekkotron/oikos/connection/EntitySourceSession.kt app/src/test/kotlin/com/gekkotron/oikos/connection/EntitySourceSessionTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(frigate): Frigate URL and login on the profile and in the setup payload

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 3: Oikos transport — request headers and a JSON POST over LAN or gateway

Requires Task 1's `v1.4` on JitPack.

**Files:**
- Modify: `gradle/libs.versions.toml:37` (`athenaGatewayClient = "v1.4"`)
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/GatewayDownloader.kt`
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/LanOrGatewayGet.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/GatewayDownloaderTest.kt`, `LanOrGatewayGetTest.kt`
- Modify (fakes only): `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcherTest.kt` (`FakeGateway`)

**Interfaces:**
- Produces:
  - `interface GatewayDownloader { suspend fun download(url: String, timeoutSeconds: Int, headers: Map<String, String> = emptyMap()): RawDownload; suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost }`
  - `class RawPost(val status: Int, val headers: Map<String, String>)`
  - `LanOrGatewayGet.get(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit = { _, _ -> }, headers: Map<String, String> = emptyMap()): HttpGetResult`
  - `LanOrGatewayGet.post(url: String, json: String): HttpPostResult`
  - `sealed interface HttpPostResult { class Response(val status: Int, val headers: Map<String, String>); data object NotAllowed; data class Unavailable(val reason: String) }`

- [ ] **Step 1: Bump the SDK** — `athenaGatewayClient = "v1.4"` in `gradle/libs.versions.toml`.

- [ ] **Step 2: Write the failing tests**

`GatewayDownloaderTest.kt` — replace the helper and add tests:

```kotlin
    private fun downloader(
        send: suspend (String, JsonElement, Int) -> GatewayResponse = { _, _, _ -> error("unused") },
        fetch: suspend (String, Int, Map<String, String>) -> GatewayDownload = { _, _, _ -> error("unused") },
    ) = AthenaGatewayDownloader(fetch, send)
```

Existing tests call `downloader { url, timeout -> … }`: rewrite each as `downloader(fetch = { url, timeout, _ -> … })`. Then add:

```kotlin
    @Test
    fun `download forwards the headers`(): Unit = runBlocking {
        var seen: Map<String, String>? = null
        downloader(fetch = { _, _, headers -> seen = headers; GatewayDownload(200, ByteArray(0), null) })
            .download("http://h/x", 30, mapOf("Cookie" to "frigate_token=t"))
        assertEquals(mapOf("Cookie" to "frigate_token=t"), seen)
    }

    @Test
    fun `a post sends the JSON body and returns status and headers`(): Unit = runBlocking {
        var seen: Triple<String, JsonElement, Int>? = null
        val r = downloader(send = { url, body, timeout ->
            seen = Triple(url, body, timeout)
            GatewayResponse(200, JsonObject(emptyMap()), mapOf("set-cookie" to "frigate_token=t"))
        }).post("http://f:8971/api/login", """{"user":"a","password":"b"}""", 30)
        assertEquals("http://f:8971/api/login", seen!!.first)
        assertEquals(Json.parseToJsonElement("""{"user":"a","password":"b"}"""), seen!!.second)
        assertEquals(30, seen!!.third)
        assertEquals(200, r.status)
        assertEquals("frigate_token=t", r.headers["set-cookie"])
    }

    @Test
    fun `a refused post is DownloadNotAllowed`(): Unit = runBlocking {
        assertFailsWith<DownloadNotAllowed> {
            downloader(send = { _, _, _ -> throw GatewayException.Rejected("port not allowed for this secret") }).post("http://h/x", "{}", 30)
        }
    }
```

(imports: `io.github.gekkotron.athena.gateway.client.GatewayResponse`, `kotlinx.serialization.json.Json`, `JsonElement`, `JsonObject`).

`LanOrGatewayGetTest.kt` — update `FakeGateway`:

```kotlin
    private class FakeGateway(
        var answer: () -> RawDownload = { RawDownload(200, "gw".encodeToByteArray(), "application/json") },
        var postAnswer: () -> RawPost = { RawPost(200, mapOf("set-cookie" to "frigate_token=gw")) },
    ) : GatewayDownloader {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val posts = mutableListOf<Pair<String, String>>()
        override suspend fun download(url: String, timeoutSeconds: Int, headers: Map<String, String>): RawDownload {
            check(timeoutSeconds == 30)
            urls += url
            this.headers += headers
            return answer()
        }
        override suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost {
            check(timeoutSeconds == 30)
            posts += url to json
            return postAnswer()
        }
    }
```

and add:

```kotlin
    @Test
    fun `headers go on the LAN request`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        get(local = true, gateway = FakeGateway()).get(url(), 1_000, headers = mapOf("Cookie" to "frigate_token=t"))
        assertEquals("frigate_token=t", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `headers go on the gateway download`(): Unit = runBlocking {
        val gateway = FakeGateway()
        get(local = false, gateway = gateway).get(url(), 1_000, headers = mapOf("Cookie" to "frigate_token=t"))
        assertEquals(listOf(mapOf("Cookie" to "frigate_token=t")), gateway.headers)
    }

    @Test
    fun `a LAN post sends JSON and returns lower-case headers, every Set-Cookie kept`(): Unit = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "a=1").addHeader("Set-Cookie", "frigate_token=lan; HttpOnly").setBody("{}"))
        val r = assertIs<HttpPostResult.Response>(get(local = true, gateway = FakeGateway()).post(url("/api/login"), """{"user":"u"}"""))
        assertEquals(200, r.status)
        assertEquals("a=1\nfrigate_token=lan; HttpOnly", r.headers["set-cookie"])
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("""{"user":"u"}""", sent.body.readUtf8())
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `a post falls back to the gateway when the LAN is down`(): Unit = runBlocking {
        val gateway = FakeGateway()
        val r = assertIs<HttpPostResult.Response>(get(local = false, gateway = gateway).post(url("/api/login"), "{}"))
        assertEquals("frigate_token=gw", r.headers["set-cookie"])
        assertEquals(listOf(url("/api/login") to "{}"), gateway.posts)
    }

    @Test
    fun `a refused post is NotAllowed and no route is Unavailable`(): Unit = runBlocking {
        val refused = FakeGateway(postAnswer = { throw DownloadNotAllowed("destination not allowed for this secret") })
        assertEquals(HttpPostResult.NotAllowed, get(local = false, gateway = refused).post(url(), "{}"))
        assertIs<HttpPostResult.Unavailable>(get(local = false, gateway = null).post(url(), "{}"))
    }
```

`MediaFetcherTest.kt` `FakeGateway` (core/alerts): change the override to `download(url: String, timeoutSeconds: Int, headers: Map<String, String>)` and add `override suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost = error("unused")` (import `RawPost`).

- [ ] **Step 3: Run to see them fail**

Run: `./gradlew :core:transport:test`
Expected: compilation failure (`RawPost`, `post`, `HttpPostResult` unresolved).

- [ ] **Step 4: Implement `GatewayDownloader.kt`**

```kotlin
/** A raw GET (gateway v1.3) or a JSON POST (gateway `http`, v1.4 for headers) through the gateway (push alerts spec §6.3, Frigate auth spec §4). */
interface GatewayDownloader {
    /** @throws DownloadNotAllowed the gateway key has no rule for this destination or port. @throws DownloadFailed anything else. */
    suspend fun download(url: String, timeoutSeconds: Int, headers: Map<String, String> = emptyMap()): RawDownload

    /** Same failures as [download]. A gateway older than v1.4 answers with no headers. */
    suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost
}

/** [status] may be an upstream error (404...): the caller decides what that means. */
class RawDownload(val status: Int, val bytes: ByteArray, val contentType: String?)

/** [headers]: lower-case names, repeated headers joined with `\n`. */
class RawPost(val status: Int, val headers: Map<String, String>)
```

`AthenaGatewayDownloader`:

```kotlin
/** [GatewayDownloader] over the SDK's `download` and `http`, with non-2xx answers returned instead of thrown. */
class AthenaGatewayDownloader internal constructor(
    private val fetch: suspend (url: String, timeoutSeconds: Int, headers: Map<String, String>) -> GatewayDownload,
    private val send: suspend (url: String, body: JsonElement, timeoutSeconds: Int) -> GatewayResponse,
) : GatewayDownloader {
    constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient()) : this(clientFor(spec, http))

    private constructor(client: AthenaGatewayClient) : this(
        fetch = { url, timeout, headers -> client.download(url, headers = headers, timeoutSeconds = timeout, throwOnUpstreamError = false) },
        send = { url, body, timeout ->
            client.http(url, method = "POST", headers = JSON_HEADERS, body = body, timeoutSeconds = timeout, throwOnUpstreamError = false)
        },
    )

    override suspend fun download(url: String, timeoutSeconds: Int, headers: Map<String, String>): RawDownload =
        guarded { fetch(url, timeoutSeconds, headers).let { RawDownload(it.status, it.bytes, it.contentType) } }

    override suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost =
        guarded { send(url, Json.parseToJsonElement(json), timeoutSeconds).let { RawPost(it.status, it.headers) } }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        throw e.toDownloadFailure()
    }

    private companion object {
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")

        fun clientFor(spec: TransportSpec.Gateway, http: OkHttpClient): AthenaGatewayClient {
            require(spec.url.startsWith("https://")) { "the gateway URL must use https" }
            return AthenaGatewayClient(spec.url, spec.key.toHex(), http.noDowngrade())
        }
    }
}
```

(imports: `GatewayResponse`, `kotlinx.serialization.json.Json`, `JsonElement`. If `core/transport` lacks a direct kotlinx-serialization-json dependency, it comes transitively as `api` from the SDK; add nothing unless compilation says otherwise.)

- [ ] **Step 5: Implement `LanOrGatewayGet.kt`**

Add the result type next to `HttpGetResult`:

```kotlin
sealed interface HttpPostResult {
    /** Any HTTP answer, with its headers (lower-case names, repeated ones joined with `\n`). */
    class Response(val status: Int, val headers: Map<String, String>) : HttpPostResult
    data object NotAllowed : HttpPostResult
    data class Unavailable(val reason: String) : HttpPostResult
}
```

Change `get` to take headers last and pass them on:

```kotlin
    suspend fun get(
        url: String,
        maxBytes: Long,
        onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> },
        headers: Map<String, String> = emptyMap(),
    ): HttpGetResult {
```

with `fromLan(url, maxBytes, onProgress, headers)` building `Request.Builder().url(url).headers(headers.toHeaders()).build()` and `fromGateway(gateway, url, host, maxBytes, headers)` calling `gateway.download(url, GATEWAY_TIMEOUT_SECONDS, headers)`.

Add the POST (same route choice as `get`; the KDoc of the class becomes "A GET or JSON POST of a LAN URL: …"):

```kotlin
    /** A JSON POST, e.g. a login: direct when the LAN is reachable, the gateway's `http` call otherwise. */
    suspend fun post(url: String, json: String): HttpPostResult {
        val host = url.toHttpUrlOrNull()?.host ?: return HttpPostResult.Unavailable("not an http url")
        val route = routes.current()
        var lanReason: String? = null
        val probe = route.local
        if (probe != null && isLanHost(host) && probe.reachable()) {
            try {
                lan.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).build()).await().use { response ->
                    return HttpPostResult.Response(response.code, response.headers.lowerCaseMap())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lanReason = e.message ?: e.javaClass.simpleName
            }
        }
        val gateway = route.gateway ?: return HttpPostResult.Unavailable(lanReason ?: "no route")
        return try {
            gateway.post(url, json, GATEWAY_TIMEOUT_SECONDS).let { HttpPostResult.Response(it.status, it.headers) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadNotAllowed) {
            if (warnedHosts.add(host)) log("the gateway refused $host (${e.reason}); add the host to this key's rules")
            HttpPostResult.NotAllowed
        } catch (e: Exception) {
            HttpPostResult.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }
```

companion: `val JSON = "application/json; charset=utf-8".toMediaType()`; file-level helper:

```kotlin
/** Lower-case names; a repeated header (several Set-Cookie) keeps every value, joined with `\n`. */
internal fun Headers.lowerCaseMap(): Map<String, String> = names().associate { it.lowercase() to values(it).joinToString("\n") }
```

(imports: `okhttp3.Headers`, `okhttp3.Headers.Companion.toHeaders`, `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.RequestBody.Companion.toRequestBody`).

- [ ] **Step 6: Run the tests**

Run: `./gradlew :core:transport:test :core:alerts:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS (the history client calls `get(url, maxBytes)` and is unaffected).

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml core/transport core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcherTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(transport): request headers and a JSON POST over LAN or gateway v1.4

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 4: Oikos alerts — `FrigateAuth`, cookie on media, `AuthFailed`

**Files:**
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/MediaAuth.kt`
- Create: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/FrigateAuth.kt`
- Modify: `core/alerts/src/main/kotlin/com/gekkotron/oikos/alerts/media/MediaFetcher.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/AlertsModule.kt`
- Modify: `feature/alerts/src/main/kotlin/com/gekkotron/oikos/feature/alerts/ui/AlertDetailSheet.kt:70-73`
- Modify: `feature/alerts/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Test: `core/alerts/src/test/kotlin/com/gekkotron/oikos/alerts/media/FrigateAuthTest.kt` (new), `MediaFetcherTest.kt`

**Interfaces:**
- Consumes: `FrigateConfig` (Task 2), `LanOrGatewayGet.post`, `HttpPostResult`, `get(..., headers)` (Task 3).
- Produces:
  - `sealed interface AuthHeaders { data object None; data class Ready(val headers: Map<String, String>); data class Failed(val failure: MediaFailure) }`
  - `interface MediaAuth { suspend fun headersFor(url: String): AuthHeaders; suspend fun renew(url: String, rejected: Map<String, String>): AuthHeaders }`
  - `class FrigateAuth(config: suspend () -> FrigateConfig?, post: suspend (url: String, json: String) -> HttpPostResult) : MediaAuth`
  - `MediaFailure.AuthFailed`
  - `MediaFetcher(..., auth: MediaAuth? = null)` (last parameter)

- [ ] **Step 1: Write the failing `FrigateAuthTest.kt`**

```kotlin
package com.gekkotron.oikos.alerts.media

import com.gekkotron.oikos.data.FrigateConfig
import com.gekkotron.oikos.transport.HttpPostResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrigateAuthTest {
    private val login = FrigateConfig("http://192.168.1.91:8971", "admin", "pw")
    private val snap = "http://192.168.1.91:8971/api/events/1/snapshot.jpg"
    private val posts = mutableListOf<Pair<String, String>>()

    private fun auth(
        config: FrigateConfig? = login,
        answer: () -> HttpPostResult = { HttpPostResult.Response(200, mapOf("set-cookie" to "frigate_token=t${posts.size}; Path=/; HttpOnly")) },
    ) = FrigateAuth(config = { config }, post = { url, json -> posts += url to json; delay(10); answer() })

    @Test
    fun `logs in once and sends the cookie`(): Unit = runBlocking {
        val a = auth()
        assertEquals(AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=t1")), a.headersFor(snap))
        assertEquals(AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=t1")), a.headersFor(snap))
        assertEquals(listOf("http://192.168.1.91:8971/api/login" to """{"user":"admin","password":"pw"}"""), posts)
    }

    @Test
    fun `concurrent fetches share one login`(): Unit = runBlocking {
        val a = auth()
        List(5) { async { a.headersFor(snap) } }.awaitAll()
        assertEquals(1, posts.size)
    }

    @Test
    fun `other hosts, ports and schemes get nothing, and default ports match`(): Unit = runBlocking {
        val a = auth()
        assertEquals(AuthHeaders.None, a.headersFor("http://192.168.1.91:5000/api/events/1/snapshot.jpg"))
        assertEquals(AuthHeaders.None, a.headersFor("http://192.168.1.92:8971/x.jpg"))
        assertEquals(AuthHeaders.None, a.headersFor("https://192.168.1.91:8971/x.jpg"))
        assertEquals(AuthHeaders.None, a.headersFor("not a url"))
        assertTrue(posts.isEmpty())
        val plain = auth(config = FrigateConfig("http://frigate", "admin", "pw"))
        assertIs<AuthHeaders.Ready>(plain.headersFor("http://frigate:80/x.jpg"))
    }

    @Test
    fun `no config or no login means no cookie`(): Unit = runBlocking {
        assertEquals(AuthHeaders.None, auth(config = null).headersFor(snap))
        assertEquals(AuthHeaders.None, auth(config = FrigateConfig("http://192.168.1.91:8971")).headersFor(snap))
        assertEquals(AuthHeaders.None, auth(config = FrigateConfig("http://192.168.1.91:8971", "admin", null)).headersFor(snap))
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `a refused login is AuthFailed`(): Unit = runBlocking {
        for (status in listOf(400, 401, 403)) {
            assertEquals(AuthHeaders.Failed(MediaFailure.AuthFailed), auth(answer = { HttpPostResult.Response(status, emptyMap()) }).headersFor(snap))
        }
    }

    @Test
    fun `a login answer without the cookie (gateway older than v1_4) is Unavailable, not AuthFailed`(): Unit = runBlocking {
        val r = assertIs<AuthHeaders.Failed>(auth(answer = { HttpPostResult.Response(200, emptyMap()) }).headersFor(snap))
        val failure = assertIs<MediaFailure.Unavailable>(r.failure)
        assertTrue("v1.4" in failure.reason)
    }

    @Test
    fun `rate limit, refused destination and no route map to their failures`(): Unit = runBlocking {
        assertIs<MediaFailure.Unavailable>(assertIs<AuthHeaders.Failed>(auth(answer = { HttpPostResult.Response(429, emptyMap()) }).headersFor(snap)).failure)
        assertEquals(AuthHeaders.Failed(MediaFailure.NotAllowed), auth(answer = { HttpPostResult.NotAllowed }).headersFor(snap))
        assertEquals(AuthHeaders.Failed(MediaFailure.Unavailable("no route")), auth(answer = { HttpPostResult.Unavailable("no route") }).headersFor(snap))
    }

    @Test
    fun `renew logs in again for a rejected token, but not when another caller already did`(): Unit = runBlocking {
        val a = auth()
        val first = assertIs<AuthHeaders.Ready>(a.headersFor(snap))
        val second = assertIs<AuthHeaders.Ready>(a.renew(snap, first.headers))
        assertEquals(mapOf("Cookie" to "frigate_token=t2"), second.headers)
        assertEquals(second, a.renew(snap, first.headers)) // stale rejection: reuse t2
        assertEquals(2, posts.size)
    }

    @Test
    fun `a changed config drops the cached token`(): Unit = runBlocking {
        var config = login
        val a = FrigateAuth(config = { config }, post = { url, json -> posts += url to json; HttpPostResult.Response(200, mapOf("set-cookie" to "frigate_token=t${posts.size}")) })
        a.headersFor(snap)
        config = login.copy(password = "new")
        assertEquals(AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=t2")), a.headersFor(snap))
    }

    @Test
    fun `the token is found among several cookies, joined either way`() {
        assertEquals("jwt", FrigateAuth.tokenIn("a=1; Path=/\nfrigate_token=jwt; HttpOnly"))
        assertEquals("jwt", FrigateAuth.tokenIn("a=1; Path=/, frigate_token=jwt; HttpOnly"))
        assertEquals(null, FrigateAuth.tokenIn("a=1"))
        assertEquals(null, FrigateAuth.tokenIn(null))
    }
}
```

- [ ] **Step 2: Add the failing `MediaFetcherTest` cases** — add a fake auth and tests:

```kotlin
    private class FakeAuth(
        var first: AuthHeaders = AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=old")),
        var renewed: AuthHeaders = AuthHeaders.Ready(mapOf("Cookie" to "frigate_token=new")),
    ) : MediaAuth {
        var renews = 0
        override suspend fun headersFor(url: String) = first
        override suspend fun renew(url: String, rejected: Map<String, String>): AuthHeaders { renews++; return renewed }
    }

    private fun authed(auth: MediaAuth) = MediaFetcher(
        routes = { MediaRoute(LocalProbe { true }, null) },
        http = OkHttpClient(),
        isLanHost = { true },
        log = { logged += it },
        auth = auth,
    )

    @Test
    fun `the cookie is sent and a 401 is retried once with a renewed token`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("jpeg"))
        val auth = FakeAuth()
        val media = authed(auth).bytes(url(), 1_000).getOrThrow()
        assertContentEquals("jpeg".encodeToByteArray(), media.bytes)
        assertEquals("frigate_token=old", server.takeRequest().getHeader("Cookie"))
        assertEquals("frigate_token=new", server.takeRequest().getHeader("Cookie"))
        assertEquals(1, auth.renews)
    }

    @Test
    fun `a second 401 is AuthFailed and there is no loop`(): Unit = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(401)) }
        val auth = FakeAuth()
        assertEquals(MediaFailure.AuthFailed, failure(authed(auth).bytes(url(), 1_000)))
        assertEquals(2, server.requestCount)
        assertEquals(1, auth.renews)
    }

    @Test
    fun `a failed login stops before any media request`(): Unit = runBlocking {
        val auth = FakeAuth(first = AuthHeaders.Failed(MediaFailure.AuthFailed))
        assertEquals(MediaFailure.AuthFailed, failure(authed(auth).bytes(url(), 1_000)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `without auth headers a 401 stays Unavailable and nothing is renewed`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val auth = FakeAuth(first = AuthHeaders.None)
        assertEquals(MediaFailure.Unavailable("http 401"), failure(authed(auth).bytes(url(), 1_000)))
        assertEquals(0, auth.renews)
        assertNull(server.takeRequest().getHeader("Cookie"))
    }
```

- [ ] **Step 3: Run to see them fail**

Run: `./gradlew :core:alerts:testDebugUnitTest`
Expected: compilation failure (`FrigateAuth`, `MediaAuth`, `AuthHeaders`, `MediaFailure.AuthFailed` unresolved).

- [ ] **Step 4: Implement `MediaAuth.kt`**

```kotlin
package com.gekkotron.oikos.alerts.media

/** What a media URL needs before it is fetched (Frigate auth spec §4). */
sealed interface AuthHeaders {
    /** No login applies: another host, or no credentials. */
    data object None : AuthHeaders

    data class Ready(val headers: Map<String, String>) : AuthHeaders

    data class Failed(val failure: MediaFailure) : AuthHeaders
}

/** Adds login headers to media fetches; [MediaFetcher] calls [renew] at most once per fetch, after a 401. */
interface MediaAuth {
    suspend fun headersFor(url: String): AuthHeaders

    /** [rejected] got a 401: log in again, unless another caller already replaced that token. */
    suspend fun renew(url: String, rejected: Map<String, String>): AuthHeaders
}
```

- [ ] **Step 5: Implement `FrigateAuth.kt`**

```kotlin
package com.gekkotron.oikos.alerts.media

import com.gekkotron.oikos.data.FrigateConfig
import com.gekkotron.oikos.transport.HttpPostResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Frigate's `frigate_token` cookie for media on the configured Frigate origin (Frigate auth spec §4). The token is
 * kept in memory only; logins are serialised so concurrent fetches share one.
 */
class FrigateAuth(
    private val config: suspend () -> FrigateConfig?,
    private val post: suspend (url: String, json: String) -> HttpPostResult,
) : MediaAuth {
    private val mutex = Mutex()

    /** The config the token was obtained with; guarded by [mutex]. */
    private var cached: Pair<FrigateConfig, String>? = null

    override suspend fun headersFor(url: String): AuthHeaders {
        val c = configFor(url) ?: return AuthHeaders.None
        return mutex.withLock { cached?.takeIf { it.first == c }?.let { ready(it.second) } ?: login(c) }
    }

    override suspend fun renew(url: String, rejected: Map<String, String>): AuthHeaders {
        val c = configFor(url) ?: return AuthHeaders.None
        return mutex.withLock {
            val current = cached?.takeIf { it.first == c }?.let { ready(it.second) }
            if (current != null && current.headers != rejected) current else login(c)
        }
    }

    private suspend fun configFor(url: String): FrigateConfig? = config()?.takeIf { it.hasLogin && sameOrigin(it.url, url) }

    /** Runs under [mutex]. */
    private suspend fun login(c: FrigateConfig): AuthHeaders {
        cached = null
        val body = buildJsonObject { put("user", c.username); put("password", c.password) }.toString()
        return when (val r = post("${c.url}/api/login", body)) {
            is HttpPostResult.Response -> when (r.status) {
                in 200..299 -> tokenIn(r.headers["set-cookie"])?.let { token -> cached = c to token; ready(token) }
                    ?: AuthHeaders.Failed(MediaFailure.Unavailable("no token in Frigate's login answer (the gateway needs v1.4)"))
                400, 401, 403 -> AuthHeaders.Failed(MediaFailure.AuthFailed)
                429 -> AuthHeaders.Failed(MediaFailure.Unavailable("too many Frigate login attempts"))
                else -> AuthHeaders.Failed(MediaFailure.Unavailable("Frigate login http ${r.status}"))
            }
            HttpPostResult.NotAllowed -> AuthHeaders.Failed(MediaFailure.NotAllowed)
            is HttpPostResult.Unavailable -> AuthHeaders.Failed(MediaFailure.Unavailable(r.reason))
        }
    }

    companion object {
        const val COOKIE = "frigate_token"
        private val TOKEN = Regex("""(?:^|[\n,]\s*)$COOKIE=([^;,\s]+)""")

        /** The token in a `Set-Cookie` value; several cookies may be joined with `\n` (gateway, OkHttp) or `, `. */
        internal fun tokenIn(setCookie: String?): String? = setCookie?.let { TOKEN.find(it)?.groupValues?.get(1) }

        private fun ready(token: String) = AuthHeaders.Ready(mapOf("Cookie" to "$COOKIE=$token"))

        /** Same scheme, host and port, default ports filled in. */
        internal fun sameOrigin(base: String, url: String): Boolean {
            val a = base.toHttpUrlOrNull() ?: return false
            val b = url.toHttpUrlOrNull() ?: return false
            return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
        }
    }
}
```

- [ ] **Step 6: Wire it into `MediaFetcher.kt`**

Add to `MediaFailure`:

```kotlin
    /** Frigate refused the configured login (Frigate auth spec §4). */
    data object AuthFailed : MediaFailure
```

Constructor gains `private val auth: MediaAuth? = null` as the last parameter. `bytes` becomes:

```kotlin
    override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
        var headers = when (val a = auth?.headersFor(url) ?: AuthHeaders.None) {
            AuthHeaders.None -> emptyMap()
            is AuthHeaders.Ready -> a.headers
            is AuthHeaders.Failed -> return fail(a.failure)
        }
        var r = get.get(url, maxBytes, onProgress, headers)
        if (headers.isNotEmpty() && r is HttpGetResult.Response && r.status == 401) {
            when (val again = auth!!.renew(url, headers)) {
                AuthHeaders.None -> Unit
                is AuthHeaders.Ready -> {
                    headers = again.headers
                    r = get.get(url, maxBytes, onProgress, headers)
                }
                is AuthHeaders.Failed -> return fail(again.failure)
            }
        }
        return when (r) {
            is HttpGetResult.Response -> when {
                r.status == 404 || r.status == 410 -> fail(MediaFailure.Gone)
                r.status == 401 && headers.isNotEmpty() -> fail(MediaFailure.AuthFailed)
                r.status !in 200..299 -> fail(MediaFailure.Unavailable("http ${r.status}"))
                else -> Result.success(Media(r.bytes, r.contentType))
            }
            HttpGetResult.TooLarge -> fail(MediaFailure.Unavailable(TOO_LARGE))
            HttpGetResult.NotAllowed -> fail(MediaFailure.NotAllowed)
            is HttpGetResult.Unavailable -> fail(MediaFailure.Unavailable(if (r.reason == "no route") "no route to the media" else r.reason))
        }
    }
```

Update the class KDoc: "… spec §6.3; a Frigate URL gets its login cookie from [auth] (Frigate auth spec §4)."

- [ ] **Step 7: UI string** — `AlertDetailSheet.kt`:

```kotlin
internal fun mediaErrorRes(failure: MediaFailure): Int = when (failure) {
    MediaFailure.Gone, MediaFailure.NotAllowed -> R.string.alert_media_gone
    MediaFailure.AuthFailed -> R.string.alert_media_login
    is MediaFailure.Unavailable -> R.string.alert_media_failed
}
```

`values/strings.xml`: `<string name="alert_media_login">Frigate login failed — check Settings</string>`
`values-fr/strings.xml`: `<string name="alert_media_login">Connexion à Frigate refusée — vérifiez les réglages</string>`

If a test covers `mediaErrorRes` (grep `mediaErrorRes` under `feature/alerts/src/test`), add `assertEquals(R.string.alert_media_login, mediaErrorRes(MediaFailure.AuthFailed))` there.

- [ ] **Step 8: DI** — `AlertsModule.kt`:

```kotlin
    @Provides
    @Singleton
    fun frigateAuth(profiles: ProfileRepository, routes: MediaRoutes, http: OkHttpClient): FrigateAuth {
        val lan = LanOrGatewayGet(routes, http, ::isLikelyLocalHost, log = { Log.w("OikosFrigate", it) })
        return FrigateAuth(
            config = { ((profiles.state.first { it !is ProfileState.Loading } as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.frigate },
            post = lan::post,
        )
    }

    @Provides
    @Singleton
    fun mediaFetcher(routes: MediaRoutes, http: OkHttpClient, auth: FrigateAuth): MediaFetcher = MediaFetcher(routes, http, auth = auth)
```

(imports: `android.util.Log`, `FrigateAuth`, `LanOrGatewayGet`, `isLikelyLocalHost`, `ConnectionProfile`, `ProfileState`, `kotlinx.coroutines.flow.first`).

- [ ] **Step 9: Run the tests**

Run: `./gradlew :core:alerts:testDebugUnitTest :feature:alerts:testDebugUnitTest :app:testDebugUnitTest :core:push:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add core/alerts feature/alerts app/src/main/kotlin/com/gekkotron/oikos/di/AlertsModule.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(frigate): log in to Frigate and send its cookie with alert media

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 5: Oikos Settings — Frigate row and dialog

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/ConnectionSummary.kt`
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/SettingsScreen.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Test: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModelTest.kt`, `ConnectionSummaryTest.kt`, `ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `FrigateConfig`, `DEFAULT_FRIGATE_URL`, `normalizeFrigateUrl`, `ProfileRepository.setFrigate` (Task 2).
- Produces:
  - `ConnectionSummary.Direct(..., val frigateUrl: String? = null, val frigateUser: String? = null, val frigateHasPassword: Boolean = false)` — never the password itself.
  - `SettingsViewModel.setFrigate(url: String, user: String, password: String): Boolean`
  - `SettingsScreen(..., onSaveFrigate: (url: String, user: String, password: String) -> Boolean = { _, _, _ -> true })`
  - `internal fun FrigateDialogContent(url: String, user: String, password: String, hasPassword: Boolean, failed: Boolean, onUrl: (String) -> Unit, onUser: (String) -> Unit, onPassword: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit)`

- [ ] **Step 1: Write the failing view-model tests** — in `SettingsViewModelTest.kt`:

```kotlin
    private fun frigateOf(profiles: ProfileRepository) =
        ((profiles.state.value as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.frigate

    @Test
    fun `setFrigate saves, keeps the password for the same user only, and a blank url clears`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs-f.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile-f.json"), PassThrough, backgroundScope)
        val vm = SettingsViewModel(profiles, prefs, FakePushStatusSource())
        profiles.save(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))
        profiles.state.first { it is ProfileState.Ready }

        assertFalse(vm.setFrigate("frigate:8971", "admin", "pw"))
        assertTrue(vm.setFrigate(" http://192.168.1.91:8971/ ", " admin ", "pw"))
        profiles.state.first { frigateOf(profiles) == FrigateConfig("http://192.168.1.91:8971", "admin", "pw") }

        assertTrue(vm.setFrigate("http://192.168.1.91:8971", "admin", "")) // blank = unchanged
        profiles.state.first { frigateOf(profiles)?.password == "pw" }

        assertTrue(vm.setFrigate("http://192.168.1.91:8971", "other", "")) // new user: the old password is not reused
        profiles.state.first { frigateOf(profiles) == FrigateConfig("http://192.168.1.91:8971", "other", null) }

        assertTrue(vm.setFrigate("http://frigate:5000", "", "ignored")) // no user: no login
        profiles.state.first { frigateOf(profiles) == FrigateConfig("http://frigate:5000") }

        assertTrue(vm.setFrigate("  ", "", ""))
        profiles.state.first { it is ProfileState.Ready && frigateOf(profiles) == null }
    }
```

(import `com.gekkotron.oikos.data.FrigateConfig`).

`ConnectionSummaryTest.kt` — add:

```kotlin
    @Test
    fun `frigate shows url and user, and only whether a password exists`() {
        val s = ConnectionSummary.from(
            ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("h"), frigate = FrigateConfig("http://f:8971", "admin", "fr1gate-secret"))),
        ) as ConnectionSummary.Direct
        assertEquals("http://f:8971", s.frigateUrl)
        assertEquals("admin", s.frigateUser)
        assertTrue(s.frigateHasPassword)
        assertFalse("fr1gate-secret" in s.toString())
    }
```

- [ ] **Step 2: Write the failing UI tests** — in `SettingsScreenTest.kt`, follow the existing history-dialog tests (`show(...)`, `composeRule`). Add a recorder `savedFrigate = mutableListOf<Triple<String, String, String>>()` and `frigateAccepts = true`, pass `onSaveFrigate = { u, n, p -> savedFrigate += Triple(u, n, p); frigateAccepts }` in the screen helper, then:

```kotlin
    @Test
    fun `an unset frigate shows Not set and the dialog starts from the generic default`() {
        show(SettingsUi(connection = direct))
        composeRule.onNodeWithText("Frigate").assertExists()
        composeRule.onNodeWithText("Not set").performClick()
        composeRule.onNodeWithText("http://frigate:8971").assertExists()
        composeRule.onNodeWithText("e.g. http://frigate:8971 (login) or http://frigate:5000 (no login)").assertExists()
    }

    @Test
    fun `a configured frigate shows its url and user, and save reports the three fields`() {
        show(SettingsUi(connection = direct.copy(frigateUrl = "http://192.168.1.91:8971", frigateUser = "admin", frigateHasPassword = true)))
        composeRule.onNodeWithText("http://192.168.1.91:8971").performClick()
        composeRule.onNodeWithText("Unchanged").assertExists()
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(listOf(Triple("http://192.168.1.91:8971", "admin", "")), savedFrigate)
    }

    @Test
    fun `a refused frigate save keeps the dialog open with the error`() {
        frigateAccepts = false
        show(SettingsUi(connection = direct))
        composeRule.onNodeWithText("Not set").performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Use http(s)://host[:port], nothing after it").assertExists()
    }
```

(Adjust finders to the file's existing helpers if it uses tags or `useUnmergedTree`; the "Not set" row is the value text of the Frigate `InfoRow`.)

- [ ] **Step 3: Run to see them fail**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: compilation failure (`frigateUrl`, `setFrigate`, `onSaveFrigate` unresolved).

- [ ] **Step 4: Implement the summary and view model**

`ConnectionSummary.Direct` — add after `historyDefault`:

```kotlin
        /** Frigate's address; null = not set. The password itself never reaches Settings, only [frigateHasPassword]. */
        val frigateUrl: String? = null,
        val frigateUser: String? = null,
        val frigateHasPassword: Boolean = false,
```

and in `from`: `frigateUrl = p.frigate?.url, frigateUser = p.frigate?.username, frigateHasPassword = !p.frigate?.password.isNullOrEmpty(),`.

`SettingsViewModel`:

```kotlin
    /**
     * Blank [url] removes Frigate; otherwise it must be `http(s)://host[:port]` (Frigate auth spec §3). A blank [password]
     * keeps the stored one, but only for the same user; no user means no login.
     */
    fun setFrigate(url: String, user: String, password: String): Boolean {
        if (url.isBlank()) {
            viewModelScope.launch { profiles.setFrigate(null) }
            return true
        }
        val normalized = normalizeFrigateUrl(url) ?: return false
        val username = user.trim().ifEmpty { null }
        val old = ((profiles.state.value as? ProfileState.Ready)?.profile as? ConnectionProfile.Direct)?.frigate
        val pass = when {
            username == null -> null
            password.isNotEmpty() -> password
            old?.username == username -> old.password
            else -> null
        }
        viewModelScope.launch { profiles.setFrigate(FrigateConfig(normalized, username, pass)) }
        return true
    }
```

(imports: `ConnectionProfile`, `FrigateConfig`, `ProfileState`, `normalizeFrigateUrl`).

- [ ] **Step 5: Implement the UI** — in `SettingsScreen.kt`:
- `SettingsScreenRoute` call passes `onSaveFrigate = viewModel::setFrigate`.
- `SettingsScreen` gains `onSaveFrigate: (String, String, String) -> Boolean = { _, _, _ -> true }` and `var frigateDialog by rememberSaveable { mutableStateOf(false) }`; `ConnectionRows(state.connection, onEditHistory = …, onEditFrigate = { frigateDialog = true })`.
- In `ConnectionRows`'s `Direct` branch, after the history row:

```kotlin
            InfoRow(
                stringResource(R.string.settings_frigate),
                summary.frigateUrl ?: stringResource(R.string.settings_frigate_none),
                summary.frigateUser?.let { stringResource(R.string.settings_frigate_login, it) },
                onClick = onEditFrigate,
                onClickLabel = stringResource(R.string.settings_history_edit),
            )
```

- After the history dialog block:

```kotlin
    if (frigateDialog) {
        val direct = state.connection as? ConnectionSummary.Direct
        FrigateDialog(
            initialUrl = direct?.frigateUrl ?: DEFAULT_FRIGATE_URL,
            initialUser = direct?.frigateUser.orEmpty(),
            hasPassword = direct?.frigateHasPassword == true,
            onSave = onSaveFrigate,
            onDismiss = { frigateDialog = false },
        )
    }
```

- The dialog, mirroring `HistoryUrlDialog` / `HistoryUrlDialogContent`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrigateDialog(initialUrl: String, initialUser: String, hasPassword: Boolean, onSave: (String, String, String) -> Boolean, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var user by rememberSaveable { mutableStateOf(initialUser) }
    // Not saveable: a password must not land in the saved instance state.
    var password by remember { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            FrigateDialogContent(
                url, user, password, hasPassword, failed,
                onUrl = { url = it; failed = false },
                onUser = { user = it },
                onPassword = { password = it },
                onSave = { if (onSave(url, user, password)) onDismiss() else failed = true },
                onCancel = onDismiss,
            )
        }
    }
}

/** Stateless body of the Frigate dialog, so it can be tested without a dialog window. */
@Composable
internal fun FrigateDialogContent(
    url: String,
    user: String,
    password: String,
    hasPassword: Boolean,
    failed: Boolean,
    onUrl: (String) -> Unit,
    onUser: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val error = stringResource(R.string.settings_history_error)
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(R.string.settings_frigate), style = OikosTheme.type.display)
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text(stringResource(R.string.settings_frigate_hint)) },
            singleLine = true,
            isError = failed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { if (failed) error(error) },
        )
        Text(if (failed) error else "", style = OikosTheme.type.label, color = MaterialTheme.colorScheme.error, minLines = 1, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = user,
            onValueChange = onUser,
            label = { Text(stringResource(R.string.settings_frigate_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.settings_frigate_password)) },
            placeholder = if (hasPassword) ({ Text(stringResource(R.string.settings_frigate_unchanged)) }) else null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.settings_history_save)) }
        }
    }
}
```

(imports: `androidx.compose.runtime.remember`, `androidx.compose.ui.text.input.PasswordVisualTransformation`, `com.gekkotron.oikos.data.DEFAULT_FRIGATE_URL`.) If the "Unchanged" placeholder is not found by the test because the field is unfocused, use `supportingText` for it instead and keep the test.

- [ ] **Step 6: Strings**

`values/strings.xml`:

```xml
    <string name="settings_frigate">Frigate</string>
    <string name="settings_frigate_none">Not set</string>
    <string name="settings_frigate_login">Login: %1$s</string>
    <string name="settings_frigate_hint">e.g. http://frigate:8971 (login) or http://frigate:5000 (no login)</string>
    <string name="settings_frigate_user">Username (optional)</string>
    <string name="settings_frigate_password">Password</string>
    <string name="settings_frigate_unchanged">Unchanged</string>
```

`values-fr/strings.xml`:

```xml
    <string name="settings_frigate">Frigate</string>
    <string name="settings_frigate_none">Non configuré</string>
    <string name="settings_frigate_login">Identifiant : %1$s</string>
    <string name="settings_frigate_hint">ex. http://frigate:8971 (identifiant) ou http://frigate:5000 (sans)</string>
    <string name="settings_frigate_user">Identifiant (facultatif)</string>
    <string name="settings_frigate_password">Mot de passe</string>
    <string name="settings_frigate_unchanged">Inchangé</string>
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :feature:settings:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add feature/settings
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(settings): Frigate address and login

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 6: `tools/setup-qr.py` — the `frigate` section

**Files:**
- Modify: `tools/setup-qr.py`

**Interfaces:**
- Consumes: the payload shape from Task 2: `"frigate": {"url": "http(s)://host[:port]", "user"?: str, "pass"?: str}`.

- [ ] **Step 1: Implement** — docstring: add a paragraph after the history one:

```
The optional `frigate` section ({"url", "user"?, "pass"?}) is set with --frigate-url http://192.168.1.91:8971,
--frigate-user admin and --frigate-pass-file PATH (the password is read from a file and never printed), and removed
with --no-frigate; without these flags it is left as it is. Use http://<host>:5000 and no user for a Frigate
without login.
```

Arguments:

```python
    parser.add_argument("--frigate-url", help="Frigate's address, e.g. http://192.168.1.91:8971 (login) or http://192.168.1.91:5000")
    parser.add_argument("--frigate-user", help="Frigate username (optional)")
    parser.add_argument("--frigate-pass-file", help="file holding the Frigate password (needs --frigate-user)")
    parser.add_argument("--no-frigate", action="store_true", help="remove the frigate section")
```

Logic, after the history block:

```python
    if args.no_frigate:
        payload.pop("frigate", None)
    elif args.frigate_url:
        if not re.fullmatch(r"https?://[A-Za-z0-9._-]+(:\d{1,5})?/?", args.frigate_url):
            fail("--frigate-url must be http(s)://host[:port]")
        frigate = {"url": args.frigate_url.rstrip("/")}
        if args.frigate_pass_file and not args.frigate_user:
            fail("--frigate-pass-file needs --frigate-user")
        if args.frigate_user:
            frigate["user"] = args.frigate_user
            if args.frigate_pass_file:
                password = Path(args.frigate_pass_file).read_text().strip()
                if not password:
                    fail(f"{args.frigate_pass_file} is empty")
                frigate["pass"] = password
        payload["frigate"] = frigate
    elif args.frigate_user or args.frigate_pass_file:
        fail("--frigate-user and --frigate-pass-file need --frigate-url")
```

Summary line: `("local", "gateway", "push", "history", "frigate")`.

- [ ] **Step 2: Verify by hand** (no project test harness for this tool), in the scratchpad:

```bash
S=/private/tmp/claude-501/-Users-julienhuguel-Documents-SourcesPerso-Oikos/frigate-qr && mkdir -p $S
echo '{"v":1,"local":{"host":"192.168.1.91"}}' > $S/in.json && printf 'pw\n' > $S/pass.txt
python3 tools/setup-qr.py --in $S/in.json --out $S/a --frigate-url http://192.168.1.91:8971/ --frigate-user admin --frigate-pass-file $S/pass.txt
python3 -c "import json;print(json.load(open('$S/a.json'))['frigate'])"   # {'url': 'http://192.168.1.91:8971', 'user': 'admin', 'pass': 'pw'}
python3 tools/setup-qr.py --in $S/a.json --out $S/b --no-frigate && grep -c frigate $S/b.json   # 0
python3 tools/setup-qr.py --in $S/in.json --out $S/c --frigate-url frigate:8971; echo $?           # setup-qr: --frigate-url must be …, exit 1
python3 tools/setup-qr.py --in $S/in.json --out $S/d --frigate-url http://f:8971 --frigate-pass-file $S/pass.txt; echo $?  # needs --frigate-user, exit 1
```

Expected: outputs as commented; the password never appears on stdout.

- [ ] **Step 3: OikosPush `.env.example` (spec §6)** — in `/Users/julienhuguel/Documents/SourcesPerso/OikosPush/.env.example`, make the `FRIGATE_URL` comment read:

```
# Frigate's address as the phone reaches it on the LAN; the bridge builds snapshot/clip URLs from it.
# http://<host>:8971 for Frigate's login port (set the login in Oikos Settings or the setup QR),
# http://<host>:5000 for the unauthenticated port.
```

Commit there (its own repo, `main`, do not push):

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/OikosPush && git add .env.example && \
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "docs(frigate): FRIGATE_URL may be the login port 8971

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
cd /Users/julienhuguel/Documents/SourcesPerso/Oikos
```

- [ ] **Step 4: Commit**

```bash
git add tools/setup-qr.py
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(tools): frigate section in the setup QR

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

## After all tasks

- Full check: `./gradlew testDebugUnitTest :core:transport:test lint` (or the project's usual `check`), then a whole-branch review.
- Verify commit identity: `git log --format='%h %ae %ce' feat/history..feat/frigate-auth` — every line `60887050+Gekkotron@users.noreply.github.com`.
- Device check (user): Settings → Frigate → `http://192.168.1.91:8971`, user, password; open a Frigate alert on Wi‑Fi (snapshot + clip), then on mobile data.
