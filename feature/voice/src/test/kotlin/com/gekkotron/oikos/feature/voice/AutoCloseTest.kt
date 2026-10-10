package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCloseTest {
    private val intent = VoiceIntent(VoiceAction.OPEN, listOf("a", "b"))

    private fun done(succeeded: List<String>, failed: List<Pair<String, String>> = emptyList()) =
        VoiceSessionState.Done(ExecutionResult(intent, VoiceSource.RULES, succeeded, failed))

    @Test fun `a fully successful command closes on its own`() {
        assertTrue(autoCloses(done(succeeded = listOf("a", "b"))))
    }

    @Test fun `a partly failed command stays open so the failures can be read`() {
        assertFalse(autoCloses(done(succeeded = listOf("a"), failed = listOf("b" to "offline"))))
    }

    @Test fun `a command where nothing ran stays open`() {
        assertFalse(autoCloses(done(succeeded = emptyList())))
    }

    @Test fun `only the Done state closes on its own`() {
        assertFalse(autoCloses(VoiceSessionState.Listening("")))
        assertFalse(autoCloses(VoiceSessionState.Executing(intent)))
        assertFalse(autoCloses(VoiceSessionState.NotUnderstood(emptyList())))
        assertFalse(autoCloses(VoiceSessionState.Error(ErrorKind.SILENCE)))
    }
}
