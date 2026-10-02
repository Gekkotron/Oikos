package com.gekkotron.oikos.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/** One stroke of a glyph in SVG path syntax on the 24-unit grid; a surface is filled as well as outlined. */
private class Part(val d: String, val surface: Boolean)

private fun line(d: String) = Part(d, surface = false)
private fun area(d: String) = Part(d, surface = true)

/** Static duotone glyphs (spec §2.5). The accent, when given, fills the surfaces (a lit bulb, a live plug). */
enum class OikosGlyph(private val parts: List<Part>) {
    BULB(listOf(area("M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"), line("M9.5 18.5h5M10.5 21h3"))),
    PLUG(listOf(area("M7 9h10v3a5 5 0 0 1-10 0z"), line("M9.5 4v5M14.5 4v5M12 17v4"))),
    SNOWFLAKE(listOf(line("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9.5 4.5 12 7l2.5-2.5M9.5 19.5 12 17l2.5 2.5"))),
    THERMOMETER(listOf(area("M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z"), line("M12 9v7"))),
    DROP(listOf(area("M12 3.5c3.5 4.2 6 7.6 6 10.5a6 6 0 0 1-12 0c0-2.9 2.5-6.3 6-10.5z"))),
    BOLT(listOf(area("M13 3 5.5 13.5H12L11 21l7.5-10.5H12z"))),
    TREND(listOf(line("M4 19h16M5 15l4-4 3 3 6-6M15 8h3v3"))),
    WALKER(listOf(area("M14 5.5a1.75 1.75 0 1 1-3.5 0 1.75 1.75 0 0 1 3.5 0z"), line("M12 9l-2.5 4.5 3 2.5V21M12 9l3 3.5 2.5.5M9.5 13.5 7 21M10.5 10.5 7.5 12"))),
    FLAME(listOf(area("M12 3c3 3.5 6 6.4 6 10.5a6 6 0 0 1-12 0c0-2.4 1.2-4.3 3-5.8.2 1.8 1 3 2.3 3.6C10.6 8.5 10.9 5.6 12 3z"))),
    SHIELD(listOf(area("M12 3 19 6v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"))),
    SLIDER(listOf(line("M4 8h16M4 16h16"), area("M10 8a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM18 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0z"))),
    LIST(listOf(line("M9 7h11M9 12h11M9 17h11M5 7h1M5 12h1M5 17h1"))),
    PLAY(listOf(area("M8 5.5v13l10.5-6.5z"))),
    DOT(listOf(area("M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0z"))),
    ;

    /** Parsed once per glyph. */
    internal val paths: List<Pair<Path, Boolean>> by lazy { parts.map { PathParser().parsePathString(it.d).toPath() to it.surface } }
}

@Composable
fun StaticIcon(glyph: OikosGlyph, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    glyph.paths.forEach { (path, isSurface) ->
        if (isSurface) surface(path, accent ?: neutral) else outline(path, neutral)
    }
}
