package com.gekkotron.oikos.push

import kotlinx.coroutines.flow.Flow

/** Where a push registration goes, derived from the saved profile (push alerts spec §5.4). */
sealed interface PushTarget {
    /** No profile yet, or its secrets are locked. */
    data object None : PushTarget

    /** The demo house never registers (spec §7.6). */
    data object Demo : PushTarget

    data class Ready(val installId: String, val tokenTopic: String) : PushTarget
}

interface PushTargets {
    suspend fun current(): PushTarget

    val changes: Flow<PushTarget>
}
