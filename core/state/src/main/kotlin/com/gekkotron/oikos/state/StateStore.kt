package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.kinds.Decoded
import com.gekkotron.oikos.state.kinds.KindMapper
import com.gekkotron.oikos.state.kinds.KindMappers
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.normalize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Maps each incoming (topic, payload) onto every entity bound to that topic (spec §4.4). Thread-safe. */
class StateStore(
    private val now: () -> Instant = Instant::now,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
) {
    private class Tracked(
        val bindings: MqttBindings,
        val mapper: KindMapper,
        val availability: AvailabilityTracker?,
    ) {
        val stateTopics: Set<String> = mapper.stateTopics(bindings)
        val attributesTopic: String? = bindings.str("json_attributes_topic")
        val allTopics: Set<String> = stateTopics + availability?.spec?.topics.orEmpty() + setOfNotNull(attributesTopic)
    }

    private val lock = Any()
    private var tracked: Map<String, Tracked> = emptyMap()
    private var topicIndex: Map<String, List<String>> = emptyMap()
    private val confirmed = mutableMapOf<String, StateValue>()
    private val lastPayload = mutableMapOf<String, String>()
    private val reported = mutableSetOf<String>()

    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states.asStateFlow()

    fun setEntities(entities: Collection<Entity>): Unit = synchronized(lock) {
        val old = tracked
        val fresh = mutableSetOf<String>()
        tracked = entities.mapNotNull { e ->
            val b = e.bindings as? MqttBindings ?: return@mapNotNull null
            val kept = old[e.uniqueId]?.takeIf { it.bindings == b }
            val t = kept ?: Tracked(b, KindMappers.forKind(e.kind), AvailabilitySpec.of(b)?.let(::AvailabilityTracker))
                .also { fresh += e.uniqueId }
            e.uniqueId to t
        }.toMap()

        topicIndex = buildMap<String, MutableList<String>> {
            for ((id, t) in tracked) for (topic in t.allTopics) getOrPut(topic) { mutableListOf() } += id
        }
        confirmed.keys.retainAll(tracked.keys - fresh)
        reported.removeAll { it.substringBeforeLast(':') !in tracked.keys - fresh }
        lastPayload.keys.retainAll(topicIndex.keys)

        val current = _states.value
        val next = tracked.mapValuesTo(mutableMapOf()) { (id, t) ->
            if (id in fresh) EntityState(id, StateValue.Unknown, available = t.availability == null)
            else current[id] ?: EntityState(id, StateValue.Unknown, available = t.availability == null)
        }
        for (id in fresh) {
            val t = tracked.getValue(id)
            for (topic in t.allTopics) lastPayload[topic]?.let { apply(id, topic, it, next) }
        }
        _states.value = next
    }

    fun subscriptionTopics(): Set<String> = synchronized(lock) { topicIndex.keys.toSet() }

    /** Returns the ids whose value was confirmed by this message. */
    fun onMessage(topic: String, payload: String): Set<String> = synchronized(lock) {
        val ids = topicIndex[topic] ?: return emptySet()
        lastPayload[topic] = payload
        val next = _states.value.toMutableMap()
        val confirmedIds = ids.filterTo(mutableSetOf()) { apply(it, topic, payload, next) }
        _states.value = next
        confirmedIds
    }

    fun markPending(uniqueId: String, predicted: StateValue): Unit = update(uniqueId) {
        it.copy(value = predicted, pending = true)
    }

    /** For entities without state feedback: the prediction becomes the confirmed value. */
    fun confirmPending(uniqueId: String): Unit = synchronized(lock) {
        val s = _states.value[uniqueId]?.takeIf { it.pending } ?: return
        confirmed[uniqueId] = s.value
        _states.value = _states.value + (uniqueId to s.copy(pending = false, lastUpdate = now()))
    }

    /** Restores the last confirmed value. Returns true if the entity was pending. */
    fun revert(uniqueId: String): Boolean = synchronized(lock) {
        val s = _states.value[uniqueId]?.takeIf { it.pending } ?: return false
        _states.value = _states.value + (uniqueId to s.copy(value = confirmed[uniqueId] ?: StateValue.Unknown, pending = false))
        true
    }

    fun isPending(uniqueId: String): Boolean = _states.value[uniqueId]?.pending == true

    private fun update(uniqueId: String, change: (EntityState) -> EntityState): Unit = synchronized(lock) {
        val s = _states.value[uniqueId] ?: return
        _states.value = _states.value + (uniqueId to change(s))
    }

    /** Applies one message to one entity inside [states]. Returns true if its value was confirmed. */
    private fun apply(id: String, topic: String, payload: String, states: MutableMap<String, EntityState>): Boolean {
        val t = tracked[id] ?: return false
        var s = states[id] ?: return false
        var confirmedNow = false
        if (topic in t.stateTopics) {
            when (val d = t.mapper.decode(t.bindings, topic, payload, confirmed[id] ?: StateValue.Unknown)) {
                is Decoded.State -> {
                    confirmed[id] = d.value
                    s = s.copy(value = d.value, pending = false, lastUpdate = now())
                    confirmedNow = true
                }
                is Decoded.Unsupported -> {
                    reportOnce(id, "unsupported", "template unsupported on $topic: ${d.reason}")
                    if (!s.pending) s = s.copy(value = StateValue.Unsupported)
                }
                is Decoded.Error -> reportOnce(id, "error", "cannot read $topic: ${d.reason}")
                Decoded.Ignored -> Unit
            }
        }
        t.availability?.let { if (topic in it.spec.topics && it.onMessage(topic, payload)) s = s.copy(available = it.available) }
        if (topic == t.attributesTopic) parseAttributes(payload)?.let { s = s.copy(attributes = it) }
        states[id] = s
        return confirmedNow
    }

    private fun parseAttributes(payload: String): Map<String, Any?>? = try {
        (Json.parseToJsonElement(payload) as? JsonObject)?.mapValues { normalize(it.value) }
    } catch (e: SerializationException) {
        null
    }

    private fun reportOnce(id: String, kind: String, message: String) {
        if (reported.add("$id:$kind")) diagnostics.report(Diagnostic("state", "$id: $message"))
    }
}
