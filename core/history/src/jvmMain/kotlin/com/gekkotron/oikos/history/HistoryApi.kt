package com.gekkotron.oikos.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Instant

/** The hub's `/api/v1/history/...` HTTP contract (see `crates/oikos-api/src/history.rs`). */
internal object HistoryApi {
    fun seriesUrl(base: String, ref: HistoryRef, range: HistoryRange) = url(base, "series", ref, "range" to range.query)
    fun dailyUrl(base: String, ref: HistoryRef, days: Int, kind: EnergyKind) = url(base, "daily", ref, "days" to "$days", "kind" to kind.query)
    fun todayUrl(base: String, ref: HistoryRef, kind: EnergyKind) = url(base, "today", ref, "kind" to kind.query)

    /** Repeated `entity=` in order; the hub answers one `items[i]` per `entity`. */
    fun lastChangeUrl(base: String, refs: List<HistoryRef>): String =
        refs.joinToString("&", prefix = "${base.trimEnd('/')}/api/v1/history/last_change?") { "entity=${encode(it.id)}" }

    fun parseLastChange(text: String, refs: List<HistoryRef>): Map<HistoryRef, LastChange> {
        val o = (Json.parseToJsonElement(text) as? JsonObject ?: error("not an object"))
        val items = o.getValue("items").jsonArray
        require(items.size == refs.size) { "expected ${refs.size} items, got ${items.size}" }
        return refs.zip(items).associate { (ref, item) ->
            val i = item as? JsonObject ?: error("bad item")
            val at = i["changed_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
            // The hub emits `{changed_at: <ts>, recorded: true}` or `{changed_at: null, recorded: false}`;
            // the OlderThan branch OikosHistory used (recorded without a timestamp) is gone with the hub.
            ref to if (at != null) LastChange.At(Instant.ofEpochSecond(at)) else LastChange.NotRecorded
        }
    }

    private fun url(base: String, path: String, ref: HistoryRef, vararg extra: Pair<String, String>): String =
        (listOf("entity" to ref.id) + extra)
            .joinToString("&", prefix = "${base.trimEnd('/')}/api/v1/history/$path?") { (k, v) -> "$k=${encode(v)}" }

    private fun encode(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")

    fun parseSeries(text: String): Series {
        val o = (Json.parseToJsonElement(text) as? JsonObject ?: error("not an object"))
        val points = o.getValue("points").jsonArray.map { p ->
            val a = p.jsonArray
            HistoryPoint(a.long(0), a.double(1), a.double(2), a.double(3))
        }
        return Series(o.getValue("window").jsonPrimitive.longOrNull ?: 0, points)
    }

    fun parseDaily(text: String, factor: Double): DailyTotals {
        val o = (Json.parseToJsonElement(text) as? JsonObject ?: error("not an object"))
        return DailyTotals(o.getValue("days").jsonArray.map { d -> d.jsonArray.let { DayTotal(it.long(0), kwh(it.double(1), factor)) } })
    }

    fun parseToday(text: String, factor: Double): Double? {
        val kwh = ((Json.parseToJsonElement(text) as? JsonObject ?: error("not an object"))).getValue("kwh")
        return if (kwh is JsonNull) null else kwh.jsonPrimitive.doubleOrNull?.let { kwh(it, factor) }
    }

    private fun kwh(raw: Double, factor: Double) = Math.round(raw * factor * 1000) / 1000.0

    private fun JsonArray.long(i: Int) = (getOrNull(i) ?: error("short array")).jsonPrimitive.longOrNull ?: error("bad timestamp")
    private fun JsonArray.double(i: Int) = (getOrNull(i) ?: error("short array")).jsonPrimitive.doubleOrNull ?: error("bad value")
}
