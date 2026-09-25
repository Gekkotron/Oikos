package com.gekkotron.oikos.transport

import com.gekkotron.oikos.crypto.EnvelopeCrypto
import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.Broker
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The real SDK against MockWebServer playing the gateway (replies sealed with our own EnvelopeCrypto). */
class AthenaGatewayApiTest {
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val crypto = EnvelopeCrypto(key)
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun api(broker: Broker? = Broker(host = "192.168.1.91", username = "oikos", password = "hunter2")) = AthenaGatewayApi(
        AthenaGatewayClient(server.url("/").toString(), key.toHex(), OkHttpClient()),
        broker,
    )

    private fun seal(json: String) = crypto.seal(Json.parseToJsonElement(json).jsonObject)
    private fun event(json: String) = "data: ${seal(json)}\n\n"
    private fun sse(vararg events: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString(""))
    private fun envelope(json: String) =
        MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(seal(json))
    private fun sent(): JsonObject = crypto.open(server.takeRequest().body.readUtf8())
    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    @Test
    fun `subscribe sends sorted topics, qos 1 and the broker inside the envelope`() = runBlocking {
        server.enqueue(sse(event("""{"type":"connected","topic":"a","topics":["a"]}""")))
        assertEquals(listOf<GatewayEvent>(GatewayEvent.Connected), api().stream(setOf("zigbee2mqtt/#", "homeassistant/#")).toList())
        val request = server.takeRequest()
        assertEquals("/mqtt/subscribe", request.path)
        val raw = request.body.readUtf8()
        assertFalse("hunter2" in raw, "the password travels only inside the envelope")
        val body = crypto.open(raw)
        assertEquals(listOf("homeassistant/#", "zigbee2mqtt/#"), body["topics"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(1), body["qos"])
        assertEquals(JsonPrimitive("192.168.1.91"), body["broker_host"])
        assertEquals(JsonPrimitive("oikos"), body["username"])
        assertEquals(JsonPrimitive("hunter2"), body["password"])
        assertFalse("broker_port" in body)
    }

    @Test
    fun `messages keep topic and retain, and payloads are re-encoded`() = runBlocking {
        server.enqueue(
            sse(
                event("""{"type":"connected"}"""),
                event("""{"type":"message","topic":"zigbee2mqtt/lamp","payload":{"state":"ON"},"qos":1,"retain":true}"""),
                event("""{"type":"message","topic":"homeassistant/light/x/config","payload":"","qos":0,"retain":true}"""),
            ),
        )
        val messages = api().stream(setOf("#")).toList().filterIsInstance<GatewayEvent.Message>().map { it.message }
        assertEquals(MqttMessage("zigbee2mqtt/lamp", """{"state":"ON"}""".encodeToByteArray(), retained = true), messages[0])
        assertEquals(0, messages[1].payload.size, "an empty retained payload still removes the entity")
    }

    @Test
    fun `a disconnected frame ends the stream normally`() = runBlocking {
        server.enqueue(sse(event("""{"type":"connected"}"""), event("""{"type":"disconnected","message":"x"}"""), event("""{"type":"connected"}""")))
        assertEquals(listOf<GatewayEvent>(GatewayEvent.Connected), api().stream(setOf("a")).toList())
    }

    @Test
    fun `401 is KeyRejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Refusal.KeyRejected, assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal)
    }

    @Test
    fun `a gateway 403 expired is ClockSkew, another 403 is Forbidden`() = runBlocking {
        server.enqueue(envelope("""{"status":403,"body":{"error":"Request expired"},"source":"gateway"}"""))
        assertEquals(Refusal.ClockSkew, assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal)
        server.enqueue(envelope("""{"status":403,"body":{"error":"destination not allowed for this secret"},"source":"gateway"}"""))
        assertEquals(
            Refusal.Forbidden("destination not allowed for this secret"),
            assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal,
        )
    }

    @Test
    fun `a refused broker login behind the gateway is BrokerRejected`() = runBlocking {
        server.enqueue(sse(event("""{"type":"error","message":"Connection failed with code 5","code":5}""")))
        assertEquals(Refusal.BrokerRejected, assertFailsWith<GatewayRefused> { api().stream(setOf("a")).collect() }.refusal)
    }

    @Test
    fun `raw bytes win over the parsed payload`() = runBlocking {
        val binary = byteArrayOf(0xFF.toByte(), 0x00, 0x01)
        server.enqueue(
            sse(
                event("""{"type":"connected"}"""),
                event(
                    """{"type":"message","topic":"sensor/temp","payload":21.5,""" +
                        """"payload_b64":"${"21.50".encodeToByteArray().toBase64()}","qos":1,"retain":false}""",
                ),
                event(
                    """{"type":"message","topic":"sensor/raw","payload":null,""" +
                        """"payload_b64":"${binary.toBase64()}","qos":0,"retain":false}""",
                ),
            ),
        )
        val messages = api().stream(setOf("#")).toList().filterIsInstance<GatewayEvent.Message>().map { it.message }
        assertEquals("21.50", messages[0].payload.decodeToString(), "the exact broker bytes win over the reparsed 21.5")
        assertEquals(binary.toList(), messages[1].payload.toList(), "a binary payload passes through untouched")
    }

    @Test
    fun `other stream errors, bad frames and server errors are retryable`() = runBlocking {
        server.enqueue(sse(event("""{"type":"error","message":"MQTT connection error: [Errno 111] Connection refused"}""")))
        assertFailsWith<IOException> { api().stream(setOf("a")).collect() }
        server.enqueue(sse("data: not-an-envelope\n\n"))
        assertFailsWith<IOException> { api().stream(setOf("a")).collect() }
        server.enqueue(MockResponse().setResponseCode(502))
        assertFailsWith<IOException> { api().stream(setOf("a")).collect() }
        Unit
    }

    @Test
    fun `publish sends the payload as a string and succeeds`() = runBlocking {
        server.enqueue(envelope("""{"status":200,"body":"{\"success\": true, \"topic\": \"zigbee2mqtt/lamp/set\", \"message\": \"Published successfully\"}"}"""))
        api().publish("zigbee2mqtt/lamp/set", "ON".encodeToByteArray(), qos = 1, retain = false)
        val body = sent()
        assertEquals(JsonPrimitive("zigbee2mqtt/lamp/set"), body["topic"])
        assertEquals(JsonPrimitive("ON"), body["message"])
        assertEquals(JsonPrimitive(1), body["qos"])
        assertEquals(JsonPrimitive(false), body["retain"])
    }

    @Test
    fun `publish failures carry a readable message`() = runBlocking {
        server.enqueue(envelope("""{"status":500,"body":"{\"error\": \"MQTT error: boom\"}","source":"gateway"}"""))
        assertEquals("MQTT error: boom", assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(LinkState.Offline.KEY_REJECTED, assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        server.enqueue(envelope("""{"status":403,"body":{"error":"Request expired"},"source":"gateway"}"""))
        assertEquals(LinkState.Offline.CLOCK_SKEW, assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        server.enqueue(envelope("""{"status":200,"body":"{\"success\": false, \"topic\": \"t\", \"message\": \"Failed with code 4\"}"}"""))
        assertEquals("Failed with code 4", assertFailsWith<PublishFailed> { api().publish("t", byteArrayOf(), 1, false) }.message)
        Unit
    }

    @Test
    fun `publish to an invalid topic never reaches the gateway`() = runBlocking {
        assertFailsWith<PublishFailed> { api().publish("a/+/b", byteArrayOf(), 1, false) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the public constructor refuses a cleartext URL and builds the broker from the spec`() {
        assertFailsWith<IllegalArgumentException> { AthenaGatewayApi(TransportSpec.Gateway("http://gw.example", key, null, null, null)) }
        assertEquals(null, TransportSpec.Gateway("https://gw.example", key, null, null, null).broker())
        assertEquals(
            Broker(host = "192.168.1.91", username = "oikos", password = "pw"),
            TransportSpec.Gateway("https://gw.example", key, "192.168.1.91", "oikos", "pw").broker(),
        )
    }

    @Test
    fun `the key is sent to the SDK as 64 lowercase hex characters`() {
        val hex = ByteArray(32) { if (it == 0) 0xAB.toByte() else it.toByte() }.toHex()
        assertEquals(64, hex.length)
        assertEquals("ab01", hex.take(4))
    }

    @Test
    fun `failures map to refusal or retry`() {
        assertIs<IOException>(IllegalStateException("x").toStreamFailure())
    }
}
