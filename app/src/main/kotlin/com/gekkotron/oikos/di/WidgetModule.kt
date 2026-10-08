package com.gekkotron.oikos.di

import android.content.Context
import androidx.work.WorkManager
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.HeadlessEntities
import com.gekkotron.oikos.connection.TransportFactory
import com.gekkotron.oikos.connection.WidgetMirror
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.feature.widget.WidgetComponent
import com.gekkotron.oikos.feature.widget.WidgetEntities
import com.gekkotron.oikos.feature.widget.WidgetRefresher
import com.gekkotron.oikos.feature.widget.WidgetUpdater
import com.gekkotron.oikos.feature.widget.store.FileWidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.FileWidgetSnapshotStore
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import com.gekkotron.oikos.feature.widget.ui.updateAllOikosWidgets
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler
import com.gekkotron.oikos.feature.widget.work.WorkManagerWidgetScheduler
import com.gekkotron.oikos.transport.ActiveTransport
import com.gekkotron.oikos.transport.LinkState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** The widget's stores, apart from [WidgetModule] so a test can give each run its own files (one DataStore per file per process). */
@Module
@InstallIn(SingletonComponent::class)
object WidgetStoreModule {
    @Provides
    @Singleton
    fun widgetConfigs(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): WidgetConfigStore =
        FileWidgetConfigStore.create(context, scope)

    @Provides
    @Singleton
    fun widgetSnapshots(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): WidgetSnapshotStore =
        FileWidgetSnapshotStore.create(context, scope)
}

/** The home-screen widget (widgets spec §3). */
@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {
    @Provides
    @Singleton
    fun widgetScheduler(@ApplicationContext context: Context): WidgetScheduler = WorkManagerWidgetScheduler { WorkManager.getInstance(context) }

    @Provides
    @Singleton
    fun widgetUpdater(@ApplicationContext context: Context): WidgetUpdater = WidgetUpdater { updateAllOikosWidgets(context) }

    @Provides
    @Singleton
    fun widgetEntities(
        profiles: ProfileRepository,
        session: EntitySourceSession,
        active: ActiveTransport,
        cache: EntityCache,
        http: OkHttpClient,
    ): WidgetEntities = HeadlessEntities(
        profile = { profiles.state.first { it !is ProfileState.Loading } },
        route = { profiles.route.value },
        installId = { profiles.installId() },
        live = { if (active.link.value is LinkState.Connected) session.peek() else null },
        foreground = { active.link.value is LinkState.Connected },
        cache = cache,
        // A session is too short to care about network switches.
        transports = { spec, scope -> TransportFactory(scope, emptyFlow(), http).create(spec) },
    )

    @Provides
    @Singleton
    fun widgetRefresher(entities: WidgetEntities, configs: WidgetConfigStore, snapshots: WidgetSnapshotStore, updater: WidgetUpdater): WidgetRefresher =
        WidgetRefresher(entities, configs, snapshots, updater)

    @Provides
    @Singleton
    fun widgetComponent(
        entities: WidgetEntities,
        configs: WidgetConfigStore,
        snapshots: WidgetSnapshotStore,
        scheduler: WidgetScheduler,
        refresher: WidgetRefresher,
    ): WidgetComponent = WidgetComponent(entities, configs, snapshots, scheduler, refresher)

    @Provides
    @Singleton
    fun widgetMirror(
        profiles: ProfileRepository,
        session: EntitySourceSession,
        active: ActiveTransport,
        configs: WidgetConfigStore,
        snapshots: WidgetSnapshotStore,
        updater: WidgetUpdater,
        @ApplicationScope scope: CoroutineScope,
    ): WidgetMirror = WidgetMirror(
        sources = active.link.map { if (it is LinkState.Connected) session.peek() else null }.distinctUntilChanged(),
        configs = configs,
        snapshots = snapshots,
        updater = updater,
        historyEnabled = { (profiles.state.value as? ProfileState.Ready)?.profile is ConnectionProfile.Direct },
        scope = scope,
    )
}
