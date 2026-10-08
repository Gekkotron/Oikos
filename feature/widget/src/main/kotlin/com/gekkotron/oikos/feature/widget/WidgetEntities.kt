package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState

enum class Failure { OFFLINE, AUTH_FAILED, NOT_CONFIGURED }

sealed interface RefreshResult {
    /** [complete]: discovery settled, so an id missing from [entities] is really gone. */
    data class Ok(val entities: Map<String, Entity>, val states: Map<String, EntityState>, val complete: Boolean, val historyEnabled: Boolean) : RefreshResult

    data class Failed(val reason: Failure) : RefreshResult
}

sealed interface SendResult {
    data class Confirmed(val entity: Entity, val state: EntityState, val historyEnabled: Boolean) : SendResult

    data object NotConfirmed : SendResult

    data class Failed(val reason: Failure) : SendResult
}

sealed interface CatalogResult {
    data class Ok(val entities: List<Entity>, val areas: List<Area>) : CatalogResult

    data class Failed(val reason: Failure) : CatalogResult
}

/** The widget's only door to the house (widgets spec §3.2); `:app` implements it, so this module never touches a transport. */
interface WidgetEntities {
    suspend fun refresh(ids: Set<String>): RefreshResult

    suspend fun send(id: String, command: Command): SendResult

    /** Every entity, for the picker: live, else fresh from the broker, else from the cache when offline. */
    suspend fun catalog(): CatalogResult
}
