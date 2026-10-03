package com.gekkotron.oikos.model

/** Which tabs show an entity. Home leaves out what Energy or Security already shows, unless pinned. */
object Placement {
    /** Shown on Security by default: alarm panels and binary sensors of a security class. */
    fun securityKind(kind: Kind, deviceClass: String?): Boolean =
        kind == Kind.ALARM_PANEL || (kind == Kind.BINARY_SENSOR && SensorGroup.of(deviceClass) != null)

    /** A kind the user can opt into Security as an action (security actions spec §2). */
    fun actionKind(kind: Kind): Boolean = kind == Kind.SWITCH || kind == Kind.BUTTON

    fun onSecurity(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        (!ui.securityHidden && securityKind(kind, deviceClass)) || (ui.securityAction && actionKind(kind))

    /** Energy (it has a role) or Security already shows it. */
    fun elsewhere(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        ui.energyRole != null || onSecurity(kind, deviceClass, ui)

    fun onHome(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        !ui.hidden && (ui.homePinned || !elsewhere(kind, deviceClass, ui))
}
