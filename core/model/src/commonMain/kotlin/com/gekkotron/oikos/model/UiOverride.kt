package com.gekkotron.oikos.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    /** Keeps this cover or light out of its room's group card (room groups spec §6). */
    val ungrouped: Boolean = false,
    /** Switch presentation type — one of [SHOW_AS], or null for a plain switch. Only read when `protocolKind == Kind.SWITCH` (device sheet spec §5.1).
     *  An unknown value round-trips but is treated as a plain switch. */
    val showAs: String? = null,
    /** When set and the user taps a light's tile to turn it on, send this preset's brightness and
     *  colour instead of a bare `on`, so the strip comes back the way the user wants rather than
     *  the state another command (scene, voice…) last left it in. One of [LightOnPreset.key] or null. */
    val lightOnPreset: String? = null,
    /** Overrides the preset's baked brightness (1..255). Only read when [lightOnPreset] is set and the
     *  light supports brightness; a null keeps the preset's own value. */
    val lightOnBrightness: Int? = null,
    /** Comma-separated °C targets for the climate sheet's quick-set chips (e.g. "19,21,23").
     *  Blank or null = no chips; invalid entries drop silently so a typo cannot break the sheet. */
    val climatePresets: String? = null,
)

/** Known values for [UiOverride.showAs]; absent means a plain switch. Mirrors the hub's `SHOW_AS`. */
val SHOW_AS: List<String> = listOf("light", "door", "siren", "chime", "plug")

/** The user-pickable presentation of a Z2M switch (device sheet spec §5.1). Only meaningful when `protocolKind == Kind.SWITCH`. */
enum class SwitchType(val key: String?) {
    SWITCH(null),
    LIGHT("light"),
    DOOR("door"),
    SIREN("siren"),
    CHIME("chime"),
    PLUG("plug"),
    ;

    companion object {
        /** The entity's type, or null for a non-switch. An unknown [UiOverride.showAs] reads as [SWITCH]. */
        fun of(protocolKind: Kind, ui: UiOverride): SwitchType? {
            if (protocolKind != Kind.SWITCH) return null
            val key = ui.showAs ?: return SWITCH
            return entries.firstOrNull { it.key == key } ?: SWITCH
        }
    }
}

/** The kind the house should treat this entity as. Promotes `Kind.SWITCH + showAs = "light"` to `Kind.LIGHT`; everything else keeps its protocol kind. */
fun effectiveKind(protocolKind: Kind, ui: UiOverride): Kind =
    if (protocolKind == Kind.SWITCH && SwitchType.of(protocolKind, ui) == SwitchType.LIGHT) Kind.LIGHT else protocolKind

/** Named defaults for [UiOverride.lightOnPreset]. Kept small on purpose: a free picker belongs in the light sheet, not the override. */
enum class LightOnPreset(val key: String, val brightness: Int, val color: Hs) {
    WHITE("white", 255, Hs(0.0, 0.0)),
    WARM("warm", 200, Hs(30.0, 0.3)),
    RED("red", 255, Hs(0.0, 1.0)),
    GREEN("green", 255, Hs(120.0, 1.0)),
    BLUE("blue", 240, Hs(240.0, 1.0)),
    ;

    companion object {
        fun of(key: String?): LightOnPreset? = key?.let { k -> entries.firstOrNull { it.key == k } }
    }
}

object ClimatePresets {
    /** The sheet shows at most this many chips, so a pasted number list cannot swamp the row. */
    const val MAX = 6

    /** Shown when the card has no [UiOverride.climatePresets] override; the UI still clamps to [min, max]. */
    val DEFAULTS: List<Double> = listOf(16.0, 18.0, 20.0, 22.0)

    /** Parses "19,21,23" (or "19 ; 21 ; 23") into targets, dropping blanks and unparseable tokens
     *  so a typo never kills the whole list. Clamped to [MAX] and de-duplicated in input order. */
    fun parse(csv: String?): List<Double> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',', ';')
            .mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
            .distinct()
            .take(MAX)
    }

    /** [parse] with [DEFAULTS] as fallback when the override is empty. */
    fun parseOrDefault(csv: String?): List<Double> = parse(csv).ifEmpty { DEFAULTS }
}

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
    /** Reads a retained UI override, folding a legacy `asLight: true` into `showAs: "light"` so the house's existing payloads boot the right type. */
    fun decode(payload: String): UiOverride? = runCatching {
        val element = OikosJson.parseToJsonElement(payload)
        val obj = element as? JsonObject ?: return@runCatching null
        val normalized = absorbLegacyAsLight(obj)
        OikosJson.decodeFromJsonElement(UiOverride.serializer(), normalized)
    }.getOrNull()

    fun encode(override: UiOverride): String = OikosJson.encodeToString(UiOverride.serializer(), override)

    /** Drops `asLight` from the JSON and injects `showAs: "light"` when it was `true` and nothing newer is already there. */
    private fun absorbLegacyAsLight(obj: JsonObject): JsonObject {
        val asLight = obj["asLight"]
        if (asLight == null) return obj
        val legacyOn = (asLight as? JsonPrimitive)?.booleanOrNull == true
        val next = obj.toMutableMap().apply {
            remove("asLight")
            if (legacyOn && !containsKey("showAs")) put("showAs", JsonPrimitive("light"))
        }
        return JsonObject(next)
    }
}
