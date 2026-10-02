package com.gekkotron.oikos.feature.home.presentation

import java.text.Normalizer
import java.util.Locale

/** Home search (case- and accent-insensitive; every word of the query must appear in one of the fields). */
object SearchMatch {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun matches(query: String, vararg fields: String?): Boolean {
        val words = normalize(query).split(spaces).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString(" ") { normalize(it) }
        return words.all { it in haystack }
    }
}
