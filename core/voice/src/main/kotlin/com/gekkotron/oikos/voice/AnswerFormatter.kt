package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class Answer(val spoken: String, val display: String, val speakable: Boolean, val staleIds: List<String>)

/** Composes the sentence for a QUERY_STATE intent from a state snapshot. Pure: no Android, no clock unless defaulted. */
object AnswerFormatter {
    private const val MAX_NAMES = 3
    private val CONTINUOUS = setOf("temperature", "humidity", "pressure", "illuminance")
    private val DIACRITICS = Regex("\\p{Mn}+")

    fun format(
        lang: VoiceLang,
        intent: VoiceIntent,
        states: Map<String, EntityState>,
        catalog: VoiceCatalog,
        now: Instant = Instant.now(),
    ): Answer {
        val t = if (lang == VoiceLang.FR) FrTemplates else EnTemplates
        val targets = intent.entityIds.mapNotNull { id -> catalog.entity(id)?.let { Target(it, states[id]) } }
        val usable = targets.filter { it.hasReading() }
        val unusable = targets.filterNot { it.hasReading() }

        if (usable.isEmpty()) {
            val text = t.noReading(unusable.map { it.entity.displayName })
            return Answer(text, text, speakable = false, staleIds = emptyList())
        }

        val ctx = Ctx(t, catalog, intent.hint)
        val body = usable
            .groupBy { partitionKey(it.entity) }
            .values
            .joinToString("; ") { compose(ctx, it) }

        val stale = usable.filter { it.state!!.stale }
        val tails = buildList {
            if (unusable.isNotEmpty()) add(t.noData(unusable.size))
            if (stale.isNotEmpty()) add(t.updatedAgo(staleness(stale.mapNotNull { it.state!!.lastUpdate }.maxOrNull(), now)))
        }
        val text = (listOf(body) + tails).joinToString(" ")
        return Answer(text, text, speakable = true, staleIds = stale.map { it.entity.uniqueId })
    }

    private class Target(val entity: VoiceEntity, val state: EntityState?) {
        val value: StateValue get() = state!!.value
        fun hasReading(): Boolean {
            val s = state ?: return false
            if (!s.available) return false
            return when (val v = s.value) {
                StateValue.Unknown, StateValue.Unsupported -> false
                is StateValue.Cover -> v.state != CoverState.UNKNOWN
                is StateValue.Climate -> v.current != null || v.target != null || v.mode != null
                else -> true
            }
        }
    }

    private class Ctx(val t: Templates, val catalog: VoiceCatalog, val hint: QueryHint?) {
        fun room(e: VoiceEntity): String? = catalog.area(e.areaId)?.displayName
    }

    private fun partitionKey(e: VoiceEntity): String = when (e.kind) {
        Kind.SENSOR, Kind.BINARY_SENSOR -> "${e.kind}/${e.deviceClass}"
        else -> e.kind.name
    }

    private fun compose(ctx: Ctx, group: List<Target>): String {
        if (group.size == 1) return single(ctx, group[0])
        return when (group[0].entity.kind) {
            Kind.COVER, Kind.LIGHT, Kind.SWITCH, Kind.BINARY_SENSOR -> booleanGroup(ctx, group)
            Kind.SENSOR -> sensorGroup(ctx, group)
            else -> group.joinToString("; ") { single(ctx, it) }
        }
    }

    // ---- single entity -------------------------------------------------------------------------

