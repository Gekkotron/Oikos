package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

/** The broker connection status; [onClick] null shows it as a plain status (the Energy header). */
@Composable
fun LinkPill(link: LinkState, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
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
    val clickLabel = stringResource(R.string.link_details)
    StatusPill(
        stringResource(label),
        tone,
        if (onClick == null) {
            modifier
        } else {
            modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
        },
    )
}
