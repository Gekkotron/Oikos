package com.gekkotron.oikos.alerts.media

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class AlertMediaFetcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()

    private class FakeSource(var answer: () -> Result<Media>) : MediaSource {
        var calls = 0
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
            calls++
            return answer()
        }
    }

    private fun cache() = AlertImageCache(DiskCache.Builder().directory(tmp.newFolder("img").toOkioPath()).maxSizeBytes(1_000_000).build())
    private val jpeg = Media(byteArrayOf(1, 2, 3, 4), "image/jpeg")

    private fun fetcher(source: MediaSource, cache: AlertImageCache, url: String = "http://192.168.1.20:5000/s.jpg") =
        AlertMediaFetcher.Factory(source, cache).create(AlertMedia(url), Options(context), ImageLoader(context))

    @Test
    fun `the first fetch goes to the source, the second is served from the disk cache`(): Unit = runBlocking {
        val source = FakeSource { Result.success(jpeg) }
        val cache = cache()
        val first = fetcher(source, cache).fetch() as SourceFetchResult
        assertEquals(DataSource.NETWORK, first.dataSource)
        assertContentEquals(jpeg.bytes, first.source.source().readByteArray())
        val second = fetcher(source, cache).fetch() as SourceFetchResult
        assertEquals(DataSource.DISK, second.dataSource)
        assertContentEquals(jpeg.bytes, second.source.source().readByteArray())
        assertEquals(1, source.calls)
    }

    @Test
    fun `a picture the notifier already stored is a cache hit`(): Unit = runBlocking {
        val source = FakeSource { error("must not be called") }
        val cache = cache()
        cache.put("http://192.168.1.20:5000/s.jpg", jpeg.bytes)
        val result = fetcher(source, cache).fetch() as SourceFetchResult
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals(0, source.calls)
    }

    @Test
    fun `a source failure propagates as its MediaException`() {
        val source = FakeSource { Result.failure(MediaException(MediaFailure.Gone)) }
        val e = assertFailsWith<MediaException> { runBlocking { fetcher(source, cache()).fetch() } }
        assertEquals(MediaFailure.Gone, e.failure)
    }

    @Test
    fun `the keyer keys by url so the memory cache can hold the picture`() {
        assertEquals("alert-media:http://h/s.jpg", AlertMediaKeyer.key(AlertMedia("http://h/s.jpg"), Options(context)))
    }

    @Test
    fun `a raw string url never reaches the alert media source`(): Unit = runBlocking {
        val source = FakeSource { Result.success(jpeg) }
        val loader = ImageLoader.Builder(context).components { add(AlertMediaFetcher.Factory(source, cache())) }.build()
        val result = loader.execute(ImageRequest.Builder(context).data("https://example.com/x.jpg").build())
        assertIs<coil3.request.ErrorResult>(result)
        assertEquals(0, source.calls)
    }
}
