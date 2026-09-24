package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.MqttTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Demo mode is the only transport until the Direct/Gateway plan lands. */
    @Provides
    @Singleton
    fun transport(@ApplicationScope scope: CoroutineScope): MqttTransport =
        DemoBroker(scope).also { it.startTicking() }

    @Provides
    @Singleton
    fun entitySource(transport: MqttTransport, @ApplicationScope scope: CoroutineScope): EntitySource =
        MqttDiscoverySource(transport, scope, diagnostics = { Log.w("Oikos", "${it.source}: ${it.message}") })
}
