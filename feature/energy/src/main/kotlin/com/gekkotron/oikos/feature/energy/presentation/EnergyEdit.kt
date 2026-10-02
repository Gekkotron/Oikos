package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.ConsumerIcon
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.UiOverride

/** The details sheet's editable fields for one sensor. */
data class SensorEdit(val name: String, val role: EnergyRole?, val invert: Boolean, val icon: ConsumerIcon? = null)

/**
 * Turns an edit into the sensor's new override. Only name, role, invert and icon change, so the room, favourite and
 * order set on Home survive; a blank name goes back to the discovered one, and an untouched name stays as stored.
 */
object EnergyEdit {
    fun from(entity: Entity) = SensorEdit(entity.name, entity.ui.energyRole, entity.ui.energyInvert, ConsumerIcon.of(entity.ui.energyIcon))

    fun apply(entity: Entity, edit: SensorEdit): UiOverride {
        val typed = edit.name.trim()
        return entity.ui.copy(
            name = when {
                typed == entity.name -> entity.ui.name
                typed.isEmpty() -> null
                else -> typed
            },
            energyRole = edit.role,
            energyInvert = edit.invert && edit.role?.signed == true,
            energyIcon = edit.icon?.key.takeIf { edit.role == EnergyRole.CONSUMER },
        )
    }
}
