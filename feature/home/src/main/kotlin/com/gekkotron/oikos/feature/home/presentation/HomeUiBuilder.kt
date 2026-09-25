package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import java.util.Locale

/** Entities + states + areas → everything the Home screen draws (spec §5.2). */
object HomeUiBuilder {
    const val HERO_WEATHER = "hero_weather"
    private val WIDE_CLASSES = setOf("power", "energy")
    private val SHEET_KINDS = setOf(Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL)

    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        selected: RoomFilter,
        link: LinkState,
        locale: Locale,
    ): HomeUiState {
        val areaIndex = areas.withIndex().associate { (i, a) -> a.id to i }
        val areaNames = areas.associate { it.id to it.name }
        val visible = entities.values.filterNot { it.ui.hidden }
        val hidden = entities.values.filter { it.ui.hidden }
        val hero = visible.filter { it.ui.cardStyle == HERO_WEATHER }.minByOrNull { it.uniqueId }
        val grid = visible.filter { it.uniqueId != hero?.uniqueId }

        val used = grid.mapTo(mutableSetOf()) { it.area }
        val chips = buildList {
            add(ChipUi(RoomFilter.All, null))
            if (grid.any { it.ui.favorite }) add(ChipUi(RoomFilter.Favorites, null))
            for (area in areas) {
                if (area.id in used) add(ChipUi(RoomFilter.Area(area.id), area.name.takeUnless { area.id == UNASSIGNED_AREA }))
            }
            if (hidden.isNotEmpty()) add(ChipUi(RoomFilter.Hidden, null))
        }
        val effective = selected.takeIf { s -> chips.any { it.filter == s } } ?: RoomFilter.All

        val shown = if (effective == RoomFilter.Hidden) hidden else grid.filter { matches(it, effective) }
        val cards = shown
            .sortedWith(
                compareBy<Entity>(
                    { it.ui.order ?: Int.MAX_VALUE },
                    { areaIndex[it.area] ?: Int.MAX_VALUE },
                    { it.name.lowercase(locale) },
                    { it.uniqueId },
                ),
            )
            .map { card(it, states[it.uniqueId], areaNames, locale) }

        return HomeUiState(
            loading = entities.isEmpty(),
            link = link,
            hero = hero?.let { card(it, states[it.uniqueId], areaNames, locale) },
            chips = chips,
            selected = effective,
            cards = cards,
            rooms = areas.filter { it.id != UNASSIGNED_AREA },
            hiddenCount = hidden.size,
        )
    }

    private fun matches(entity: Entity, filter: RoomFilter) = when (filter) {
        RoomFilter.All -> true
        RoomFilter.Favorites -> entity.ui.favorite
        is RoomFilter.Area -> entity.area == filter.id
        RoomFilter.Hidden -> false
    }

    private fun card(entity: Entity, state: EntityState?, areaNames: Map<String, String>, locale: Locale): CardUi {
        val value = state?.value ?: StateValue.Unknown
        return CardUi(
            id = entity.uniqueId,
            name = entity.name,
            kind = entity.kind,
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
            tap = tapFor(entity.kind),
            hasSheet = entity.kind in SHEET_KINDS || (entity.kind == Kind.LIGHT && entity.capabilities.brightness),
            areaId = entity.area,
            ui = entity.ui,
            hidden = entity.ui.hidden,
        )
    }

    private fun tapFor(kind: Kind) = when (kind) {
        Kind.LIGHT, Kind.SWITCH -> TapAction.TOGGLE
        Kind.BUTTON -> TapAction.PRESS
        Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL -> TapAction.OPEN_SHEET
        Kind.SENSOR, Kind.BINARY_SENSOR -> TapAction.NONE
    }

    private fun isOn(kind: Kind, v: StateValue) = when (v) {
        is StateValue.Light -> v.on
        is StateValue.OnOff -> kind == Kind.SWITCH && v.on
        is StateValue.Cover -> v.state == CoverState.OPEN || v.state == CoverState.OPENING ||
            (v.state == CoverState.STOPPED && (v.position ?: 0) > 0)
        is StateValue.Climate -> v.mode != null && v.mode != "off"
        else -> false
    }
}
