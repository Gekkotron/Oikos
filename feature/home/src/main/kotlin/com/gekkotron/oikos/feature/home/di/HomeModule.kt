package com.gekkotron.oikos.feature.home.di

import android.content.Context
import com.gekkotron.oikos.feature.home.presentation.EntityLabels
import com.gekkotron.oikos.feature.home.presentation.LabelText
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object HomeModule {
    // Resolved on every lookup, so a language change applies on the next rebuild.
    @Provides
    fun labelText(@ApplicationContext context: Context): LabelText =
        LabelText { label -> EntityLabels.resId(label)?.let(context::getString) }
}
