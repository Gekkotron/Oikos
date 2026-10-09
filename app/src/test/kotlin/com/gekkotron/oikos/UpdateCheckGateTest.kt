package com.gekkotron.oikos

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckGateTest {
    private var now = 1_000_000L
    private val gate = UpdateCheckGate { now }
    private val minute = 60_000L

    @Test
    fun `the first return to the app checks`() {
        assertTrue(gate.shouldCheck())
    }

    @Test
    fun `no second check while one is in flight`() {
        assertTrue(gate.shouldCheck())
        now += 60 * minute
        assertFalse(gate.shouldCheck())
    }

    @Test
    fun `checks are at least fifteen minutes apart`() {
        assertTrue(gate.shouldCheck())
        gate.finished(cancelled = false)
        now += 14 * minute
        assertFalse(gate.shouldCheck())
        now += 1 * minute
        assertTrue(gate.shouldCheck())
    }

    @Test
    fun `a cancelled update is not offered again for six hours`() {
        assertTrue(gate.shouldCheck())
        gate.finished(cancelled = true)
        now += 6 * 60 * minute - 1
        assertFalse(gate.shouldCheck())
        now += 1
        assertTrue(gate.shouldCheck())
    }
}
