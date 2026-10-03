package com.gekkotron.oikos.designsystem.icons

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class OikosGlyphTest {
    @Test
    fun `glyphs parse to non empty paths`() {
        for (glyph in listOf(OikosGlyph.SUN, OikosGlyph.HOUSE, OikosGlyph.PYLON, OikosGlyph.BATTERY, OikosGlyph.SEARCH, OikosGlyph.CLOSE, OikosGlyph.GEAR, OikosGlyph.DRAG_HANDLE)) {
            assertTrue(glyph.paths.isNotEmpty(), "$glyph has no parts")
            assertTrue(glyph.paths.all { (path, _) -> !path.isEmpty }, "$glyph has an empty path")
        }
    }
}
