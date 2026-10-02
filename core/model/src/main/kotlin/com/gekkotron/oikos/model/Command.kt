package com.gekkotron.oikos.model

data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB channels must be 0..255" }
    }
}

sealed interface Command {
    /** [brightness] is 0..255; [color] carries no brightness; [effect] is one of the light's `effects`. */
    data class TurnOn(val brightness: Int? = null, val color: Hs? = null, val effect: String? = null) : Command {
        init {
            require(brightness == null || brightness in 0..255) { "brightness must be 0..255" }
        }
    }
    data object TurnOff : Command
    data object Toggle : Command
    data class SetPosition(val position: Int) : Command {
        init {
            require(position in 0..100) { "position must be 0..100" }
        }
    }
    data object Open : Command
    data object Close : Command
    data object Stop : Command
    data class SetTemperature(val temperature: Double) : Command
    data class SetHvacMode(val mode: String) : Command
    data class SetNumber(val value: Double) : Command
    data class SelectOption(val option: String) : Command
    data object Press : Command
    data class Arm(val mode: AlarmMode, val code: String? = null) : Command
    data class Disarm(val code: String? = null) : Command
}

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Rejected(val reason: String) : CommandResult
}

/** Emitted when an optimistic command was not confirmed in time. */
data class CommandFailure(val uniqueId: String, val command: Command, val reason: String)
