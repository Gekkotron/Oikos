package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

/** Link status and the Plan 1 way out of a profile; Settings takes this over later (spec §5.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(link: LinkState, onSetUpAgain: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(linkDescription(link), style = OikosTheme.type.body)
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
    is LinkState.Offline ->
        if (link.reason == LinkState.Offline.REJECTED) stringResource(R.string.link_rejected) else stringResource(R.string.link_offline, link.reason)
}
