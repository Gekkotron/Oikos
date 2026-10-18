package com.gekkotron.oikos.voice

import java.text.Normalizer

private val DIACRITICS = Regex("\\p{Mn}+")

internal actual fun stripDiacritics(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "")
