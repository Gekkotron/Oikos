package com.gekkotron.oikos.feature.home.presentation

import androidx.annotation.StringRes
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.SwitchType

/** What a tap on a Home card does when the card's kind is Switch (device sheet spec §5.3). Pure. */
sealed interface CardTap {
    data class Send(val command: Command) : CardTap

    /** Ask the user first — the UI shows [labelResId] with the entity's name as the format argument. */
    data class Confirm(val command: Command, @StringRes val labelResId: Int) : CardTap

    /** Nothing to do — Light arrives as `Kind.LIGHT` on Home, so this guards an unreachable branch. */
    data object None : CardTap
}

/**
 * The tap rule per switch type (device sheet spec §5.3):
 *  - Switch, Plug, null: today's toggle.
 *  - Door opener: always `TurnOn`; asks first when [ask] is on.
 *  - Siren: `on` turns off without asking; `off` turns on, asking first when [ask] is on.
 *  - Chime: fires `TurnOn` without ever asking.
 *  - Light: no-op (promoted to `Kind.LIGHT` upstream).
 */
fun cardTap(type: SwitchType?, on: Boolean, ask: Boolean): CardTap = when (type) {
    null, SwitchType.SWITCH, SwitchType.PLUG ->
        CardTap.Send(if (on) Command.TurnOff else Command.TurnOn())
    SwitchType.DOOR ->
        if (ask) CardTap.Confirm(Command.TurnOn(), R.string.card_confirm_open)
        else CardTap.Send(Command.TurnOn())
    SwitchType.SIREN -> when {
        on -> CardTap.Send(Command.TurnOff)
        ask -> CardTap.Confirm(Command.TurnOn(), R.string.card_confirm_ring)
        else -> CardTap.Send(Command.TurnOn())
    }
    SwitchType.CHIME -> CardTap.Send(Command.TurnOn())
    SwitchType.LIGHT -> CardTap.None
}
