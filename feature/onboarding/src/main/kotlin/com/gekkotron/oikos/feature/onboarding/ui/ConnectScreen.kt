package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R
import com.gekkotron.oikos.feature.onboarding.TestState

@Composable
internal fun ConnectScreen(
    test: TestState,
    saving: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    saveFailed: Boolean = false,
) {
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    OnboardingColumn {
        when (test) {
            TestState.Connecting -> {
                Text(stringResource(R.string.connect_connecting), style = OikosTheme.type.display, modifier = announce)
                CircularProgressIndicator(color = OikosTheme.colors.content)
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
            is TestState.Failed -> {
                Text(stringResource(R.string.connect_failed), style = OikosTheme.type.display)
                Text(testFailureText(test.failure), style = OikosTheme.type.body, modifier = announce)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_retry)) }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
            is TestState.Connected -> {
                val count = test.devices.size
                Text(pluralStringResource(R.plurals.connect_discovering, count, count), style = OikosTheme.type.display, modifier = announce)
                if (count == 0) {
                    Text(stringResource(R.string.connect_no_devices), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
                }
                test.devices.forEach { Text(it, style = OikosTheme.type.body) }
                if (saveFailed) SaveFailedText()
                Button(onClick = onContinue, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_continue)) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
        }
    }
}
