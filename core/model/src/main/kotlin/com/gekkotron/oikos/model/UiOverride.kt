package com.gekkotron.oikos.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Retained JSON on `oikos/ui/<unique_id>`. */
@Serializable
data class UiOverride(
    val name: String? = null,
    /** Area id (or a raw area name, resolved by the source). */
    val area: String? = null,
    val hidden: Boolean = false,
    val order: Int? = null,
    val cardStyle: String? = null,
    val favorite: Boolean = false,
    val energyRole: EnergyRole? = null,
    /** Flips the sign of a [EnergyRole.signed] entity; meters and HA integrations differ (energy spec §2). */
    val energyInvert: Boolean = false,
    /** Left out of the Security tab only; Home still shows it (security spec §2). */
    val securityHidden: Boolean = false,
    /** A [ConsumerIcon.key]; kept as a string so a value this app doesn't know falls back to the default plug instead of failing the decode. */
    val energyIcon: String? = null,
    /** Unique id of the energy counter shown on this power sensor's card; null = paired automatically. */
    val energyMeter: String? = null,
    /** Kept on Home although Energy or Security already shows it ([Placement.elsewhere]). */
    val homePinned: Boolean = false,
    /** A SWITCH or BUTTON shown in Security's Actions section; opt-in (security actions spec §2). */
    val securityAction: Boolean = false,
    /** Ask before turning this action on or pressing it; only read when [securityAction]. */
    val securityAsk: Boolean = true,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class EnergyRole {
    @SerialName("solar") SOLAR,
    @SerialName("grid") GRID,
    @SerialName("grid_export") GRID_EXPORT,
    @SerialName("battery") BATTERY,
    @SerialName("house") HOUSE,

    /** `water_heater` is this role's name from before the energy screen (energy spec §2). */
    @SerialName("consumer") @JsonNames("water_heater") CONSUMER,
    @SerialName("water") WATER,
    ;

    /** Its sign means a direction and can be flipped by [UiOverride.energyInvert]. */
    val signed: Boolean get() = this == GRID || this == BATTERY

    /** Its value must be a power ([PowerUnits]). */
    val power: Boolean get() = this != WATER
}

/** The glyphs a [EnergyRole.CONSUMER] can wear on the Energy screen, by their stored [key]. */
enum class ConsumerIcon(val key: String) {
    WATER_HEATER("water_heater"),
    HEAT_PUMP("heat_pump"),
    OVEN("oven"),
    FRIDGE("fridge"),
    WASHER("washer"),
    DRYER("dryer"),
    DISHWASHER("dishwasher"),
    ;

    companion object {
        fun of(key: String?): ConsumerIcon? = entries.firstOrNull { it.key == key }
    }
}

object UiOverrides {
    fun decode(payload: String): UiOverride? =
        runCatching { OikosJson.decodeFromString(UiOverride.serializer(), payload) }.getOrNull()

    fun encode(override: UiOverride): String = OikosJson.encodeToString(UiOverride.serializer(), override)
}
