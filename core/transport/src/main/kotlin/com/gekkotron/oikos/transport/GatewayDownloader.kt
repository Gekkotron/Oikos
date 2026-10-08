package com.gekkotron.oikos.transport

import io.github.gekkotron.athena.gateway.client.AthenaGatewayClient
import io.github.gekkotron.athena.gateway.client.GatewayDownload
import io.github.gekkotron.athena.gateway.client.GatewayException
import io.github.gekkotron.athena.gateway.client.GatewayResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.io.IOException

/** A raw GET (gateway v1.3) or a JSON POST (gateway `http`, v1.4 for headers) through the gateway (push alerts spec §6.3, Frigate auth spec §4). */
interface GatewayDownloader {
    /** @throws DownloadNotAllowed the gateway key has no rule for this destination or port. @throws DownloadFailed anything else. */
    suspend fun download(url: String, timeoutSeconds: Int, headers: Map<String, String> = emptyMap()): RawDownload

    /** Same failures as [download]. A gateway older than v1.4 answers with no headers. */
    suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost
}

/** [status] may be an upstream error (404...): the caller decides what that means. */
class RawDownload(val status: Int, val bytes: ByteArray, val contentType: String?)

/** [headers]: lower-case names, repeated headers joined with `\n`. */
class RawPost(val status: Int, val headers: Map<String, String>)

/** "destination not allowed for this secret": the user has to add the host to the key's rules (spec §10 step 2). */
class DownloadNotAllowed(val reason: String) : Exception(reason)

class DownloadFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** [GatewayDownloader] over the SDK's `download` and `http`, with non-2xx answers returned instead of thrown. */
class AthenaGatewayDownloader internal constructor(
    private val fetch: suspend (url: String, timeoutSeconds: Int, headers: Map<String, String>) -> GatewayDownload,
    private val send: suspend (url: String, body: JsonElement, timeoutSeconds: Int) -> GatewayResponse,
) : GatewayDownloader {
    constructor(spec: TransportSpec.Gateway, http: OkHttpClient = OkHttpClient()) : this(clientFor(spec, http))

    private constructor(client: AthenaGatewayClient) : this(
        fetch = { url, timeout, headers -> client.download(url, headers = headers, timeoutSeconds = timeout, throwOnUpstreamError = false) },
        send = { url, body, timeout ->
            client.http(url, method = "POST", headers = JSON_HEADERS, body = body, timeoutSeconds = timeout, throwOnUpstreamError = false)
        },
    )

    override suspend fun download(url: String, timeoutSeconds: Int, headers: Map<String, String>): RawDownload =
        guarded { fetch(url, timeoutSeconds, headers).let { RawDownload(it.status, it.bytes, it.contentType) } }

    override suspend fun post(url: String, json: String, timeoutSeconds: Int): RawPost =
        guarded { send(url, Json.parseToJsonElement(json), timeoutSeconds).let { RawPost(it.status, it.headers) } }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        throw e.toDownloadFailure()
    }

    private companion object {
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")

        fun clientFor(spec: TransportSpec.Gateway, http: OkHttpClient): AthenaGatewayClient {
            require(spec.url.startsWith("https://")) { "the gateway URL must use https" }
            return AthenaGatewayClient(spec.url, spec.key.toHex(), http.noDowngrade())
        }
    }
}

internal fun GatewayException.toDownloadFailure(): Exception = when {
    this is GatewayException.Rejected && (reason.startsWith("destination not allowed") || reason.startsWith("port not allowed")) ->
        DownloadNotAllowed(reason)
    else -> DownloadFailed(publishText(), this)
}
