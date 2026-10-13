package com.gekkotron.oikos.presence

/** Fetches a current GPS fix. Injected into [PresenceRefreshWorker] so tests can run without Play Services. */
fun interface CurrentLocationSource {
    /** `null` when no fix is available (missing permission, Play Services unavailable, GPS off, timeout). */
    suspend fun current(): Pair<Double, Double>?
}
