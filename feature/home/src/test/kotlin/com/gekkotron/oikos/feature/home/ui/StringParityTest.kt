package com.gekkotron.oikos.feature.home.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards that every string the Show-as chip row needs is present in both en and fr, and that the
 * retired `customize_as_light` row is gone from both.
 */
class StringParityTest {
    private val required = setOf(
        "customize_show_as",
        "customize_show_as_switch",
        "customize_show_as_light",
        "customize_show_as_door",
        "customize_show_as_siren",
        "customize_show_as_chime",
        "customize_show_as_plug",
    )

    private val stringNameRegex = Regex("""<string\s+name="([^"]+)"""")

    private fun parse(path: String): Set<String> {
        val xml = File(path).readText()
        return stringNameRegex.findAll(xml).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `localized Show-as strings are present in en and fr`() {
        val en = parse("src/main/res/values/strings.xml")
        val fr = parse("src/main/res/values-fr/strings.xml")
        assertTrue(required.all { it in en }, "English missing: ${required - en}")
        assertTrue(required.all { it in fr }, "French missing: ${required - fr}")
    }

    @Test
    fun `customize_as_light is retired in both locales`() {
        val en = parse("src/main/res/values/strings.xml")
        val fr = parse("src/main/res/values-fr/strings.xml")
        assertFalse("customize_as_light" in en, "customize_as_light should be removed (en)")
        assertFalse("customize_as_light" in fr, "customize_as_light should be removed (fr)")
    }
}
