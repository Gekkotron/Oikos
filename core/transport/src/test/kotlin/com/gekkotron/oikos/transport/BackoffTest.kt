package com.gekkotron.oikos.transport

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun `without jitter the delay doubles up to the cap`() {
        val b = Backoff(jitter = 0.0)
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }, (1..7).map(b::delayFor))
    }

    @Test
    fun `jitter stays within plus or minus 20 percent`() {
        val b = Backoff(random = Random(42))
        repeat(200) {
            val first = b.delayFor(1)
            assertTrue(first in 800.milliseconds..1200.milliseconds, "attempt 1: $first")
            val capped = b.delayFor(10)
            assertTrue(capped in 24.seconds..36.seconds, "attempt 10: $capped")
        }
    }

    @Test
    fun `a huge attempt number does not overflow`() {
        assertEquals(30.seconds, Backoff(jitter = 0.0).delayFor(10_000))
    }

    @Test
    fun `attempts start at 1`() {
        assertFailsWith<IllegalArgumentException> { Backoff().delayFor(0) }
    }
}
