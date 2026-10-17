package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.voice.Confirmation
import com.gekkotron.oikos.voice.Suggestion
import com.gekkotron.oikos.voice.VoiceAction
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
    data class Spoken(val text: String, val speakable: Boolean, val staleIds: List<String>) : VoiceSessionState
    data class Picking(
        val step: PickStep,
        val heardText: String,
        val filter: String,
        val pickedId: String? = null,
        val suggestions: List<Suggestion>,
    ) : VoiceSessionState
}

enum class PickStep { Device, Action }

/** A fully-run command or a spoken answer closes the sheet by itself (spec §3); failures and picking stay up. */
fun autoCloses(state: VoiceSessionState): Boolean = when (state) {
    is VoiceSessionState.Done -> state.result.succeeded.isNotEmpty() && state.result.failed.isEmpty()
    is VoiceSessionState.Spoken -> true
    else -> false
}

/** Delay before the sheet closes; 0 means no auto-close. Gate on [autoCloses] first. */
fun autoCloseDelayMs(state: VoiceSessionState): Long = when {
    state is VoiceSessionState.Spoken -> 5000L
    state is VoiceSessionState.Done && autoCloses(state) -> 2000L
    else -> 0L
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
    private val tts: TtsSpeaker? = null,
) {
    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Listening(""))
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()
    private val engine = VoiceEngine(catalog, shortcuts)
    private var pendingIntent: VoiceIntent? = null
    private var pendingSource: VoiceSource = VoiceSource.RULES
    private var pendingCode: String? = null
    private var lastSpoken: String? = null
    private var notUnderstoodSuggestions: List<Suggestion> = emptyList()
    var lastTranscript: String? = null
        private set

    fun start() {
        speech.start(object : SpeechCapture.Callbacks {
            override fun onPartial(text: String) { _state.value = VoiceSessionState.Listening(text) }
            override fun onFinal(text: String) {
                lastTranscript = text
                when (val r = engine.resolve(text)) {
                    is VoiceResolution.Understood -> handleIntent(r.intent, r.source)
                    is VoiceResolution.NotUnderstood -> {
                        notUnderstoodSuggestions = r.suggestions
                        _state.value = VoiceSessionState.NotUnderstood(r.suggestions)
                    }
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

    fun pickOther() {
        if (_state.value !is VoiceSessionState.NotUnderstood) return
        val heard = lastTranscript.orEmpty()
        _state.value = VoiceSessionState.Picking(
            step = PickStep.Device,
            heardText = heard,
            filter = heard,
            suggestions = notUnderstoodSuggestions,
        )
    }

    fun pickDevice(uniqueId: String) {
        val current = _state.value as? VoiceSessionState.Picking ?: return
        _state.value = current.copy(step = PickStep.Action, pickedId = uniqueId)
    }

    fun pickBack() {
        val current = _state.value as? VoiceSessionState.Picking ?: return
        _state.value = when (current.step) {
            PickStep.Action -> current.copy(step = PickStep.Device, pickedId = null)
            PickStep.Device -> VoiceSessionState.NotUnderstood(current.suggestions)
        }
    }

    fun pickFilterChange(filter: String) {
        val current = _state.value as? VoiceSessionState.Picking ?: return
        _state.value = current.copy(filter = filter)
    }

    fun pickAction(action: VoiceAction, remember: Boolean) {
        val current = _state.value as? VoiceSessionState.Picking ?: return
        val pickedId = current.pickedId ?: return
        val intent = VoiceIntent(action, listOf(pickedId))
        val teach = if (remember) {
            suspend {
                onLearnSafely(
                    VoiceShortcut(
                        id = id(catalog.lang, VoiceText.normalizeShortcut(current.heardText, catalog.lang)),
                        phrase = current.heardText,
                        lang = catalog.lang,
                        intent = intent,
                    )
                )
            }
        } else null
        handleIntent(intent, VoiceSource.RULES, teach)
    }

    fun repeatSpoken() {
        lastSpoken?.let { tts?.speak(it) }
    }

    private suspend fun onLearnSafely(shortcut: VoiceShortcut) {
        try {
            onLearn(shortcut)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // learning is best-effort
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

    private fun handleIntent(intent: VoiceIntent, source: VoiceSource, teach: (suspend () -> Unit)? = null) {
        pendingIntent = intent
        pendingSource = source
        if (intent.action == VoiceAction.QUERY_STATE) {
            query(intent, teach)
            return
        }
        if (Confirmation.needsConfirmation(intent, catalog)) {
            val format = intent.entityIds.firstNotNullOfOrNull { catalog.entity(it) }?.codeFormat
            _state.value = VoiceSessionState.ConfirmNeeded(intent, source, format)
            return
        }
        execute(intent, source, code = null, teach = teach)
    }

    private fun execute(intent: VoiceIntent, source: VoiceSource, code: String?, teach: (suspend () -> Unit)? = null) {
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
            if (result.succeeded.isNotEmpty()) teach?.invoke()
        }
    }

    private fun query(intent: VoiceIntent, teach: (suspend () -> Unit)?) {
        scope.launch {
            val answer = try {
                runner.query(intent, catalog).answer
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = VoiceSessionState.Error(ErrorKind.UNKNOWN)
                return@launch
            }
            lastSpoken = answer.spoken
            _state.value = VoiceSessionState.Spoken(answer.display, answer.speakable, answer.staleIds)
            if (answer.speakable) tts?.speak(answer.spoken)
            if (answer.speakable) teach?.invoke()
        }
    }
}
