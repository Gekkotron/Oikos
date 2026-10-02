package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState

sealed interface RoomFilter {
    val key: String

    data object All : RoomFilter {
        override val key = "all"
    }

    data object Favorites : RoomFilter {
        override val key = "favorites"
    }

    /** Hidden entities only, so they can be found and unhidden (Settings takes this over later). */
    data object Hidden : RoomFilter {
        override val key = "hidden"
    }

    data class Area(val id: String) : RoomFilter {
        override val key get() = AREA_PREFIX + id
    }

    companion object {
        private const val AREA_PREFIX = "area:"

        fun fromKey(key: String): RoomFilter = when {
            key == Favorites.key -> Favorites
            key == Hidden.key -> Hidden
            key.startsWith(AREA_PREFIX) -> Area(key.removePrefix(AREA_PREFIX))
            else -> All
        }
    }
}

/** A null [label] is localized by the UI: All, Favourites or Unassigned. */
data class ChipUi(val filter: RoomFilter, val label: String?)

enum class TapAction { TOGGLE, PRESS, OPEN_SHEET, NONE }

/** What a card shows, before localisation. */
sealed interface CardValue {
    data object None : CardValue
    data class OnOff(val on: Boolean) : CardValue
    data class Brightness(val percent: Int) : CardValue
    data class Measurement(val text: String, val unit: String?) : CardValue
    data class Text(val value: String) : CardValue
    data class Cover(val state: CoverState, val position: Int?) : CardValue
    data class Climate(val current: String?, val target: String?, val mode: String?) : CardValue
    data class Alarm(val state: AlarmState) : CardValue
    data class Contact(val deviceClass: String?, val on: Boolean) : CardValue
}

data class CardUi(
    val id: String,
    val name: String,
    val kind: Kind,
    val deviceClass: String?,
    /** Null means Unassigned (localized by the UI). */
    val areaName: String?,
    val value: CardValue,
    val state: StateValue,
    val capabilities: Capabilities,
    val unit: String?,
    val on: Boolean,
    val wide: Boolean,
    val available: Boolean,
    val pending: Boolean,
    /** The value comes from the entity cache and no live data has confirmed it yet. */
    val stale: Boolean = false,
    val tap: TapAction,
    val hasSheet: Boolean,
    /** Effective area id; [UNASSIGNED_AREA] when the entity has no room. */
    val areaId: String,
    /** The override as it is now, for the override sheet. */
    val ui: UiOverride,
    val hidden: Boolean,
    /** A light's folded device controls (speed, palette…), shown in its sheet instead of as cards. */
    val companions: List<CardUi> = emptyList(),
)

data class HomeUiState(
    val loading: Boolean = true,
    val link: LinkState = LinkState.Connecting,
    val hero: CardUi? = null,
    val chips: List<ChipUi> = listOf(ChipUi(RoomFilter.All, null)),
    val selected: RoomFilter = RoomFilter.All,
    val cards: List<CardUi> = emptyList(),
    /** Rooms the override sheet can pick from: every area except Unassigned. */
    val rooms: List<Area> = emptyList(),
    val hiddenCount: Int = 0,
    /** The Home search text; blank when not searching. */
    val query: String = "",
) {
    fun card(id: String): CardUi? =
        (cards + listOfNotNull(hero)).firstNotNullOfOrNull { c -> c.takeIf { it.id == id } ?: c.companions.firstOrNull { it.id == id } }
}

sealed interface HomeEvent {
    data class NotConfirmed(val id: String, val command: Command) : HomeEvent
    data class Rejected(val id: String, val reason: String) : HomeEvent
    data object SaveFailed : HomeEvent
    data object WaitingForConnection : HomeEvent
}
