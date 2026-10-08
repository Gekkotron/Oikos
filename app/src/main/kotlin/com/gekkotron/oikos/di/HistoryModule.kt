package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.alerts.media.MediaRoutes
import com.gekkotron.oikos.connection.ProfileHistoryClient
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.isLikelyLocalHost
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.transport.LanOrGatewayGet
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    /** History answers are small; anything bigger is not ours. */
    private const val MAX_BYTES = 1L shl 20

    @Provides
    @Singleton
    fun historyClient(profiles: ProfileRepository, routes: MediaRoutes, http: OkHttpClient, @ApplicationScope scope: CoroutineScope): HistoryClient {
        val get = LanOrGatewayGet(routes, http, ::isLikelyLocalHost, log = { Log.w("OikosHistory", it) })
        return ProfileHistoryClient(profiles.state, get = { get.get(it, MAX_BYTES) }, scope = scope)
    }
}
