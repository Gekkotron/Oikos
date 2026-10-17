package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Suspends until the source is connected and has had a brief window for retained-replay to populate
 * the entity map. Without this guard, launching the voice sheet on a cold start snapshots an empty
 * catalog and every utterance falls into NotUnderstood with no suggestions.
 *
 * Returns false if the broker never came up before [connectTimeout]; true otherwise, even when
 * [settleTimeout] expires with no entities (a house may genuinely have none).
 */
suspend fun awaitVoiceSourceReady(
    source: EntitySource,
    connectTimeout: Duration = 5.seconds,
    settleTimeout: Duration = 2.seconds,
): Boolean {
    val connected = withTimeoutOrNull(connectTimeout) {
        source.link.first { it is LinkState.Connected }
    } ?: return false
    if (source.entities.value.isEmpty()) {
        withTimeoutOrNull(settleTimeout) { source.entities.first { it.isNotEmpty() } }
    }
    return true
}
