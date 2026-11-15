package com.abrogani.torrent.link

import com.abrogani.torrent.engine.TorrentBackend
import com.flixclusive.core.util.log.warnLog
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.Stream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class TorrentLinkRewriter(
    private val backend: TorrentBackend,
    private val resolveTimeout: Duration = 20.seconds,
    maxConcurrentResolutions: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolutions = Semaphore(maxConcurrentResolutions)
    private val startMutex = Mutex()
    @Volatile private var completedStarts = 0L
    private var lastStart: Result<Int?>? = null
    private val fileCache = FileListCache(maxEntries = 64)

    /** Returns the Stream to emit instead of [stream], or null to pass [stream] through unchanged
     *  (not a torrent, engine not installed, or engine failed). Never throws except CancellationException. */
    suspend fun rewrite(stream: Stream, episode: Episode?): Stream? {
        val link = TorrentLink.parse(stream.url) ?: return null
        return rewrite(stream, link, episode)
    }

    internal suspend fun rewrite(stream: Stream, link: TorrentLink, episode: Episode?): Stream? {
        val port = startEngine() ?: return null

        val file =
            if (link.fileIndex != null) {
                TorrentFile(id = link.fileIndex, path = link.displayName.orEmpty(), length = 0)
            } else {
                resolveFiles(link)?.let { FileSelector.select(it, episode) }
            }

        return Stream(
            name = stream.name,
            url = StreamUrl.build(port, link, file),
            description = describe(stream.description, file?.path?.substringAfterLast('/')),
            flags = stream.flags.orEmpty()
                .filterNot { it is Flag.RequiresAuth || it is Flag.Expires }
                .toSet() + Flag.Expires(clock() + LINK_LIFETIME_MILLIS),
        )
    }

    /**
     * Single-flight: a burst of links waits on one start attempt and shares its outcome, so a
     * broken engine is started (and logged) once per burst rather than once per link.
     */
    private suspend fun startEngine(): Int? {
        val seen = completedStarts
        val outcome =
            startMutex.withLock {
                lastStart?.takeIf { completedStarts > seen }
                    ?: runCatchingNonCancellation { backend.ensureRunning() }.also { result ->
                        lastStart = result
                        completedStarts++
                        result.exceptionOrNull()?.let { warnLog("[Torrent] Engine failed to start: ${it.message}") }
                    }
            }
        return outcome.getOrNull()
    }

    private suspend fun resolveFiles(link: TorrentLink): List<TorrentFile>? {
        val key = link.infoHash ?: link.source
        fileCache[key]?.let { return it }

        val result =
            resolutions.withPermit {
                withTimeoutOrNull(resolveTimeout) { runCatchingNonCancellation { backend.files(link) } }
            }
        val label = link.displayName ?: key
        return when {
            result == null -> null.also { warnLog("[Torrent] Timed out listing files of $label") }
            result.isFailure -> null.also { warnLog("[Torrent] Couldn't list files of $label: ${result.exceptionOrNull()?.message}") }
            else -> result.getOrThrow().also { fileCache[key] = it }
        }
    }

    private fun describe(original: String?, fileName: String?): String {
        val label = if (fileName.isNullOrBlank()) TORRENT_LABEL else "$TORRENT_LABEL · $fileName"
        return if (original.isNullOrBlank()) label else "$label\n$original"
    }

    private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private class FileListCache(private val maxEntries: Int) {
        private val entries =
            object : LinkedHashMap<String, List<TorrentFile>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TorrentFile>>) = size > maxEntries
            }

        @Synchronized
        operator fun get(key: String): List<TorrentFile>? = entries[key]

        @Synchronized
        operator fun set(key: String, files: List<TorrentFile>) {
            entries[key] = files
        }
    }

    private companion object {
        const val TORRENT_LABEL = "Torrent"

        // The host caches emitted links. A loopback URL is only good while the engine serves on
        // that port, so keep cached copies short-lived instead of replaying them days later.
        const val LINK_LIFETIME_MILLIS = 6L * 60 * 60 * 1000
    }
}
