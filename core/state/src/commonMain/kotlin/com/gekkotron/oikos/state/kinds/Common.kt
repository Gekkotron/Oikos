package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.TemplateResult
import com.gekkotron.oikos.state.template.Templates
import com.gekkotron.oikos.state.template.asPayloadString

/** Renders [templateKey] over [payload], or passes the raw payload through when no template is set. */
internal fun renderIncoming(b: MqttBindings, templateKey: String, payload: String): TemplateResult {
    val template = b.str(templateKey) ?: return TemplateResult.Value(payload)
    return Templates.renderValue(template, payload)
}

internal inline fun TemplateResult.decodeWith(block: (Any?) -> Decoded): Decoded = when (this) {
    is TemplateResult.Value -> block(value)
    is TemplateResult.Unsupported -> Decoded.Unsupported(reason)
    is TemplateResult.Error -> Decoded.Error(reason)
}

/** Builds one publish to [topicKey], rendering [templateKey] (with `value` = [value] + [extraVars]) when set. */
internal fun publish(
    b: MqttBindings,
    topicKey: String,
    templateKey: String?,
    value: String,
    extraVars: Map<String, Any?> = emptyMap(),
): Encoded {
    val topic = b.str(topicKey) ?: return Encoded.NotSupported("no $topicKey configured")
    val template = templateKey?.let { b.str(it) }
    val payload = if (template == null) {
        value
    } else {
        when (val r = Templates.render(template, mapOf("value" to value) + extraVars)) {
            is TemplateResult.Value -> r.value.asPayloadString() ?: ""
            is TemplateResult.Unsupported -> return Encoded.NotSupported("$templateKey unsupported: ${r.reason}")
            is TemplateResult.Error -> return Encoded.NotSupported("$templateKey failed: ${r.reason}")
        }
    }
    return Encoded.Publish(listOf(outgoing(b, topic, payload)))
}

internal fun outgoing(b: MqttBindings, topic: String, payload: String) =
    Outgoing(topic, payload, retain = b.bool("retain", false), qos = b.int("qos", 0))

/** On/off target of a TurnOn/TurnOff/Toggle command, or null for other commands. */
internal fun targetOn(command: Command, previous: StateValue): Boolean? = when (command) {
    is Command.TurnOn -> true
    Command.TurnOff -> false
    Command.Toggle -> !previous.isOn()
    else -> null
}

internal fun StateValue.isOn(): Boolean = when (this) {
    is StateValue.OnOff -> on
    is StateValue.Light -> on
    else -> false
}

internal fun onOffFrom(rendered: String, on: String, off: String): Decoded = when (rendered) {
    on -> Decoded.State(StateValue.OnOff(true))
    off -> Decoded.State(StateValue.OnOff(false))
    else -> Decoded.Ignored
}

/**
 * Accumulates several templated parts of one message (e.g. cover state + position on one topic).
 * Any successful part wins; otherwise the first problem is reported.
 */
internal class PartialDecode {
    private var changed = false
    private var problem: Decoded? = null

    fun part(result: TemplateResult, apply: (Any?) -> Boolean) {
        when (result) {
            is TemplateResult.Value -> if (apply(result.value)) changed = true
            is TemplateResult.Unsupported -> problem = problem ?: Decoded.Unsupported(result.reason)
            is TemplateResult.Error -> problem = problem ?: Decoded.Error(result.reason)
        }
    }

    fun result(value: StateValue): Decoded = if (changed) Decoded.State(value) else problem ?: Decoded.Ignored
}
