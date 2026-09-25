package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.R
import java.util.Locale

/** English entity labels published by discovery sources (Z2M, Tasmota, ESPHome…) → a translated string resource. */
object EntityLabels {
    /** trim, lowercase (Locale.ROOT), '_' and '-' become spaces, runs of whitespace collapse to one space. */
    fun normalize(label: String): String =
        label.trim().lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")

    /** The string resource for [label], or null when the label is not a known one. */
    fun resId(label: String): Int? = map[normalize(label)]

    internal val keys: Set<String> get() = map.keys

    private val map: Map<String, Int> = mapOf(
        "temperature" to R.string.entity_label_temperature,
        "local temperature" to R.string.entity_label_local_temperature,
        "humidity" to R.string.entity_label_humidity,
        "pressure" to R.string.entity_label_pressure,
        "battery" to R.string.entity_label_battery,
        "battery low" to R.string.entity_label_battery_low,
        "voltage" to R.string.entity_label_voltage,
        "current" to R.string.entity_label_current,
        "power" to R.string.entity_label_power,
        "energy" to R.string.entity_label_energy,
        "illuminance" to R.string.entity_label_illuminance,
        "illuminance lux" to R.string.entity_label_illuminance,
        "occupancy" to R.string.entity_label_occupancy,
        "presence" to R.string.entity_label_presence,
        "motion" to R.string.entity_label_motion,
        "contact" to R.string.entity_label_contact,
        "door" to R.string.entity_label_door,
        "window" to R.string.entity_label_window,
        "water leak" to R.string.entity_label_water_leak,
        "smoke" to R.string.entity_label_smoke,
        "gas" to R.string.entity_label_gas,
        "carbon monoxide" to R.string.entity_label_carbon_monoxide,
        "co2" to R.string.entity_label_co2,
        "voc" to R.string.entity_label_voc,
        "formaldehyde" to R.string.entity_label_formaldehyde,
        "pm25" to R.string.entity_label_pm25,
        "pm10" to R.string.entity_label_pm10,
        "vibration" to R.string.entity_label_vibration,
        "tamper" to R.string.entity_label_tamper,
        "action" to R.string.entity_label_action,
        "state" to R.string.entity_label_state,
        "position" to R.string.entity_label_position,
        "tilt" to R.string.entity_label_tilt,
        "moving" to R.string.entity_label_moving,
        "brightness" to R.string.entity_label_brightness,
        "color temp" to R.string.entity_label_color_temp,
        "soil moisture" to R.string.entity_label_soil_moisture,
        "rain" to R.string.entity_label_rain,
        "wind speed" to R.string.entity_label_wind_speed,
        "noise" to R.string.entity_label_noise,
        "alarm" to R.string.entity_label_alarm,
        "lock" to R.string.entity_label_lock,
        "valve" to R.string.entity_label_valve,
        "heating" to R.string.entity_label_heating,
        "cooling" to R.string.entity_label_cooling,
        "running state" to R.string.entity_label_running_state,
        "system mode" to R.string.entity_label_system_mode,
        "preset" to R.string.entity_label_preset,
        "fan mode" to R.string.entity_label_fan_mode,
        "child lock" to R.string.entity_label_child_lock,
        "led indication" to R.string.entity_label_led_indication,
    )
}
