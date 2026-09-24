package com.gekkotron.oikos.model

data class Device(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val suggestedArea: String?,
)
