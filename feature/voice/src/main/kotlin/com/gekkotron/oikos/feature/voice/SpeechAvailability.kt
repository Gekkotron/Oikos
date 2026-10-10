package com.gekkotron.oikos.feature.voice

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

object SpeechAvailability {
    sealed interface Status {
        data object Ready : Status
        data object NeedsApi31 : Status
        data object NoOnDevice : Status
        data class LanguagePackMissing(val tag: String) : Status
    }

    fun check(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.NeedsApi31
        return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) Status.Ready else Status.NoOnDevice
    }
}