    private fun single(ctx: Ctx, tg: Target): String {
        val e = tg.entity
        val v = tg.value
        val t = ctx.t
        val room = ctx.room(e)
        return when (e.kind) {
            Kind.SENSOR -> sensorSingle(ctx, e, v, room)
            Kind.BINARY_SENSOR -> {
                val on = isOn(v)
                val noun = t.deviceNoun(e.deviceClass)
                if (on != null && noun != null && e.deviceClass in OPENING_CLASSES) {
                    t.opening(noun, room ?: e.displayName, room != null, t.boolWord(Family.OPENING, on, noun.fem, plural = false))
                } else if (on != null) {
                    "${e.displayName} : ${t.boolWord(Family.ACTIVE, on, fem = false, plural = false)}"
                } else generic(ctx, e, v)
            }
            Kind.LIGHT -> {
                val on = isOn(v) ?: return generic(ctx, e, v)
                val pct = (v as? StateValue.Light)?.brightness?.takeIf { e.brightness }?.let { Math.round(it * 100.0 / 255).toInt() }
                t.light(e.displayName, room, t.boolWord(Family.POWER, on, fem = true, plural = false), pct, on)
            }
            Kind.SWITCH -> {
                val on = isOn(v) ?: return generic(ctx, e, v)
                "${e.displayName}${t.colon} ${t.boolWord(Family.POWER, on, fem = false, plural = false)}"
            }
            Kind.COVER -> {
                val c = v as? StateValue.Cover ?: return generic(ctx, e, v)
                t.cover(room, e.displayName, c.state, c.position)
            }
            Kind.CLIMATE, Kind.WATER_HEATER -> {
                val c = v as? StateValue.Climate ?: return generic(ctx, e, v)
                t.climate(room ?: e.displayName, c.current?.let(::fmtNum), c.target?.let(::fmtNum), c.mode)
            }
            Kind.VACUUM -> {
                val c = v as? StateValue.Vacuum ?: return generic(ctx, e, v)
                t.vacuum(e.displayName, c.state, c.battery)
            }
            Kind.ALARM_PANEL -> {
                val a = v as? StateValue.Alarm ?: return generic(ctx, e, v)
                t.alarm(a.state)
            }
            else -> generic(ctx, e, v)
        }
    }

    private fun sensorSingle(ctx: Ctx, e: VoiceEntity, v: StateValue, room: String?): String {
        val num = (v as? StateValue.Numeric)?.value ?: return generic(ctx, e, v)
        return continuousSentence(ctx, e, Reading(num, averaged = false, entity = e), room)
    }

    private fun generic(ctx: Ctx, e: VoiceEntity, v: StateValue): String {
        val t = ctx.t
        val shown = when (v) {
            is StateValue.Numeric -> fmtValue(v.value, e)
            is StateValue.Text -> v.value
            is StateValue.OnOff -> t.boolWord(Family.ACTIVE, v.on, fem = false, plural = false)
            is StateValue.Light -> t.boolWord(Family.POWER, v.on, fem = false, plural = false)
            else -> return t.noReading(listOf(e.displayName))
        }
        return "${e.displayName}${t.colon} $shown"
    }

    // ---- boolean groups ------------------------------------------------------------------------

    private enum class Family { OPENING, POWER, ACTIVE }

    private val OPENING_CLASSES = setOf("door", "window", "opening")

    private fun isOn(v: StateValue): Boolean? = when (v) {
        is StateValue.OnOff -> v.on
        is StateValue.Light -> v.on
        is StateValue.Cover -> v.state != CoverState.CLOSED
        else -> null
    }

