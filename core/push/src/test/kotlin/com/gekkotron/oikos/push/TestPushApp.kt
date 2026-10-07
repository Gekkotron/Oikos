package com.gekkotron.oikos.push

import android.app.Application

/** The application Robolectric tests of the service and receivers run under. */
class TestPushApp : Application(), PushComponentProvider {
    lateinit var component: PushComponent

    override fun pushComponent(): PushComponent = component
}
