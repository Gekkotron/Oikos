package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.AreasDocument
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.OikosJson
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.UiOverrides
import com.gekkotron.oikos.source.discovery.DiscoveredEntity
import com.gekkotron.oikos.source.discovery.DiscoveryParser
import com.gekkotron.oikos.source.discovery.DiscoveryResult
import com.gekkotron.oikos.state.CommandTracker
import com.gekkotron.oikos.state.StateStore
import com.gekkotron.oikos.state.kinds.Encoded
import com.gekkotron.oikos.state.kinds.KindMappers
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.isValidPublishTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Home Assistant MQTT Discovery + `oikos/ui/…` overrides (spec §4.3). */
@OptIn(FlowPreview::class)
class MqttDiscoverySource(
    private val transport: MqttTransport,
    scope: CoroutineScope,
    private val prefix: String = DEFAULT_PREFIX,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    commandTimeout: Duration = 5.seconds,
    now: () -> Instant = Instant::now,
) : EntitySource {
    private val store = StateStore(now, diagnostics)
    private val tracker = CommandTracker(store, scope, commandTimeout)

    private val lock = Any()
    private val discovered = linkedMapOf<String, List<DiscoveredEntity>>() // config topic -> entities
    private val overrides = mutableMapOf<String, UiOverride>()
    private var areasDocument = AreasDocument()

    private val _entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val entities: StateFlow<Map<String, Entity>> = _entities.asStateFlow()
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()
    private val _areas = MutableStateFlow<List<Area>>(emptyList())
    override val areas: StateFlow<List<Area>> = _areas.asStateFlow()
    override val commandFailures: SharedFlow<CommandFailure> = tracker.failures
    override val link: StateFlow<LinkState> = transport.link

    private val baseFilters = setOf("$prefix/#", "$UI_PREFIX#")
    private val stateTopics = MutableStateFlow<Set<String>>(emptySet())

    private class Snapshot(val entities: Map<String, Entity>, val devices: Map<String, Device>, val areas: List<Area>)

    init {
        // Undispatched so the collector is subscribed before any subscription can replay retained messages.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.messages().collect { handle(it) } }
        scope.launch {
            transport.setSubscriptions(baseFilters)
            var applied = emptySet<String>()
            stateTopics.debounce(SUBSCRIPTION_DEBOUNCE).collect { topics ->
                if (topics != applied) {
                    applied = topics
                    transport.setSubscriptions(baseFilters + topics)
                    store.retainPayloads(topics)
                }
            }
        }
    }

    override fun states(): Flow<Map<String, EntityState>> = store.states

    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        val entity = _entities.value[uniqueId] ?: return CommandResult.Rejected("unknown entity $uniqueId")
        val bindings = entity.bindings as? MqttBindings ?: return CommandResult.Rejected("not an MQTT entity")
        val state = store.states.value[uniqueId]
        if (state != null && !state.available) return CommandResult.Rejected("unavailable")
        val mapper = KindMappers.forKind(entity.kind)
        val previous = state?.value ?: StateValue.Unknown
        val publish = when (val encoded = mapper.encode(bindings, command, previous)) {
            is Encoded.NotSupported -> return CommandResult.Rejected(encoded.reason)
            is Encoded.Publish -> encoded
        }
        val confirmBy = if (bindings.bool("optimistic", false)) emptySet() else mapper.feedbackTopics(bindings, command)
        val hasFeedback = confirmBy.isNotEmpty()
        val predicted = mapper.predict(command, previous)
        tracker.track(uniqueId, command, predicted, hasFeedback, confirmBy)
        try {
            for (m in publish.messages) transport.publish(m.topic, m.payload.encodeToByteArray(), m.qos, m.retain)
        } catch (e: PublishFailed) {
            tracker.abort(uniqueId)
            return CommandResult.Rejected(e.message ?: "publish failed")
        }
        if (!hasFeedback) store.confirmPending(uniqueId, predicted?.let { mapper.settle(command, it) })
        return CommandResult.Accepted
    }

    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        require(uniqueId.isNotEmpty() && uniqueId != AREAS_ID && isValidPublishTopic(UI_PREFIX + uniqueId)) {
            "unique_id '$uniqueId' cannot be used in an override topic"
        }
        synchronized(lock) { overrides[uniqueId] = override }
        publishEntities()
        transport.publish(UI_PREFIX + uniqueId, UiOverrides.encode(override).encodeToByteArray(), qos = 1, retain = true)
    }

    override suspend fun setAreas(areas: List<Area>) {
        val document = AreasDocument(areas)
        synchronized(lock) { areasDocument = document }
        publishEntities()
        val json = OikosJson.encodeToString(AreasDocument.serializer(), document)
        transport.publish(AREAS_TOPIC, json.encodeToByteArray(), qos = 1, retain = true)
    }

    private fun handle(message: MqttMessage) {
        val topic = message.topic
        try {
            val payload = message.payloadString()
            when {
                topic == AREAS_TOPIC -> onAreas(payload)
                topic.startsWith(UI_PREFIX) -> onOverride(topic.removePrefix(UI_PREFIX), payload)
                else -> when (val r = DiscoveryParser.parse(prefix, topic, payload)) {
                    DiscoveryResult.NotDiscovery -> store.onMessage(topic, payload)
                    DiscoveryResult.Removed -> replaceDiscovered(topic, null)
                    // Like HA: an invalid update is logged and the previously discovered entities are kept.
                    is DiscoveryResult.Skipped -> report("$topic: ${r.reason}")
                    is DiscoveryResult.Entities -> {
                        r.skipped.forEach { report("$topic: $it") }
                        replaceDiscovered(topic, r.items)
                    }
                }
            }
        } catch (e: Exception) {
            report("unexpected error handling $topic: $e")
        }
    }

    private fun onAreas(payload: String) {
        val document = if (payload.isEmpty()) {
            AreasDocument()
        } else {
            runCatching { OikosJson.decodeFromString(AreasDocument.serializer(), payload) }.getOrNull()
                ?: return report("$AREAS_TOPIC: invalid areas document")
        }
        synchronized(lock) { areasDocument = document }
        publishEntities()
    }

    private fun onOverride(uniqueId: String, payload: String) {
        if (payload.isEmpty()) {
            synchronized(lock) { overrides.remove(uniqueId) }
        } else {
            val override = UiOverrides.decode(payload) ?: return report("$UI_PREFIX$uniqueId: invalid override")
            synchronized(lock) { overrides[uniqueId] = override }
        }
        publishEntities()
    }

    private fun replaceDiscovered(topic: String, items: List<DiscoveredEntity>?) {
        synchronized(lock) {
            if (items == null) discovered.remove(topic) else discovered[topic] = items
        }
        publishEntities()
    }

    /** Rebuilds and publishes everything under [lock], so a stale snapshot can never be written last. */
    private fun publishEntities(): Unit = synchronized(lock) {
        val snapshot = run {
            val all = discovered.values.flatten()
            val declared = areasDocument.areas
            val entities = all.associate { d ->
                val ui = overrides[d.entity.uniqueId] ?: UiOverride()
                val area = resolveArea(ui.area ?: d.device?.suggestedArea ?: UNASSIGNED_AREA, declared)
                d.entity.uniqueId to d.entity.copy(name = ui.name ?: d.entity.name, area = area, ui = ui)
            }
            Snapshot(entities, all.mapNotNull { it.device }.associateBy { it.id }, computeAreas(declared, entities.values))
        }
        // Lock order is always source -> store; the store never calls back into the source.
        store.setEntities(snapshot.entities.values)
        _entities.value = snapshot.entities
        _devices.value = snapshot.devices
        _areas.value = snapshot.areas
        stateTopics.value = store.subscriptionTopics()
    }

    private fun resolveArea(raw: String, declared: List<Area>): String =
        declared.firstOrNull { it.id == raw || it.name == raw }?.id ?: raw

    private fun computeAreas(declared: List<Area>, entities: Collection<Entity>): List<Area> {
        val used = entities.mapTo(mutableSetOf()) { it.area }
        val declaredIds = declared.mapTo(mutableSetOf()) { it.id }
        val ordered = declared.sortedWith(compareBy<Area>({ it.order ?: Int.MAX_VALUE }, { it.name }))
        val implicit = (used - declaredIds - UNASSIGNED_AREA).sorted().map { Area(it, it) }
        val unassigned = if (UNASSIGNED_AREA in used && UNASSIGNED_AREA !in declaredIds) listOf(Area(UNASSIGNED_AREA, UNASSIGNED_AREA)) else emptyList()
        return ordered + implicit + unassigned
    }

    private fun report(message: String) = diagnostics.report(Diagnostic("discovery", message))

    companion object {
        const val DEFAULT_PREFIX = "homeassistant"
        const val UI_PREFIX = "oikos/ui/"
        const val AREAS_ID = "_areas"
        const val AREAS_TOPIC = UI_PREFIX + AREAS_ID
        val SUBSCRIPTION_DEBOUNCE: Duration = 500.milliseconds
    }
}
