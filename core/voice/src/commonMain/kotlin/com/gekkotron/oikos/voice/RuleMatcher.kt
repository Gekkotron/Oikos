package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind

private typealias Verb = VoiceLexicon.Verb

private val VALUE_PCT = Regex("""(\d{1,4})\s*(?:%|pour ?cent|percent|pct)""")
private val VALUE_A = Regex("""\ba\s+(\d{1,4})\b""")
private val HALF = Regex("""\bmoitie\b""")

/** Attribute sentinel meaning "any state"; it never narrows by device class. */
private const val STATE_ATTR = "state"

private fun lev(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length]
}

/** Cheap ASR-slip tolerance: Levenshtein <= 1, only for tokens of 5+ chars. */
private fun near(a: String, b: String) = a == b || (a.length >= 5 && b.length >= 5 && lev(a, b) <= 1)

private fun <V> lookup(map: Map<String, V>, t: String): V? =
    map[t] ?: if (t.length >= 5) map.entries.firstOrNull { it.key.length >= 5 && lev(it.key, t) <= 1 }?.value else null

private fun isAlarmWord(w: String) = near(w, "alarme") || near(w, "alarm") || w == "alarmes"

private class Parsed(
    val verb: Verb?,
    val value: Int?,
    val badValue: Boolean,
    val all: Boolean,
    val plural: Boolean,
    val dev: Set<String>,
    val devWords: Set<String>,
    val rooms: Set<String>,
    val personAreas: Set<String>,
    val mods: Set<String>,
    val alarm: Boolean,
    val alarmHome: Boolean,
    val queryStructural: Boolean,
    val attrs: Set<String>,
    val verbSet: Set<Verb>,
)

/** Deterministic rule matcher: returns an intent only when the phrase is unambiguous. */
class RuleMatcher(private val catalog: VoiceCatalog) {
    private val table = VoiceLexicon.of(catalog.lang)

    /** A catalog entity pre-digested into concepts. Modifier literals are words with no known synonym. */
    private inner class Ent(val e: VoiceEntity) {
        val dev = mutableSetOf<String>()
        val mods = mutableSetOf<String>()
        val literals = mutableListOf<String>()
        val nameTokens = mutableListOf<String>()
        var pluralName = false

        init {
            for (w in VoiceText.words(VoiceText.prepare(e.displayName, catalog.lang))) {
                nameTokens += w
                if (w in table.rooms) continue
                val d = table.devices[w]
                val m = table.modifiers[w]
                if (d != null) {
                    dev += d
                    if (w.endsWith("s")) pluralName = true
                }
                if (m != null) mods += m
                if (d == null && m == null && w !in table.noise) literals += w
            }
        }

        /** A group entity is named by a bare plural device word, e.g. "shutters". */
        val isGroup get() = pluralName && mods.isEmpty() && literals.isEmpty()
    }

    private val ents = catalog.entities.map { Ent(it) }

    private val areaRoom: Map<String, String?> = catalog.areas.associate { it.id to it.roomConcept }

    private val personTokens: Map<String, List<String>> =
        catalog.areas.filter { it.isPersonRoom }
            .associate { it.id to VoiceText.words(VoiceText.prepare(it.displayName, catalog.lang)) }

    /**
     * Command entities narrowed to the room(s) and/or device kind the text mentions.
     * Returns every command entity when neither is recognized or nothing would be left.
     */
    fun shortlist(text: String): List<VoiceEntity> {
        val all = catalog.entities
        val p = parse(text)
        var rooms = p.rooms
        if ("bedroom" in rooms && p.personAreas.isNotEmpty()) rooms = rooms - "bedroom"
        val areas: Set<String>? =
            (rooms.flatMap { r -> catalog.areas.filter { areaRoom[it.id] == r }.map { it.id } } + p.personAreas)
                .toSet()
                .takeIf { it.isNotEmpty() }
        val wantsDevice = p.dev.isNotEmpty() || p.alarm
        fun deviceMatch(e: VoiceEntity): Boolean {
            if (p.alarm && e.kind == Kind.ALARM_PANEL) return true
            return p.dev.isNotEmpty() && kindMatches(Ent(e), p.dev)
        }
        val byRoom = areas?.let { a -> all.filter { it.areaId in a } }.orEmpty()
        val byDevice = if (wantsDevice) all.filter(::deviceMatch) else emptyList()
        val both = if (areas != null && wantsDevice) byRoom.filter(::deviceMatch) else emptyList()
        return when {
            both.isNotEmpty() -> both
            byRoom.isNotEmpty() -> byRoom
            byDevice.isNotEmpty() -> byDevice
            else -> all
        }
    }

