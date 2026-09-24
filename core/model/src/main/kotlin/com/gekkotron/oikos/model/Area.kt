package com.gekkotron.oikos.model

import kotlinx.serialization.Serializable

const val UNASSIGNED_AREA = "Unassigned"

@Serializable
data class Area(val id: String, val name: String, val icon: String? = null, val order: Int? = null)

/** Retained payload of `oikos/ui/_areas`. */
@Serializable
data class AreasDocument(val areas: List<Area> = emptyList())