    private fun booleanGroup(ctx: Ctx, group: List<Target>): String {
        val t = ctx.t
        val e0 = group[0].entity
        val family = when (e0.kind) {
            Kind.COVER -> Family.OPENING
            Kind.LIGHT, Kind.SWITCH -> Family.POWER
            else -> if (e0.deviceClass in OPENING_CLASSES) Family.OPENING else Family.ACTIVE
        }
        val noun = t.groupNoun(e0.kind, e0.deviceClass)
        val flags = group.map { isOn(it.value) }
        if (flags.any { it == null }) return group.joinToString("; ") { single(ctx, it) }
        val n = flags.size
        val trues = flags.count { it == true }

        val hinted: Boolean? = when (ctx.hint) {
            QueryHint.EXPECT_ON, QueryHint.EXPECT_OPEN -> true
            QueryHint.EXPECT_OFF, QueryHint.EXPECT_CLOSED -> false
            null -> null
        }
        val target = hinted ?: true
        val matching = if (target) trues else n - trues
        val rest = n - matching
        fun word(value: Boolean, plural: Boolean) = t.boolWord(family, value, noun.fem, plural)

        return when {
            matching == n && hinted != null -> t.yes + t.allMatch(noun, word(target, true))
            matching == n -> t.allBare(noun, word(target, true))
            matching == 0 && hinted != null -> t.no + t.noneMatch(noun, word(target, true))
            matching == 0 -> t.allBare(noun, word(!target, true))
            else -> (if (hinted != null) t.no else "") +
                t.mixed(matching, n, noun, word(target, matching > 1), rest, word(!target, rest > 1))
        }
    }

    // ---- sensor groups -------------------------------------------------------------------------

    private class Reading(val value: Double, val averaged: Boolean, val entity: VoiceEntity)

    private fun sensorGroup(ctx: Ctx, group: List<Target>): String {
        val e0 = group[0].entity
        val nums = group.map { (it.value as? StateValue.Numeric)?.value }
        if (e0.deviceClass !in CONTINUOUS || nums.any { it == null }) {
            return group.joinToString(", ") { discreteItem(ctx, it) }
        }
        val byRoom = group.groupBy { it.entity.areaId }
        if (byRoom.size == 1) {
            val reading = collapse(ctx, group)
            return continuousSentence(ctx, reading.entity, reading, ctx.room(reading.entity))
        }
        val parts = byRoom.values.mapIndexed { i, list ->
            val r = collapse(ctx, list)
            val label = ctx.room(r.entity) ?: r.entity.displayName
            val shown = if (i == 0) fmtValue(r.value, r.entity) else fmtNum(r.value)
            "$label${ctx.t.colon} $shown"
        }
        return parts.joinToString(", ")
    }

    private fun discreteItem(ctx: Ctx, tg: Target): String {
        val v = tg.value
        val shown = when (v) {
            is StateValue.Numeric -> fmtValue(v.value, tg.entity)
            is StateValue.Text -> v.value
            else -> return single(ctx, tg)
        }
        return "${tg.entity.displayName}${ctx.t.colon} $shown"
    }

    private fun collapse(ctx: Ctx, list: List<Target>): Reading {
        if (list.size == 1) return Reading((list[0].value as StateValue.Numeric).value, false, list[0].entity)
        val noun = ctx.t.deviceNoun(list[0].entity.deviceClass)?.let { fold(it.singular) }
        val roomName = ctx.room(list[0].entity)
        val ambient = list.firstOrNull { tg ->
            val name = fold(tg.entity.displayName)
            name == noun || name == fold(list[0].entity.deviceClass.orEmpty()) ||
                (roomName != null && noun != null && name == fold("$roomName ${ctx.t.deviceNoun(list[0].entity.deviceClass)!!.singular}"))
        }
        if (ambient != null) return Reading((ambient.value as StateValue.Numeric).value, false, ambient.entity)
        val avg = list.map { (it.value as StateValue.Numeric).value }.average()
        return Reading(avg, true, list[0].entity)
    }

    private fun continuousSentence(ctx: Ctx, e: VoiceEntity, r: Reading, room: String?): String {
        val t = ctx.t
        val shown = fmtValue(r.value, e)
        return when (e.deviceClass) {
            "temperature" -> t.temperature(shown, room, e.displayName, r.averaged)
            "humidity" -> t.humidity(shown, room, e.displayName, r.averaged)
            "battery" -> t.battery(e.displayName, shown)
            else -> if (r.averaged) {
                t.averagedGeneric(t.deviceNoun(e.deviceClass)?.singular ?: e.displayName, room, shown)
            } else "${e.displayName}${t.colon} $shown"
        }
    }

