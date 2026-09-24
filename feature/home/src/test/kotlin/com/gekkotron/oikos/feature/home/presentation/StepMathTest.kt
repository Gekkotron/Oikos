package com.gekkotron.oikos.feature.home.presentation

import org.junit.Test
import kotlin.test.assertEquals

class StepMathTest {
    @Test
    fun `setpoint steps are clamped and free of float drift`() {
        assertEquals(21.5, StepMath.stepTemperature(21.0, 0.5, 16.0, 30.0))
        assertEquals(30.0, StepMath.stepTemperature(29.8, 0.5, 16.0, 30.0))
        assertEquals(16.0, StepMath.stepTemperature(16.2, -0.5, 16.0, 30.0))
        assertEquals(20.3, StepMath.stepTemperature(20.2, 0.1, 7.0, 35.0))
    }

    @Test
    fun `slider values snap to the step grid from min`() {
        assertEquals(58.0, StepMath.snap(57.6, 40.0, 1.0))
        assertEquals(45.0, StepMath.snap(46.0, 40.0, 5.0))
        assertEquals(0.3, StepMath.snap(0.29, 0.0, 0.1))
    }
}