    /** The verb in [text] as an action; [kind] disambiguates "arrete" (stop a cover, switch off the rest). */
    fun detectAction(text: String, kind: Kind? = null): VoiceAction? =
        when (parse(text).verb) {
            Verb.ON, Verb.SET -> VoiceAction.TURN_ON
            Verb.OFF -> VoiceAction.TURN_OFF
            Verb.OPEN -> VoiceAction.OPEN
            Verb.CLOSE -> VoiceAction.CLOSE
            Verb.STOP -> VoiceAction.STOP
            Verb.STOP_OR_OFF -> if (kind == Kind.COVER) VoiceAction.STOP else VoiceAction.TURN_OFF
            Verb.ARM -> VoiceAction.ARM_AWAY
            Verb.DISARM -> VoiceAction.DISARM
            Verb.QUERY -> VoiceAction.QUERY_STATE
            null -> null
        }

    /** True when [text] names a room or a person's room. */
    fun mentionsArea(text: String): Boolean {
        val p = parse(text)
        return p.rooms.isNotEmpty() || p.personAreas.isNotEmpty()
    }

    /** Same notion of "light" as the rules: a light, or a switch named like a light. */
    fun isLightClass(e: VoiceEntity) = e.kind == Kind.LIGHT || (e.kind == Kind.SWITCH && "light" in Ent(e).dev)

    /** How well the entity's device, name qualifiers and room fit [text]; 0 when nothing fits. */
    fun relevance(text: String, e: VoiceEntity): Int {
        val p = parse(text)
        val en = Ent(e)
        var score = 0
        if (p.dev.isNotEmpty() && kindMatches(en, p.dev)) score += 3
        score += 2 * p.mods.count { hasMod(en, it) }
        val room = areaRoom[e.areaId]
        if ((room != null && room in p.rooms) || e.areaId in p.personAreas) score += 1
        return score
    }

    private fun parse(text: String): Parsed {
        val s = VoiceText.prepare(text, catalog.lang)
        val ws = VoiceText.words(s)
        var value: Int? = null
        var bad = false
        val num = VALUE_PCT.find(s)?.groupValues?.get(1) ?: VALUE_A.find(s)?.groupValues?.get(1)
        if (num != null) {
            val n = num.toInt()
            if (n in 0..100) value = n else bad = true
        } else if (HALF.containsMatchIn(s)) {
            value = 50
        }

        val alarm = ws.any(::isAlarmWord)
        val queryStructural = table.structuralQuery.any { it.containsMatchIn(s) }
        val attrs = mutableSetOf<String>()
        val verbs = mutableListOf<Verb>()
        val dev = mutableSetOf<String>()
        val devWords = mutableSetOf<String>()
        val mods = mutableSetOf<String>()
        val rooms = mutableSetOf<String>()
        val persons = mutableSetOf<String>()
        var all = false
        var plural = false
        var alarmHome = false

        for (w in ws) {
            if (w in table.allWords) { all = true; continue }
            if (w == "les") plural = true
            if (isAlarmWord(w)) continue
            table.verbs[w]?.let { verbs += it; continue }
            if (w in table.noise) continue
            if (alarm && w in table.alarmHomeWords) { alarmHome = true; continue }
            lookup(table.attributes, w)?.let { attrs += it }
            val person = personTokens.entries.firstOrNull { (_, toks) -> toks.any { near(it, w) } }?.key
            if (person != null) { persons += person; continue }
            val d = lookup(table.devices, w)
            val m = lookup(table.modifiers, w)
            val r = lookup(table.rooms, w)
            if (d != null || m != null || r != null) {
                if (d != null) {
                    dev += d
                    devWords += w
                    if (w.endsWith("s") || w.endsWith("x")) plural = true
                }
                if (m != null) mods += m
                if (r != null) rooms += r
                continue
            }
            lookup(table.verbs, w)?.let { verbs += it; continue }
            mods += "~$w"
        }
        // A structural question phrase wins; otherwise an explicit action verb beats a bare query word.
        val firstAction = verbs.firstOrNull { it != Verb.QUERY }
            ?.let { if (it == Verb.SET && Verb.ON in verbs) Verb.ON else it }
        val verb = when {
            queryStructural -> Verb.QUERY
            firstAction != null -> firstAction
            Verb.QUERY in verbs -> Verb.QUERY
            else -> null
        }
        return Parsed(
            verb, value, bad, all, plural, dev, devWords, rooms, persons, mods, alarm, alarmHome,
            queryStructural, attrs, verbs.toSet(),
        )
    }

