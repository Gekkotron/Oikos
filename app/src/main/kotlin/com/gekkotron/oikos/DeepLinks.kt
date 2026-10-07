package com.gekkotron.oikos

import android.net.Uri

/** `oikos://alert/<id>`, the link every alert notification opens (push alerts spec §5.2). */
object DeepLinks {
    fun alertId(uri: Uri?): String? {
        if (uri == null || uri.scheme != "oikos" || uri.host != "alert") return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
