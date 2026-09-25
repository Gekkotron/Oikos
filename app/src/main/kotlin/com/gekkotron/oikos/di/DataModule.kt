package com.gekkotron.oikos.di

import android.content.Context
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.FileEntityCache
import com.gekkotron.oikos.data.KeystoreSecretStore
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun secretStore(): SecretStore = KeystoreSecretStore()

    @Provides
    @Singleton
    fun profiles(@ApplicationContext context: Context, secrets: SecretStore, @ApplicationScope scope: CoroutineScope): ProfileRepository =
        ProfileRepository.create(context, secrets, scope)

    @Provides
    @Singleton
    fun entityCache(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): EntityCache =
        FileEntityCache.create(context, scope)
}
