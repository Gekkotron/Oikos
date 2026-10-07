package com.gekkotron.oikos.alerts

import java.time.Instant

/** The seven notification channels (push alerts spec §1); the id is also the Android channel id. */
enum class AlertChannel(val id: String) {
    ALARM("alarm"),
    SECURITY("security"),
    CAMERA("camera"),
    ENERGY("energy"),
    CLIMATE("climate"),
    SYSTEM("system"),
    MISC("misc"),
    ;

    companion object {
        /** An unknown or missing id is [MISC] (spec §2.2). */
        fun fromId(id: String?): AlertChannel = entries.firstOrNull { it.id == id } ?: MISC
    }
}

enum class Severity(val id: String) {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical"),
    ;

    companion object {
        fun fromId(id: String?): Severity = entries.firstOrNull { it.id == id } ?: INFO
    }
}

data class AlertAction(val id: String, val label: String)

/**
 * One normalized alert (spec §4.1). [resolved] is also what a cancel parses to: no other path sets it.
 * [receivedAt] is when this phone got it; for a history entry it is [sentAt].
 */
data class Alert(
    val id: String,
    val channel: AlertChannel,
    val severity: Severity,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val entityId: String?,
    val actions: List<AlertAction>,
    val source: String?,
    val silent: Boolean,
    val resolved: Boolean,
    val sentAt: Instant,
    val receivedAt: Instant,
) {
    /** A notification and the sheet show at most [MAX_ACTIONS] buttons (spec §2.2). */
    val shownActions: List<AlertAction> get() = actions.take(MAX_ACTIONS)

    companion object {
        const val MAX_ACTIONS = 3
    }
}
