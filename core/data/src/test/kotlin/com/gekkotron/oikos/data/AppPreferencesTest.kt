package com.gekkotron.oikos.data

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class AppPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String = "prefs.json") = tmp.root.resolve(name)
    private fun TestScope.prefs(f: File = file()) = AppPreferences.create(f, backgroundScope)
    private suspend fun AppPreferences.loaded() = theme.filterNotNull().first()

    @Test
    fun `missing file is System`() = runTest {
        assertEquals(ThemePreference.SYSTEM, prefs().loaded())
    }

    @Test
    fun `theme round-trips`() = runTest {
        val p = prefs()
        p.setTheme(ThemePreference.WARM_MINIMAL)
        assertEquals(ThemePreference.WARM_MINIMAL, p.theme.first { it == ThemePreference.WARM_MINIMAL })
    }

    @Test
    fun `a corrupt file falls back to System`() = runTest {
        val f = file().apply { writeText("{not json") }
        assertEquals(ThemePreference.SYSTEM, prefs(f).loaded())
    }

    @Test
    fun `an unknown theme name from a newer app is System`() = runTest {
        val f = file().apply { writeText("""{"theme":"OLED_BLACK"}""") }
        assertEquals(ThemePreference.SYSTEM, prefs(f).loaded())
    }

    @Test
    fun `clearing the profile keeps the theme`() = runTest {
        val p = prefs()
        p.setTheme(ThemePreference.NIGHT_GLASS)
        val profiles = ProfileRepository.create(file("profile.json"), FakeSecretStore(), backgroundScope)
        profiles.save(ConnectionProfile.Demo)
        profiles.clear()
        assertEquals(ThemePreference.NIGHT_GLASS, p.theme.first { it == ThemePreference.NIGHT_GLASS })
    }

    @Test
    fun `voiceEnabled defaults to false`() = runTest {
        assertEquals(false, prefs().voiceEnabled.filterNotNull().first())
    }

    @Test
    fun `voiceEnabled round-trips`() = runTest {
        val p = prefs()
        p.setVoiceEnabled(true)
        assertEquals(true, p.voiceEnabled.first { it == true })
        p.setVoiceEnabled(false)
        assertEquals(false, p.voiceEnabled.first { it == false })
    }

    @Test
    fun `an older file without voiceEnabled stays false`() = runTest {
        val f = file().apply { writeText("""{"theme":"WARM_MINIMAL"}""") }
        val p = prefs(f)
        assertEquals(false, p.voiceEnabled.filterNotNull().first())
        assertEquals(ThemePreference.WARM_MINIMAL, p.theme.first { it == ThemePreference.WARM_MINIMAL })
    }
}
