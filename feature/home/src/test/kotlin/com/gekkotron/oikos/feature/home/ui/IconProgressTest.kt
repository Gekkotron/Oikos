package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.model.CoverState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class IconProgressTest {
    @get:Rule
    val compose = createComposeRule()

    private var spec by mutableStateOf<IconSpec>(IconSpec.Swing(SwingShape.DOOR, false))
    private var animate by mutableStateOf(true)
    private var drawn = -1f

    private fun start(initial: IconSpec) {
        change(initial)
        compose.mainClock.autoAdvance = false
        compose.setContent { drawn = rememberIconProgress(spec, animate).value }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    /** With autoAdvance off, a write from the test thread reaches the composition only once applied. */
    private fun change(next: IconSpec) {
        spec = next
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `an open door draws open at once on first composition`() {
        start(IconSpec.Swing(SwingShape.DOOR, true))
        assertEquals(1f, drawn)
    }

    @Test
    fun `a shutter draws its position at once on first composition`() {
        start(IconSpec.Shutter(0.6f, null))
        assertEquals(0.6f, drawn)
    }

    @Test
    fun `a door swings open in 500 ms`() {
        start(IconSpec.Swing(SwingShape.DOOR, false))
        change(IconSpec.Swing(SwingShape.DOOR, true))
        advance(250)
        assertTrue(drawn > 0.1f && drawn < 0.99f, "mid-swing at 250 ms, was $drawn")
        advance(300)
        assertEquals(1f, drawn)
    }

    @Test
    fun `a door that flaps back mid-swing turns around from where it is`() {
        start(IconSpec.Swing(SwingShape.DOOR, false))
        change(IconSpec.Swing(SwingShape.DOOR, true))
        advance(200)
        val mid = drawn
        change(IconSpec.Swing(SwingShape.DOOR, false))
        advance(100) // the change lands a frame after the effect runs
        assertTrue(drawn > 0f && drawn < mid, "turns around without jumping: $mid then $drawn")
        advance(600)
        assertEquals(0f, drawn)
    }

    @Test
    fun `a moving shutter drifts at a full travel per 8 s, and a position update retargets`() {
        start(IconSpec.Shutter(0f, null))
        change(IconSpec.Shutter(0f, CoverState.OPENING))
        advance(2000)
        assertEquals(0.25f, drawn, 0.03f)
        change(IconSpec.Shutter(0.5f, CoverState.OPENING))
        advance(600)
        assertEquals(0.5f, drawn, 0.03f)
        advance(4000)
        assertEquals(1f, drawn, 0.03f)
    }

    @Test
    fun `opening a shutter that is already open neither stalls nor crashes`() {
        start(IconSpec.Shutter(1f, null))
        change(IconSpec.Shutter(1f, CoverState.OPENING))
        advance(100)
        assertEquals(1f, drawn)
    }

    @Test
    fun `with animations off every change snaps and nothing drifts`() {
        animate = false
        Snapshot.sendApplyNotifications()
        start(IconSpec.Shutter(0f, null))
        change(IconSpec.Shutter(0.7f, null))
        advance(48) // effect, snap, then the recomposition that reads it
        assertEquals(0.7f, drawn)
        change(IconSpec.Shutter(null, CoverState.OPENING))
        advance(4000)
        assertEquals(0.7f, drawn)
    }

    @Test
    fun `the first known state after a cold start draws at once, without a swing`() {
        start(IconSpec.Swing(SwingShape.DOOR, null)) // cached card, no state yet
        change(IconSpec.Swing(SwingShape.DOOR, true))
        advance(48)
        assertEquals(1f, drawn)
    }

    @Test
    fun `the first known position after a cold start draws at once, without rolling`() {
        start(IconSpec.Shutter(null, null))
        change(IconSpec.Shutter(0.8f, null))
        advance(48)
        assertEquals(0.8f, drawn)
    }
}
