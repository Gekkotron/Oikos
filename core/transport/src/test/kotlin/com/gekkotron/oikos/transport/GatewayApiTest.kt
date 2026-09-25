package com.gekkotron.oikos.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GatewayApiTest {
    private fun bytesOf(json: String) = Json.parseToJsonElement(json).toPayloadBytes().decodeToString()

    @Test
    fun `payloads are re-encoded - strings raw, other JSON compact`() {
        assertEquals("ON", bytesOf("\"ON\""))
        assertEquals("""{"state":"ON","brightness":120}""", bytesOf("""{"state": "ON", "brightness": 120}"""))
        assertEquals("21.5", bytesOf("21.5"))
        assertEquals("true", bytesOf("true"))
        assertEquals("[1,2]", bytesOf("[1, 2]"))
        assertEquals("null", JsonNull.toPayloadBytes().decodeToString())
    }

    @Test
    fun `an empty payload becomes zero bytes, so a discovery removal still removes`() {
        assertEquals(0, JsonPrimitive("").toPayloadBytes().size)
        assertEquals(0, null.toPayloadBytes().size)
    }

    @Test
    fun `each refusal names its offline reason`() {
        assertEquals(LinkState.Offline.KEY_REJECTED, Refusal.KeyRejected.offlineReason)
        assertEquals(LinkState.Offline.CLOCK_SKEW, Refusal.ClockSkew.offlineReason)
        assertEquals(LinkState.Offline.REJECTED, Refusal.BrokerRejected.offlineReason)
        assertEquals(LinkState.Offline.GATEWAY_REFUSED, Refusal.Forbidden("destination not allowed for this secret").offlineReason)
    }

    @Test
    fun `error text reads an object or a JSON string holding one`() {
        assertEquals("boom", errorText(Json.parseToJsonElement("""{"error":"boom"}""")))
        assertEquals("boom", errorText(JsonPrimitive("""{"error": "boom"}""")))
        assertEquals("Failed with code 4", errorText(Json.parseToJsonElement("""{"success":false,"message":"Failed with code 4"}""")))
        assertNull(errorText(JsonPrimitive("plain text")))
        assertNull(errorText(null))
    }
}
