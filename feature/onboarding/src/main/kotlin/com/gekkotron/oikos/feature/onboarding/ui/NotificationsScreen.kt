package com.gekkotron.oikos.feature.onboarding.ui

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.permissions.rememberNotificationEnabler
import com.gekkotron.oikos.feature.onboarding.R

/** The last onboarding step, after rooms (push alerts spec §7.5). [onDone] runs whatever the user answers. */
@Composable
fun NotificationsRoute(onDone: () -> Unit) {
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val ask = rememberNotificationEnabler(onResult = onDone)
    NotificationsScreen(canAsk, onEnable = if (canAsk) ask else onDone, onSkip = onDone)
}

@Composable
internal fun NotificationsScreen(canAsk: Boolean, onEnable: () -> Unit, onSkip: () -> Unit) {
    OnboardingColumn {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.notifications_title), style = OikosTheme.type.display)
        Text(stringResource(R.string.notifications_body), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (canAsk) R.string.notifications_turn_on else R.string.notifications_continue))
        }
        if (canAsk) {
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.notifications_not_now)) }
        }
    }
}
