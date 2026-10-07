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
    fun `the LAN answers first and the gateway is not touched`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("lan-bytes").setHeader("Content-Type", "image/jpeg"))
        val gateway = FakeGateway()
        val media = fetcher(local = true, gateway = gateway).bytes(url(), 1_000).getOrThrow()
        assertContentEquals("lan-bytes".encodeToByteArray(), media.bytes)
        assertEquals("image/jpeg", media.contentType)
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a LAN 404 is Gone and does not fall back`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val gateway = FakeGateway()
        assertEquals(MediaFailure.Gone, failure(fetcher(true, gateway).bytes(url(), 1_000)))
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a LAN 500 is Unavailable and does not fall back`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val gateway = FakeGateway()
        assertEquals(MediaFailure.Unavailable("http 500"), failure(fetcher(true, gateway).bytes(url(), 1_000)))
        assertTrue(gateway.urls.isEmpty())
    }

    @Test
    fun `a connection error falls back to the gateway`(): Unit = runBlocking {
        val dead = url()
        server.shutdown()
        val gateway = FakeGateway()
        val media = fetcher(true, gateway).bytes(dead, 1_000).getOrThrow()
        assertContentEquals(byteArrayOf(9, 9), media.bytes)
        assertEquals(listOf(dead), gateway.urls)
    }

    @Test
    fun `a probe that does not answer goes straight to the gateway`(): Unit = runBlocking {
        val gateway = FakeGateway()
        fetcher(local = false, gateway = gateway).bytes(url(), 1_000).getOrThrow()
        assertEquals(0, server.requestCount)
        assertEquals(1, gateway.urls.size)
    }

    @Test
    fun `a host outside the private network never gets a direct request`(): Unit = runBlocking {
        val gateway = FakeGateway()
        fetcher(local = true, gateway = gateway, lan = { false }).bytes(url(), 1_000).getOrThrow()
        assertEquals(0, server.requestCount)
        assertEquals(1, gateway.urls.size)
    }

    @Test
    fun `gateway 404 and 410 are Gone, other statuses are Unavailable`(): Unit = runBlocking {
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
    fun `a refused destination is NotAllowed and logged once per host`(): Unit = runBlocking {
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
    fun `any other gateway failure is Unavailable with its text`(): Unit = runBlocking {
        val gateway = FakeGateway { throw DownloadFailed("gateway key not accepted") }
        assertEquals(MediaFailure.Unavailable("gateway key not accepted"), failure(fetcher(null, gateway).bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `with no route at all the media is Unavailable`(): Unit = runBlocking {
        val f = fetcher(local = null, gateway = null)
        assertIs<MediaFailure.Unavailable>(failure(f.bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `a body over maxBytes is cut off, by Content-Length or while streaming`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2_000))))
        assertEquals(MediaFailure.Unavailable("too large"), failure(fetcher(true, null).bytes(url(), 1_000)))
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(40_000)), 4_096))
        assertEquals(MediaFailure.Unavailable("too large"), failure(fetcher(true, null).bytes(url(), 10_000)))
    }

    @Test
    fun `a gateway body over maxBytes is refused too`(): Unit = runBlocking {
        val gateway = FakeGateway { RawDownload(200, ByteArray(2_000), null) }
        assertEquals(MediaFailure.Unavailable("too large"), failure(fetcher(null, gateway).bytes("http://192.168.1.20/x", 1_000)))
    }

    @Test
    fun `progress is reported with the total when the server sends a length`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(40_000))))
        val seen = mutableListOf<Pair<Long, Long?>>()
        fetcher(true, null).bytes(url(), 100_000) { read, total -> seen += read to total }.getOrThrow()
        assertTrue(seen.isNotEmpty())
        assertEquals(40_000L, seen.last().first)
        assertEquals(40_000L, seen.last().second)
    }

    @Test
    fun `a url that is not http is Unavailable`(): Unit = runBlocking {
        assertNull(fetcher(true, FakeGateway()).bytes("not a url", 1_000).getOrNull())
    }
}
