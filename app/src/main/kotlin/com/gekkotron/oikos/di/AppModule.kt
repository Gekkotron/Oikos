package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.connection.DirectConnectionTester
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.clientIdFor
import com.gekkotron.oikos.connection.setupClientIdFor
import com.gekkotron.oikos.connection.toSpec
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.feature.home.presentation.DefaultDispatcher
import com.gekkotron.oikos.feature.onboarding.ConnectionTester
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.ActiveTransport
import com.gekkotron.oikos.transport.DirectMqttTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun activeTransport(profiles: ProfileRepository, @ApplicationScope scope: CoroutineScope): ActiveTransport = ActiveTransport(
        // installId() is a suspend DataStore write; resolved once rather than re-run on every emission.
        specs = flow {
            val id = profiles.installId()
            emitAll(profiles.state.map { it.toSpec(clientIdFor(id)) })
        },
        factory = { spec ->
            when (spec) {
                TransportSpec.Demo -> DemoBroker(scope)
                is TransportSpec.Direct -> DirectMqttTransport(spec, scope)
            }
        },
        scope = scope,
    )

    @Provides
    fun transport(active: ActiveTransport): MqttTransport = active

    @Provides
    @Singleton
    fun entitySourceSession(transport: MqttTransport, cache: EntityCache, @ApplicationScope scope: CoroutineScope): EntitySourceSession =
        EntitySourceSession(transport, scope, diagnostics = { Log.w("Oikos", "${it.source}: ${it.message}") }, cache = cache)

    /** Unscoped: each new Home gets the source for the current profile (spec §2). Home only exists while Ready. */
    @Provides
    fun entitySource(session: EntitySourceSession, profiles: ProfileRepository): EntitySource =
        session.current((profiles.state.value as? ProfileState.Ready)?.profile)

    @Provides
    @Singleton
    fun connectionTester(profiles: ProfileRepository): ConnectionTester =
        DirectConnectionTester(clientId = { setupClientIdFor(profiles.installId()) })
}
