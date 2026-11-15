package com.abrogani.torrent.intercept

import com.abrogani.torrent.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo

class InterceptingLinkApiTest {
    @get:Rule
    val logRule = LogRule()

    private val magnet = Stream(
        name = "Torrent 1080p",
        url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Some.Movie",
    )
    private val http = Stream(name = "Direct", url = "https://cdn.example.com/video.m3u8")
    private val subtitle = Subtitle(language = "English", url = "https://cdn.example.com/en.vtt")
    private val rewritten = Stream(name = "Torrent 1080p", url = "http://127.0.0.1:8090/stream/x?link=abc&index=1&play")
    private val media = mockk<MediaMetadata>(relaxed = true)

    private fun innerEmitting(vararg links: MediaLink) = object : MediaLinkProviderApi {
        override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

        override suspend fun getLinks(media: MediaMetadata, episode: Episode?, onLinkFound: (MediaLink) -> Unit) {
            links.forEach(onLinkFound)
        }
    }

    private suspend fun collect(api: MediaLinkProviderApi): List<MediaLink> {
        val found = mutableListOf<MediaLink>()
        api.getLinks(media, null) { synchronized(found) { found += it } }
        return found
    }

    @Test
    fun `torrent streams are rewritten and others pass through in order`() = runTest {
        val rewriter = mockk<TorrentLinkRewriter>()
        coEvery { rewriter.rewrite(magnet, null) } returns rewritten
        val api = InterceptingLinkApi(innerEmitting(http, magnet, subtitle), rewriter) { true }

        val found = collect(api)

        expectThat(found).containsExactly(http, subtitle, rewritten)
        coVerify(exactly = 1) { rewriter.rewrite(any(), any()) }
    }

    @Test
    fun `torrent stream falls back to the original when the rewriter declines`() = runTest {
        val rewriter = mockk<TorrentLinkRewriter>()
        coEvery { rewriter.rewrite(any(), any()) } returns null
        val api = InterceptingLinkApi(innerEmitting(magnet), rewriter) { true }

        expectThat(collect(api)).containsExactly(magnet)
    }

    @Test
    fun `disabled interception passes everything through untouched`() = runTest {
        val rewriter = mockk<TorrentLinkRewriter>()
        val api = InterceptingLinkApi(innerEmitting(magnet, http), rewriter) { false }

        expectThat(collect(api)).containsExactly(magnet, http)
        coVerify(exactly = 0) { rewriter.rewrite(any(), any()) }
    }

    @Test
    fun `a failing toggle is treated as disabled`() = runTest {
        val rewriter = mockk<TorrentLinkRewriter>()
        val api = InterceptingLinkApi(innerEmitting(magnet), rewriter) { error("datastore broken") }

        expectThat(collect(api)).containsExactly(magnet)
    }

    @Test
    fun `getLinks returns only after pending rewrites have emitted`() = runTest {
        val rewriter = mockk<TorrentLinkRewriter>()
        coEvery { rewriter.rewrite(any(), any()) } coAnswers {
            delay(5_000)
            rewritten
        }
        val api = InterceptingLinkApi(innerEmitting(magnet, http), rewriter) { true }

        val found = collect(api)

        expectThat(found).containsExactly(http, rewritten)
    }

    @Test
    fun `isEnabled is read once per call`() = runTest {
        var reads = 0
        val rewriter = mockk<TorrentLinkRewriter>()
        coEvery { rewriter.rewrite(any(), any()) } returns rewritten
        val api = InterceptingLinkApi(innerEmitting(magnet, magnet, http), rewriter) {
            reads++
            true
        }

        collect(api)

        expectThat(reads).isEqualTo(1)
    }

    @Test
    fun `supported link types are delegated`() {
        val api = InterceptingLinkApi(innerEmitting(), mockk()) { true }

        expectThat(api.supportedLinkTypes).isEqualTo(setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES))
    }
}