    private fun actionFor(verb: Verb, value: Int?, e: VoiceEntity): VoiceAction? {
        val cover = e.kind == Kind.COVER
        val power = e.kind == Kind.LIGHT || e.kind == Kind.SWITCH
        if (value != null) {
            if (verb == Verb.OFF || verb == Verb.STOP || verb == Verb.STOP_OR_OFF || verb == Verb.ARM || verb == Verb.DISARM) return null
            return when {
                cover && e.position -> VoiceAction.SET_POSITION
                e.kind == Kind.LIGHT && e.brightness -> VoiceAction.SET_BRIGHTNESS
                else -> null
            }
        }
        return when (verb) {
            Verb.ON, Verb.SET -> if (power) VoiceAction.TURN_ON else null
            Verb.OFF -> if (power) VoiceAction.TURN_OFF else null
            Verb.STOP_OR_OFF -> if (cover) VoiceAction.STOP else if (power) VoiceAction.TURN_OFF else null
            Verb.STOP -> if (cover) VoiceAction.STOP else null
            Verb.OPEN -> if (cover) VoiceAction.OPEN else null
            Verb.CLOSE -> if (cover) VoiceAction.CLOSE else null
            Verb.ARM, Verb.DISARM -> null
            Verb.QUERY -> VoiceAction.QUERY_STATE
        }
    }

    private fun kindMatches(en: Ent, dev: Set<String>) =
        dev.all { q ->
            when (q) {
                "light" -> "light" in en.dev || (en.e.kind == Kind.LIGHT && en.dev.isEmpty())
                "cover" -> "cover" in en.dev || en.e.kind == Kind.COVER
                else -> q in en.dev
            }
        }

    private fun hasMod(en: Ent, m: String) =
        if (m.startsWith("~")) en.literals.any { near(it, m.drop(1)) } else m in en.mods

    /** Number of name qualifiers the entity has that the request did not mention. */
    private fun extras(en: Ent, q: Set<String>) =
        en.mods.count { it !in q } +
            en.literals.count { lit -> q.none { it.startsWith("~") && near(lit, it.drop(1)) } }

    /** True when the entity's own name contains one of the device words the user said. */
    private fun namedByUser(en: Ent, p: Parsed) = p.devWords.any { w -> en.nameTokens.any { near(it, w) } }

