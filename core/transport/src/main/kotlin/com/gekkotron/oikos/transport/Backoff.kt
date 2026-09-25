package com.gekkotron.oikos.transport

import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Reconnect delay: [initial] doubled per attempt up to [max], then ±[jitter] (parent spec §4.5 step 6). */
class Backoff(
    private val initial: Duration = 1.seconds,
    private val max: Duration = 30.seconds,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** The delay before reconnect [attempt], counting from 1. */
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempts start at 1" }
        val doubled = initial * (1L shl min(attempt - 1, 20)).toDouble()
        val capped = minOf(doubled, max)
        if (jitter == 0.0) return capped
        return capped * (1 + random.nextDouble(-jitter, jitter))
    }
}
