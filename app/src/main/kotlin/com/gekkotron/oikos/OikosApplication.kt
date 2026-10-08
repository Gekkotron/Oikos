package com.gekkotron.oikos

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.gekkotron.oikos.connection.HistoryWiring
import com.gekkotron.oikos.connection.PushWiring
import com.gekkotron.oikos.connection.WidgetMirror
import com.gekkotron.oikos.feature.widget.WidgetComponent
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import com.gekkotron.oikos.feature.widget.work.WidgetWorkerFactory
import com.gekkotron.oikos.push.AlertChannels
import com.gekkotron.oikos.push.PushComponent
import com.gekkotron.oikos.push.PushComponentProvider
import com.gekkotron.oikos.push.PushWorkerFactory
import com.gekkotron.oikos.transport.ActiveTransport
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OikosApplication : Application(), PushComponentProvider, WidgetComponentProvider, Configuration.Provider, SingletonImageLoader.Factory {
    @Inject
    lateinit var transport: ActiveTransport

    @Inject
    lateinit var component: PushComponent

    @Inject
    lateinit var channels: AlertChannels

    @Inject
    lateinit var workerFactory: PushWorkerFactory

    @Inject
    lateinit var imageLoader: Lazy<ImageLoader>

    @Inject
    lateinit var pushWiring: PushWiring

    @Inject
    lateinit var historyWiring: HistoryWiring

    @Inject
    lateinit var widgets: WidgetComponent

    @Inject
    lateinit var widgetMirror: WidgetMirror

    override fun pushComponent(): PushComponent = component

    override fun widgetComponent(): WidgetComponent = widgets

    /** On-demand WorkManager with the push and widget worker factories; the default initializer is removed from the manifest. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(
            DelegatingWorkerFactory().apply {
                addFactory(workerFactory)
                addFactory(WidgetWorkerFactory { widgets.refresher })
            },
        ).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()

    override fun onCreate() {
        super.onCreate()
        channels.ensure()
        pushWiring.start()
        historyWiring.start()
        widgetMirror.start()
        // Connected only in the foreground; ActiveTransport closes 30 s after onStop (spec §3.3).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = transport.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = transport.setForeground(false)
        })
    }
}
