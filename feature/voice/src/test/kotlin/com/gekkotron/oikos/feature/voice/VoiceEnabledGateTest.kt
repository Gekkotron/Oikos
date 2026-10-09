package com.gekkotron.oikos.feature.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEnabledGateTest {
    @Test fun `waits for the first emission then returns true`() = runTest {
        val flow = MutableStateFlow<Boolean?>(null)
        var result: Boolean? = null
        launch { result = awaitVoiceEnabled(flow) }
        advanceUntilIdle()
        assertNull(result)
        flow.value = true
        advanceUntilIdle()
        assertEquals(true, result)
    }

    @Test fun `waits for the first emission then returns false`() = runTest {
        val flow = MutableStateFlow<Boolean?>(null)
        var result: Boolean? = null
        launch { result = awaitVoiceEnabled(flow) }
        advanceUntilIdle()
        assertNull(result)
        flow.value = false
        advanceUntilIdle()
        assertEquals(false, result)
    }
}
