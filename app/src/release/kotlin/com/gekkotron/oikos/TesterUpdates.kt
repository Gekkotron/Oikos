package com.gekkotron.oikos

import android.app.Activity
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appdistribution.FirebaseAppDistribution

/** Offers testers a newer App Distribution build, if there is one. */
object TesterUpdates {
    fun check(activity: Activity) {
        // No google-services.json in this build (e.g. CI unit tests): Firebase isn't initialized.
        if (FirebaseApp.getApps(activity).isEmpty()) return
        FirebaseAppDistribution.getInstance().updateIfNewReleaseAvailable()
            .addOnFailureListener { Log.w("TesterUpdates", "update check failed", it) }
    }
}
