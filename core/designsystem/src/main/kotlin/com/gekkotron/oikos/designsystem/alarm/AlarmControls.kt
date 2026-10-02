package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** Arm modes and Disarm; an action that needs a code goes through [CodeKeypadSheet] first (security spec §4.2). */
@Composable
fun AlarmControls(state: AlarmState?, available: Boolean, caps: Capabilities, onSend: (Command) -> Unit, modifier: Modifier = Modifier) {
    val actions = AlarmActions.of(state, available, caps)
    var asking by remember { mutableStateOf<Command?>(null) }
    val accent = alarmAccent(state, available, OikosTheme.colors)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val request: (Command) -> Unit = { if (AlarmActions.needsCode(caps, it)) asking = it else onSend(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.arm.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in actions.arm) {
                    val selected = mode == actions.current
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) request(Command.Arm(mode)) },
                        enabled = actions.enabled,
                        label = { Text(stringResource(armLabel(mode)), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
                        leadingIcon = if (selected) {
                            { StaticIcon(OikosGlyph.SHIELD, onAccent, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor = onAccent,
                            disabledSelectedContainerColor = accent.copy(alpha = 0.38f),
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = actions.enabled,
                            selected = selected,
                            borderColor = accent.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = OikosDimens.minTouch),
                    )
                }
            }
        }
        if (actions.disarm) {
            Button(
                onClick = { request(Command.Disarm()) },
                enabled = actions.enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.alarm_disarm)) }
        }
    }
    asking?.let { command ->
        CodeKeypadSheet(
            caps.codeFormat,
            onSubmit = { code ->
                asking = null
                onSend(
                    when (command) {
                        is Command.Arm -> command.copy(code = code)
                        is Command.Disarm -> command.copy(code = code)
                        else -> command
                    },
                )
            },
            onDismiss = { asking = null },
        )
    }
}
