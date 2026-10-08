package com.gekkotron.oikos.transport

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

/** How a LAN URL can be reached right now: a local probe when a LAN broker is allowed, a gateway when allowed. */
class HttpRoute(val local: LocalProbe?, val gateway: GatewayDownloader?)

fun interface HttpRoutes {
    suspend fun current(): HttpRoute
}

sealed interface HttpGetResult {
    /** Any HTTP answer; a non-2xx LAN answer has an empty body (it is never read). */
    class Response(val status: Int, val bytes: ByteArray, val contentType: String?) : HttpGetResult
    data object TooLarge : HttpGetResult
    /** The gateway key has no rule for this host or port. */
    data object NotAllowed : HttpGetResult
    data class Unavailable(val reason: String) : HttpGetResult
}

/** A GET of a LAN URL: direct when the LAN is reachable (5 s connect, 15 s read), the gateway's raw download otherwise (30 s). */
class LanOrGatewayGet(
    private val routes: HttpRoutes,
    http: OkHttpClient,
    private val isLanHost: (String) -> Boolean,
    private val log: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lan: OkHttpClient = http.newBuilder()
        .connectTimeout(LAN_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LAN_READ_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false) // a LAN host must not bounce the request to another (cleartext) host
        .followSslRedirects(false)
        .build()
    private val warnedHosts = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Lan {
        class Done(val result: HttpGetResult) : Lan
        class Down(val reason: String) : Lan
    }

    suspend fun get(url: String, maxBytes: Long, onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): HttpGetResult {
        val parsed = url.toHttpUrlOrNull() ?: return HttpGetResult.Unavailable("not an http url")
        val host = parsed.host
        val route = routes.current()
        var lanReason: String? = null
        val probe = route.local
        if (probe != null && isLanHost(host) && probe.reachable()) {
            when (val result = fromLan(url, maxBytes, onProgress)) {
                is Lan.Done -> return result.result
                is Lan.Down -> lanReason = result.reason
            }
        }
        val gateway = route.gateway ?: return HttpGetResult.Unavailable(lanReason ?: "no route")
        return fromGateway(gateway, url, host, maxBytes)
    }

    private suspend fun fromGateway(gateway: GatewayDownloader, url: String, host: String, maxBytes: Long): HttpGetResult = try {
        val download = gateway.download(url, GATEWAY_TIMEOUT_SECONDS)
        if (download.bytes.size > maxBytes) HttpGetResult.TooLarge else HttpGetResult.Response(download.status, download.bytes, download.contentType)
    } catch (e: CancellationException) {
        throw e
    } catch (e: DownloadNotAllowed) {
        if (warnedHosts.add(host)) log("the gateway refused $host (${e.reason}); add the host to this key's rules")
        HttpGetResult.NotAllowed
    } catch (e: Exception) {
        HttpGetResult.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun fromLan(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Lan = try {
        lan.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) Lan.Done(HttpGetResult.Response(response.code, ByteArray(0), null))
            else Lan.Done(readBody(response, maxBytes, onProgress))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Lan.Down(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun readBody(response: Response, maxBytes: Long, onProgress: (Long, Long?) -> Unit): HttpGetResult = withContext(io) {
        val body = response.body ?: return@withContext HttpGetResult.Unavailable("empty body")
        val total = body.contentLength().takeIf { it >= 0 }
        if (total != null && total > maxBytes) return@withContext HttpGetResult.TooLarge
        val out = ByteArrayOutputStream(total?.toInt()?.coerceIn(1, 1 shl 20) ?: INITIAL_BUFFER)
        val source = body.source()
        val chunk = ByteArray(CHUNK)
        var read = 0L
        while (true) {
            ensureActive()
            val n = source.read(chunk)
            if (n < 0) break
            read += n
            if (read > maxBytes) return@withContext HttpGetResult.TooLarge
            out.write(chunk, 0, n)
            onProgress(read, total)
        }
        HttpGetResult.Response(response.code, out.toByteArray(), body.contentType()?.toString())
    }

    private companion object {
        const val LAN_CONNECT_SECONDS = 5L
        const val LAN_READ_SECONDS = 15L
        const val GATEWAY_TIMEOUT_SECONDS = 30
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
