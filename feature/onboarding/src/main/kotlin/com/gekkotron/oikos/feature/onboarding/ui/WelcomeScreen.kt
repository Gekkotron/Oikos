package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R

@Composable
internal fun WelcomeScreen(locked: Boolean, busy: Boolean, onScan: () -> Unit, onManual: () -> Unit, onDemo: () -> Unit) {
    OnboardingColumn {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.welcome_title), style = OikosTheme.type.display)
        Text(stringResource(R.string.welcome_tagline), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
        if (locked) {
            Text(
                stringResource(R.string.welcome_locked),
                style = OikosTheme.type.body,
                color = OikosTheme.colors.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(OikosTheme.colors.warningContainer)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_scan)) }
        OutlinedButton(onClick = onManual, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_manual)) }
        TextButton(onClick = onDemo, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_demo)) }
    }
}
