package com.gekkotron.oikos.history

import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpHistoryClientTest {
    private val ref = HistoryRef("zigbee2mqtt/rooms/living/thermometer", "température_int.")
    private val urls = mutableListOf<String>()
    private var now = 0L
    private var answer: HttpGetResult = ok("""{"window":600,"points":[[1696000000,21.0,20.5,21.5],[1696000600,21.2,21.0,21.4]]}""")

    private fun ok(json: String) = HttpGetResult.Response(200, json.encodeToByteArray(), "application/json")
    private fun client(base: String? = "http://192.168.1.91:8090/") =
        HttpHistoryClient(baseUrl = { base }, get = { urls += it; answer }, clock = { now })

    @Test
    fun `series is parsed`(): Unit = runBlocking {
        val s = assertIs<HistoryResult.Ok<Series>>(client().series(ref, HistoryRange.H24)).value
        assertEquals(600, s.windowSeconds)
        assertEquals(listOf(HistoryPoint(1696000000, 21.0, 20.5, 21.5), HistoryPoint(1696000600, 21.2, 21.0, 21.4)), s.points)
    }

    @Test
    fun `key is url encoded and base url trailing slash is not doubled`(): Unit = runBlocking {
        client().series(ref, HistoryRange.D7)
        assertEquals(
            "http://192.168.1.91:8090/v1/series?topic=zigbee2mqtt%2Frooms%2Fliving%2Fthermometer&key=temp%C3%A9rature_int.&range=7d",
            urls.single(),
        )
    }

    @Test
    fun `daily and today apply the scale`(): Unit = runBlocking {
        answer = ok("""{"days":[[1695938400,3200.0],[1696024800,1500.0]]}""")
        val d = assertIs<HistoryResult.Ok<DailyTotals>>(client().daily(ref, 7, EnergyScale(EnergyKind.COUNTER, 0.001))).value
        assertEquals(listOf(DayTotal(1695938400, 3.2), DayTotal(1696024800, 1.5)), d.days)
        assertEquals("http://192.168.1.91:8090/v1/daily?topic=zigbee2mqtt%2Frooms%2Fliving%2Fthermometer&key=temp%C3%A9rature_int.&days=7&kind=counter", urls.last())
        answer = ok("""{"kwh":3.412,"since":1696024800}""")
        assertEquals(HistoryResult.Ok(3.412), client().today(ref, EnergyScale(EnergyKind.POWER, 1.0)))
        answer = ok("""{"kwh":null,"since":1696024800}""")
        assertEquals(HistoryResult.Ok(null), client().today(ref, EnergyScale(EnergyKind.POWER, 1.0)))
    }

    @Test
    fun `statuses map to results`(): Unit = runBlocking {
        answer = HttpGetResult.Response(404, """{"error":"not_recorded"}""".encodeToByteArray(), null)
        assertEquals(HistoryResult.NotRecorded, client().series(ref, HistoryRange.H24))
        answer = HttpGetResult.Response(404, ByteArray(0), null) // a LAN 404: body never read
        assertEquals(HistoryResult.NotRecorded, client().series(ref, HistoryRange.D7))
        answer = HttpGetResult.Response(503, """{"error":"influx_unavailable"}""".encodeToByteArray(), null)
        assertIs<HistoryResult.Unavailable>(client().series(ref, HistoryRange.D30))
        answer = ok("not json")
        assertIs<HistoryResult.Unavailable>(client().today(ref, EnergyScale(EnergyKind.COUNTER, 1.0)))
    }

    @Test
    fun `gateway refusal is NotAllowed and no url is Unavailable`(): Unit = runBlocking {
        answer = HttpGetResult.NotAllowed
        assertEquals(HistoryResult.NotAllowed, client().series(ref, HistoryRange.H24))
        assertIs<HistoryResult.Unavailable>(client(base = null).series(ref, HistoryRange.H24))
    }

    @Test
    fun `answers are cached per request for their ttl, failures are not`(): Unit = runBlocking {
        val c = client()
        c.series(ref, HistoryRange.H24)
        now += 59_000
        c.series(ref, HistoryRange.H24)
        assertEquals(1, urls.size)
        now += 2_000
        c.series(ref, HistoryRange.H24)
        assertEquals(2, urls.size)
        c.series(ref, HistoryRange.D7)
        now += 9 * 60_000
        c.series(ref, HistoryRange.D7)
        assertEquals(3, urls.size)
        answer = HttpGetResult.Unavailable("down")
        c.series(ref, HistoryRange.D30)
        c.series(ref, HistoryRange.D30)
        assertEquals(5, urls.size)
    }
}
