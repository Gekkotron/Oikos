package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.feature.home.presentation.OverrideDraft
import com.gekkotron.oikos.model.SwitchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the Show-as chip row's state transition (device sheet spec §5.1, §6.2 Type). */
class CustomizeSheetShowAsTest {
    private fun baseDraft(
        showAs: String? = null,
        securityAction: Boolean = false,
        securityAsk: Boolean = true,
    ) = OverrideDraft(
        name = "",
        areaId = "",
        favorite = false,
        hidden = false,
        order = "",
        cardStyle = null,
        energyRole = null,
        energyInvert = false,
        securityHidden = false,
        homePinned = false,
        securityAction = securityAction,
        securityAsk = securityAsk,
        grouped = true,
        showAs = showAs,
    )

    @Test
    fun `picking door writes the key`() {
        assertEquals("door", pickType(baseDraft(), SwitchType.DOOR).showAs)
        assertEquals("siren", pickType(baseDraft(), SwitchType.SIREN).showAs)
        assertEquals("chime", pickType(baseDraft(), SwitchType.CHIME).showAs)
        assertEquals("plug", pickType(baseDraft(), SwitchType.PLUG).showAs)
    }

    @Test
    fun `picking switch clears show as`() {
        assertNull(pickType(baseDraft(showAs = "door"), SwitchType.SWITCH).showAs)
        assertNull(pickType(baseDraft(showAs = "light"), SwitchType.SWITCH).showAs)
    }

    @Test
    fun `picking light clears a leftover security action`() {
        val d = baseDraft(securityAction = true, securityAsk = true)
        val next = pickType(d, SwitchType.LIGHT)
        assertEquals("light", next.showAs)
        assertFalse(next.securityAction)
        // The preference stays — Ask toggle is remembered across types.
        assertTrue(next.securityAsk)
    }

    @Test
    fun `picking door keeps security action`() {
        val next = pickType(baseDraft(securityAction = true), SwitchType.DOOR)
        assertEquals("door", next.showAs)
        assertTrue(next.securityAction)
    }
}