    // ---- formatting helpers --------------------------------------------------------------------

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").trim()

    private fun fmtNum(v: Double): String {
        val s = String.format(Locale.ROOT, "%.0f", v)
        return if (s == "-0") "0" else s
    }

    private fun fmtValue(v: Double, e: VoiceEntity): String {
        val n = fmtNum(v)
        if (e.deviceClass == "temperature") return if (e.unit == "°F") "$n°F" else "$n°"
        val u = e.unit ?: e.deviceClass?.takeIf { it == "battery" || it == "humidity" }?.let { "%" }
        return if (u == null) n else "$n $u"
    }

    private fun staleness(latest: Instant?, now: Instant): Long? {
        if (latest == null) return null
        return Duration.between(latest, now).toHours().coerceAtLeast(0)
    }

    // ---- templates -----------------------------------------------------------------------------

    private class Noun(val singular: String, val plural: String, val fem: Boolean)

    private abstract class Templates {
        abstract val colon: String
        abstract val yes: String
        abstract val no: String
        abstract fun noReading(names: List<String>): String
        abstract fun noData(count: Int): String
        abstract fun updatedAgo(hours: Long?): String
        abstract fun deviceNoun(deviceClass: String?): Noun?
        abstract fun groupNoun(kind: Kind, deviceClass: String?): Noun
        abstract fun boolWord(family: Family, value: Boolean, fem: Boolean, plural: Boolean): String
        abstract fun allMatch(noun: Noun, state: String): String
        abstract fun allBare(noun: Noun, state: String): String
        abstract fun noneMatch(noun: Noun, state: String): String
        abstract fun mixed(k: Int, n: Int, noun: Noun, state1: String, rest: Int, state2: String): String
        abstract fun temperature(v: String, room: String?, name: String, averaged: Boolean): String
        abstract fun humidity(v: String, room: String?, name: String, averaged: Boolean): String
        abstract fun battery(name: String, v: String): String
        abstract fun averagedGeneric(noun: String, room: String?, v: String): String
        abstract fun opening(noun: Noun, where: String, isRoom: Boolean, state: String): String
        abstract fun light(name: String, room: String?, state: String, pct: Int?, on: Boolean): String
        abstract fun cover(room: String?, name: String, state: CoverState, position: Int?): String
        abstract fun climate(label: String, current: String?, target: String?, mode: String?): String
        abstract fun vacuum(name: String, state: String?, battery: Int?): String
        abstract fun alarm(state: AlarmState): String
    }

    private object FrTemplates : Templates() {
        override val colon = " :"
        override val yes = "Oui, "
        override val no = "Non, "

        override fun noReading(names: List<String>): String = when {
            names.isEmpty() -> "Je n'ai pas la valeur"
            names.size <= MAX_NAMES -> "Je n'ai pas la valeur de ${names.joinToString(", ")}"
            else -> "Je n'ai pas la valeur de ${names.take(MAX_NAMES).joinToString(", ")} et ${names.size - MAX_NAMES} autres"
        }

        override fun noData(count: Int) = if (count == 1) "(1 entité sans donnée)" else "($count entités sans donnée)"

        override fun updatedAgo(hours: Long?) = when {
            hours == null -> "(mise à jour inconnue)"
            hours < 1 -> "(mise à jour il y a moins d'une heure)"
            else -> "(mise à jour il y a ${hours}h)"
        }

        private val nouns = mapOf(
            "temperature" to Noun("température", "températures", true),
            "humidity" to Noun("humidité", "humidités", true),
            "pressure" to Noun("pression", "pressions", true),
            "illuminance" to Noun("luminosité", "luminosités", true),
            "power" to Noun("puissance", "puissances", true),
            "energy" to Noun("énergie", "énergies", true),
            "voltage" to Noun("tension", "tensions", true),
            "current" to Noun("courant", "courants", false),
            "battery" to Noun("batterie", "batteries", true),
            "door" to Noun("porte", "portes", true),
            "window" to Noun("fenêtre", "fenêtres", true),
            "opening" to Noun("ouverture", "ouvertures", true),
        )

