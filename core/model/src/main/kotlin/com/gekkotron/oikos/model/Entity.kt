package com.gekkotron.oikos.model

/** Source-specific wiring (topics, templates…). Opaque to features. */
interface Bindings

data class Capabilities(
    val brightness: Boolean = false,
    val colorModes: Set<String> = emptySet(),
    val position: Boolean = false,
    val hvacModes: List<String> = emptyList(),
    val minTemp: Double? = null,
    val maxTemp: Double? = null,
    val tempStep: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(),
    val alarmModes: Set<AlarmMode> = emptySet(),
    val codeArmRequired: Boolean = false,
)

data class Entity(
    val uniqueId: String,
    val deviceId: String?,
    val name: String,
    val kind: Kind,
    val deviceClass: String?,
    val unit: String?,
    val icon: String?,
    val capabilities: Capabilities,
    val bindings: Bindings,
    /** Effective area id: override → device suggested_area → [UNASSIGNED_AREA]. */
    val area: String = UNASSIGNED_AREA,
    /** The override applied to this entity (defaults when none). */
    val ui: UiOverride = UiOverride(),
)
