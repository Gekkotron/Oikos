package com.gekkotron.oikos.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The FCM token behind a seam, so unit tests need no Firebase. */
interface FcmTokens {
    /** @throws FirebaseUnavailable in a build without a Firebase configuration. */
    suspend fun token(): String

    suspend fun delete()
}

/** Debug builds carry no `google-services.json` (the plugin task is disabled), so there is no FirebaseApp. */
class FirebaseUnavailable : Exception("Firebase is not configured in this build")

class FirebaseFcmTokens(private val context: Context) : FcmTokens {
    override suspend fun token(): String {
        if (FirebaseApp.getApps(context).isEmpty()) throw FirebaseUnavailable()
        return FirebaseMessaging.getInstance().token.await()
    }

    override suspend fun delete() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
