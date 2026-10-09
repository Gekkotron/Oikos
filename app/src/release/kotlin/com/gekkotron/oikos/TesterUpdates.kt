package com.gekkotron.oikos

import android.app.Activity
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status

/** Offers testers a newer App Distribution build, if there is one; called each time the app comes to the front. */
object TesterUpdates {
    private val gate = UpdateCheckGate()

    fun check(activity: Activity) {
        // No google-services.json in this build (e.g. CI unit tests): Firebase isn't initialized.
        if (FirebaseApp.getApps(activity).isEmpty()) return
        if (!gate.shouldCheck()) return
        FirebaseAppDistribution.getInstance().updateIfNewReleaseAvailable()
            .addOnFailureListener { Log.w("TesterUpdates", "update check failed", it) }
            .addOnCompleteListener { task ->
                val status = (task.exception as? FirebaseAppDistributionException)?.errorCode
                gate.finished(cancelled = status == Status.INSTALLATION_CANCELED || status == Status.AUTHENTICATION_CANCELED)
            }
    }
}
