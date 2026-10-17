package com.gekkotron.oikos.feature.voice

import android.speech.tts.TextToSpeech
import com.gekkotron.oikos.voice.VoiceLang
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsSpeakerTest {

    private class FakeTtsEngine(
        var availableResult: Int = TextToSpeech.LANG_AVAILABLE,
        var setLanguageResult: Int = TextToSpeech.LANG_AVAILABLE,
    ) : TtsEngine {
        val spoken = mutableListOf<Pair<String, Int>>()
        val languagesSet = mutableListOf<Locale>()
        val setLanguageCalls: Int get() = languagesSet.size
        var shutdownCalls = 0

        override fun setLanguage(locale: Locale): Int {
            languagesSet += locale
            return setLanguageResult
        }

        override fun isLanguageAvailable(locale: Locale): Int = availableResult

        override fun speak(text: String, queueMode: Int): Int {
            spoken += text to queueMode
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            shutdownCalls++
        }
    }

    private class Harness(
        lang: VoiceLang = VoiceLang.FR,
        availableResult: Int = TextToSpeech.LANG_AVAILABLE,
        setLanguageResult: Int = TextToSpeech.LANG_AVAILABLE,
    ) {
        val engine = FakeTtsEngine(availableResult, setLanguageResult)
        var listener: TextToSpeech.OnInitListener? = null
        val speaker = TtsSpeaker(lang) { l ->
            listener = l
            engine
        }

        fun init(status: Int) = listener!!.onInit(status)
    }

    @Test
    fun `availability starts Unknown and becomes Ready on init success with language available`() {
        val h = Harness()
        assertEquals(TtsAvailability.Unknown, h.speaker.availability.value)
        h.init(TextToSpeech.SUCCESS)
        assertEquals(TtsAvailability.Ready, h.speaker.availability.value)
        assertEquals(listOf(Locale.FRENCH), h.engine.languagesSet)
    }

    @Test
    fun `TtsSpeaker reports LanguageMissing when isLanguageAvailable is LANG_MISSING_DATA`() {
        val h = Harness(availableResult = TextToSpeech.LANG_MISSING_DATA)
        h.init(TextToSpeech.SUCCESS)
        assertEquals(TtsAvailability.LanguageMissing, h.speaker.availability.value)
    }

    @Test
    fun `TtsSpeaker reports LanguageMissing when setLanguage returns LANG_NOT_SUPPORTED`() {
        val h = Harness(
            availableResult = TextToSpeech.LANG_AVAILABLE,
            setLanguageResult = TextToSpeech.LANG_NOT_SUPPORTED,
        )
        h.init(TextToSpeech.SUCCESS)
        assertEquals(TtsAvailability.LanguageMissing, h.speaker.availability.value)
    }

    @Test
    fun `init callback fired synchronously inside factory is replayed once`() {
        val fake = FakeTtsEngine()
        val speaker = TtsSpeaker(VoiceLang.FR) { l ->
            l.onInit(TextToSpeech.SUCCESS)
            fake
        }
        assertEquals(TtsAvailability.Ready, speaker.availability.value)
        assertEquals(1, fake.setLanguageCalls)
    }

    @Test
    fun `availability becomes EngineMissing on init error`() {
        val h = Harness()
        h.init(TextToSpeech.ERROR)
        assertEquals(TtsAvailability.EngineMissing, h.speaker.availability.value)
    }

    @Test
    fun `speak flushes the queue (QUEUE_FLUSH)`() {
        val h = Harness(lang = VoiceLang.EN)
        h.init(TextToSpeech.SUCCESS)
        h.speaker.speak("hello")
        assertEquals(listOf("hello" to TextToSpeech.QUEUE_FLUSH), h.engine.spoken)
        assertEquals(listOf(Locale.ENGLISH), h.engine.languagesSet)
    }

    @Test
    fun `speak is a no-op when availability is LanguageMissing or EngineMissing`() {
        val missing = Harness(availableResult = TextToSpeech.LANG_NOT_SUPPORTED)
        missing.init(TextToSpeech.SUCCESS)
        missing.speaker.speak("x")
        assertTrue(missing.engine.spoken.isEmpty())

        val broken = Harness()
        broken.init(TextToSpeech.ERROR)
        broken.speaker.speak("x")
        assertTrue(broken.engine.spoken.isEmpty())

        val unknown = Harness()
        unknown.speaker.speak("x")
        assertTrue(unknown.engine.spoken.isEmpty())
    }

    @Test
    fun `release calls shutdown and ignores subsequent speak calls`() {
        val h = Harness()
        h.init(TextToSpeech.SUCCESS)
        h.speaker.release()
        h.speaker.speak("late")
        assertEquals(1, h.engine.shutdownCalls)
        assertTrue(h.engine.spoken.isEmpty())
    }
}
