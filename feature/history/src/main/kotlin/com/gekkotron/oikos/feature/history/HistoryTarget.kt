package com.gekkotron.oikos.feature.history

/** What a history screen shows: one entity, or the whole house. */
sealed interface HistoryTarget {
    data class Entity(val id: String) : HistoryTarget
    data object House : HistoryTarget

    /** "entity:<id>" or "house", for a navigation argument. */
    fun encode(): String = when (this) {
        is Entity -> "entity:$id"
        House -> "house"
    }

    companion object {
        fun decode(text: String): HistoryTarget? = when {
            text == "house" -> House
            text.startsWith("entity:") -> Entity(text.removePrefix("entity:"))
            else -> null
        }
    }
}
