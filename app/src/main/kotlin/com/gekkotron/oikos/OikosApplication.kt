package com.gekkotron.oikos

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gekkotron.oikos.transport.ActiveTransport
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OikosApplication : Application() {
    @Inject
    lateinit var transport: ActiveTransport

    override fun onCreate() {
        super.onCreate()
        // Connected only in the foreground; ActiveTransport closes 30 s after onStop (spec §3.3).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = transport.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = transport.setForeground(false)
        })
    }
}
