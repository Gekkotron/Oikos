package com.gekkotron.oikos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.AlertMedia
import com.gekkotron.oikos.alerts.media.AlertMediaFetcher
import com.gekkotron.oikos.alerts.media.AlertMediaKeyer
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.di.alertImageLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertImageLoaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val source = object : MediaSource {
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> = Result.failure(IllegalStateException())
    }

    @Test
    fun `the loader registers the AlertMedia keyer and fetcher and shares the alert disk cache`() {
        val cache = AlertImageCache.create(context)
        val loader = alertImageLoader(context, source, cache)
        assertTrue(loader.components.keyers.any { (keyer, type) -> keyer === AlertMediaKeyer && type == AlertMedia::class })
        assertTrue(loader.components.fetcherFactories.any { (factory, type) -> factory is AlertMediaFetcher.Factory && type == AlertMedia::class })
        assertSame(cache.diskCache, loader.diskCache)
        assertEquals(1, loader.components.fetcherFactories.count { it.first is AlertMediaFetcher.Factory })
    }
}