        override fun deviceNoun(deviceClass: String?) = nouns[deviceClass]

        override fun groupNoun(kind: Kind, deviceClass: String?): Noun = when (kind) {
            Kind.COVER -> Noun("volet", "volets", false)
            Kind.LIGHT -> Noun("lumière", "lumières", true)
            Kind.SWITCH -> Noun("interrupteur", "interrupteurs", false)
            else -> nouns[deviceClass]?.takeIf { deviceClass in OPENING_CLASSES } ?: Noun("capteur", "capteurs", false)
        }

        override fun boolWord(family: Family, value: Boolean, fem: Boolean, plural: Boolean): String {
            val base = when (family) {
                Family.OPENING -> if (value) "ouvert" else "fermé"
                Family.POWER -> if (value) "allumé" else "éteint"
                Family.ACTIVE -> if (value) "actif" else "inactif"
            }
            return base + (if (fem) "e" else "") + (if (plural) "s" else "")
        }

        override fun allMatch(noun: Noun, state: String) = "${if (noun.fem) "toutes" else "tous"} les ${noun.plural} sont $state"
        override fun allBare(noun: Noun, state: String) = "${if (noun.fem) "Toutes" else "Tous"} les ${noun.plural} sont $state"
        override fun noneMatch(noun: Noun, state: String): String {
            // Singular agreement: "aucun volet n'est fermé".
            val single = state.removeSuffix("s")
            return "${if (noun.fem) "aucune" else "aucun"} ${noun.singular} n'est $single"
        }

        override fun mixed(k: Int, n: Int, noun: Noun, state1: String, rest: Int, state2: String): String {
            val head = if (k == 1) "$k ${noun.singular} sur $n est $state1" else "$k ${noun.plural} sur $n sont $state1"
            return "$head, $rest $state2"
        }

        override fun temperature(v: String, room: String?, name: String, averaged: Boolean) = when {
            room == null -> if (averaged) "Température en moyenne : $v" else "$name : $v"
            averaged -> "Il fait $v en moyenne dans le ${room.lowercase()}"
            else -> "Il fait $v dans le ${room.lowercase()}"
        }

        override fun humidity(v: String, room: String?, name: String, averaged: Boolean) = when {
            room == null -> if (averaged) "Humidité en moyenne : $v" else "$name : $v"
            averaged -> "L'humidité moyenne du ${room.lowercase()} est de $v"
            else -> "L'humidité du ${room.lowercase()} est de $v"
        }

        override fun battery(name: String, v: String) = "Batterie de $name : $v"

        override fun averagedGeneric(noun: String, room: String?, v: String): String {
            val label = noun.replaceFirstChar { it.uppercase() }
            return if (room == null) "$label : $v en moyenne" else "$label du ${room.lowercase()} : $v en moyenne"
        }

        override fun opening(noun: Noun, where: String, isRoom: Boolean, state: String): String {
            val article = if (noun.fem) "La" else "Le"
            return if (isRoom) "$article ${noun.singular} du ${where.lowercase()} est $state" else "$where est $state"
        }

        override fun light(name: String, room: String?, state: String, pct: Int?, on: Boolean): String {
            val base = if (room != null) "La lumière du ${room.lowercase()} est $state" else "$name est $state"
            return if (pct != null && on) "$base, luminosité $pct %" else base
        }

        override fun cover(room: String?, name: String, state: CoverState, position: Int?): String {
            val word = when {
                state == CoverState.OPEN && position != null && position in 1..99 -> "partiellement ouvert ($position %)"
                state == CoverState.OPEN -> "ouvert"
                state == CoverState.CLOSED -> "fermé"
                state == CoverState.OPENING -> "en cours d'ouverture"
                state == CoverState.CLOSING -> "en cours de fermeture"
                else -> "arrêté"
            }
            return if (room != null) "Le volet du ${room.lowercase()} est $word" else "$name est $word"
        }

