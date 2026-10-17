package com.gekkotron.oikos.feature.voice.di

import com.gekkotron.oikos.feature.voice.CommandRunner
import com.gekkotron.oikos.feature.voice.VoiceCommandRunner
import com.gekkotron.oikos.source.EntitySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceProvidesModule {
    @Provides @Singleton
    fun commandRunner(source: Provider<EntitySource>): CommandRunner = VoiceCommandRunner { source.get() }
}
