package com.gekkotron.oikos.designsystem.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice

@Composable
private fun Sheet(choice: ThemeChoice) = OikosTheme(choice) {
    val c = OikosTheme.colors
    Column(Modifier.background(c.backgroundBottom).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (t in listOf(0f, 0.5f, 1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val accent = if (t > 0f) c.warning else c.connected
                ShutterIcon(t, Modifier.size(40.dp))
                DoorIcon(t, accent, Modifier.size(40.dp))
                WindowIcon(t, accent, Modifier.size(40.dp))
                GarageIcon(t, accent, Modifier.size(40.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OikosGlyph.entries.forEach { StaticIcon(it, if (it == OikosGlyph.BULB) c.warning else null) }
        }
    }
}

@Preview(name = "Icons — Night Glass")
@Composable
private fun IconsNightGlass() = Sheet(ThemeChoice.NIGHT_GLASS)

@Preview(name = "Icons — Warm Minimal")
@Composable
private fun IconsWarmMinimal() = Sheet(ThemeChoice.WARM_MINIMAL)