        private val modes = mapOf(
            "heat" to "chauffage", "cool" to "climatisation", "off" to "arrêt", "auto" to "auto",
            "heat_cool" to "chauffage/clim", "dry" to "déshumidification", "fan_only" to "ventilation",
        )

        override fun climate(label: String, current: String?, target: String?, mode: String?): String {
            val parts = listOfNotNull(
                current?.let { "$it° actuel" },
                target?.let { "consigne $it°" },
                mode?.let { "mode ${modes[it] ?: it}" },
            )
            return "$label : ${parts.joinToString(", ")}"
        }

        private val vacuumStates = mapOf(
            "cleaning" to "en nettoyage", "docked" to "à la base", "paused" to "en pause",
            "idle" to "inactif", "returning" to "retourne à la base", "error" to "en erreur",
        )

        override fun vacuum(name: String, state: String?, battery: Int?): String {
            val parts = listOfNotNull(state?.let { vacuumStates[it] ?: it }, battery?.let { "batterie $it %" })
            return "$name : ${parts.joinToString(", ")}"
        }

        override fun alarm(state: AlarmState): String = "L'alarme est " + when (state) {
            AlarmState.DISARMED -> "désarmée"
            AlarmState.ARMED_HOME -> "armée (présence)"
            AlarmState.ARMED_AWAY -> "armée (absence)"
            AlarmState.ARMED_NIGHT -> "armée (nuit)"
            AlarmState.ARMED_VACATION -> "armée (vacances)"
            AlarmState.ARMED_CUSTOM_BYPASS -> "armée (personnalisé)"
            AlarmState.PENDING -> "en attente"
            AlarmState.ARMING -> "en cours d'armement"
            AlarmState.DISARMING -> "en cours de désarmement"
            AlarmState.TRIGGERED -> "déclenchée"
        }
    }

    private object EnTemplates : Templates() {
        override val colon = ":"
        override val yes = "Yes, "
        override val no = "No, "

        override fun noReading(names: List<String>): String = when {
            names.isEmpty() -> "I don't have a reading"
            names.size <= MAX_NAMES -> "I don't have a reading for ${names.joinToString(", ")}"
            else -> "I don't have a reading for ${names.take(MAX_NAMES).joinToString(", ")} and ${names.size - MAX_NAMES} more"
        }

        override fun noData(count: Int) = if (count == 1) "(1 entity with no data)" else "($count entities with no data)"

        override fun updatedAgo(hours: Long?) = when {
            hours == null -> "(last update unknown)"
            hours < 1 -> "(updated less than an hour ago)"
            else -> "(updated ${hours}h ago)"
        }

        private val nouns = mapOf(
            "temperature" to Noun("temperature", "temperatures", false),
            "humidity" to Noun("humidity", "humidity", false),
            "pressure" to Noun("pressure", "pressures", false),
            "illuminance" to Noun("illuminance", "illuminance", false),
            "power" to Noun("power", "power", false),
            "energy" to Noun("energy", "energy", false),
            "voltage" to Noun("voltage", "voltage", false),
            "current" to Noun("current", "current", false),
            "battery" to Noun("battery", "batteries", false),
            "door" to Noun("door", "doors", false),
            "window" to Noun("window", "windows", false),
            "opening" to Noun("opening", "openings", false),
        )

        override fun deviceNoun(deviceClass: String?) = nouns[deviceClass]

        override fun groupNoun(kind: Kind, deviceClass: String?): Noun = when (kind) {
            Kind.COVER -> Noun("shutter", "shutters", false)
            Kind.LIGHT -> Noun("light", "lights", false)
            Kind.SWITCH -> Noun("switch", "switches", false)
            else -> nouns[deviceClass]?.takeIf { deviceClass in OPENING_CLASSES } ?: Noun("sensor", "sensors", false)
        }

