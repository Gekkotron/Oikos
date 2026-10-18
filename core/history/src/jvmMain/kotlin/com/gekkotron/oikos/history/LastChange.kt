package com.gekkotron.oikos.history

import java.time.Duration
import java.time.Instant

/** When a binary series last flipped (last-change spec §4.2). */
sealed interface LastChange {
    data class At(val at: Instant) : LastChange

    /** Recorded, but no flip within [window]. */
    data class OlderThan(val window: Duration) : LastChange

    data object NotRecorded : LastChange
}
