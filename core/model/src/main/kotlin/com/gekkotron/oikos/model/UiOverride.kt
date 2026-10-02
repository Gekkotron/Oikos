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

object UiOverrides {
    fun decode(payload: String): UiOverride? =
        runCatching { OikosJson.decodeFromString(UiOverride.serializer(), payload) }.getOrNull()

    fun encode(override: UiOverride): String = OikosJson.encodeToString(UiOverride.serializer(), override)
}
