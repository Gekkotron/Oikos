package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.ConnectionSummary

@Composable
internal fun ConnectionRows(summary: ConnectionSummary?, onEditHistory: () -> Unit, onEditFrigate: () -> Unit) {
    when (summary) {
        null -> Unit
        ConnectionSummary.Demo -> Text(stringResource(R.string.settings_demo), style = OikosTheme.type.body)
        is ConnectionSummary.Direct -> {
            val tags = listOfNotNull(
                stringResource(R.string.settings_tls).takeIf { summary.tls },
                stringResource(R.string.settings_ws).takeIf { summary.ws },
            )
            InfoRow(stringResource(R.string.settings_broker), summary.broker, tags.joinToString(" · ").ifEmpty { null })
            InfoRow(stringResource(R.string.settings_user), summary.username ?: stringResource(R.string.settings_none))
            InfoRow(stringResource(R.string.settings_prefix), summary.prefix)
            summary.gatewayUrl?.let { InfoRow(stringResource(R.string.settings_gateway), it) }
            InfoRow(
                stringResource(R.string.settings_history),
                summary.historyUrl ?: stringResource(R.string.settings_history_auto, summary.historyDefault),
                onClick = onEditHistory,
                onClickLabel = stringResource(R.string.settings_history_edit),
            )
            InfoRow(
                stringResource(R.string.settings_frigate),
                summary.frigateUrl ?: stringResource(R.string.settings_frigate_none),
                summary.frigateUser?.takeIf { summary.frigateHasPassword }?.let { stringResource(R.string.settings_frigate_login, it) },
                onClick = onEditFrigate,
                onClickLabel = stringResource(R.string.settings_history_edit),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, extra: String? = null, onClick: (() -> Unit)? = null, onClickLabel: String? = null) {
    val tap = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(tap).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted, modifier = Modifier.width(140.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = OikosTheme.type.body)
            if (extra != null) Text(extra, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        if (onClick != null) Text("›", style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
    }
}
