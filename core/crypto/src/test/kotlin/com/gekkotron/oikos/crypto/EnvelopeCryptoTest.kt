package com.gekkotron.oikos.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvelopeCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val fixedNonce = ByteArray(12) { it.toByte() }

    // Produced by the gateway scheme (Python cryptography AESGCM), key 00..1f, nonce 00..0b.
    private val vectorPlaintext = """{"timestamp": 1790241732, "topic": "oikos/test", "message": "hello"}"""
    private val vectorEnvelope =
        "AAECAwQFBgcICQoLPCCicqiAsW/sLOepi8lJWrrmtQDBTGxOFEfH8XIZadEjKo7ewKh59weLC4j78woUznsN6Cmlwr1atRA5OouQgpxT5AeCAOF1ohlJF6GrNozuys49"

    private fun crypto(now: Long = 1790241732L) =
        EnvelopeCrypto(key, maxAgeSeconds = 60, nowSeconds = { now }, nonceSource = { fixedNonce.copyOf() })

    @Test
    fun `sealBytes matches the gateway vector byte for byte`() {
        assertEquals(vectorEnvelope, crypto().sealBytes(vectorPlaintext.encodeToByteArray()))
    }

    @Test
    fun `open decrypts the gateway vector`() {
        val obj = crypto().open(vectorEnvelope)
        assertEquals(1790241732L, obj["timestamp"]!!.jsonPrimitive.long)
        assertEquals("oikos/test", obj["topic"]!!.jsonPrimitive.content)
        assertEquals("hello", obj["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `seal adds timestamp and round-trips`() {
        val c = crypto(now = 1000)
        val obj = c.open(c.seal(JsonObject(mapOf("topic" to JsonPrimitive("a/b")))))
        assertEquals(1000L, obj["timestamp"]!!.jsonPrimitive.long)
        assertEquals("a/b", obj["topic"]!!.jsonPrimitive.content)
    }

    @Test
    fun `seal keeps an explicit timestamp`() {
        val c = crypto(now = 1000)
        val obj = c.open(c.seal(JsonObject(mapOf("timestamp" to JsonPrimitive(5)))))
        assertEquals(5L, obj["timestamp"]!!.jsonPrimitive.long)
    }

    @Test
    fun `random nonces differ between calls`() {
        val c = EnvelopeCrypto(key)
        val a = c.sealBytes("x".encodeToByteArray())
        val b = c.sealBytes("x".encodeToByteArray())
        assert(a != b)
    }

    @Test
    fun `wrong key fails authentication`() {
        val other = EnvelopeCrypto(ByteArray(32) { 7 })
        assertFailsWith<EnvelopeException.AuthenticationFailed> { other.open(vectorEnvelope) }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val raw = Base64.getDecoder().decode(vectorEnvelope)
        raw[20] = (raw[20].toInt() xor 1).toByte()
        assertFailsWith<EnvelopeException.AuthenticationFailed> {
            crypto().open(Base64.getEncoder().encodeToString(raw))
        }
    }

    @Test
    fun `truncated input is malformed`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(27))
        assertFailsWith<EnvelopeException.Malformed> { crypto().open(short) }
    }

    @Test
    fun `non-base64 input is malformed`() {
        assertFailsWith<EnvelopeException.Malformed> { crypto().open("%%% not base64 %%%") }
    }

    @Test
    fun `plaintext that is not a JSON object is malformed`() {
        val c = crypto()
        assertFailsWith<EnvelopeException.Malformed> { c.open(c.sealBytes("[1,2]".encodeToByteArray())) }
        assertFailsWith<EnvelopeException.Malformed> { c.open(c.sealBytes("nope".encodeToByteArray())) }
    }

    @Test
    fun `openFresh accepts the window edges and rejects outside`() {
        crypto(now = 1790241732L + 60).openFresh(vectorEnvelope)
        crypto(now = 1790241732L - 60).openFresh(vectorEnvelope)
        val late = assertFailsWith<EnvelopeException.Expired> { crypto(now = 1790241732L + 61).openFresh(vectorEnvelope) }
        assertEquals(61L, late.skewSeconds)
        assertFailsWith<EnvelopeException.Expired> { crypto(now = 1790241732L - 61).openFresh(vectorEnvelope) }
    }

    @Test
    fun `openFresh requires a timestamp`() {
        val c = crypto()
        val env = c.sealBytes("""{"status":200}""".encodeToByteArray())
        assertFailsWith<EnvelopeException.MissingTimestamp> { c.openFresh(env) }
    }

    @Test
    fun `openFresh accepts a float timestamp`() {
        val c = crypto(now = 100)
        c.openFresh(c.sealBytes("""{"timestamp":100.7}""".encodeToByteArray()))
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { EnvelopeCrypto(ByteArray(16)) }
    }

    @Test
    fun `keyFromHex parses 64 hex chars and rejects the rest`() {
        assertEquals(key.toList(), EnvelopeCrypto.keyFromHex("000102030405060708090a0b0c0d0e0f101112131415161718191A1B1C1D1E1F").toList())
        assertFailsWith<IllegalArgumentException> { EnvelopeCrypto.keyFromHex("abcd") }
        assertFailsWith<IllegalArgumentException> { EnvelopeCrypto.keyFromHex("zz".repeat(32)) }
    }

    @Test
    fun `exception messages never contain the key`() {
        val hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val e = assertFailsWith<EnvelopeException> { EnvelopeCrypto(ByteArray(32) { 7 }).open(vectorEnvelope) }
        assert(hex !in (e.message ?: ""))
    }
}
