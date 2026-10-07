package com.gekkotron.oikos

import android.content.Intent
import android.net.Uri

/** `oikos://alert/<id>`, the link every alert notification opens (push alerts spec §5.2). */
object DeepLinks {
    fun alertId(uri: Uri?): String? {
        if (uri == null || uri.scheme != "oikos" || uri.host != "alert") return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /** The alert a fresh tap asks for; a relaunch from Recents re-delivers the old launch intent and must not replay it. */
    fun alertToOpen(intent: Intent?): String? {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return alertId(intent.data)
    }
}
