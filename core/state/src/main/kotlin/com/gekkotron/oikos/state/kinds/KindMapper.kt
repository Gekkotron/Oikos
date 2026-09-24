package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

sealed interface Decoded {
    data class State(val value: StateValue) : Decoded
    data object Ignored : Decoded
    data class Unsupported(val reason: String) : Decoded
    data class Error(val reason: String) : Decoded
}

data class Outgoing(val topic: String, val payload: String, val retain: Boolean, val qos: Int)

sealed interface Encoded {
    data class Publish(val messages: List<Outgoing>) : Encoded
    data class NotSupported(val reason: String) : Encoded
}

/** HA payload semantics for one component (spec §4.4). One object per kind, in kinds/. */
interface KindMapper {
    /** Topics whose messages feed [decode]. Empty = no state feedback (optimistic entity). */
    fun stateTopics(b: MqttBindings): Set<String>

    /** [previous] is the last confirmed value; mappers merge partial updates onto it. */
    fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded

    fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded =
        Encoded.NotSupported("read-only entity")

    /** Optimistic value shown while waiting for confirmation; null = nothing to predict. */
    fun predict(command: Command, previous: StateValue): StateValue? = null
}

object KindMappers {
    fun forKind(kind: Kind): KindMapper = when (kind) {
        Kind.LIGHT -> LightMapper
        Kind.SWITCH -> SwitchMapper
        Kind.COVER -> CoverMapper
        Kind.CLIMATE -> ClimateMapper
        Kind.SENSOR -> SensorMapper
        Kind.BINARY_SENSOR -> BinarySensorMapper
        Kind.ALARM_PANEL -> AlarmPanelMapper
        Kind.NUMBER -> NumberMapper
        Kind.SELECT -> SelectMapper
        Kind.BUTTON -> ButtonMapper
    }
}
