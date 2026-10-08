package com.gekkotron.oikos.alerts.media

/** What a media URL needs before it is fetched (Frigate auth spec §4). */
sealed interface AuthHeaders {
    /** No login applies: another host, or no credentials. */
    data object None : AuthHeaders

    data class Ready(val headers: Map<String, String>) : AuthHeaders

    data class Failed(val failure: MediaFailure) : AuthHeaders
}

/** Adds login headers to media fetches; [MediaFetcher] calls [renew] at most once per fetch, after a 401. */
interface MediaAuth {
    suspend fun headersFor(url: String): AuthHeaders

    /** [rejected] got a 401: log in again, unless another caller already replaced that token. */
    suspend fun renew(url: String, rejected: Map<String, String>): AuthHeaders
}
