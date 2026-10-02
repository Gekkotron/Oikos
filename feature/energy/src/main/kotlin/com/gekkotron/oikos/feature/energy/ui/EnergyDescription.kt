package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyFormat
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import java.util.Locale

/** The diagram as one TalkBack sentence (spec §5); unknown values are left out. */
@Composable
internal fun energyDescription(state: EnergyUiState, locale: Locale): String {
    val kw = stringResource(R.string.energy_spoken_kw)
    val w = stringResource(R.string.energy_spoken_w)
    fun spoken(watts: Double) = (if (watts >= 1_000) kw else w).format(EnergyFormat.spokenNumber(watts, locale))

    val solar = stringResource(R.string.energy_cd_solar)
    val house = stringResource(R.string.energy_cd_house)
    val gridIn = stringResource(R.string.energy_cd_grid_in)
    val gridOut = stringResource(R.string.energy_cd_grid_out)
    val batteryIn = stringResource(R.string.energy_cd_battery_in)
    val batteryOut = stringResource(R.string.energy_cd_battery_out)
    val consumer = stringResource(R.string.energy_cd_consumer)
    val water = stringResource(R.string.energy_cd_water)

    fun part(n: EnergyNode): String? {
        val v = n.value ?: return null
        return when (n.kind) {
            NodeKind.SOLAR -> solar.format(spoken(v))
            NodeKind.HOUSE -> house.format(spoken(v))
            NodeKind.GRID -> when (n.flow) {
                Flow.IN -> gridIn.format(spoken(v))
                Flow.OUT -> gridOut.format(spoken(v))
                Flow.IDLE -> null
            }
            NodeKind.BATTERY -> when (n.flow) {
                Flow.IN -> batteryIn.format(spoken(v))
                Flow.OUT -> batteryOut.format(spoken(v))
                Flow.IDLE -> null
            }
            NodeKind.CONSUMER -> consumer.format(n.name.orEmpty(), spoken(v))
            NodeKind.WATER -> water.format(EnergyFormat.amount(v, n.unit, locale))
            NodeKind.MORE -> null
        }
    }
    // Every consumer is read, including the folded ones, so TalkBack users need no sheet.
    return (state.diagram.filter { it.kind != NodeKind.CONSUMER && it.kind != NodeKind.MORE && it.kind != NodeKind.WATER } +
        state.consumers + listOfNotNull(state.cards.firstOrNull { it.kind == NodeKind.WATER }))
        .mapNotNull(::part)
        .joinToString(" ")
}
