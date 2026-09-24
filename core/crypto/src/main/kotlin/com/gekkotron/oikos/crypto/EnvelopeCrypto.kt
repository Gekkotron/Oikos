package com.gekkotron.oikos.crypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * AES-256-GCM envelope used by Athena-HttpMqttGateway:
 * `base64(nonce[12] ‖ ciphertext ‖ tag[16])` over UTF-8 JSON that carries `timestamp` (epoch seconds).
 */
class EnvelopeCrypto(
    key: ByteArray,
    private val maxAgeSeconds: Long = 60,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nonceSource: () -> ByteArray = ::randomNonce,
) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == KEY_BYTES) { "AES-256 key must be $KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    /** Encrypts [fields], adding `timestamp` when absent. */
    fun seal(fields: JsonObject): String {
        val stamped = if ("timestamp" in fields) fields else JsonObject(fields + ("timestamp" to JsonPrimitive(nowSeconds())))
        return sealBytes(stamped.toString().encodeToByteArray())
    }

    fun sealBytes(plaintext: ByteArray): String {
        val nonce = nonceSource()
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext))
    }

    /** Decrypts without a freshness check (used for SSE events). */
    fun open(envelope: String): JsonObject {
        val raw = try {
            Base64.getDecoder().decode(envelope.trim())
        } catch (e: IllegalArgumentException) {
            throw EnvelopeException.Malformed("not base64")
        }
        if (raw.size < NONCE_BYTES + TAG_BITS / 8) throw EnvelopeException.Malformed("too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
        val plaintext = try {
            cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw EnvelopeException.AuthenticationFailed()
        }
        val element = try {
            Json.parseToJsonElement(plaintext.decodeToString())
        } catch (e: SerializationException) {
            throw EnvelopeException.Malformed("plaintext is not JSON")
        }
        return element as? JsonObject ?: throw EnvelopeException.Malformed("plaintext is not a JSON object")
    }

    /** Decrypts and rejects a `timestamp` outside ±maxAgeSeconds (used for responses). */
    fun openFresh(envelope: String): JsonObject {
        val obj = open(envelope)
        val ts = (obj["timestamp"] as? JsonPrimitive)?.doubleOrNull?.toLong()
            ?: throw EnvelopeException.MissingTimestamp()
        val skew = nowSeconds() - ts
        if (abs(skew) > maxAgeSeconds) throw EnvelopeException.Expired(skew)
        return obj
    }

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Parses the 64-character hex key from the onboarding QR payload. */
        fun keyFromHex(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length == KEY_BYTES * 2 && clean.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                "key must be ${KEY_BYTES * 2} hex characters"
            }
            return ByteArray(KEY_BYTES) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}

private fun randomNonce(): ByteArray = ByteArray(EnvelopeCrypto.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
