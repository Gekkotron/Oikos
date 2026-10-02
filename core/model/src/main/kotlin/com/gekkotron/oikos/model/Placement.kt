package com.gekkotron.oikos.model

/** Which tabs show an entity. Home leaves out what Energy or Security already shows, unless pinned. */
object Placement {
    /** Its kind and class let it appear on Security. */
    fun securityKind(kind: Kind, deviceClass: String?): Boolean =
        kind == Kind.ALARM_PANEL || (kind == Kind.BINARY_SENSOR && SensorGroup.of(deviceClass) != null)

    fun onSecurity(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        !ui.securityHidden && securityKind(kind, deviceClass)

    /** Energy (it has a role) or Security already shows it. */
    fun elsewhere(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        ui.energyRole != null || onSecurity(kind, deviceClass, ui)

    fun onHome(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        !ui.hidden && (ui.homePinned || !elsewhere(kind, deviceClass, ui))
}
