package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.transport.Route

/** One option of a radio group; the whole row is the touch target. */
@Composable
fun OikosRadioRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = OikosTheme.type.body)
    }
}

/** How to reach a profile that has a gateway (gateway spec §4); shared by the link sheet and Settings. */
@Composable
fun RoutePicker(selected: Route, onSelect: (Route) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.route_title), style = OikosTheme.type.label, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            OikosRadioRow(stringResource(R.string.route_auto), selected == Route.AUTO, { onSelect(Route.AUTO) })
            OikosRadioRow(stringResource(R.string.route_direct), selected == Route.DIRECT, { onSelect(Route.DIRECT) })
            OikosRadioRow(stringResource(R.string.route_gateway), selected == Route.GATEWAY, { onSelect(Route.GATEWAY) })
        }
    }
}
