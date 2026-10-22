package com.gekkotron.oikos.history

import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpHistoryClientTest {
    private val ref = HistoryRef("living room/thermometer")
    private val urls = mutableListOf<String>()
    private var now = 0L
    private var answer: HttpGetResult = ok("""{"window":600,"points":[[1696000000,21.0,20.5,21.5],[1696000600,21.2,21.0,21.4]]}""")

    private fun ok(json: String) = HttpGetResult.Response(200, json.encodeToByteArray(), "application/json")
    private fun client(base: String? = "http://192.168.1.91:8081/") =
        HttpHistoryClient(baseUrl = { base }, get = { urls += it; answer }, clock = { now })

    @Test
    fun `series is parsed`(): Unit = runBlocking {
        val s = assertIs<HistoryResult.Ok<Series>>(client().series(ref, HistoryRange.H24)).value
        assertEquals(600, s.windowSeconds)
        assertEquals(listOf(HistoryPoint(1696000000, 21.0, 20.5, 21.5), HistoryPoint(1696000600, 21.2, 21.0, 21.4)), s.points)
    }

    @Test
    fun `entity id is url encoded and base url trailing slash is not doubled`(): Unit = runBlocking {
        client().series(ref, HistoryRange.D7)
        assertEquals(
            "http://192.168.1.91:8081/api/v1/history/series?entity=living%20room%2Fthermometer&range=7d",
            urls.single(),
        )
    }

    @Test
    fun `daily and today apply the scale`(): Unit = runBlocking {
        answer = ok("""{"days":[[1695938400,3200.0],[1696024800,1500.0]]}""")
        val d = assertIs<HistoryResult.Ok<DailyTotals>>(client().daily(ref, 7, EnergyScale(EnergyKind.COUNTER, 0.001))).value
        assertEquals(listOf(DayTotal(1695938400, 3.2), DayTotal(1696024800, 1.5)), d.days)
        assertEquals("http://192.168.1.91:8081/api/v1/history/daily?entity=living%20room%2Fthermometer&days=7&kind=counter", urls.last())
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
        answer = HttpGetResult.Response(503, """{"error":"history_unavailable"}""".encodeToByteArray(), null)
        assertIs<HistoryResult.Unavailable>(client().series(ref, HistoryRange.D30))
        answer = ok("not json")
        assertIs<HistoryResult.Unavailable>(client().today(ref, EnergyScale(EnergyKind.COUNTER, 1.0)))
        answer = ok("[]")
        assertIs<HistoryResult.Unavailable>(client().series(ref, HistoryRange.H24))
        answer = ok("""{"points":[[1]],"window":60}""")
        assertIs<HistoryResult.Unavailable>(client().series(ref, HistoryRange.D7))
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

    private val door = HistoryRef("entrance_door")
    private val hall = HistoryRef("hall_motion")
    private val shed = HistoryRef("shed_door")

    @Test
    fun `lastChanges sends sorted, de-duplicated ids and maps the two states`(): Unit = runBlocking {
        answer = ok("""{"items":[{"changed_at":1760350920,"recorded":true},{"changed_at":null,"recorded":false},{"changed_at":null,"recorded":false}]}""")
        val r = assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(client().lastChanges(listOf(shed, door, hall, door))).value
        assertEquals(
            "http://192.168.1.91:8081/api/v1/history/last_change?entity=entrance_door&entity=hall_motion&entity=shed_door",
            urls.single(),
        )
        assertEquals(
            mapOf(
                door to LastChange.At(Instant.ofEpochSecond(1760350920)),
                hall to LastChange.NotRecorded,
                shed to LastChange.NotRecorded,
            ),
            r,
        )
    }

    @Test
    fun `lastChanges encodes awkward ids`(): Unit = runBlocking {
        answer = ok("""{"items":[{"changed_at":null,"recorded":false}]}""")
        client().lastChanges(listOf(HistoryRef("salle de bain & co+1")))
        assertEquals(
            "http://192.168.1.91:8081/api/v1/history/last_change?entity=salle%20de%20bain%20%26%20co%2B1",
            urls.single(),
        )
    }

    @Test
    fun `lastChanges with no refs asks nothing`(): Unit = runBlocking {
        assertEquals(HistoryResult.Ok(emptyMap()), client().lastChanges(emptyList()))
        assertEquals(emptyList(), urls)
    }

    @Test
    fun `lastChanges chunks by 50 and any failed chunk fails the whole`(): Unit = runBlocking {
        val refs = (0 until 60).map { HistoryRef("d%02d".format(it)) }
        var calls = 0
        val c = HttpHistoryClient(baseUrl = { "http://h:8081" }, get = { url ->
            urls += url
            calls++
            val n = url.split("&entity=").size
            ok("""{"items":[${List(n) { """{"changed_at":1760350920,"recorded":true}""" }.joinToString(",")}]}""")
        }, clock = { now })
        val all = assertIs<HistoryResult.Ok<Map<HistoryRef, LastChange>>>(c.lastChanges(refs)).value
        assertEquals(60, all.size)
        assertEquals(2, calls)
        assertEquals(50, urls[0].split("&entity=").size)

        val gated = HttpHistoryClient(baseUrl = { "http://h:8081" }, get = { url ->
            if (url.contains("entity=d55")) HttpGetResult.NotAllowed else ok("""{"items":[${List(50) { """{"changed_at":1,"recorded":true}""" }.joinToString(",")}]}""")
        }, clock = { now })
        assertEquals(HistoryResult.NotAllowed, gated.lastChanges(refs))
    }

    @Test
    fun `lastChanges failures map like the other calls`(): Unit = runBlocking {
        answer = HttpGetResult.Response(404, ByteArray(0), null) // a hub that lost its history link
        assertEquals(HistoryResult.NotRecorded, client().lastChanges(listOf(door)))
        answer = ok("""{"items":[]}""") // item count does not match
        assertIs<HistoryResult.Unavailable>(client().lastChanges(listOf(hall)))
        answer = ok("""{}""") // no items field
        assertIs<HistoryResult.Unavailable>(client().lastChanges(listOf(shed)))
    }

    @Test
    fun `lastChanges answers are cached for a minute`(): Unit = runBlocking {
        answer = ok("""{"items":[{"changed_at":1760350920,"recorded":true}]}""")
        val c = client()
        c.lastChanges(listOf(door))
        now = 59_000
        c.lastChanges(listOf(door))
        assertEquals(1, urls.size)
        now = 61_000
        c.lastChanges(listOf(door))
        assertEquals(2, urls.size)
    }
}
