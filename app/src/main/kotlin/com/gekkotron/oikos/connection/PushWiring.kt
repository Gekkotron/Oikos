package com.gekkotron.oikos.connection

import android.util.Log
import com.gekkotron.oikos.transport.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/** Tells the registrar each time the live link connects (push alerts spec §5.4 trigger 3). */
class PushWiring(
    private val link: StateFlow<LinkState>,
    private val onConnected: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            link.filterIsInstance<LinkState.Connected>().collect {
                try {
                    onConnected()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Oikos", "push registration check failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
