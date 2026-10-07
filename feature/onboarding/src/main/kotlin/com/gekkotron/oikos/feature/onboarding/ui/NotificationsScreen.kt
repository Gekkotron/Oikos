package com.gekkotron.oikos.feature.onboarding.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.permissions.openNotificationSettings
import com.gekkotron.oikos.designsystem.permissions.rememberNotificationEnabler
import com.gekkotron.oikos.feature.onboarding.R

/** The last onboarding step, after rooms (push alerts spec §7.5). [onDone] runs whatever the user answers. */
@Composable
fun NotificationsRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val ask = rememberNotificationEnabler(onResult = onDone)
    val onEnable: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        when {
            !canAsk -> onDone()
            !granted -> ask()
            // Already allowed (it survives "Set up again"): nothing to ask. Switched off for the app: show the settings, but never strand the step.
            else -> {
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) openNotificationSettings(context)
                onDone()
            }
        }
    }
    NotificationsScreen(canAsk, onEnable = onEnable, onSkip = onDone)
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
