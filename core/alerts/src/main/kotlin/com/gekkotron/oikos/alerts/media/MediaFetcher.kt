package com.gekkotron.oikos.alerts.media

import android.util.Log
import com.gekkotron.oikos.data.isLikelyLocalHost
import com.gekkotron.oikos.transport.DownloadNotAllowed
import com.gekkotron.oikos.transport.GatewayDownloader
import com.gekkotron.oikos.transport.LocalProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Media(val bytes: ByteArray, val contentType: String?)

sealed interface MediaFailure {
    /** HTTP 404 or 410: the event is gone from the producer. */
    data object Gone : MediaFailure

    /** The gateway key has no rule for this host (spec §10 step 2). */
    data object NotAllowed : MediaFailure

    data class Unavailable(val reason: String) : MediaFailure
}

class MediaException(val failure: MediaFailure) : Exception(failure.toString())

/** How media can be reached right now (spec §6.3): a local probe when a LAN broker is allowed, a gateway when allowed. */
class MediaRoute(val local: LocalProbe?, val gateway: GatewayDownloader?)

fun interface MediaRoutes {
    suspend fun current(): MediaRoute
}

/** What the notifier, the Coil fetcher and the video cache need; tests fake it. */
interface MediaSource {
    suspend fun bytes(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): Result<Media>
}

/** LAN first (5 s connect, 15 s read), the gateway's raw download otherwise (30 s); spec §6.3. */
class MediaFetcher(
    private val routes: MediaRoutes,
    http: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val isLanHost: (String) -> Boolean = ::isLikelyLocalHost,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : MediaSource {
    private val lan: OkHttpClient = http.newBuilder()
        .connectTimeout(LAN_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LAN_READ_SECONDS, TimeUnit.SECONDS)
        .build()
    private val warnedHosts = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Lan {
        class Got(val media: Media) : Lan
        class Refused(val failure: MediaFailure) : Lan
        class Down(val reason: String) : Lan
    }

    override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
        val host = url.toHttpUrlOrNull()?.host ?: return fail(MediaFailure.Unavailable("not an http url"))
        val route = routes.current()
        var lanReason: String? = null
        val probe = route.local
        if (probe != null && isLanHost(host) && probe.reachable()) {
            when (val result = fromLan(url, maxBytes, onProgress)) {
                is Lan.Got -> return Result.success(result.media)
                is Lan.Refused -> return fail(result.failure)
                is Lan.Down -> lanReason = result.reason
            }
        }
        val gateway = route.gateway ?: return fail(MediaFailure.Unavailable(lanReason ?: "no route to the media"))
        return fromGateway(gateway, url, host, maxBytes)
    }

    private suspend fun fromGateway(gateway: GatewayDownloader, url: String, host: String, maxBytes: Long): Result<Media> = try {
        val download = gateway.download(url, GATEWAY_TIMEOUT_SECONDS)
        when {
            download.status == 404 || download.status == 410 -> fail(MediaFailure.Gone)
            download.status !in 200..299 -> fail(MediaFailure.Unavailable("http ${download.status}"))
            download.bytes.size > maxBytes -> fail(MediaFailure.Unavailable(TOO_LARGE))
            else -> Result.success(Media(download.bytes, download.contentType))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DownloadNotAllowed) {
        if (warnedHosts.add(host)) log("the gateway refused $host (${e.reason}); add the host to this key's rules")
        fail(MediaFailure.NotAllowed)
    } catch (e: Exception) {
        fail(MediaFailure.Unavailable(e.message ?: e.javaClass.simpleName))
    }

    private suspend fun fromLan(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Lan = try {
        lan.newCall(Request.Builder().url(url).build()).await().use { response ->
            when {
                response.code == 404 || response.code == 410 -> Lan.Refused(MediaFailure.Gone)
                !response.isSuccessful -> Lan.Refused(MediaFailure.Unavailable("http ${response.code}"))
                else -> readBody(response, maxBytes, onProgress)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Lan.Down(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun readBody(response: Response, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Lan = withContext(io) {
        val body = response.body ?: return@withContext Lan.Refused(MediaFailure.Unavailable("empty body"))
        val total = body.contentLength().takeIf { it >= 0 }
        if (total != null && total > maxBytes) return@withContext Lan.Refused(MediaFailure.Unavailable(TOO_LARGE))
        val out = ByteArrayOutputStream(total?.toInt()?.coerceIn(1, 1 shl 20) ?: INITIAL_BUFFER)
        val source = body.source()
        val chunk = ByteArray(CHUNK)
        var read = 0L
        while (true) {
            ensureActive()
            val n = source.read(chunk)
            if (n < 0) break
            read += n
            if (read > maxBytes) return@withContext Lan.Refused(MediaFailure.Unavailable(TOO_LARGE))
            out.write(chunk, 0, n)
            onProgress(read, total)
        }
        Lan.Got(Media(out.toByteArray(), body.contentType()?.toString()))
    }

    private fun fail(failure: MediaFailure): Result<Media> = Result.failure(MediaException(failure))

    private companion object {
        const val TAG = "OikosMedia"
        const val LAN_CONNECT_SECONDS = 5L
        const val LAN_READ_SECONDS = 15L
        const val GATEWAY_TIMEOUT_SECONDS = 30
        const val TOO_LARGE = "too large"
        const val INITIAL_BUFFER = 64 * 1024
        const val CHUNK = 16 * 1024
    }
}

/** Cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        },
    )
}
