package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** Arm modes and Disarm; an action that needs a code goes through [CodeKeypadSheet] first (security spec §4.2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmControls(state: AlarmState?, available: Boolean, caps: Capabilities, onSend: (Command) -> Unit, modifier: Modifier = Modifier) {
    val actions = AlarmActions.of(state, available, caps)
    var asking by remember { mutableStateOf<Command?>(null) }
    val request: (Command) -> Unit = { if (AlarmActions.needsCode(caps, it)) asking = it else onSend(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.arm.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in actions.arm) {
                    FilterChip(
                        selected = mode == actions.current,
                        onClick = { if (mode != actions.current) request(Command.Arm(mode)) },
                        enabled = actions.enabled,
                        label = { Text(stringResource(armLabel(mode))) },
                        modifier = Modifier.heightIn(min = OikosDimens.minTouch),
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
