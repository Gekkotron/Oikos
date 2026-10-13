package com.gekkotron.oikos.di

import android.content.Context
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.presence.CurrentLocationSource
import com.gekkotron.oikos.presence.FusedCurrentLocationSource
import com.gekkotron.oikos.presence.PresenceGeofenceManager
import com.gekkotron.oikos.presence.PresenceWorkerFactory
import com.gekkotron.oikos.presence.WorkManagerPresenceRefresher
import com.gekkotron.oikos.push.PresenceRefresher
import com.gekkotron.oikos.push.TopicPublisher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PresenceModule {
    @Provides
    @Singleton
    fun currentLocationSource(@ApplicationContext context: Context): CurrentLocationSource = FusedCurrentLocationSource(context)

    @Provides
    @Singleton
    fun presenceWorkerFactory(
        publisher: TopicPublisher,
        preferences: AppPreferences,
        fixSource: CurrentLocationSource,
    ): PresenceWorkerFactory = PresenceWorkerFactory(publisher, preferences, fixSource)

    @Provides
    @Singleton
    fun presenceGeofenceManager(
        @ApplicationContext context: Context,
        preferences: AppPreferences,
        @ApplicationScope scope: CoroutineScope,
    ): PresenceGeofenceManager = PresenceGeofenceManager(context, preferences, scope)

    @Provides
    @Singleton
    fun presenceRefresher(@ApplicationContext context: Context): PresenceRefresher = WorkManagerPresenceRefresher(context)
}
