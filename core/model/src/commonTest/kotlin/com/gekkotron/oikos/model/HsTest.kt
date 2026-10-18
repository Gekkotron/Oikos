package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class HsTest {
    @Test
    fun `hue and saturation are range checked`() {
        Hs(0.0, 0.0)
        Hs(359.9, 1.0)
        assertFailsWith<IllegalArgumentException> { Hs(360.0, 0.5) }
        assertFailsWith<IllegalArgumentException> { Hs(-1.0, 0.5) }
        assertFailsWith<IllegalArgumentException> { Hs(10.0, 1.1) }
    }
}
