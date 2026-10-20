package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.SwitchType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Device sheet spec §5.3: how a Home tap turns into a command per switch type. Pure. */
class CardActionTest {
    @Test
    fun `door tap always sends open and asks only with security ask`() {
        assertEquals(CardTap.Send(Command.TurnOn()), cardTap(SwitchType.DOOR, on = false, ask = false))
        assertEquals(
            CardTap.Confirm(Command.TurnOn(), R.string.card_confirm_open),
            cardTap(SwitchType.DOOR, on = false, ask = true),
        )
        // The relay reads "on" meanwhile — the user tap still means Open.
        assertEquals(CardTap.Send(Command.TurnOn()), cardTap(SwitchType.DOOR, on = true, ask = false))
    }

    @Test
    fun `siren tap toggles`() {
        assertEquals(CardTap.Send(Command.TurnOff), cardTap(SwitchType.SIREN, on = true, ask = true))
        assertEquals(
            CardTap.Confirm(Command.TurnOn(), R.string.card_confirm_ring),
            cardTap(SwitchType.SIREN, on = false, ask = true),
        )
        assertEquals(CardTap.Send(Command.TurnOn()), cardTap(SwitchType.SIREN, on = false, ask = false))
    }

    @Test
    fun `chime always plays and never asks`() {
        assertEquals(CardTap.Send(Command.TurnOn()), cardTap(SwitchType.CHIME, on = false, ask = true))
        assertEquals(CardTap.Send(Command.TurnOn()), cardTap(SwitchType.CHIME, on = true, ask = false))
    }

    @Test
    fun `plain switch and plug keep the toggle`() {
        assertEquals(CardTap.Send(Command.TurnOff), cardTap(SwitchType.SWITCH, on = true, ask = true))
        assertEquals(CardTap.Send(Command.TurnOn()), cardTap(SwitchType.PLUG, on = false, ask = true))
        assertEquals(CardTap.Send(Command.TurnOff), cardTap(null, on = true, ask = true))
    }

    @Test
    fun `light type is a no-op from the Home tap path`() {
        // Home promotes a Light-typed switch to Kind.LIGHT before the tap handler runs; this guards the branch.
        assertEquals(CardTap.None, cardTap(SwitchType.LIGHT, on = false, ask = true))
    }
}
