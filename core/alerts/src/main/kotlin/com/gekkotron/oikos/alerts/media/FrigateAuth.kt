package com.gekkotron.oikos.alerts.media

import com.gekkotron.oikos.data.FrigateConfig
import com.gekkotron.oikos.transport.HttpPostResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Frigate's `frigate_token` cookie for media on the configured Frigate origin (Frigate auth spec §4). The token is
 * kept in memory only; logins are serialised so concurrent fetches share one.
 */
class FrigateAuth(
    private val config: suspend () -> FrigateConfig?,
    private val post: suspend (url: String, json: String) -> HttpPostResult,
) : MediaAuth {
    private val mutex = Mutex()

    /** The config the token was obtained with; guarded by [mutex]. */
    private var cached: Pair<FrigateConfig, String>? = null

    override suspend fun headersFor(url: String): AuthHeaders {
        val c = configFor(url) ?: return AuthHeaders.None
        return mutex.withLock { cached?.takeIf { it.first == c }?.let { ready(it.second) } ?: login(c) }
    }

    override suspend fun renew(url: String, rejected: Map<String, String>): AuthHeaders {
        val c = configFor(url) ?: return AuthHeaders.None
        return mutex.withLock {
            val current = cached?.takeIf { it.first == c }?.let { ready(it.second) }
            if (current != null && current.headers != rejected) current else login(c)
        }
    }

    private suspend fun configFor(url: String): FrigateConfig? = config()?.takeIf { it.hasLogin && sameOrigin(it.url, url) }

    /** Runs under [mutex]. */
    private suspend fun login(c: FrigateConfig): AuthHeaders {
        cached = null
        val body = buildJsonObject { put("user", c.username); put("password", c.password) }.toString()
        return when (val r = post("${c.url}/api/login", body)) {
            is HttpPostResult.Response -> when (r.status) {
                in 200..299 -> tokenIn(r.headers["set-cookie"])?.let { token -> cached = c to token; ready(token) }
                    ?: AuthHeaders.Failed(MediaFailure.Unavailable("no token in Frigate's login answer (the gateway needs v1.4)"))
                400, 401, 403 -> AuthHeaders.Failed(MediaFailure.AuthFailed)
                429 -> AuthHeaders.Failed(MediaFailure.Unavailable("too many Frigate login attempts"))
                else -> AuthHeaders.Failed(MediaFailure.Unavailable("Frigate login http ${r.status}"))
            }
            HttpPostResult.NotAllowed -> AuthHeaders.Failed(MediaFailure.NotAllowed)
            is HttpPostResult.Unavailable -> AuthHeaders.Failed(MediaFailure.Unavailable(r.reason))
        }
    }

    companion object {
        const val COOKIE = "frigate_token"
        private val TOKEN = Regex("""(?:^|[\n,]\s*)$COOKIE=([^;,\s]+)""")

        /** The token in a `Set-Cookie` value; several cookies may be joined with `\n` (gateway, OkHttp) or `, `. */
        internal fun tokenIn(setCookie: String?): String? = setCookie?.let { TOKEN.find(it)?.groupValues?.get(1) }

        private fun ready(token: String) = AuthHeaders.Ready(mapOf("Cookie" to "$COOKIE=$token"))

        /** Same scheme, host and port, default ports filled in. */
        internal fun sameOrigin(base: String, url: String): Boolean {
            val a = base.toHttpUrlOrNull() ?: return false
            val b = url.toHttpUrlOrNull() ?: return false
            return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
        }
    }
}
