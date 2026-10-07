package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.GatewayDownload
import io.github.gekkotron.athena.gateway.client.GatewayException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.IOException

/** A raw GET through the gateway (gateway v1.3 `/gateway`, push alerts spec §6.3). */
interface GatewayDownloader {
    /** @throws DownloadNotAllowed the gateway key has no rule for this destination or port. @throws DownloadFailed anything else. */
    suspend fun download(url: String, timeoutSeconds: Int): RawDownload
}

/** [status] may be an upstream error (404...): the caller decides what that means. */
class RawDownload(val status: Int, val bytes: ByteArray, val contentType: String?)

/** "destination not allowed for this secret": the user has to add the host to the key's rules (spec §10 step 2). */
class DownloadNotAllowed(val reason: String) : Exception(reason)

class DownloadFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** [GatewayDownloader] over the SDK's `download`, with non-2xx answers returned instead of thrown. */
class AthenaGatewayDownloader internal constructor(
    private val fetch: suspend (url: String, timeoutSeconds: Int) -> GatewayDownload,
) : GatewayDownloader {
    constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient()) : this(clientFor(spec, http))

    override suspend fun download(url: String, timeoutSeconds: Int): RawDownload = try {
        fetch(url, timeoutSeconds).let { RawDownload(it.status, it.bytes, it.contentType) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        throw e.toDownloadFailure()
    }

    private companion object {
        fun clientFor(spec: TransportSpec.Gateway, http: OkHttpClient): suspend (String, Int) -> GatewayDownload {
            require(spec.url.startsWith("https://")) { "the gateway URL must use https" }
            val client = AthenaGatewayClient(spec.url, spec.key.toHex(), http)
            return { url, timeout -> client.download(url, timeoutSeconds = timeout, throwOnUpstreamError = false) }
        }
    }
}

internal fun GatewayException.toDownloadFailure(): Exception = when {
    this is GatewayException.Rejected && (reason.startsWith("destination not allowed") || reason.startsWith("port not allowed")) ->
        DownloadNotAllowed(reason)
    else -> DownloadFailed(publishText(), this)
}
