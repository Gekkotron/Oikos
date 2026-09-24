package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme

enum class PillTone { CONNECTED, WARNING, OFFLINE }

@Composable
fun StatusPill(label: String, tone: PillTone, modifier: Modifier = Modifier) {
    val c = OikosTheme.colors
    val (fg, bg) = when (tone) {
        PillTone.CONNECTED -> c.connected to c.connectedContainer
        PillTone.WARNING -> c.warning to c.warningContainer
        PillTone.OFFLINE -> c.offline to c.offlineContainer
    }
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(label, style = OikosTheme.type.label, color = fg)
    }
}
