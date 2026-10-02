package com.gekkotron.oikos.feature.home.presentation

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchMatchTest {
    @Test
    fun `case and accents are ignored`() {
        assertTrue(SearchMatch.matches("cuisine", "Lampe", "Cuisine"))
        assertTrue(SearchMatch.matches("ECLAIRAGE", "Éclairage"))
        assertTrue(SearchMatch.matches("salle de bain", "Spot", "Salle de bain"))
    }

    @Test
    fun `every word must match, across fields`() {
        assertTrue(SearchMatch.matches("lampe salon", "Lampe", "Salon"))
        assertFalse(SearchMatch.matches("lampe garage", "Lampe", "Salon"))
    }

    @Test
    fun `a blank query matches everything and null fields are skipped`() {
        assertTrue(SearchMatch.matches("  ", "Anything"))
        assertTrue(SearchMatch.matches("plug", null, "Garage plug"))
    }
}
