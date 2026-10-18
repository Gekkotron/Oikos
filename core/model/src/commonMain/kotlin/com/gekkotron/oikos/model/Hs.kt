package com.gekkotron.oikos.model

/** A light colour without brightness: [hue] in degrees 0..<360, [saturation] 0..1. */
data class Hs(val hue: Double, val saturation: Double) {
    init {
        require(hue >= 0.0 && hue < 360.0) { "hue must be 0..<360" }
        require(saturation in 0.0..1.0) { "saturation must be 0..1" }
    }
}
