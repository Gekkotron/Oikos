package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.voice.Answer
import com.gekkotron.oikos.voice.CommandMapper
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceSource

class FakeSpeechCapture : SpeechCapture {
    private var cb: SpeechCapture.Callbacks? = null
    private var pending: String? = null
    override fun start(callbacks: SpeechCapture.Callbacks) { cb = callbacks; pending?.let { emitFinal(it); pending = null } }
    override fun cancel() { cb = null }
    fun scheduleFinal(text: String) { pending = text }
    fun emitFinal(text: String) { cb?.onFinal(text) }
    fun emitError(kind: SpeechError) { cb?.onError(kind) }
}

class FakeRunner(
    private val mqttDown: Boolean = false,
    private val resultByEntity: Map<String, CommandResult> = emptyMap(),
) : CommandRunner {
    val sent = mutableListOf<Pair<String, Command>>()
    override suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String?, source: VoiceSource): ExecutionResult {
        if (mqttDown) throw MqttDownException()
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for ((id, cmd) in CommandMapper.map(intent, catalog, code)) {
            sent += id to cmd
            when (val r = resultByEntity[id] ?: CommandResult.Accepted) {
                CommandResult.Accepted -> succeeded += id
                is CommandResult.Rejected -> failed += id to r.reason
            }
        }
        return ExecutionResult(intent, source, succeeded, failed)
    }
    override suspend fun query(intent: VoiceIntent, catalog: VoiceCatalog): QueryResult =
        QueryResult(Answer("", "", speakable = false, staleIds = emptyList()))
}
