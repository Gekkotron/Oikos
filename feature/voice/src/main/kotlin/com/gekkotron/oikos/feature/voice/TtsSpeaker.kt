package com.gekkotron.oikos.feature.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import com.gekkotron.oikos.voice.VoiceLang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsAvailability { Unknown, Ready, LanguageMissing, EngineMissing }

/** Tiny seam over [TextToSpeech] so JVM tests can double it. */
interface TtsEngine {
    fun setLanguage(locale: Locale): Int
    fun isLanguageAvailable(locale: Locale): Int
    fun speak(text: String, queueMode: Int): Int
    fun shutdown()
}

class TtsSpeaker(
    lang: VoiceLang,
    engineFactory: (OnInitListener) -> TtsEngine,
) {
    private val locale: Locale = when (lang) {
        VoiceLang.FR -> Locale.FRENCH
        VoiceLang.EN -> Locale.ENGLISH
    }

    private val _availability = MutableStateFlow(TtsAvailability.Unknown)
    val availability: StateFlow<TtsAvailability> = _availability.asStateFlow()

    @Volatile private var engine: TtsEngine? = null
    @Volatile private var released = false

    // Init may complete before the factory returns; keep the status until the engine is assigned.
    private var pendingStatus: Int? = null
    private val lock = Any()

    init {
        val created = engineFactory(OnInitListener { status -> onInit(status) })
        val replay = synchronized(lock) {
            engine = created
            pendingStatus.also { pendingStatus = null }
        }
        if (replay != null) onInit(replay)
    }

    private fun onInit(status: Int) {
        val e = synchronized(lock) {
            val current = engine
            if (current == null) {
                pendingStatus = status
                return
            }
            current
        }
        if (released) return
        if (status != TextToSpeech.SUCCESS) {
            _availability.value = TtsAvailability.EngineMissing
            return
        }
        val langStatus = e.isLanguageAvailable(locale)
        if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            _availability.value = TtsAvailability.LanguageMissing
            return
        }
        val setStatus = e.setLanguage(locale)
        _availability.value =
            if (setStatus == TextToSpeech.LANG_MISSING_DATA || setStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                TtsAvailability.LanguageMissing
            } else {
                TtsAvailability.Ready
            }
    }

    fun speak(text: String) {
        if (released || _availability.value != TtsAvailability.Ready) return
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH)
    }

    fun release() {
        if (released) return
        released = true
        engine?.shutdown()
        engine = null
    }
}

class AndroidTtsEngine(context: Context, listener: OnInitListener) : TtsEngine {
    private val tts = TextToSpeech(context, listener)

    override fun setLanguage(locale: Locale): Int = tts.setLanguage(locale)
    override fun isLanguageAvailable(locale: Locale): Int = tts.isLanguageAvailable(locale)
    override fun speak(text: String, queueMode: Int): Int = tts.speak(text, queueMode, null, null)
    override fun shutdown() = tts.shutdown()
}
