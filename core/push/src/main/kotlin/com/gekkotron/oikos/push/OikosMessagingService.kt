package com.gekkotron.oikos.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Receives OikosPush's data messages (push alerts spec §5.2). FCM runs this off the main thread, so it may block. */
class OikosMessagingService : FirebaseMessagingService() {
    private val component: PushComponent get() = (applicationContext as PushComponentProvider).pushComponent()

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            runBlocking(Dispatchers.IO) { component.ingestor.onMessage(message.data) }
        } catch (e: Exception) {
            Log.w(TAG, "an alert could not be handled: ${e.javaClass.simpleName}")
        }
    }

    override fun onNewToken(token: String) {
        component.registrar.request()
    }

    private companion object {
        const val TAG = "OikosPush"
    }
}