    fun resolve(text: String): VoiceIntent? {
        val p = parse(text)
        val verb = p.verb ?: return null
        if (p.badValue) return null

        // Room filter: null means "no room said".
        var rooms = p.rooms
        if ("bedroom" in rooms && p.personAreas.isNotEmpty()) rooms = rooms - "bedroom"
        val wanted: Set<String>? =
            if (rooms.isEmpty() && p.personAreas.isEmpty()) {
                null
            } else {
                val byRoom = rooms.map { r -> catalog.areas.filter { areaRoom[it.id] == r }.map { it.id } }
                if (byRoom.any { it.isEmpty() }) return null
                byRoom.flatten().toSet() + p.personAreas
            }

        if (verb == Verb.QUERY) return resolveQuery(p, wanted)

        if (p.alarm && "siren" !in p.dev) return resolveAlarm(p, verb, wanted)

        if (p.dev.isEmpty() && p.mods.isEmpty()) return null

        val cands = ents.filter {
            (wanted == null || it.e.areaId in wanted) &&
                kindMatches(it, p.dev) &&
                p.mods.all { m -> hasMod(it, m) } &&
                actionFor(verb, p.value, it.e) != null
        }
        if (cands.isEmpty()) return null
        val actions = cands.map { actionFor(verb, p.value, it.e)!! }.toSet()
        if (actions.size != 1) return null

        val chosen: List<Ent> =
            if (cands.size == 1) {
                cands
            } else if (p.plural || p.all) {
                if (wanted == null && p.mods.isEmpty() && !p.all) return null
                cands
            } else {
                // Naming the device ("la lampe") narrows to entities that carry that word in their name.
                val named = cands.filter { namedByUser(it, p) }
                val pool = if (named.isNotEmpty()) named else cands
                val groups = pool.filter { it.isGroup }
                if (pool.size == 1) {
                    pool
                } else if (wanted == null && p.mods.isEmpty()) {
                    return null
                } else if (groups.size == 1) {
                    groups
                } else {
                    val scored = pool.groupBy { extras(it, p.mods) }
                    val best = scored[scored.keys.min()]!!
                    // Singular device word plus a room: several matches mean the whole room group.
                    if (best.size == 1 || wanted != null) best else return null
                }
            }
        return VoiceIntent(actions.first(), chosen.map { it.e.uniqueId }, p.value)
    }

    /**
     * Queries return every matching entity: asking "quelle temperature" means every room.
     * An attribute word narrows by device class and beats the device word, which only breaks ties.
     * Modifiers are soft: filler words such as "est" parse as the modifier "east", so they only break ties.
     */
    private fun resolveQuery(p: Parsed, wanted: Set<String>?): VoiceIntent? {
        val attrClasses = p.attrs - STATE_ATTR
        val mods = p.mods.filterNot { it.startsWith("~") }
        val inRoom = ents.filter { wanted == null || it.e.areaId in wanted }
        val cands: List<Ent> = when {
            attrClasses.isNotEmpty() -> {
                val byAttr = inRoom.filter { it.e.deviceClass in attrClasses }
                val byDev = if (p.dev.isEmpty()) byAttr else byAttr.filter { kindMatches(it, p.dev) }.ifEmpty { byAttr }
                byDev.filter { en -> mods.all { hasMod(en, it) } }.ifEmpty { byDev }
            }
            p.dev.isNotEmpty() -> {
                val byDev = inRoom.filter { kindMatches(it, p.dev) }
                byDev.filter { en -> mods.all { hasMod(en, it) } }.ifEmpty { byDev }
            }
            else -> emptyList()
        }
        if (cands.isEmpty()) return null
        return VoiceIntent(VoiceAction.QUERY_STATE, cands.map { it.e.uniqueId }, null, queryHint(p.verbSet))
    }

    private fun queryHint(verbs: Set<Verb>): QueryHint? = when {
        Verb.CLOSE in verbs -> QueryHint.EXPECT_CLOSED
        Verb.OPEN in verbs -> QueryHint.EXPECT_OPEN
        Verb.ON in verbs -> QueryHint.EXPECT_ON
        Verb.OFF in verbs -> QueryHint.EXPECT_OFF
        else -> null
    }

    private fun resolveAlarm(p: Parsed, verb: Verb, wanted: Set<String>?): VoiceIntent? {
        val action = when (verb) {
            Verb.ON, Verb.SET, Verb.ARM -> if (p.alarmHome) VoiceAction.ARM_HOME else VoiceAction.ARM_AWAY
            Verb.OFF, Verb.STOP_OR_OFF, Verb.DISARM -> VoiceAction.DISARM
            else -> return null
        }
        val panels = catalog.entities.filter {
            it.kind == Kind.ALARM_PANEL && (wanted == null || it.areaId in wanted)
        }
        if (panels.size != 1) return null
        return VoiceIntent(action, listOf(panels[0].uniqueId))
    }
}
