package com.gekkotron.oikos.alerts.media

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** The Coil request type for alert pictures. Only this type is routed through [MediaFetcher]; raw strings never are (spec §6.3). */
data class AlertMedia(val url: String)

/** Lets Coil's memory cache hold alert pictures: a data class without a keyer is not cached in memory. */
object AlertMediaKeyer : Keyer<AlertMedia> {
    override fun key(data: AlertMedia, options: Options): String = "alert-media:${data.url}"
}

/** Coil's disk cache, shared by the `ImageLoader` and the notifier so a picture is downloaded once (spec §5.2, §6.3). */
class AlertImageCache(val diskCache: DiskCache) {
    suspend fun put(url: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val editor = diskCache.openEditor(url) ?: return@withContext
        try {
            diskCache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
        } catch (e: java.io.IOException) {
            editor.abort()
        }
    }

    fun open(url: String): DiskCache.Snapshot? = diskCache.openSnapshot(url)

    fun clear() {
        diskCache.clear()
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024

        fun create(context: Context): AlertImageCache = AlertImageCache(
            DiskCache.Builder().directory(context.cacheDir.resolve("alerts/image").toOkioPath()).maxSizeBytes(MAX_BYTES).build(),
        )
    }
}

class AlertMediaFetcher(
    private val data: AlertMedia,
    private val source: MediaSource,
    private val cache: AlertImageCache,
    private val maxBytes: Long,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        cache.open(data.url)?.let { snapshot ->
            return SourceFetchResult(
                source = ImageSource(snapshot.data, cache.diskCache.fileSystem, diskCacheKey = data.url, closeable = snapshot),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
        val media = source.bytes(data.url, maxBytes).getOrThrow()
        cache.put(data.url, media.bytes)
        return SourceFetchResult(
            source = ImageSource(Buffer().apply { write(media.bytes) }, FileSystem.SYSTEM),
            mimeType = media.contentType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(
        private val source: MediaSource,
        private val cache: AlertImageCache,
        private val maxBytes: Long = DEFAULT_MAX_BYTES,
    ) : Fetcher.Factory<AlertMedia> {
        override fun create(data: AlertMedia, options: Options, imageLoader: ImageLoader): Fetcher =
            AlertMediaFetcher(data, source, cache, maxBytes)
    }

    companion object {
        /** A notification or thumbnail picture; a snapshot is far below this. */
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
    }
}