        override fun boolWord(family: Family, value: Boolean, fem: Boolean, plural: Boolean): String = when (family) {
            Family.OPENING -> if (value) "open" else "closed"
            Family.POWER, Family.ACTIVE -> if (value) "on" else "off"
        }

        override fun allMatch(noun: Noun, state: String) = "all the ${noun.plural} are $state"
        override fun allBare(noun: Noun, state: String) = "All the ${noun.plural} are $state"
        override fun noneMatch(noun: Noun, state: String) = "none of the ${noun.plural} are $state"

        override fun mixed(k: Int, n: Int, noun: Noun, state1: String, rest: Int, state2: String) =
            "$k of $n ${noun.plural} ${if (k == 1) "is" else "are"} $state1, $rest $state2"

        override fun temperature(v: String, room: String?, name: String, averaged: Boolean) = when {
            room == null -> if (averaged) "Average temperature: $v" else "$name: $v"
            averaged -> "It's $v on average in the ${room.lowercase()}"
            else -> "It's $v in the ${room.lowercase()}"
        }

        override fun humidity(v: String, room: String?, name: String, averaged: Boolean) = when {
            room == null -> if (averaged) "Average humidity: $v" else "$name: $v"
            averaged -> "Average humidity in the ${room.lowercase()} is $v"
            else -> "Humidity in the ${room.lowercase()} is $v"
        }

        override fun battery(name: String, v: String) = "$name battery: $v"

        override fun averagedGeneric(noun: String, room: String?, v: String): String {
            return if (room == null) "Average ${noun.lowercase()}: $v" else "Average ${noun.lowercase()} in the ${room.lowercase()}: $v"
        }

        override fun opening(noun: Noun, where: String, isRoom: Boolean, state: String) =
            if (isRoom) "The ${noun.singular} in the ${where.lowercase()} is $state" else "$where is $state"

        override fun light(name: String, room: String?, state: String, pct: Int?, on: Boolean): String {
            val base = if (room != null) "The ${room.lowercase()} light is $state" else "$name is $state"
            return if (pct != null && on) "$base, brightness $pct %" else base
        }

        override fun cover(room: String?, name: String, state: CoverState, position: Int?): String {
            val word = when {
                state == CoverState.OPEN && position != null && position in 1..99 -> "partially open ($position %)"
                state == CoverState.OPEN -> "open"
                state == CoverState.CLOSED -> "closed"
                state == CoverState.OPENING -> "opening"
                state == CoverState.CLOSING -> "closing"
                else -> "stopped"
            }
            return if (room != null) "The ${room.lowercase()} shutter is $word" else "$name is $word"
        }

        override fun climate(label: String, current: String?, target: String?, mode: String?): String {
            val parts = listOfNotNull(current?.let { "$it° now" }, target?.let { "target $it°" }, mode?.let { "mode $it" })
            return "$label: ${parts.joinToString(", ")}"
        }

        override fun vacuum(name: String, state: String?, battery: Int?): String {
            val parts = listOfNotNull(state, battery?.let { "battery $it %" })
            return "$name: ${parts.joinToString(", ")}"
        }

        override fun alarm(state: AlarmState): String = "The alarm is " + when (state) {
            AlarmState.DISARMED -> "disarmed"
            AlarmState.ARMED_HOME -> "armed home"
            AlarmState.ARMED_AWAY -> "armed away"
            AlarmState.ARMED_NIGHT -> "armed night"
            AlarmState.ARMED_VACATION -> "armed vacation"
            AlarmState.ARMED_CUSTOM_BYPASS -> "armed custom bypass"
            AlarmState.PENDING -> "pending"
            AlarmState.ARMING -> "arming"
            AlarmState.DISARMING -> "disarming"
            AlarmState.TRIGGERED -> "triggered"
        }
    }
}
