package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CommandTest {
    @Test
    fun `position must be 0 to 100`() {
        Command.SetPosition(0); Command.SetPosition(100)
        assertFailsWith<IllegalArgumentException> { Command.SetPosition(101) }
        assertFailsWith<IllegalArgumentException> { Command.SetPosition(-1) }
    }

    @Test
    fun `brightness must be 0 to 255`() {
        Command.TurnOn(brightness = 255)
        assertFailsWith<IllegalArgumentException> { Command.TurnOn(brightness = 256) }
    }

    @Test
    fun `rgb channels must be 0 to 255`() {
        assertFailsWith<IllegalArgumentException> { Rgb(0, 256, 0) }
    }
}
