package com.gekkotron.oikos.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.disk.DiskCache
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaException
import com.gekkotron.oikos.alerts.media.MediaFailure
import com.gekkotron.oikos.alerts.media.MediaSource
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class FetchedNotificationImagesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = AlertImageCache(DiskCache.Builder().directory(tmp.newFolder().toOkioPath()).maxSizeBytes(1_000_000).build())

    private class Source(val answer: Result<Media>) : MediaSource {
        var limit = 0L
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
            limit = maxBytes
            return answer
        }
    }

    @Test
    fun `a fetched picture is stored in the shared cache under its url`(): Unit = runBlocking {
        val cache = cache()
        val source = Source(Result.success(Media(byteArrayOf(1, 2, 3), "image/jpeg")))
        FetchedNotificationImages(source, cache).load("http://h/s.jpg")
        assertNotNull(cache.open("http://h/s.jpg"))
        assertEquals(5L * 1024 * 1024, source.limit)
    }

    @Test
    fun `a failed fetch gives no picture and caches nothing`(): Unit = runBlocking {
        val cache = cache()
        val source = Source(Result.failure(MediaException(MediaFailure.Gone)))
        assertNull(FetchedNotificationImages(source, cache).load("http://h/s.jpg"))
        assertNull(cache.open("http://h/s.jpg"))
    }

    @Test
    fun `big pictures are subsampled to about the target size, small ones are left alone`() {
        assertEquals(1, sampleSizeFor(800, 600, 1024))
        assertEquals(1, sampleSizeFor(1500, 1000, 1024))
        assertEquals(2, sampleSizeFor(4000, 3000, 1024))
        assertEquals(4, sampleSizeFor(8000, 6000, 1024))
        assertEquals(1, sampleSizeFor(4000, 900, 1024))
    }
}
