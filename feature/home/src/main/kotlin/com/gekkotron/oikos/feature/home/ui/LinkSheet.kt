package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.RoutePicker
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.Route
import com.gekkotron.oikos.transport.TransportMode

/**
 * Link status, the route choice when the profile has a gateway, and the Plan 1 way out of a profile.
 * Settings takes this over later (spec §5.2; gateway spec §5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(
    link: LinkState,
    route: Route?,
    onRouteChange: (Route) -> Unit,
    onSetUpAgain: () -> Unit,
    onAssignRooms: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(linkDescription(link), style = OikosTheme.type.body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (route != null) RoutePicker(route, onRouteChange)
            OutlinedButton(onClick = onAssignRooms, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_assign_rooms)) }
            OutlinedButton(onClick = onSetUpAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_set_up_again)) }
        }
    }
}

@Composable
private fun linkDescription(link: LinkState): String = when (link) {
    LinkState.Connecting -> stringResource(R.string.link_connecting)
    is LinkState.Connected -> stringResource(
        when (link.mode) {
            TransportMode.DIRECT -> R.string.link_direct
            TransportMode.GATEWAY -> R.string.link_gateway
            TransportMode.DEMO -> R.string.link_demo
        },
    )
    is LinkState.Reconnecting -> stringResource(R.string.link_reconnecting, link.attempt)
    is LinkState.Offline -> stringResource(
        when (link.reason) {
            LinkState.Offline.REJECTED -> R.string.link_rejected
            LinkState.Offline.CLOSED -> R.string.link_offline_closed
            LinkState.Offline.PAUSED -> R.string.link_offline_paused
            LinkState.Offline.NOT_CONFIGURED -> R.string.link_offline_not_configured
            LinkState.Offline.NOT_STARTED -> R.string.link_offline_not_started
            LinkState.Offline.FAILED -> R.string.link_offline_failed
            LinkState.Offline.KEY_REJECTED -> R.string.link_key_rejected
            LinkState.Offline.CLOCK_SKEW -> R.string.link_clock_skew
            LinkState.Offline.GATEWAY_REFUSED -> R.string.link_gateway_refused
            // The reason constants are English; an unknown one is never shown raw.
            else -> R.string.link_offline
        },
    )
}
