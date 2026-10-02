package com.gekkotron.oikos.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class OnboardingPayloadTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private fun ok(text: String) = assertIs<ParseResult.Ok>(OnboardingPayload.parse(text)).profile
    private fun error(text: String) = assertIs<ParseResult.Error>(OnboardingPayload.parse(text)).error

    @Test
    fun `full payload parses every field`() {
        val p = ok(
            """{"v":1,"local":{"host":"192.168.1.91","port":8883,"tls":true,"ws":false,"user":"oikos","pass":"s3cret"},
               "gateway":{"url":"https://gw.example","key":"$key","broker_host":"192.168.1.91"},"prefix":"ha",
               "push":{"token_topic":"fcm-push/tokens/{installId}"}}""",
        )
        assertEquals(LocalBroker("192.168.1.91", 8883, tls = true, ws = false, username = "oikos", password = "s3cret"), p.local)
        assertEquals("ha", p.prefix)
        assertEquals(GatewayConfig("https://gw.example", ByteArray(32) { it.toByte() }, "192.168.1.91"), p.gateway)
        assertEquals("fcm-push/tokens/{installId}", p.pushTokenTopic)
    }

    @Test
    fun `minimal payload uses defaults`() {
        val p = ok("""{"v":1,"local":{"host":"broker.local"}}""")
        assertEquals(LocalBroker("broker.local"), p.local)
        assertEquals(DEFAULT_PREFIX, p.prefix)
        assertNull(p.gateway)
        assertNull(p.pushTokenTopic)
    }

    @Test
    fun `unknown fields are ignored`() {
        ok("""{"v":1,"local":{"host":"h","colour":"blue"},"extra":42}""")
    }

    @Test
    fun `things that are not Oikos codes`() {
        assertEquals(PayloadError.NotOikosCode, error("https://example.com"))
        assertEquals(PayloadError.NotOikosCode, error("[1,2]"))
        assertEquals(PayloadError.NotOikosCode, error("""{"local":{"host":"h"}}"""))
        assertEquals(PayloadError.NotOikosCode, error("""{"v":"1","local":{"host":"h"}}"""))
    }

    @Test
    fun `newer versions are unsupported`() {
        assertEquals(PayloadError.UnsupportedVersion(2), error("""{"v":2,"local":{"host":"h"}}"""))
    }

    @Test
    fun `invalid local fields are named`() {
        assertEquals(PayloadError.InvalidField("local"), error("""{"v":1}"""))
        assertEquals(PayloadError.InvalidField("local.host"), error("""{"v":1,"local":{"host":"  "}}"""))
        assertEquals(PayloadError.InvalidField("local.port"), error("""{"v":1,"local":{"host":"h","port":0}}"""))
        assertEquals(PayloadError.InvalidField("local.port"), error("""{"v":1,"local":{"host":"h","port":70000}}"""))
        assertEquals(PayloadError.InvalidField("local.port"), error("""{"v":1,"local":{"host":"h","port":"1883"}}"""))
        assertEquals(PayloadError.InvalidField("local.tls"), error("""{"v":1,"local":{"host":"h","tls":"yes"}}"""))
    }

    @Test
    fun `invalid gateway fields are named`() {
        val base = """{"v":1,"local":{"host":"h"},"gateway":"""
        assertEquals(PayloadError.InvalidField("gateway.url"), error("""$base{"url":"http://gw.example","key":"$key"}}"""))
        for (url in listOf(
                "https://my host", "https://h:99999", "https://", "https://:443", "https://h:0",
                "https://my_gw:99999", "https://my_gw:0", "https://my_gw:x", "https://:8443/athena", "https://..example",
            )) {
            assertEquals(PayloadError.InvalidField("gateway.url"), error("""$base{"url":"$url","key":"$key"}}"""), url)
        }
        assertEquals(PayloadError.InvalidField("gateway.key"), error("""$base{"url":"https://gw.example","key":"${key.drop(1)}"}}"""))
        assertEquals(PayloadError.InvalidField("gateway.key"), error("""$base{"url":"https://gw.example","key":"${key.dropLast(2)}zz"}}"""))
        assertEquals(PayloadError.InvalidField("gateway.key"), error("""$base{"url":"https://gw.example","key":"${key.dropLast(2)}"}}"""))
    }

    @Test
    fun `well-formed gateway URLs are accepted`() {
        val base = """{"v":1,"local":{"host":"h"},"gateway":"""
        for (url in listOf(
                "https://gw.example", "https://gw.example:8443/", "https://gw.example/athena", "https://192.168.1.10:443",
                "https://my_gw.example", "https://my_gw.example:8443/athena", "https://bücher.example", "https://bücher.example:8443/",
            )) {
            assertEquals(url, ok("""$base{"url":"$url","key":"$key"}}""").gateway?.url, url)
        }
    }

    @Test
    fun `blank prefix is invalid`() {
        assertEquals(PayloadError.InvalidField("prefix"), error("""{"v":1,"local":{"host":"h"},"prefix":" "}"""))
    }

    @Test
    fun `toString never shows secrets`() {
        val p = ok("""{"v":1,"local":{"host":"h","user":"u","pass":"s3cret"},"gateway":{"url":"https://gw.example","key":"$key"}}""")
        assertFalse("s3cret" in p.toString())
        assertFalse(key in p.toString())
    }
}
