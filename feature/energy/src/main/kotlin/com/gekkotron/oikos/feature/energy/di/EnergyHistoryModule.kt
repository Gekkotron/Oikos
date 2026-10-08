package com.gekkotron.oikos.feature.energy.di

import com.gekkotron.oikos.feature.energy.history.EnergyHistoryImpl
import com.gekkotron.oikos.history.EnergyHistory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class EnergyHistoryModule {
    @Binds
    abstract fun energyHistory(impl: EnergyHistoryImpl): EnergyHistory
}
