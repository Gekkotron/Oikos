package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind

private enum class Cls(val priority: Int) { LIGHT(0), POWER(1), COVER(2), ALARM(3) }

private class Cand(
    val intent: VoiceIntent,
    val label: String,
    val detected: Boolean,
    val group: Boolean,
    val relevance: Int,
    val cls: Cls,
    val size: Int,
    val actionIdx: Int,
)

private fun compatibleKinds(a: VoiceAction): Set<Kind> = when (a) {
    VoiceAction.TURN_ON, VoiceAction.TURN_OFF -> setOf(Kind.LIGHT, Kind.SWITCH)
    VoiceAction.OPEN, VoiceAction.CLOSE, VoiceAction.STOP, VoiceAction.SET_POSITION -> setOf(Kind.COVER)
    VoiceAction.SET_BRIGHTNESS -> setOf(Kind.LIGHT)
    VoiceAction.ARM_AWAY, VoiceAction.ARM_HOME, VoiceAction.DISARM -> setOf(Kind.ALARM_PANEL)
    VoiceAction.QUERY_STATE -> emptySet()
}

private fun groupNoun(lang: VoiceLang, cls: Cls): String = when (lang) {
    VoiceLang.FR -> when (cls) {
        Cls.LIGHT -> "Lumières"; Cls.COVER -> "Volets"; Cls.POWER -> "Appareils"; Cls.ALARM -> "Alarmes"
    }
    VoiceLang.EN -> when (cls) {
        Cls.LIGHT -> "Lights"; Cls.COVER -> "Shutters"; Cls.POWER -> "Devices"; Cls.ALARM -> "Alarms"
    }
}

/** Turns an unresolved phrase into a short list of plausible commands the user can tap to teach. */
class Suggester(private val catalog: VoiceCatalog, private val rules: RuleMatcher) {

    private fun classOf(e: VoiceEntity): Cls? = when {
        rules.isLightClass(e) -> Cls.LIGHT
        e.kind == Kind.SWITCH -> Cls.POWER
        e.kind == Kind.COVER -> Cls.COVER
        e.kind == Kind.ALARM_PANEL -> Cls.ALARM
        else -> null
    }

    private fun defaults(c: Cls): List<VoiceAction> = when (c) {
        Cls.LIGHT, Cls.POWER -> listOf(VoiceAction.TURN_ON, VoiceAction.TURN_OFF)
        Cls.COVER -> listOf(VoiceAction.OPEN, VoiceAction.CLOSE)
        Cls.ALARM -> listOf(VoiceAction.ARM_AWAY, VoiceAction.DISARM)
    }

    private fun areaName(e: VoiceEntity) = catalog.area(e.areaId)?.displayName

    private fun singleName(e: VoiceEntity, c: Cls): String {
        val fr = catalog.lang == VoiceLang.FR
        val prefix = when (c) {
            Cls.COVER -> if (fr) "Volet " else "Shutter "
            Cls.LIGHT -> if (e.kind == Kind.LIGHT) (if (fr) "Lumière " else "Light ") else ""
            else -> ""
        }
        val name = if (e.displayName.startsWith(prefix, ignoreCase = true)) e.displayName else prefix + e.displayName
        return name + (areaName(e)?.let { " ($it)" } ?: "")
    }

    private fun groupName(c: Cls, area: String?, n: Int): String =
        "${groupNoun(catalog.lang, c)} ${area ?: ""}".trim() + " ($n)"

    fun suggest(text: String, max: Int = 6): List<Suggestion> {
        val roomMentioned = rules.mentionsArea(text)
        val entries = rules.shortlist(text).mapNotNull { e -> classOf(e)?.let { e to it } }
        val cands = mutableListOf<Cand>()

        fun add(members: List<VoiceEntity>, c: Cls, group: Boolean, area: String?) {
            val detected = rules.detectAction(text, members.first().kind)
            val fits = detected != null && members.all { it.kind in compatibleKinds(detected) }
            val actions = if (fits) listOf(detected) else defaults(c)
            val rel = members.maxOf { rules.relevance(text, it) }
            for ((i, a) in actions.withIndex()) {
                val label = VoiceLexicon.actionLabel(catalog.lang, a) + " · " +
                    if (group) groupName(c, area, members.size) else singleName(members.first(), c)
                cands += Cand(VoiceIntent(a, members.map { it.uniqueId }), label, fits, group, rel, c, members.size, i)
            }
        }

        entries.groupBy { (e, c) -> e.areaId to c }.forEach { (key, list) ->
            if (list.size >= 2) add(list.map { it.first }, key.second, true, areaName(list.first().first))
        }
        for ((e, c) in entries) add(listOf(e), c, false, null)

        val ranked = cands.sortedWith(
            compareBy<Cand> { if (it.detected) 0 else 1 }
                .thenBy { if (roomMentioned && !it.group) 1 else 0 }
                .thenByDescending { it.relevance }
                .thenBy { it.cls.priority }
                .thenByDescending { it.size }
                .thenBy { it.actionIdx }
                .thenBy { it.label }
        )
        val seen = HashSet<Pair<VoiceAction, Set<String>>>()
        return ranked
            .filter { seen.add(it.intent.action to it.intent.entityIds.toSet()) }
            .take(max)
            .map { Suggestion(it.intent, it.label) }
    }
}
