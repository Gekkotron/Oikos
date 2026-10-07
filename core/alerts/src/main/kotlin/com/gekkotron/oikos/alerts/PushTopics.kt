package com.gekkotron.oikos.alerts

/** The OikosPush topic contract (push alerts spec §2.1). */
object PushTopics {
    const val DEFAULT_TOKEN_TOPIC = "oikos-push/tokens/{installId}"

    /** Oikos to producers; not retained. */
    const val RESPONSES = "oikos-push/responses"

    /** OikosPush to Oikos; retained JSON array of normalized alerts. */
    const val HISTORY = "oikos-push/history"

    /** The profile's `pushTokenTopic` (or the default) with `{installId}` substituted. */
    fun tokenTopic(template: String?, installId: String): String =
        (template?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_TOPIC).replace("{installId}", installId)
}
