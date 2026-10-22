package com.gekkotron.oikos.alerts

/** The push topic contract (`push-in-hub` spec §3). */
object PushTopics {
    const val DEFAULT_TOKEN_TOPIC = "oikos/push/tokens/{installId}"

    /** Oikos to producers; not retained. */
    const val RESPONSES = "oikos/push/responses"

    /** The hub to Oikos; retained JSON array of normalized alerts. */
    const val HISTORY = "oikos/push/history"

    /** Legacy OikosPush token topic kept so the one-shot cleanup can clear it. */
    private const val LEGACY_TOKEN_TOPIC = "oikos-push/tokens/{installId}"

    /** The profile's `pushTokenTopic` (or the default) with `{installId}` substituted. */
    fun tokenTopic(template: String?, installId: String): String =
        (template?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_TOPIC).replace("{installId}", installId)

    /** The legacy `oikos-push/tokens/<installId>` topic, cleared once after the hub takes over (spec §9). */
    fun legacyTokenTopic(installId: String): String = LEGACY_TOKEN_TOPIC.replace("{installId}", installId)
}
