package com.gekkotron.oikos

/**
 * Decides whether returning to the app should ask App Distribution for a newer tester build: never while a
 * check or update is in flight, at most every [minGapMillis], and not for [afterCancelMillis] once the tester
 * cancelled the update dialog. In-process only, so a cold start always checks.
 */
class UpdateCheckGate(
    private val minGapMillis: Long = 15 * 60_000L,
    private val afterCancelMillis: Long = 6 * 60 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var inFlight = false
    private var lastCheck: Long? = null
    private var cancelledAt: Long? = null

    /** True means "check now", and the check counts as started. */
    @Synchronized
    fun shouldCheck(): Boolean {
        val t = now()
        if (inFlight) return false
        if (lastCheck?.let { t - it < minGapMillis } == true) return false
        if (cancelledAt?.let { t - it < afterCancelMillis } == true) return false
        lastCheck = t
        inFlight = true
        return true
    }

    /** The check, and any update it started, is over; [cancelled] when the tester declined the update. */
    @Synchronized
    fun finished(cancelled: Boolean) {
        inFlight = false
        if (cancelled) cancelledAt = now()
    }
}
