package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import java.text.Normalizer
import java.util.Locale

/** A typed room name → the `Area` it means: an existing room with that name, or a new one with a fresh id. */
object AreaNames {
    data class Resolved(val area: Area, val isNew: Boolean)

    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val NON_ID = Regex("[^a-z0-9]+")

    /** @return null for a blank name, or for "Unassigned", which is reserved (it is what "No room" writes). */
    fun resolve(name: String, existing: List<Area>): Resolved? {
        val clean = name.trim().replace(SPACES, " ")
        if (clean.isEmpty() || clean.equals(UNASSIGNED_AREA, ignoreCase = true)) return null
        val base = slug(clean)
        // Same name ignoring case, then ignoring accents and punctuation too: typing "Séjour" must reuse the
        // "Sejour" room that an ASCII device path (rooms/sejour/…) created, not add a second chip.
        val candidates = existing.filter { it.id != UNASSIGNED_AREA }
        val key = key(clean)
        (candidates.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: candidates.firstOrNull { key.isNotEmpty() && key(it.name) == key })
            ?.let { return Resolved(it, isNew = false) }
        val ids = existing.mapTo(mutableSetOf()) { it.id }
        val id = if (base !in ids) base else generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in ids }
        return Resolved(Area(id, clean), isNew = true)
    }

    fun slug(name: String): String = key(name).ifEmpty { "room" }

    /** Lowercase ASCII letters and digits joined by `_`; empty when the name has none (emoji, CJK…). */
    private fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT).replace(NON_ID, "_").trim('_')

    /** The `_areas` document after adding [area]. Implicit areas (from suggested_area) become declared ones. */
    fun withArea(existing: List<Area>, area: Area): List<Area> = existing.filter { it.id != UNASSIGNED_AREA } + area
}
