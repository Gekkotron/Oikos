package com.gekkotron.oikos.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertTrue

/** Spec §5.3: text contrast of at least 4.5:1 in both themes. */
class ContrastTest {
    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b).toDouble()
    }

    private fun pairs(c: OikosColors): Map<String, Pair<Color, Color>> {
        val base = c.backgroundTop // the lighter end of the Night Glass gradient: worst case for light text
        return mapOf(
            "content on background" to (c.content to base),
            "content on card" to (c.content to c.card.compositeOver(base)),
            "muted on card" to (c.contentMuted to c.card.compositeOver(base)),
            "on-card content" to (c.contentOnCardOn to c.cardOnStart.compositeOver(base)),
            "chip" to (c.content to c.chip.compositeOver(base)),
            "selected chip" to (c.chipSelectedContent to c.chipSelected.compositeOver(base)),
            "pill connected" to (c.connected to c.connectedContainer.compositeOver(base)),
            "pill warning" to (c.warning to c.warningContainer.compositeOver(base)),
            "pill offline" to (c.offline to c.offlineContainer.compositeOver(base)),
        )
    }

    @Test
    fun `night glass text pairs reach 4_5 to 1`() = assertAll(NightGlassColors)

    @Test
    fun `warm minimal text pairs reach 4_5 to 1`() = assertAll(WarmMinimalColors)

    private fun assertAll(colors: OikosColors) {
        for ((name, pair) in pairs(colors)) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue(ratio >= 4.5, "$name is only %.2f:1".format(ratio))
        }
    }
}
