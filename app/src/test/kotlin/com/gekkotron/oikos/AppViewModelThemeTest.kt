package com.gekkotron.oikos

import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Test
import kotlin.test.assertEquals

/** `AppViewModel` needs a `ProfileRepository` (internal constructor), so the mapping it applies is tested directly. */
class AppViewModelThemeTest {
    @Test
    fun `each stored preference maps to the same-named choice`() {
        assertEquals(ThemeChoice.SYSTEM, ThemePreference.SYSTEM.toChoice())
        assertEquals(ThemeChoice.NIGHT_GLASS, ThemePreference.NIGHT_GLASS.toChoice())
        assertEquals(ThemeChoice.WARM_MINIMAL, ThemePreference.WARM_MINIMAL.toChoice())
    }

    @Test
    fun `the two enums list the same names`() {
        assertEquals(ThemePreference.entries.map { it.name }, ThemeChoice.entries.map { it.name })
    }
}
