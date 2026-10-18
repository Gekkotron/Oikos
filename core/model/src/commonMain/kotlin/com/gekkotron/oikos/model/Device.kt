package com.gekkotron.oikos.model

data class Device(
    val id: String,
    /** Display name: the last segment of a hierarchical name such as `rooms/julien/lampe`. */
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val suggestedArea: String?,
    /** Room taken from a hierarchical name (second-to-last segment); used only without [suggestedArea]. */
    val pathArea: String? = null,
)
