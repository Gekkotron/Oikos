package com.gekkotron.oikos.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

enum class SpeechError { NO_SPEECH, LANGUAGE_UNAVAILABLE, SERVICE_UNAVAILABLE, PERMISSION_DENIED, UNKNOWN }

interface SpeechCapture {
    fun start(callbacks: Callbacks)
    fun cancel()

    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(kind: SpeechError)
    }
}

/** Default Android on-device recognizer (API 31+). */
@RequiresApi(Build.VERSION_CODES.S)
class AndroidSpeechCapture(
    private val context: Context,
    private val localeTag: String,
) : SpeechCapture {
    private var recognizer: SpeechRecognizer? = null

    override fun start(callbacks: SpeechCapture.Callbacks) {
        cancel()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(callbacks::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                callbacks.onFinal(text)
            }
            override fun onError(error: Int) {
                val kind = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechError.LANGUAGE_UNAVAILABLE
                    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.SERVICE_UNAVAILABLE
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_DENIED
                    else -> SpeechError.UNKNOWN
                }
                if (kind == SpeechError.LANGUAGE_UNAVAILABLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Spec §2.2: request the language pack from Google Speech.
                    val checkIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    }
                    runCatching { r.triggerModelDownload(checkIntent) }
                }
                callbacks.onError(kind)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    override fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }
}
