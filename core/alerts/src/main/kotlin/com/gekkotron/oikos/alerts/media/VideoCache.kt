package com.gekkotron.oikos.alerts.media

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface VideoState {
    data class Downloading(val read: Long, val total: Long?) : VideoState {
        /** 0..1, or null when the size is not known (the gateway answers in one piece). */
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (read.toFloat() / it).coerceIn(0f, 1f) }
    }

    data class Ready(val file: File) : VideoState

    data class Failed(val failure: MediaFailure) : VideoState
}

/** Clips downloaded before playback: LRU by access time under [capBytes], [clipLimit] per clip (spec §6.3). */
class VideoCache(
    private val dir: File,
    private val source: MediaSource,
    private val capBytes: Long = 200L * 1024 * 1024,
    private val clipLimit: Long = 25L * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Emits `Downloading` updates, then `Ready` (also at once on a hit) or `Failed`. */
    fun file(alertId: String, url: String): Flow<VideoState> = channelFlow {
        val target = withContext(io) { fileFor(alertId, url) }
        if (withContext(io) { touch(target) }) {
            send(VideoState.Ready(target))
            return@channelFlow
        }
        // One download per clip: a second collector waits, then finds the finished file.
        locks.getOrPut(target.name) { Mutex() }.withLock {
            if (withContext(io) { touch(target) }) {
                send(VideoState.Ready(target))
                return@channelFlow
            }
            send(VideoState.Downloading(0, null))
            val result = source.bytes(url, clipLimit) { read, total -> trySend(VideoState.Downloading(read, total)) }
            result.fold(
                onSuccess = { media ->
                    val stored = runCatching { withContext(io) { store(target, media.bytes) } }
                    stored.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    send(
                        if (stored.isSuccess) VideoState.Ready(target)
                        else VideoState.Failed(MediaFailure.Unavailable(stored.exceptionOrNull()?.message ?: "could not store the clip")),
                    )
                },
                onFailure = { send(VideoState.Failed((it as? MediaException)?.failure ?: MediaFailure.Unavailable(it.message ?: "error"))) },
            )
        }
    }

    /** Deletes every clip ("Set up again"). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(alertId: String, url: String): File {
        dir.mkdirs()
        val safe = alertId.replace(UNSAFE, "_").take(64)
        return File(dir, "$safe-${sha1(url).take(8)}.mp4")
    }

    /** True for a complete clip; it also becomes the most recently used. */
    private fun touch(file: File): Boolean = file.isFile && file.length() > 0 && file.setLastModified(System.currentTimeMillis())

    private fun store(target: File, bytes: ByteArray) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) error("could not move the clip into place")
        } finally {
            part.delete()
        }
        evict(keep = target)
    }

    private fun evict(keep: File) {
        val clips = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.sortedBy { it.lastModified() }.orEmpty().toMutableList()
        var total = clips.sumOf { it.length() }
        for (clip in clips) {
            if (total <= capBytes) break
            if (clip == keep) continue
            total -= clip.length()
            clip.delete()
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        fun create(context: Context, source: MediaSource): VideoCache = VideoCache(context.cacheDir.resolve("alerts/video"), source)
    }
}
