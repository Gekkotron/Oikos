package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.GatewayDownload
import io.github.gekkotron.athena.gateway.client.GatewayException
import io.github.gekkotron.athena.gateway.client.GatewayResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GatewayDownloaderTest {
    private fun downloader(
        send: suspend (String, JsonElement, Int) -> GatewayResponse = { _, _, _ -> error("unused") },
        fetch: suspend (String, Int, Map<String, String>) -> GatewayDownload = { _, _, _ -> error("unused") },
    ) = AthenaGatewayDownloader(fetch, send)

    @Test
    fun `a download maps status, bytes and content type and passes the timeout on`(): Unit = runBlocking {
        var seen: Pair<String, Int>? = null
        val result = downloader(fetch = { url, timeout, _ ->
            seen = url to timeout
            GatewayDownload(200, byteArrayOf(1, 2, 3), "image/jpeg")
        }).download("http://192.168.1.20:5000/s.jpg", 30)
        assertEquals("http://192.168.1.20:5000/s.jpg" to 30, seen)
        assertEquals(200, result.status)
        assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
        assertEquals("image/jpeg", result.contentType)
    }

    @Test
    fun `a non-2xx answer is returned, not thrown`(): Unit = runBlocking {
        val result = downloader(fetch = { _, _, _ -> GatewayDownload(404, ByteArray(0), null) }).download("http://h/x", 30)
        assertEquals(404, result.status)
    }

    @Test
    fun `a refused destination or port is DownloadNotAllowed`(): Unit = runBlocking {
        for (reason in listOf("destination not allowed for this secret", "port not allowed for this secret")) {
            val e = assertFailsWith<DownloadNotAllowed> {
                downloader(fetch = { _, _, _ -> throw GatewayException.Rejected(reason) }).download("http://h/x", 30)
            }
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun `other gateway failures are DownloadFailed with a short text`(): Unit = runBlocking {
        val expired = assertFailsWith<DownloadFailed> {
            downloader(fetch = { _, _, _ -> throw GatewayException.Rejected("Request expired") }).download("http://h/x", 30)
        }
        assertEquals(LinkState.Offline.CLOCK_SKEW, expired.message)
        val tooLarge = assertFailsWith<DownloadFailed> {
            downloader(fetch = { _, _, _ -> throw GatewayException.GatewayError("response too large") }).download("http://h/x", 30)
        }
        assertEquals("response too large", tooLarge.message)
        assertIs<GatewayException.Unauthorized>(
            assertFailsWith<DownloadFailed> { downloader(fetch = { _, _, _ -> throw GatewayException.Unauthorized() }).download("http://h/x", 30) }.cause,
        )
    }

    @Test
    fun `cancellation is not turned into a failure`() {
        assertFailsWith<CancellationException> {
            runBlocking { downloader(fetch = { _, _, _ -> throw CancellationException("stop") }).download("http://h/x", 30) }
        }
    }

    @Test
    fun `a plain http gateway url is refused`() {
        val spec = TransportSpec.Gateway("http://gw.example", ByteArray(32), null, null, null)
        assertFailsWith<IllegalArgumentException> { AthenaGatewayDownloader(spec) }
    }

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
}
