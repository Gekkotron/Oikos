package com.gekkotron.oikos

import com.gekkotron.oikos.di.ApplicationScope
import com.gekkotron.oikos.di.WidgetStoreModule
import com.gekkotron.oikos.feature.widget.store.FileWidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.FileWidgetSnapshotStore
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import javax.inject.Singleton

/** Each test needs its own files (one DataStore per file per process). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WidgetStoreModule::class])
object TestWidgetStoreModule {
    @Provides
    @Singleton
    fun widgetConfigs(@ApplicationScope scope: CoroutineScope): WidgetConfigStore =
        FileWidgetConfigStore.create(Files.createTempDirectory("oikos").resolve("widget-config.json").toFile(), scope)

    @Provides
    @Singleton
    fun widgetSnapshots(@ApplicationScope scope: CoroutineScope): WidgetSnapshotStore =
        FileWidgetSnapshotStore.create(Files.createTempDirectory("oikos").resolve("widget-snapshot.json").toFile(), scope)
}
