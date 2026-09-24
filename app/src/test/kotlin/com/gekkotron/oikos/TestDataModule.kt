package com.gekkotron.oikos

import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.di.ApplicationScope
import com.gekkotron.oikos.di.DataModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import javax.inject.Singleton

/** Robolectric has no AndroidKeyStore, and each test needs its own file (one DataStore per file per process). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule {
    @Provides
    @Singleton
    fun profiles(@ApplicationScope scope: CoroutineScope): ProfileRepository =
        ProfileRepository.create(Files.createTempDirectory("oikos").resolve("profile.json").toFile(), PassThrough, scope)

    private object PassThrough : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }
}
