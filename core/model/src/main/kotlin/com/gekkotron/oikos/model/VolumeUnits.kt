package com.gekkotron.oikos.model

/** The water volume units Home Assistant reports (its `UnitOfVolume` for the `water` device class). */
object VolumeUnits {
    /** Any water volume unit: L, mL, m³, ft³, gal, CCF. Case and the ASCII "3" for "³" don't matter. */
    fun isVolume(unit: String?): Boolean = key(unit) in ALL

    /** A unit a meter index is kept in (m³, ft³, CCF), rather than a day's usage. */
    fun isMeterScale(unit: String?): Boolean = key(unit) in METER_SCALE

    private fun key(unit: String?) = unit?.trim()?.lowercase()?.replace('3', '³')

    private val METER_SCALE = setOf("m³", "ft³", "ccf")
    private val ALL = METER_SCALE + setOf("l", "ml", "gal")
}
