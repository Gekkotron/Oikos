package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsumersSheet(consumers: List<EnergyNode>, locale: Locale, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.energy_consumers_title), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            consumers.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).alpha(statusAlpha(n.status)).testTag("consumers-sheet-${n.id}").semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.name.orEmpty(), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
                    Text(nodeValueText(n, locale), style = OikosTheme.type.body)
                }
            }
        }
    }
}
