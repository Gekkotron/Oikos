package com.gekkotron.oikos.connection

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NetworkChangesTest {
    @Test
    fun `default network callbacks emit, and cancelling unregisters`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        var count = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { context.networkChanges().collect { count++ } }
        runCurrent()
        val callback = shadow.networkCallbacks.single()
        val before = count
        callback.onLost(ShadowNetwork.newInstance(7))
        runCurrent()
        callback.onAvailable(ShadowNetwork.newInstance(8))
        runCurrent()
        assertEquals(before + 2, count)
        job.cancel()
        runCurrent()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }
}
