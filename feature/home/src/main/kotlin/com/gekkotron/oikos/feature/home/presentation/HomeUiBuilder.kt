package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.Placement
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import java.util.Locale

/** Translates an entity label published by the source ("Local temperature"); null keeps it as is. */
fun interface LabelText {
    operator fun invoke(label: String): String?
}

/** Entities + states + areas → everything the Home screen draws (spec §5.2). */
object HomeUiBuilder {
    const val HERO_WEATHER = "hero_weather"
    private val WIDE_CLASSES = setOf("power", "energy")
    private val SHEET_KINDS = setOf(Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL, Kind.VACUUM, Kind.WATER_HEATER)
    private val COMPANION_KINDS = setOf(Kind.NUMBER, Kind.SELECT)

    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        selected: RoomFilter,
        link: LinkState,
        locale: Locale,
        labels: LabelText = LabelText { null },
        /** Non-blank: search every room, hidden devices included, instead of [selected]. */
        query: String = "",
        historyEnabled: Boolean = false,
    ): HomeUiState {
        val names = entities.mapValues { (_, e) -> displayName(e, labels) }
        val areaIndex = areas.withIndex().associate { (i, a) -> a.id to i }
        val areaNames = areas.associate { it.id to it.name }
        val standalone = entities.values.filter { it.partOf == null }
        val children = childrenByParent(entities)
        fun companionsOf(e: Entity): List<CardUi> = companions(e, children, entities, states, areaNames, locale, labels, historyEnabled)
        // What Energy or Security already shows stays off Home unless pinned; search still finds it.
        val visible = standalone.filter { Placement.onHome(it.kind, it.deviceClass, it.ui) }
        val hidden = standalone.filter { it.ui.hidden }
        val hero = visible.filter { it.ui.cardStyle == HERO_WEATHER }.minByOrNull { it.uniqueId }
        val grid = visible.filter { it.uniqueId != hero?.uniqueId }
        val groupable = RoomGroups.groupableIds(grid)

        val used = grid.mapTo(mutableSetOf()) { it.area }
        val hasFavorites = grid.any { it.ui.favorite }
        val chips = buildList {
            if (hasFavorites) add(ChipUi(RoomFilter.Favorites, null))
            add(ChipUi(RoomFilter.All, null))
            for (area in areas) {
                if (area.id in used) add(ChipUi(RoomFilter.Area(area.id), area.name.takeUnless { area.id == UNASSIGNED_AREA }))
            }
            if (hidden.isNotEmpty()) add(ChipUi(RoomFilter.Hidden, null))
        }
        val fallback = if (hasFavorites) RoomFilter.Favorites else RoomFilter.All
        val effective = selected.takeIf { s -> chips.any { it.filter == s } } ?: fallback

        val shown = when {
            query.isNotBlank() -> standalone.filter { SearchMatch.matches(query, names.getValue(it.uniqueId), it.deviceName, areaNames[it.area]) }
            effective == RoomFilter.Hidden -> hidden
            else -> grid.filter { matches(it, effective) }
        }
        val sorted = shown
            .sortedWith(
                compareBy<Entity>(
                    { it.ui.order ?: Int.MAX_VALUE },
                    { areaIndex[it.area] ?: Int.MAX_VALUE },
                    { names.getValue(it.uniqueId).lowercase(locale) },
                    { it.uniqueId },
                ),
            )
            .map { card(it, names.getValue(it.uniqueId), states[it.uniqueId], areaNames, locale, companionsOf(it), historyEnabled, it.uniqueId in groupable) }
        // Room groups spec §2.1: groups only in All and a room, never in Favourites, Hidden or search.
        val folds = query.isBlank() && (effective == RoomFilter.All || effective is RoomFilter.Area)
        val cards = if (folds) RoomGroups.fold(sorted, areas) else sorted

        return HomeUiState(
            loading = entities.isEmpty(),
            link = link,
            hero = hero?.let { card(it, names.getValue(it.uniqueId), states[it.uniqueId], areaNames, locale, companionsOf(it), historyEnabled) },
            chips = chips,
            selected = effective,
            cards = cards,
            rooms = areas.filter { it.id != UNASSIGNED_AREA },
            hiddenCount = hidden.size,
            query = query,
        )
    }

    /** One card for [id] wherever it lives (Home, Energy or Security), so another tab can customise it; null if unknown or attached to another card. */
    fun cardFor(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        id: String,
        locale: Locale,
        labels: LabelText = LabelText { null },
        historyEnabled: Boolean = false,
    ): CardUi? {
        val e = entities[id]?.takeIf { it.partOf == null } ?: return null
        val areaNames = areas.associate { it.id to it.name }
        return card(e, displayName(e, labels), states[id], areaNames, locale, companions(e, childrenByParent(entities), entities, states, areaNames, locale, labels, historyEnabled), historyEnabled)
    }

    private fun childrenByParent(entities: Map<String, Entity>): Map<String, List<Entity>> =
        entities.values.filter { it.partOf != null && it.kind in COMPANION_KINDS }.groupBy { it.partOf!! }

    private fun companions(
        e: Entity,
        children: Map<String, List<Entity>>,
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areaNames: Map<String, String>,
        locale: Locale,
        labels: LabelText,
        historyEnabled: Boolean,
    ): List<CardUi> {
        val list: List<Entity> = when (e.kind) {
            Kind.LIGHT -> children[e.uniqueId].orEmpty()
            Kind.WATER_HEATER -> {
                val deviceId = e.deviceId
                if (deviceId == null) emptyList()
                else entities.values.filter {
                    it.kind == Kind.NUMBER && it.deviceId == deviceId && !it.ui.hidden && it.uniqueId != e.uniqueId
                }.sortedBy { it.uniqueId }
            }
            else -> emptyList()
        }
        return list
            .map { c -> card(c, companionName(c, labels), states[c.uniqueId], areaNames, locale, emptyList(), historyEnabled) }
            .let { cards -> if (e.kind == Kind.LIGHT) cards.sortedWith(compareBy({ it.name.lowercase(locale) }, { it.id })) else cards }
    }

    private fun matches(entity: Entity, filter: RoomFilter) = when (filter) {
        RoomFilter.All -> true
        RoomFilter.Favorites -> entity.ui.favorite
        is RoomFilter.Area -> entity.area == filter.id
        RoomFilter.Hidden -> false
    }

    /** A name the user typed wins; otherwise a known label is translated after the device name. */
    private fun displayName(entity: Entity, labels: LabelText): String {
        if (entity.ui.name != null) return entity.name
        val device = entity.deviceName ?: return entity.name
        val label = entity.label?.let(labels::invoke) ?: return entity.name
        return "$device $label"
    }

    /** Inside a light's sheet the device name is redundant: "Effect speed", not "Leds Effect speed". */
    private fun companionName(entity: Entity, labels: LabelText): String {
        if (entity.ui.name != null) return entity.name
        val label = entity.label ?: return entity.name
        return labels(label) ?: label
    }

    private fun hasLightSheet(entity: Entity, companions: List<CardUi>): Boolean {
        val caps = entity.capabilities
        return caps.brightness || caps.effects.isNotEmpty() || caps.hasColor || companions.isNotEmpty()
    }

    private fun card(
        entity: Entity,
        name: String,
        state: EntityState?,
        areaNames: Map<String, String>,
        locale: Locale,
        companions: List<CardUi>,
        historyEnabled: Boolean,
        groupable: Boolean = false,
    ): CardUi {
        val hasHistory = historyEnabled && HistoryRefs.of(entity) != null
        val value = state?.value ?: StateValue.Unknown
        return CardUi(
            id = entity.uniqueId,
            name = name,
            kind = entity.kind,
            protocolKind = entity.protocolKind,
            deviceClass = entity.deviceClass,
            areaName = if (entity.area == UNASSIGNED_AREA) null else areaNames[entity.area] ?: entity.area,
            value = CardFormatter.format(entity, value, locale),
            state = value,
            capabilities = entity.capabilities,
            unit = entity.unit,
            on = isOn(entity.kind, value),
            wide = entity.kind == Kind.SENSOR && entity.deviceClass in WIDE_CLASSES,
            available = state?.available ?: true,
            pending = state?.pending ?: false,
            stale = state?.stale ?: false,
            tap = tapFor(entity.kind, hasHistory),
            hasSheet = entity.kind in SHEET_KINDS || (entity.kind == Kind.LIGHT && hasLightSheet(entity, companions)),
            hasHistory = hasHistory,
            areaId = entity.area,
            ui = entity.ui,
            hidden = entity.ui.hidden,
            companions = companions,
            groupable = groupable,
            forceToggle = forceToggleFor(entity, value),
        )
    }

    private fun tapFor(kind: Kind, hasHistory: Boolean) = when (kind) {
        Kind.LIGHT, Kind.SWITCH -> TapAction.TOGGLE
        Kind.BUTTON -> TapAction.PRESS
        Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL, Kind.VACUUM, Kind.WATER_HEATER -> TapAction.OPEN_SHEET
        Kind.SENSOR -> if (hasHistory) TapAction.OPEN_HISTORY else TapAction.NONE
        Kind.BINARY_SENSOR -> TapAction.NONE
    }

    private fun forceToggleFor(entity: Entity, value: StateValue): Boolean? {
        if (entity.kind != Kind.WATER_HEATER) return null
        val modes = entity.capabilities.hvacModes
        if ("performance" !in modes || "eco" !in modes) return null
        return (value as? StateValue.Climate)?.mode == "performance"
    }

    private fun isOn(kind: Kind, v: StateValue) = when (v) {
        is StateValue.Light -> v.on
        is StateValue.OnOff -> kind == Kind.SWITCH && v.on
        is StateValue.Cover -> v.state == CoverState.OPEN || v.state == CoverState.OPENING ||
            (v.state == CoverState.STOPPED && (v.position ?: 0) > 0)
        is StateValue.Climate ->
            if (kind == Kind.WATER_HEATER) v.mode == "performance"
            else v.mode != null && v.mode != "off"
        else -> false
    }
}
