package com.gekkotron.oikos.feature.voice

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.voice.Suggestion
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceCatalogBuilder
import com.gekkotron.oikos.voice.VoiceLang
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val source: EntitySource,
    val preferences: AppPreferences,
    private val commandRunner: CommandRunner,
    private val tts: TtsSpeaker,
) : ViewModel() {
    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Listening(""))
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()
    private var session: VoiceSession? = null
    private var starting = false

    val started: Boolean get() = session != null || starting

    fun start(launcherLang: VoiceLang) {
        if (started) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _state.value = VoiceSessionState.Error(ErrorKind.SPEECH)
            return
        }
        starting = true
        viewModelScope.launch {
            try {
                if (!awaitVoiceSourceReady(source)) {
                    _state.value = VoiceSessionState.Error(ErrorKind.MQTT_DOWN)
                    return@launch
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) launchSession(launcherLang)
            } finally {
                starting = false
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun launchSession(launcherLang: VoiceLang) {
        val catalog = VoiceCatalogBuilder.build(launcherLang, source.entities.value.values, source.areas.value)
        val speech = try {
            AndroidSpeechCapture(appContext, if (launcherLang == VoiceLang.FR) "fr-FR" else "en-US")
        } catch (e: IllegalStateException) {
            _state.value = VoiceSessionState.Error(ErrorKind.SPEECH)
            return
        }
        val s = VoiceSession(
            catalog = catalog,
            shortcuts = source.shortcuts.value.values.toList(),
            speech = speech,
            runner = commandRunner,
            scope = viewModelScope,
            onLearn = { source.saveShortcut(it) },
            tts = tts,
        )
        session = s
        viewModelScope.launch { s.state.collect { _state.value = it } }
        s.start()
    }

    fun pickSuggestion(suggestion: Suggestion) {
        val s = session ?: return
        s.pickSuggestion(suggestion, learnPhrase = s.lastTranscript.orEmpty())
    }

    fun onPickOther() { session?.pickOther() }

    fun onPickDevice(uniqueId: String) { session?.pickDevice(uniqueId) }

    fun onPickAction(action: VoiceAction, remember: Boolean) { session?.pickAction(action, remember) }

    fun onPickBack() { session?.pickBack() }

    fun onPickFilterChange(filter: String) { session?.pickFilterChange(filter) }

    fun onRepeatSpoken() { session?.repeatSpoken() }

    fun confirm(yes: Boolean) { session?.confirm(yes) }

    fun provideCode(code: String) { session?.provideCode(code) }

    fun cancel() { session?.cancel() }

    override fun onCleared() { cancel() }
}

/** Suspends until DataStore has emitted the real preference (the flow is seeded with null). */
suspend fun awaitVoiceEnabled(flow: StateFlow<Boolean?>): Boolean = flow.filterNotNull().first()
