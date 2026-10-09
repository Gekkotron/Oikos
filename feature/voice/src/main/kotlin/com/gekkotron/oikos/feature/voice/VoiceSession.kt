package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.voice.Confirmation
import com.gekkotron.oikos.voice.Suggestion
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceEngine
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceResolution
import com.gekkotron.oikos.voice.VoiceShortcut
import com.gekkotron.oikos.voice.VoiceShortcut.Companion.id
import com.gekkotron.oikos.voice.VoiceSource
import com.gekkotron.oikos.voice.VoiceText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceSessionState {
    data class Listening(val partial: String = "") : VoiceSessionState
    data class ConfirmNeeded(val intent: VoiceIntent, val source: VoiceSource, val codeFormat: CodeFormat?) : VoiceSessionState
    data class Executing(val intent: VoiceIntent) : VoiceSessionState
    data class Done(val result: ExecutionResult) : VoiceSessionState
    data class NotUnderstood(val suggestions: List<Suggestion>) : VoiceSessionState
    data class Error(val kind: ErrorKind) : VoiceSessionState
}

enum class ErrorKind { MQTT_DOWN, SPEECH, PERMISSION, SILENCE, UNKNOWN }

data class ExecutionResult(
    val intent: VoiceIntent,
    val source: VoiceSource,
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>,
)

class VoiceSession(
    private val catalog: VoiceCatalog,
    private val shortcuts: List<VoiceShortcut>,
    private val speech: SpeechCapture,
    private val runner: CommandRunner,
    private val scope: CoroutineScope,
    private val onLearn: suspend (VoiceShortcut) -> Unit = {},
) {
    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Listening(""))
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()
    private val engine = VoiceEngine(catalog, shortcuts)
    private var pendingIntent: VoiceIntent? = null
    private var pendingSource: VoiceSource = VoiceSource.RULES
    private var pendingCode: String? = null
    private var lastTranscript: String? = null

    fun start() {
        speech.start(object : SpeechCapture.Callbacks {
            override fun onPartial(text: String) { _state.value = VoiceSessionState.Listening(text) }
            override fun onFinal(text: String) {
                lastTranscript = text
                when (val r = engine.resolve(text)) {
                    is VoiceResolution.Understood -> handleIntent(r.intent, r.source)
                    is VoiceResolution.NotUnderstood -> _state.value = VoiceSessionState.NotUnderstood(r.suggestions)
                }
            }
            override fun onError(kind: SpeechError) {
                _state.value = VoiceSessionState.Error(
                    when (kind) {
                        SpeechError.NO_SPEECH -> ErrorKind.SILENCE
                        SpeechError.PERMISSION_DENIED -> ErrorKind.PERMISSION
                        else -> ErrorKind.SPEECH
                    }
                )
            }
        })
    }

    fun cancel() { speech.cancel() }

    fun pickSuggestion(suggestion: Suggestion, learnPhrase: String) {
        pendingIntent = suggestion.intent
        pendingSource = VoiceSource.RULES
        scope.launch {
            val learned = VoiceShortcut(
                id = id(catalog.lang, VoiceText.normalizeShortcut(learnPhrase, catalog.lang)),
                phrase = learnPhrase,
                lang = catalog.lang,
                intent = suggestion.intent,
            )
            try {
                onLearn(learned)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // learning is best-effort; keep executing the chosen command
            }
            handleIntent(suggestion.intent, VoiceSource.RULES)
        }
    }

    fun provideCode(code: String) { pendingCode = code }

    fun confirm(yes: Boolean) {
        val intent = pendingIntent ?: return
        if (!yes) {
            _state.value = VoiceSessionState.Done(ExecutionResult(intent, pendingSource, emptyList(), emptyList()))
            return
        }
        execute(intent, pendingSource, pendingCode)
    }

    private fun handleIntent(intent: VoiceIntent, source: VoiceSource) {
        pendingIntent = intent
        pendingSource = source
        if (Confirmation.needsConfirmation(intent, catalog)) {
            val format = intent.entityIds.firstNotNullOfOrNull { catalog.entity(it) }?.codeFormat
            _state.value = VoiceSessionState.ConfirmNeeded(intent, source, format)
            return
        }
        execute(intent, source, code = null)
    }

    private fun execute(intent: VoiceIntent, source: VoiceSource, code: String?) {
        _state.value = VoiceSessionState.Executing(intent)
        scope.launch {
            val result = try {
                runner.run(intent, catalog, code, source)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: MqttDownException) {
                _state.value = VoiceSessionState.Error(ErrorKind.MQTT_DOWN)
                return@launch
            } catch (e: Exception) {
                _state.value = VoiceSessionState.Error(ErrorKind.UNKNOWN)
                return@launch
            }
            _state.value = VoiceSessionState.Done(result)
        }
    }
}
