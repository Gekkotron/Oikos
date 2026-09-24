package com.gekkotron.oikos.model

sealed interface StateValue {
    /** Nothing received yet. UI shows "—". */
    data object Unknown : StateValue

    /** The entity's template is outside the supported subset. UI shows "—". */
    data object Unsupported : StateValue

    data class OnOff(val on: Boolean) : StateValue

    /** [brightness] is normalised to 0..255. */
    data class Light(val on: Boolean, val brightness: Int? = null) : StateValue

    data class Numeric(val value: Double) : StateValue

    data class Text(val value: String) : StateValue

    /** [position] is normalised to 0 (closed) .. 100 (open). */
    data class Cover(val state: CoverState, val position: Int? = null) : StateValue

    data class Climate(val mode: String?, val target: Double?, val current: Double?) : StateValue

    data class Alarm(val state: AlarmState) : StateValue
}

enum class CoverState { OPEN, CLOSED, OPENING, CLOSING, STOPPED, UNKNOWN }

enum class AlarmState {
    DISARMED, ARMED_HOME, ARMED_AWAY, ARMED_NIGHT, ARMED_VACATION, ARMED_CUSTOM_BYPASS,
    PENDING, ARMING, DISARMING, TRIGGERED,
}

enum class AlarmMode { HOME, AWAY, NIGHT, VACATION, CUSTOM_BYPASS }
