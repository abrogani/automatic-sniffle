package com.abrogani.torrent.link

import com.abrogani.torrent.engine.TorrentBackend
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.Stream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.contains
import strikt.assertions.endsWith
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class TorrentLinkRewriterTest {
    @get:Rule
    val logRule = LogRule()

    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val magnet = "magnet:?xt=urn:btih:$hex&dn=Show+S01"

    private class FakeBackend(
        var port: Int? = 8090,
        var startError: Throwable? = null,
        var startDelayMs: Long = 0,
        var files: suspend (TorrentLink) -> List<TorrentFile> = { emptyList() },
    ) : TorrentBackend {
        val starts = AtomicInteger()
        val fileCalls = AtomicInteger()

        override suspend fun ensureRunning(): Int? {
            starts.incrementAndGet()
            delay(startDelayMs)
            startError?.let { throw it }
            return port
        }

        override suspend fun files(link: TorrentLink): List<TorrentFile> {
            fileCalls.incrementAndGet()
            return files.invoke(link)
        }
    }

    private val seasonPack =
        listOf(
            TorrentFile(1, "Show S01/Show.S01E01.mkv", 1_000),
            TorrentFile(2, "Show S01/Show.S01E02.mkv", 1_000),
            TorrentFile(3, "Show S01/Sample/show.sample.mkv", 10),
        )

    private fun stream(url: String = magnet, flags: Set<Flag>? = null, description: String? = null) =
        Stream(name = "Provider X", url = url, description = description, flags = flags)

    private fun episode(season: Int, number: Int) = Episode(id = "e", number = number, season = season, isReleased = true)

    @Test
    fun `non-torrent streams pass through without touching the engine`() =
        runTest {
            val backend = FakeBackend()

            expectThat(TorrentLinkRewriter(backend).rewrite(stream("https://cdn.x/video.m3u8"), null)).isNull()
            expectThat(backend.starts.get()).isEqualTo(0)
        }

    @Test
    fun `engine not installed passes through`() =
        runTest {
            val backend = FakeBackend(port = null)

            expectThat(TorrentLinkRewriter(backend).rewrite(stream(), null)).isNull()
            expectThat(backend.fileCalls.get()).isEqualTo(0)
        }

    @Test
    fun `engine start failure passes through and is attempted once per burst`() =
        runTest {
            val backend = FakeBackend(startError = IllegalStateException("boom"), startDelayMs = 100)
            val rewriter = TorrentLinkRewriter(backend)

            val results = (1..10).map { async { rewriter.rewrite(stream(), null) } }.awaitAll()

            expectThat(results).all { isNull() }
            expectThat(backend.starts.get()).isEqualTo(1)

            rewriter.rewrite(stream(), null)
            expectThat(backend.starts.get()).isEqualTo(2)
        }

    @Test
    fun `concurrent links share a single engine start`() =
        runTest {
            val backend = FakeBackend(startDelayMs = 500, files = { seasonPack })
            val rewriter = TorrentLinkRewriter(backend)

            val results = (1..8).map { async { rewriter.rewrite(stream(), episode(1, 2)) } }.awaitAll()

            expectThat(backend.starts.get()).isEqualTo(1)
            expectThat(results).all { isNotNull().get { url }.contains("&index=2&play") }
        }

    @Test
    fun `resolves the episode file, labels it, drops RequiresAuth and sets a short expiry`() =
        runTest {
            val backend = FakeBackend(files = { seasonPack })
            val flags = setOf(Flag.RequiresAuth(mapOf("Referer" to "x")), Flag.IPLocked, Flag.Expires(1L))

            val result =
                TorrentLinkRewriter(backend, clock = { 1_000L })
                    .rewrite(stream(flags = flags, description = "1080p · 2 GB"), episode(1, 2))

            expectThat(result).isNotNull().and {
                get { name }.isEqualTo("Provider X")
                get { url }.isEqualTo(
                    "http://127.0.0.1:8090/stream/Show.S01E02.mkv?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DShow%252BS01&index=2&play",
                )
                get { description }.isEqualTo("Torrent · Show.S01E02.mkv\n1080p · 2 GB")
                get { this.flags }.isEqualTo(setOf(Flag.IPLocked, Flag.Expires(1_000L + 6 * 60 * 60 * 1000)))
            }
        }

    @Test
    fun `an index hint skips resolution`() =
        runTest {
            val backend = FakeBackend(files = { error("must not resolve") })
            val link = TorrentLink.parse(magnet)!!.copy(fileIndex = 4)

            val result = TorrentLinkRewriter(backend).rewrite(stream(), link, episode(1, 1))

            expectThat(backend.fileCalls.get()).isEqualTo(0)
            expectThat(result?.url).isNotNull().endsWith("&index=4&play")
        }

    @Test
    fun `resolution timeout falls back to an unresolved url`() =
        runTest {
            val backend = FakeBackend(files = { awaitCancellation() })

            val result = TorrentLinkRewriter(backend, resolveTimeout = 20.seconds).rewrite(stream(), null)

            expectThat(result).isNotNull().and {
                get { url }.isEqualTo("http://127.0.0.1:8090/stream/Show%20S01?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DShow%252BS01&play")
                get { description }.isEqualTo("Torrent")
            }
        }

    @Test
    fun `resolution error falls back to an unresolved url`() =
        runTest {
            val backend = FakeBackend(files = { throw java.io.IOException("HTTP 500") })

            val result = TorrentLinkRewriter(backend).rewrite(stream(), episode(1, 1))

            expectThat(result?.url).isNotNull().not().contains("index=")
        }

    @Test
    fun `resolutions run at most three at a time and are cached per info hash`() =
        runTest {
            val running = AtomicInteger()
            var peak = 0
            val backend =
                FakeBackend(
                    files = {
                        peak = maxOf(peak, running.incrementAndGet())
                        delay(1_000)
                        running.decrementAndGet()
                        seasonPack
                    },
                )
            val rewriter = TorrentLinkRewriter(backend)
            val hashes = (0 until 7).map { i -> "%040x".format(i + 1) }

            hashes.map { async { rewriter.rewrite(stream("magnet:?xt=urn:btih:$it"), null) } }.awaitAll()
            expectThat(peak).isEqualTo(3)
            expectThat(backend.fileCalls.get()).isEqualTo(7)

            val again = rewriter.rewrite(stream(hashes.first()), null)
            expectThat(backend.fileCalls.get()).isEqualTo(7)
            expectThat(again?.url?.contains("&index=1&play") == true).isTrue()
        }
}
