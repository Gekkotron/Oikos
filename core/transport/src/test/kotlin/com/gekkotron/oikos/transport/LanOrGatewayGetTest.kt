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
        val gateway = FakeGateway(answer = { throw DownloadNotAllowed("destination not allowed for this secret") })
        val g = get(local = false, gateway = gateway)
        assertEquals(HttpGetResult.NotAllowed, g.get(url(), 1_000))
        assertEquals(HttpGetResult.NotAllowed, g.get(url("/v1/daily"), 1_000))
        assertEquals(1, logged.size)
    }

    @Test
    fun `bodies over maxBytes are TooLarge on both routes`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(50)))
        assertEquals(HttpGetResult.TooLarge, get(local = true, gateway = null).get(url(), 10))
        val gateway = FakeGateway(answer = { RawDownload(200, ByteArray(50), null) })
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
}
