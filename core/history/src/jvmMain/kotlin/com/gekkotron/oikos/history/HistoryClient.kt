package com.gekkotron.oikos.history

import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface HistoryClient {
    val available: StateFlow<Boolean>
    suspend fun series(ref: HistoryRef, range: HistoryRange): HistoryResult<Series>
    suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale): HistoryResult<DailyTotals>
    suspend fun today(ref: HistoryRef, scale: EnergyScale): HistoryResult<Double?>

    /** Last flip per ref (last-change spec §4.2); refs absent from the map got no answer. */
    suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> =
        HistoryResult.Unavailable("not supported")

    companion object {
        /** No history: entry points hide. */
        val None: HistoryClient = object : HistoryClient {
            override val available: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
            private val off = HistoryResult.Unavailable("history is off")
            override suspend fun series(ref: HistoryRef, range: HistoryRange) = off
            override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale) = off
            override suspend fun today(ref: HistoryRef, scale: EnergyScale) = off
            override suspend fun lastChanges(refs: List<HistoryRef>) = off
        }
    }
}

/** Over the hub's `/api/v1/history/...` routes; answers are kept in memory for their TTL (spec §4.2). */
class HttpHistoryClient(
    private val baseUrl: suspend () -> String?,
    private val get: suspend (url: String) -> HttpGetResult,
    private val clock: () -> Long = System::currentTimeMillis,
) : HistoryClient {
    override val available: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    private val cache = HashMap<String, Pair<Long, HistoryResult<*>>>()
    private val lock = Mutex()

    override suspend fun series(ref: HistoryRef, range: HistoryRange) =
        fetch({ HistoryApi.seriesUrl(it, ref, range) }, range.ttlMillis, HistoryApi::parseSeries)

    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale) =
        fetch({ HistoryApi.dailyUrl(it, ref, days, scale.kind) }, LONG_TTL) { HistoryApi.parseDaily(it, scale.factor) }

    override suspend fun today(ref: HistoryRef, scale: EnergyScale) =
        fetch({ HistoryApi.todayUrl(it, ref, scale.kind) }, SHORT_TTL) { HistoryApi.parseToday(it, scale.factor) }

    override suspend fun lastChanges(refs: List<HistoryRef>): HistoryResult<Map<HistoryRef, LastChange>> {
        // Sorted and de-duplicated: the same set of refs is the same URL, so the same cache entry.
        val sorted = refs.distinct().sortedBy { it.id }
        if (sorted.isEmpty()) return HistoryResult.Ok(emptyMap())
        val all = HashMap<HistoryRef, LastChange>()
        for (chunk in sorted.chunked(MAX_REFS)) {
            when (val r = fetch({ HistoryApi.lastChangeUrl(it, chunk) }, SHORT_TTL) { HistoryApi.parseLastChange(it, chunk) }) {
                is HistoryResult.Ok -> all += r.value
                else -> return r
            }
        }
        return HistoryResult.Ok(all)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> fetch(url: (String) -> String, ttl: Long, parse: (String) -> T): HistoryResult<T> {
        val base = baseUrl() ?: return HistoryResult.Unavailable("no history url")
        val u = url(base)
        lock.withLock { cache[u]?.takeIf { clock() - it.first < ttl }?.let { return it.second as HistoryResult<T> } }
        val result: HistoryResult<T> = when (val r = get(u)) {
            is HttpGetResult.Response -> when (r.status) {
                in 200..299 -> try {
                    HistoryResult.Ok(parse(r.bytes.decodeToString()))
                } catch (e: IllegalArgumentException) {
                    HistoryResult.Unavailable("unreadable answer")
                } catch (e: IllegalStateException) {
                    HistoryResult.Unavailable("unreadable answer")
                } catch (e: NoSuchElementException) {
                    HistoryResult.Unavailable("unreadable answer")
                }
                404 -> HistoryResult.NotRecorded
                else -> HistoryResult.Unavailable("http ${r.status}")
            }
            HttpGetResult.NotAllowed -> HistoryResult.NotAllowed
            HttpGetResult.TooLarge -> HistoryResult.Unavailable("answer too large")
            is HttpGetResult.Unavailable -> HistoryResult.Unavailable(r.reason)
        }
        if (result is HistoryResult.Ok) lock.withLock { cache[u] = clock() to result }
        return result
    }

    private companion object {
        const val SHORT_TTL = 60_000L
        const val LONG_TTL = 600_000L
        const val MAX_REFS = 50
    }
}
