package com.gekkotron.oikos.alerts.media

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VideoCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource : MediaSource {
        var calls = 0
        var limit: Long? = null
        var answer: (String) -> Result<Media> = { Result.success(Media(ByteArray(4) { 7 }, "video/mp4")) }
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Result<Media> {
            calls++
            limit = maxBytes
            onProgress(2, 4)
            return answer(url)
        }
    }

    private val source = FakeSource()
    private fun cache(cap: Long = 1_000, dir: File = tmp.newFolder("video")) = VideoCache(dir, source, capBytes = cap)

    private suspend fun VideoCache.ready(id: String, url: String) = file(id, url).toList().last().let { assertIs<VideoState.Ready>(it).file }

    @Test
    fun `a miss downloads with progress, a hit does not download again`(): Unit = runBlocking {
        val cache = cache()
        val states = cache.file("a1", "http://h/c.mp4").toList()
        assertEquals(VideoState.Downloading(0, null), states.first())
        assertTrue(VideoState.Downloading(2, 4) in states)
        val file = assertIs<VideoState.Ready>(states.last()).file
        assertEquals(4, file.length())
        assertEquals(VideoState.Ready(file), cache.file("a1", "http://h/c.mp4").first())
        assertEquals(1, source.calls)
    }

    @Test
    fun `the clip cap is 25 MB`(): Unit = runBlocking {
        cache().ready("a1", "http://h/c.mp4")
        assertEquals(25L * 1024 * 1024, source.limit)
    }

    @Test
    fun `fraction is read over total, and unknown without a total`() {
        assertEquals(0.5f, VideoState.Downloading(2, 4).fraction)
        assertEquals(null, VideoState.Downloading(2, null).fraction)
        assertEquals(null, VideoState.Downloading(2, 0).fraction)
    }

    @Test
    fun `a failure is reported and leaves no file behind`(): Unit = runBlocking {
        source.answer = { Result.failure(MediaException(MediaFailure.Gone)) }
        val dir = tmp.newFolder("video2")
        val last = cache(dir = dir).file("a1", "http://h/c.mp4").toList().last()
        assertEquals(VideoState.Failed(MediaFailure.Gone), last)
        assertEquals(emptyList(), dir.listFiles()!!.toList())
    }

    @Test
    fun `the same alert id with a new clip url plays the new clip`(): Unit = runBlocking {
        val cache = cache()
        val old = cache.ready("a1", "http://h/old.mp4")
        val new = cache.ready("a1", "http://h/new.mp4")
        assertNotEquals(old, new)
        assertEquals(2, source.calls)
    }

    @Test
    fun `an alert id with path characters stays inside the cache directory`(): Unit = runBlocking {
        val dir = tmp.newFolder("video3")
        val file = cache(dir = dir).ready("../../etc/x/1", "http://h/c.mp4")
        assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
        assertTrue(file.name.endsWith(".mp4"))
    }

    @Test
    fun `the least recently used clip goes first when the cap is exceeded`(): Unit = runBlocking {
        val dir = tmp.newFolder("video4")
        val cache = cache(cap = 10, dir = dir)
        val a = cache.ready("a", "http://h/a.mp4").also { it.setLastModified(1_000) }
        val b = cache.ready("b", "http://h/b.mp4").also { it.setLastModified(2_000) }
        // 8 bytes now; a third clip makes 12 over the cap of 10: the oldest (a) is evicted, the new one stays.
        val c = cache.ready("c", "http://h/c.mp4")
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
    }

    @Test
    fun `a hit refreshes the access time`(): Unit = runBlocking {
        val cache = cache()
        val a = cache.ready("a", "http://h/a.mp4").also { it.setLastModified(1_000) }
        cache.ready("a", "http://h/a.mp4")
        assertTrue(a.lastModified() > 1_000)
    }

    @Test
    fun `clear empties the directory`(): Unit = runBlocking {
        val dir = tmp.newFolder("video5")
        val cache = cache(dir = dir)
        cache.ready("a", "http://h/a.mp4")
        cache.clear()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
