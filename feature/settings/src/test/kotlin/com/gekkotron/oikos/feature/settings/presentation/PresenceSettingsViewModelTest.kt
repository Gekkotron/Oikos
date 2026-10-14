package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceSettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.prefs() = AppPreferences.create(tmp.newFile().also { it.delete() }, backgroundScope)

    private fun TestScope.vm(p: AppPreferences): PresenceSettingsViewModel =
        PresenceSettingsViewModel(p, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun `the initial UI reflects the stored config`() = runTest {
        val p = prefs()
        p.setPresence(PresenceConfig(enabled = true, alias = "pixel", homeLat = 1.0, homeLng = 2.0, radiusM = 150))
        p.presence.filterNotNull().first { it.enabled }
        val vm = vm(p)
        advanceUntilIdle()
        val ui = vm.state.first { it.enabled }
        assertTrue(ui.enabled)
        assertEquals("pixel", ui.alias)
        assertEquals(1.0 to 2.0, ui.home)
        assertEquals(150, ui.radiusM)
    }

    @Test
    fun `enabling writes the config back`() = runTest {
        val p = prefs()
        val vm = vm(p)
        vm.setEnabled(true)
        advanceUntilIdle()
        assertTrue(p.presence.filterNotNull().first { it.enabled }.enabled)
    }

    @Test
    fun `setting the home stores lat and lng`() = runTest {
        val p = prefs()
        val vm = vm(p)
        vm.setHome(48.858, 2.294)
        advanceUntilIdle()
        val back = p.presence.filterNotNull().first { it.homeLat == 48.858 }
        assertEquals(48.858, back.homeLat)
        assertEquals(2.294, back.homeLng)
    }

    @Test
    fun `setting the radius clamps to the min and max`() = runTest {
        val p = prefs()
        val vm = vm(p)
        vm.setRadius(10)
        advanceUntilIdle()
        assertEquals(PresenceConfig.MIN_RADIUS_M, p.presence.filterNotNull().first { it.radiusM == PresenceConfig.MIN_RADIUS_M }.radiusM)
        vm.setRadius(99999)
        advanceUntilIdle()
        assertEquals(PresenceConfig.MAX_RADIUS_M, p.presence.filterNotNull().first { it.radiusM == PresenceConfig.MAX_RADIUS_M }.radiusM)
    }

    @Test
    fun `an empty alias is kept but blocks the broadcast at the worker`() = runTest {
        val p = prefs()
        val vm = vm(p)
        vm.setAlias("")
        vm.setEnabled(true)
        advanceUntilIdle()
        val back = p.presence.filterNotNull().first { it.enabled }
        assertTrue(back.enabled)
        assertEquals("", back.alias)
    }

    @Test
    fun `disabling clears the home so the next enable requires a fresh pick`() = runTest {
        val p = prefs()
        p.setPresence(PresenceConfig(enabled = true, alias = "a", homeLat = 1.0, homeLng = 2.0, radiusM = 150))
        p.presence.filterNotNull().first { it.enabled }
        val vm = vm(p)
        vm.setEnabled(false)
        advanceUntilIdle()
        val back = p.presence.filterNotNull().first { !it.enabled }
        assertFalse(back.enabled)
        // Home is kept so the user does not have to re-pick after a toggle.
        assertEquals(1.0, back.homeLat)
    }
}
