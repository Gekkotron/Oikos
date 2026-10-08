package com.gekkotron.oikos.alerts.media

import android.util.Log
import com.gekkotron.oikos.data.isLikelyLocalHost
import com.gekkotron.oikos.transport.HttpGetResult
import com.gekkotron.oikos.transport.HttpRoute
import com.gekkotron.oikos.transport.HttpRoutes
import com.gekkotron.oikos.transport.LanOrGatewayGet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

class Media(val bytes: ByteArray, val contentType: String?)

sealed interface MediaFailure {
    /** HTTP 404 or 410: the event is gone from the producer. */
    data object Gone : MediaFailure

    /** The gateway key has no rule for this host (spec §10 step 2). */
    data object NotAllowed : MediaFailure

    data class Unavailable(val reason: String) : MediaFailure
}

class MediaException(val failure: MediaFailure) : Exception(failure.toString())

/** How media can be reached right now (spec §6.3): see [HttpRoute]. */
typealias MediaRoute = HttpRoute
typealias MediaRoutes = HttpRoutes

/** What the notifier, the Coil fetcher and the video cache need; tests fake it. */
interface MediaSource {
    suspend fun bytes(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): Result<Media>
}

/** LAN first (5 s connect, 15 s read), the gateway's raw download otherwise (30 s); spec §6.3. */
class MediaFetcher(
    routes: MediaRoutes,
    http: OkHttpClient,
    io: CoroutineDispatcher = Dispatchers.IO,
    isLanHost: (String) -> Boolean = ::isLikelyLocalHost,
    log: (String) -> Unit = { Log.w(TAG, it) },
) : MediaSource {
    private val get = LanOrGatewayGet(routes, http, isLanHost, log, io)

    override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> =
        when (val r = get.get(url, maxBytes, onProgress)) {
            is HttpGetResult.Response -> when {
                r.status == 404 || r.status == 410 -> fail(MediaFailure.Gone)
                r.status !in 200..299 -> fail(MediaFailure.Unavailable("http ${r.status}"))
                else -> Result.success(Media(r.bytes, r.contentType))
            }
            HttpGetResult.TooLarge -> fail(MediaFailure.Unavailable(TOO_LARGE))
            HttpGetResult.NotAllowed -> fail(MediaFailure.NotAllowed)
            is HttpGetResult.Unavailable -> fail(MediaFailure.Unavailable(if (r.reason == "no route") "no route to the media" else r.reason))
        }

    private fun fail(failure: MediaFailure): Result<Media> = Result.failure(MediaException(failure))

    private companion object {
        const val TAG = "OikosMedia"
        const val TOO_LARGE = "too large"
    }
}
