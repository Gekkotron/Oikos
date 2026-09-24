package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.designsystem.components.PillTone
import com.gekkotron.oikos.designsystem.components.StatusPill
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

@Composable
internal fun LinkPill(link: LinkState, modifier: Modifier = Modifier) {
    val (label, tone) = when (link) {
        LinkState.Connecting -> R.string.pill_connecting to PillTone.WARNING
        is LinkState.Connected -> when (link.mode) {
            TransportMode.DIRECT -> R.string.pill_direct
            TransportMode.GATEWAY -> R.string.pill_gateway
            TransportMode.DEMO -> R.string.pill_demo
        } to PillTone.CONNECTED
        is LinkState.Reconnecting -> R.string.pill_reconnecting to PillTone.WARNING
        is LinkState.Offline -> R.string.pill_offline to PillTone.OFFLINE
    }
    StatusPill(stringResource(label), tone, modifier)
}
