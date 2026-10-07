package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.AlertTimes
import java.time.Instant

/** One alert in a list (push alerts spec §7.1): a 56 dp thumbnail or the channel glyph, title, one line of body, the time. */
@Composable
fun AlertRow(alert: Alert, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OikosTheme.colors
    val context = LocalContext.current
    OikosCard(modifier.fillMaxWidth().testTag("alert-${alert.id}"), onClick = onClick, minHeight = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(c.card).border(1.dp, c.cardBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val image = alert.imageUrl
                if (image != null) {
                    AlertImage(image, Modifier.size(56.dp))
                } else {
                    StaticIcon(alert.channel.glyph(), modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(alert.title, style = OikosTheme.type.cardName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (alert.body.isNotBlank()) {
                    Text(alert.body, style = OikosTheme.type.label, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val time = AlertTimes.relative(context, alert.sentAt, now)
                Text(
                    if (alert.resolved) "$time · ${stringResource(R.string.alerts_resolved)}" else time,
                    style = OikosTheme.type.label,
                    color = c.contentMuted,
                )
            }
        }
    }
}
