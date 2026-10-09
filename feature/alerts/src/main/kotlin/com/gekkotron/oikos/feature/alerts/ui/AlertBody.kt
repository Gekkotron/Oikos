package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.text.AlertText

/** The alert's body in the current language (localized alerts spec §5.4); reads LocalConfiguration so a language change recomposes it. */
@Composable
internal fun alertBody(alert: Alert): String {
    LocalConfiguration.current
    return AlertText.body(alert, LocalContext.current.resources)
}
