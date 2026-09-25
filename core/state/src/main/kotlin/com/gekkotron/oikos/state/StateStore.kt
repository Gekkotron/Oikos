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
    private val restoredTopics = mutableSetOf<String>() // last payload came from the entity cache
    private val confirmTopics = mutableMapOf<String, Set<String>>()
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
        confirmTopics.keys.retainAll(tracked.keys - fresh)

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

    /**
     * Drops cached payloads for topics that are no longer subscribed. Call after a subscription set is applied:
     * until then, a removed-and-re-added entity must still get its last payload (no broker replay happens).
     */
    fun retainPayloads(subscribed: Set<String>): Unit = synchronized(lock) {
        lastPayload.keys.retainAll(subscribed)
        restoredTopics.retainAll(subscribed)
    }

    /** Returns the ids whose value was confirmed by this message. A [restored] message comes from the entity cache. */
    fun onMessage(topic: String, payload: String, restored: Boolean = false): Set<String> = synchronized(lock) {
        val ids = topicIndex[topic] ?: return emptySet()
        lastPayload[topic] = payload
        if (restored) restoredTopics += topic else restoredTopics -= topic
        val next = _states.value.toMutableMap()
        val confirmedIds = ids.filterTo(mutableSetOf()) { apply(it, topic, payload, next) }
        _states.value = next
        confirmedIds
    }

    /** The broker has replayed what it holds: nothing is stale any more. */
    fun settle(): Unit = synchronized(lock) {
        restoredTopics.clear()
        _states.value = _states.value.mapValues { (_, s) -> if (s.stale) s.copy(stale = false) else s }
    }

    /**
     * Shows [predicted] until confirmed. With [confirmBy], only state messages on those topics confirm;
     * null means any state message of the entity does.
     */
    fun markPending(uniqueId: String, predicted: StateValue, confirmBy: Set<String>? = null): Unit = synchronized(lock) {
        if (confirmBy == null) confirmTopics.remove(uniqueId) else confirmTopics[uniqueId] = confirmBy
        update(uniqueId) { it.copy(value = predicted, pending = true) }
    }

    /** For optimistic commands: the prediction (or [final]) becomes the confirmed value. */
    fun confirmPending(uniqueId: String, final: StateValue? = null): Unit = synchronized(lock) {
        val s = _states.value[uniqueId]?.takeIf { it.pending } ?: return
        val value = final ?: s.value
        confirmed[uniqueId] = value
        confirmTopics.remove(uniqueId)
        _states.value = _states.value + (uniqueId to s.copy(value = value, pending = false, lastUpdate = now()))
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
                    val confirmsCommand = !s.pending || confirmTopics[id]?.contains(topic) != false
                    if (confirmsCommand) {
                        confirmTopics.remove(id)
                        s = s.copy(value = d.value, pending = false, lastUpdate = now())
                        confirmedNow = true
                    }
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
        states[id] = s.copy(stale = topic in restoredTopics)
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
