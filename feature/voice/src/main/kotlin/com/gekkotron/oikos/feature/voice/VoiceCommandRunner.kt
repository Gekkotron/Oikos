package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.voice.CommandMapper
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MqttDownException : RuntimeException("mqtt is not connected")

interface CommandRunner {
    suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String? = null, source: VoiceSource = VoiceSource.RULES): ExecutionResult
}

open class VoiceCommandRunner(private val entitySource: EntitySource) : CommandRunner {
    override suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String?, source: VoiceSource): ExecutionResult = coroutineScope {
        if (entitySource.link.value !is LinkState.Connected) throw MqttDownException()
        val commands = CommandMapper.map(intent, catalog, code)
        val results = commands.map { (id, cmd) ->
            async {
                val r = entitySource.send(id, cmd)
                id to r
            }
        }.awaitAll()
        val succeeded = results.filter { it.second is CommandResult.Accepted }.map { it.first }
        val failed = results.mapNotNull { (id, r) -> if (r is CommandResult.Rejected) id to r.reason else null }
        ExecutionResult(intent, source, succeeded, failed)
    }
}
