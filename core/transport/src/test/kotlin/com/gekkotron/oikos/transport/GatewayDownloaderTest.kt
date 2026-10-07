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
    fun `a download maps status, bytes and content type and passes the timeout on`(): Unit = runBlocking {
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
    fun `a non-2xx answer is returned, not thrown`(): Unit = runBlocking {
        val result = downloader { _, _ -> GatewayDownload(404, ByteArray(0), null) }.download("http://h/x", 30)
        assertEquals(404, result.status)
    }

    @Test
    fun `a refused destination or port is DownloadNotAllowed`(): Unit = runBlocking {
        for (reason in listOf("destination not allowed for this secret", "port not allowed for this secret")) {
            val e = assertFailsWith<DownloadNotAllowed> {
                downloader { _, _ -> throw GatewayException.Rejected(reason) }.download("http://h/x", 30)
            }
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun `other gateway failures are DownloadFailed with a short text`(): Unit = runBlocking {
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
