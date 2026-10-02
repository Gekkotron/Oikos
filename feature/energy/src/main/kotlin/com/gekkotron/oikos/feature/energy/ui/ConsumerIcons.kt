package com.gekkotron.oikos.feature.energy.ui

import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.model.ConsumerIcon

internal val ConsumerIcon.glyph: OikosGlyph
    get() = when (this) {
        ConsumerIcon.WATER_HEATER -> OikosGlyph.WATER_HEATER
        ConsumerIcon.HEAT_PUMP -> OikosGlyph.HEAT_PUMP
        ConsumerIcon.OVEN -> OikosGlyph.OVEN
        ConsumerIcon.FRIDGE -> OikosGlyph.FRIDGE
        ConsumerIcon.WASHER -> OikosGlyph.WASHER
        ConsumerIcon.DRYER -> OikosGlyph.DRYER
        ConsumerIcon.DISHWASHER -> OikosGlyph.DISHWASHER
    }
