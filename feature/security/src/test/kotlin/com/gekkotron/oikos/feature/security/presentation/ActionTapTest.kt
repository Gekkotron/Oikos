package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionTapTest {
    private fun row(kind: Kind, on: Boolean?, ask: Boolean) = ActionRow("x", "X", null, kind, on, available = true, stale = false, ask = ask)

    @Test
    fun `turning a switch off never asks`() {
        assertEquals(ActionTap(Command.TurnOff, confirm = false), row(Kind.SWITCH, on = true, ask = true).tap())
    }

    @Test
    fun `turning a switch on asks only when set`() {
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), row(Kind.SWITCH, on = false, ask = true).tap())
        assertEquals(ActionTap(Command.TurnOn(), confirm = false), row(Kind.SWITCH, on = false, ask = false).tap())
        // No state yet: treat as off, so the user can still ring it.
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), row(Kind.SWITCH, on = null, ask = true).tap())
    }

    @Test
    fun `pressing a button asks only when set`() {
        assertEquals(ActionTap(Command.Press, confirm = true), row(Kind.BUTTON, on = null, ask = true).tap())
        assertEquals(ActionTap(Command.Press, confirm = false), row(Kind.BUTTON, on = null, ask = false).tap())
    }
}
