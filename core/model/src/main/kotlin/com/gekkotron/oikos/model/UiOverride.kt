package com.gekkotron.oikos.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

@Serializable
enum class EnergyRole {
    @SerialName("solar") SOLAR,
    @SerialName("grid") GRID,
    @SerialName("house") HOUSE,
    @SerialName("water_heater") WATER_HEATER,
    @SerialName("water") WATER,
}

object UiOverrides {
    fun decode(payload: String): UiOverride? =
        runCatching { OikosJson.decodeFromString(UiOverride.serializer(), payload) }.getOrNull()

    fun encode(override: UiOverride): String = OikosJson.encodeToString(UiOverride.serializer(), override)
}
