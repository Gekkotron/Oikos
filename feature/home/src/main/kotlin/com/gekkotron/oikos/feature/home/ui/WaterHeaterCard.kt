package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardFormatter
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HeaterView

/** "Inlet 17.9 °C": the inlet temperature in the heater's own unit, °C when it has none. */
@Composable
internal fun inletText(card: CardUi, inlet: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(R.string.water_heater_inlet, "${CardFormatter.number(inlet, locale)} ${card.unit ?: "°C"}")
}

/**
 * The water heater card body below the name (water heater view spec §5, after Oikos-os): the tank temperature
 * under a "Tank" caption, the activity chip and the cold-water inlet line. The tank glyph above already glows
 * while heating, since the card is "on" exactly then.
 */
@Composable
internal fun WaterHeaterDetails(card: CardUi, heater: HeaterView, valueText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(valueText, style = OikosTheme.type.cardValue)
            Text(
                stringResource(R.string.water_heater_tank),
                style = OikosTheme.type.label,
                color = LocalContentColor.current.copy(alpha = 0.72f),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        heater.chip?.let { chip -> HeaterChipPill(heaterChipText(chip), heating = heater.heating) }
        heater.inlet?.let { inlet ->
            val muted = LocalContentColor.current.copy(alpha = 0.72f)
            Row {
                // Decorative arrow, as in Oikos-os; only the text is read out.
                Text("↓ ", style = OikosTheme.type.label, color = muted, modifier = Modifier.clearAndSetSemantics { })
                Text(inletText(card, inlet), style = OikosTheme.type.label, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HeaterChipPill(text: String, heating: Boolean) {
    val c = OikosTheme.colors
    Surface(
        shape = RoundedCornerShape(50),
        color = if (heating) c.chipSelected else c.chip,
        contentColor = if (heating) c.chipSelectedContent else LocalContentColor.current,
        border = if (heating) null else BorderStroke(1.dp, c.chipBorder),
    ) {
        Text(
            text,
            style = OikosTheme.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
