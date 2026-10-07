package com.gekkotron.oikos.feature.settings.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.push.PushStatus
import java.time.Duration
import java.time.Instant

/** The push status in words (push alerts spec §7.4). */
@Composable
internal fun pushStatusText(status: PushStatus): String = when (status) {
    is PushStatus.Registered -> stringResource(R.string.settings_push_registered, ageText(status.at))
    PushStatus.Waiting -> stringResource(R.string.settings_push_waiting)
    is PushStatus.Failed -> stringResource(R.string.settings_push_failed, status.reason)
    PushStatus.NotificationsOff -> stringResource(R.string.settings_push_off)
    PushStatus.Demo -> stringResource(R.string.settings_push_demo)
}

@Composable
private fun ageText(at: Instant): String {
    val now = Instant.now()
    if (Duration.between(at, now).seconds < 60) return stringResource(R.string.settings_push_just_now)
    return DateUtils.getRelativeTimeSpanString(
        at.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushSheet(status: PushStatus, onRegisterAgain: () -> Unit, onOpenChannels: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_push), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(pushStatusText(status), style = OikosTheme.type.body)
            FilledTonalButton(
                onClick = { onRegisterAgain(); onDismiss() },
                enabled = status != PushStatus.Demo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_push_register_again)) }
            OutlinedButton(onClick = { onOpenChannels(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_push_channels))
            }
        }
    }
}
