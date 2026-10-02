package com.gekkotron.oikos.feature.home.presentation

import java.util.Locale

/** Effect names as published ("fire_1d") → a chip label ("Fire 1d"); tidied, never translated (LED spec §4.2). */
object EffectNames {
    fun label(raw: String): String = raw.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
}
