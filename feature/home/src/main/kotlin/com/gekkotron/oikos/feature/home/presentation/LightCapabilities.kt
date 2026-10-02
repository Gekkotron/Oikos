package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Capabilities

private val COLOR_MODES = setOf("xy", "hs", "rgb", "rgbw", "rgbww")

/** The light takes a colour (LED spec §3.3): colour temperature, brightness or on/off alone do not count. */
val Capabilities.hasColor: Boolean get() = colorModes.any { it in COLOR_MODES }
