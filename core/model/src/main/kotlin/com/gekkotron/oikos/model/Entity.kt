package com.gekkotron.oikos.model

/** Source-specific wiring (topics, templates…). Opaque to features. */
interface Bindings

data class Capabilities(
    val brightness: Boolean = false,
    val colorModes: Set<String> = emptySet(),
    /** The light's `effect_list`, in published order; empty when it has no effects. */
    val effects: List<String> = emptyList(),
    val position: Boolean = false,
    val hvacModes: List<String> = emptyList(),
    /** The climate's `fan_modes`, in published order; empty when it has no fan control. */
    val fanModes: List<String> = emptyList(),
    val minTemp: Double? = null,
    val maxTemp: Double? = null,
    val tempStep: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(),
    val alarmModes: Set<AlarmMode> = emptySet(),
    val codeArmRequired: Boolean = false,
    val codeDisarmRequired: Boolean = false,
    /** Keypad (digits) or text field for the alarm code (security spec §3.1). */
    val codeFormat: CodeFormat = CodeFormat.NUMBER,
)

enum class CodeFormat { NUMBER, TEXT }

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
    /** Unique id of the entity this one duplicates (e.g. a Z2M "Moving" sensor folded into its cover). */
    val partOf: String? = null,
    /** Short device name when [name] is that device name followed by [label]. */
    val deviceName: String? = null,
    /** The entity's own label as published (e.g. "Local temperature"), or null when [name] isn't device + label. */
    val label: String? = null,
    /** The kind as discovered, which picks the MQTT mapper; differs from [kind] only when the user shows a switch as a light. */
    val protocolKind: Kind = kind,
)
