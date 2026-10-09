package com.gekkotron.oikos.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertParserTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z")

    private fun v1(vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("v" to "1", "id" to "frigate-front-1696", "title" to "Front door") + extra

    @Test
    fun `every field of the normalized data map is read`() {
        val alert = AlertParser.fromData(
            v1(
                "channel" to "camera", "severity" to "critical", "body" to "Person detected",
                "image_url" to "http://192.168.1.20:5000/s.jpg", "video_url" to "http://192.168.1.20:5000/c.mp4",
                "entity_id" to "binary_sensor.front_door", "source" to "frigate", "silent" to "true",
                "actions" to """[{"id":"disarm","label":"Disarm"}]""", "sent_at" to "1790000000",
            ),
            receivedAt = now,
        )
        assertNotNull(alert)
        assertEquals("frigate-front-1696", alert.id)
        assertEquals(AlertChannel.CAMERA, alert.channel)
        assertEquals(Severity.CRITICAL, alert.severity)
        assertEquals("Front door", alert.title)
        assertEquals("Person detected", alert.body)
        assertEquals("http://192.168.1.20:5000/s.jpg", alert.imageUrl)
        assertEquals("http://192.168.1.20:5000/c.mp4", alert.videoUrl)
        assertEquals("binary_sensor.front_door", alert.entityId)
        assertEquals("frigate", alert.source)
        assertTrue(alert.silent)
        assertFalse(alert.resolved)
        assertEquals(listOf(AlertAction("disarm", "Disarm")), alert.actions)
        assertEquals(Instant.ofEpochSecond(1790000000), alert.sentAt)
        assertEquals(now, alert.receivedAt)
    }

    @Test
    fun `an unknown or missing channel is misc and severity defaults to info`() {
        assertEquals(AlertChannel.MISC, AlertParser.fromData(v1("channel" to "garden"))!!.channel)
        assertEquals(AlertChannel.MISC, AlertParser.fromData(v1())!!.channel)
        assertEquals(Severity.INFO, AlertParser.fromData(v1("severity" to "loud"))!!.severity)
    }

    @Test
    fun `optional fields left out stay null or empty and sent_at falls back to receivedAt`() {
        val alert = AlertParser.fromData(v1(), receivedAt = now)!!
        assertNull(alert.imageUrl)
        assertNull(alert.videoUrl)
        assertNull(alert.entityId)
        assertNull(alert.source)
        assertEquals("", alert.body)
        assertEquals(emptyList(), alert.actions)
        assertFalse(alert.silent)
        assertEquals(now, alert.sentAt)
    }

    @Test
    fun `malformed data is null`() {
        assertNull(AlertParser.fromData(v1("v" to "2")))
        assertNull(AlertParser.fromData(mapOf("id" to "x", "title" to "t")))
        assertNull(AlertParser.fromData(v1("id" to " ")))
        assertNull(AlertParser.fromData(mapOf("v" to "1", "title" to "t")))
        assertNull(AlertParser.fromData(v1("title" to "")))
    }

    @Test
    fun `a cancel needs no title and is resolved`() {
        val cancel = AlertParser.fromData(mapOf("v" to "1", "id" to "frigate-front-1696", "cancel" to "true"), receivedAt = now)
        assertNotNull(cancel)
        assertTrue(cancel.resolved)
        assertEquals("", cancel.title)
    }

    @Test
    fun `bad actions json keeps the alert with no actions`() {
        assertEquals(emptyList(), AlertParser.fromData(v1("actions" to "[{oops"))!!.actions)
        assertEquals(emptyList(), AlertParser.fromData(v1("actions" to "[]"))!!.actions)
        assertEquals(
            listOf(AlertAction("a", "a"), AlertAction("b", "Bee")),
            AlertParser.fromData(v1("actions" to """[{"id":"a"},{"id":"b","label":"Bee"},{"label":"no id"},3]"""))!!.actions,
        )
    }

    @Test
    fun `at most three actions are shown`() {
        val four = """[{"id":"1","label":"1"},{"id":"2","label":"2"},{"id":"3","label":"3"},{"id":"4","label":"4"}]"""
        val alert = AlertParser.fromData(v1("actions" to four))!!
        assertEquals(4, alert.actions.size)
        assertEquals(listOf("1", "2", "3"), alert.shownActions.map { it.id })
    }

    @Test
    fun `a history entry has typed json values and its receivedAt is its sentAt`() {
        val obj = Json.parseToJsonElement(
            """{"v":1,"id":"a1","channel":"alarm","severity":"warning","title":"Alarm","body":"Triggered",
                "actions":[{"id":"silence","label":"Silence"}],"silent":false,"cancel":true,"sent_at":1790000000,"source":"jeedom"}""",
        ).jsonObject
        val alert = AlertParser.fromHistory(obj, now)!!
        assertEquals(AlertChannel.ALARM, alert.channel)
        assertEquals(Severity.WARNING, alert.severity)
        assertEquals(listOf(AlertAction("silence", "Silence")), alert.actions)
        assertTrue(alert.resolved)
        assertEquals(Instant.ofEpochSecond(1790000000), alert.sentAt)
        assertEquals(alert.sentAt, alert.receivedAt)
        assertEquals("jeedom", alert.source)
    }

    @Test
    fun `parseHistory skips malformed entries and rejects a non array`() {
        val payload = """[{"v":1,"id":"a","title":"A"},{"v":1,"title":"no id"},"junk",{"v":2,"id":"c","title":"C"},{"v":1,"id":"d","title":"D"}]"""
        assertEquals(listOf("a", "d"), AlertParser.parseHistory(payload, now)!!.map { it.id })
        assertNull(AlertParser.parseHistory("""{"v":1}""", now))
        assertNull(AlertParser.parseHistory("not json", now))
        assertEquals(emptyList(), AlertParser.parseHistory("[]", now))
    }

    private val locBase = mapOf("v" to "1", "id" to "a1", "channel" to "camera", "title" to "Driveway", "body" to "Car in Driveway")

    @Test
    fun `loc is parsed from its compact JSON`() {
        val alert = AlertParser.fromData(locBase + ("loc" to """{"key":"frigate.object","args":{"label":"car","zone":"Driveway"}}"""))!!
        assertEquals(AlertLoc("frigate.object", mapOf("label" to "car", "zone" to "Driveway")), alert.loc)
    }

    @Test
    fun `a malformed loc is ignored and the alert kept`() {
        for (raw in listOf("not json", "[]", """{"args":{}}""", """{"key":3}""", """{"key":"k","args":{"label":3}}""", """{"key":"k","args":[]}""")) {
            val alert = AlertParser.fromData(locBase + ("loc" to raw))
            assertEquals("Car in Driveway", alert?.body, raw)
            assertNull(alert?.loc, raw)
        }
        assertNull(AlertParser.fromData(locBase)!!.loc)
    }

    @Test
    fun `history entries keep loc`() {
        val json = """[{"v":1,"id":"h1","channel":"camera","title":"Driveway","body":"Car","sent_at":1700000000,
            "loc":{"key":"frigate.object","args":{"label":"car"}}}]"""
        assertEquals(AlertLoc("frigate.object", mapOf("label" to "car")), AlertParser.parseHistory(json)!!.single().loc)
    }
}
