package com.abrogani.servetorr.proxy

import com.abrogani.servetorr.engine.TorrentBackend
import com.abrogani.servetorr.link.FileSelector
import com.abrogani.servetorr.link.ProxyUrl
import com.abrogani.servetorr.link.StreamUrl
import com.abrogani.servetorr.link.TorrentFile
import com.abrogani.servetorr.link.TorrentLink
import com.abrogani.servetorr.link.TorrentLinkParser
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

internal class LinkResolver(
    private val backend: TorrentBackend,
    private val scope: CoroutineScope,
    private val timeout: Duration,
    private val blockReason: suspend () -> String? = { null },
) {
    sealed interface Outcome {
        val message: String

        data class Redirect(val location: String) : Outcome {
            override val message get() = "Redirecting to $location"
        }
        data class NotFound(override val message: String) : Outcome
        data class Unavailable(override val message: String) : Outcome
        data class Failed(override val message: String) : Outcome
    }

    private data class Metadata(val infoHash: String, val files: List<TorrentFile>)

    private class MetadataTimeout : Exception()

    private val filesByHash = LruCache<String, List<TorrentFile>>(64)
    private val hashBySource = LruCache<String, String>(128)
    private val inFlight = HashMap<String, Deferred<Metadata>>()

    suspend fun resolve(request: ProxyUrl.Request): Outcome {
        blockReason()?.let { return Outcome.Unavailable(it) }
        val port =
            try {
                backend.ensureRunning()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return Outcome.Failed(e.message ?: "The torrent engine failed to start")
            } ?: return Outcome.Unavailable("The torrent engine isn't installed. Install it from the Torrent provider's settings.")

        val metadata =
            try {
                metadata(request.link)
            } catch (e: MetadataTimeout) {
                return Outcome.Failed("Timed out waiting for the torrent's metadata")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                warnLog("[ServeTorr] Couldn't list files of ${request.link.displayName ?: request.link.source}: ${e.message}")
                return Outcome.Failed("Couldn't read the torrent: ${e.message ?: e.javaClass.simpleName}")
            }

        val file =
            FileSelector.select(metadata.files, request.season, request.episode)
                ?: return Outcome.NotFound(
                    if (request.season != null) {
                        "No video for S${request.season}E${request.episode} in this torrent"
                    } else {
                        "No video file in this torrent"
                    },
                )

        return Outcome.Redirect(StreamUrl.build(port, metadata.infoHash, request.link, file))
    }

    private suspend fun metadata(link: TorrentLink): Metadata {
        cached(link)?.let { return it }

        val key = link.infoHash ?: link.source
        val pending =
            synchronized(inFlight) {
                cached(link)?.let { return it }
                inFlight.getOrPut(key) {
                    scope.async {
                        try {
                            withTimeoutOrNull(timeout) { load(link) } ?: throw MetadataTimeout()
                        } finally {
                            synchronized(inFlight) { inFlight.remove(key) }
                        }
                    }
                }
            }
        return pending.await()
    }

    private fun cached(link: TorrentLink): Metadata? {
        val hash = link.infoHash ?: hashBySource[link.source] ?: return null
        return filesByHash[hash]?.let { Metadata(hash, it) }
    }

    private suspend fun load(link: TorrentLink): Metadata {
        val files = backend.files(link)
        val hash =
            link.infoHash
                ?: backend.infoHash(link)?.let(TorrentLinkParser::normalizeInfoHash)
                ?: throw IllegalStateException("The engine didn't report the torrent's info hash")

        filesByHash[hash] = files
        if (link.infoHash == null) hashBySource[link.source] = hash
        return Metadata(hash, files)
    }

    private class LruCache<K, V>(private val maxEntries: Int) {
        private val entries =
            object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > maxEntries
            }

        @Synchronized
        operator fun get(key: K): V? = entries[key]

        @Synchronized
        operator fun set(key: K, value: V) {
            entries[key] = value
        }
    }
}
