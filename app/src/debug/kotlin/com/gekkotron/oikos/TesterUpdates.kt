package com.gekkotron.oikos

import android.app.Activity

/** Debug builds aren't distributed to testers: nothing to check. */
object TesterUpdates {
    fun check(activity: Activity) = Unit
}
