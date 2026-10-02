package com.gekkotron.oikos.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MainTabsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `energy stacks on home and home pops it`() {
        val stack = mutableListOf<NavKey>(HomeKey)
        selectTab(stack, EnergyKey)
        assertEquals(listOf<NavKey>(HomeKey, EnergyKey), stack)
        selectTab(stack, EnergyKey)
        assertEquals(listOf<NavKey>(HomeKey, EnergyKey), stack)
        selectTab(stack, HomeKey)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun `tab bar only on the main tabs`() {
        assertTrue(showsTabBar(HomeKey))
        assertTrue(showsTabBar(EnergyKey))
        assertTrue(showsTabBar(SecurityKey))
        assertFalse(showsTabBar(OnboardingKey))
        assertFalse(showsTabBar(RoomsKey))
        assertFalse(showsTabBar(null))
    }

    @Test
    fun `tapping energy selects it`() {
        val picked = mutableListOf<NavKey>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = { picked += it }) } }
        compose.onNodeWithText("Energy").performClick()
        assertEquals(listOf<NavKey>(EnergyKey), picked)
    }

    @Test
    fun `a restored stack on the same root is kept`() {
        val stack = mutableListOf<NavKey>(HomeKey, EnergyKey)
        resetToRoot(stack, HomeKey)
        assertEquals(listOf<NavKey>(HomeKey, EnergyKey), stack)
    }

    @Test
    fun `a new root replaces the whole stack`() {
        val stack = mutableListOf<NavKey>(OnboardingKey)
        resetToRoot(stack, HomeKey)
        assertEquals(listOf<NavKey>(HomeKey), stack)
        val tabs = mutableListOf<NavKey>(HomeKey, EnergyKey)
        resetToRoot(tabs, OnboardingKey)
        assertEquals(listOf<NavKey>(OnboardingKey), tabs)
    }

    @Test
    fun `side tabs replace each other on top of home`() {
        val stack = mutableListOf<NavKey>(HomeKey)
        selectTab(stack, EnergyKey)
        selectTab(stack, SecurityKey)
        assertEquals(listOf<NavKey>(HomeKey, SecurityKey), stack)
        selectTab(stack, SecurityKey)
        assertEquals(listOf<NavKey>(HomeKey, SecurityKey), stack)
        selectTab(stack, HomeKey)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun `tapping security selects it`() {
        val picked = mutableListOf<NavKey>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = { picked += it }) } }
        compose.onNodeWithText("Security").performClick()
        assertEquals(listOf<NavKey>(SecurityKey), picked)
    }
}
