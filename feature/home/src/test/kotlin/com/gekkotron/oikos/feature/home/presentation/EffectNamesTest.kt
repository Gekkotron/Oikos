package com.gekkotron.oikos.feature.home.presentation

import org.junit.Test
import kotlin.test.assertEquals

class EffectNamesTest {
    @Test
    fun `effect names are tidied, not translated`() {
        assertEquals("Fire 1d", EffectNames.label("fire_1d"))
        assertEquals("Plasma 2d", EffectNames.label("plasma_2d"))
        assertEquals("Rainbow", EffectNames.label("rainbow"))
        assertEquals("Color Loop", EffectNames.label("Color Loop"))
        assertEquals("", EffectNames.label(""))
    }
}
