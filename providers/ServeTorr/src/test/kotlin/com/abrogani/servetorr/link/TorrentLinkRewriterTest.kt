package com.abrogani.servetorr.link

import com.flixclusive.core.util.log.LogRule
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.Stream
import org.junit.Rule
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull

class TorrentLinkRewriterTest {
    @get:Rule
    val logRule = LogRule()

    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val magnet = "magnet:?xt=urn:btih:$hex&dn=Show+S01"
    private val now = 1_000L
    private val hour = 60L * 60 * 1000

    private fun stream(url: String = magnet, flags: Set<Flag>? = null, description: String? = null) =
        Stream(name = "Provider X", url = url, description = description, flags = flags)

    private fun episode(season: Int, number: Int) = Episode(id = "e", number = number, season = season, isReleased = true)

    private fun rewriter(port: Int? = 47390, installed: Boolean = true) =
        TorrentLinkRewriter(proxyPort = { port }, engineInstalled = { installed }, clock = { now })

    @Test
    fun `non-torrent streams are not rewritten`() {
        expectThat(rewriter().rewrite(stream("https://cdn.x/video.m3u8"), null)).isNull()
        expectThat(rewriter(port = null).rewrite(stream("https://cdn.x/video.m3u8"), null)).isNull()
    }

    @Test
    fun `movie link becomes a proxy url, keeps name and description, drops auth and old expiry`() {
        val flags = setOf(Flag.RequiresAuth(mapOf("Referer" to "x")), Flag.IPLocked, Flag.Expires(1L))

        val result = rewriter().rewrite(stream(flags = flags, description = "1080p · 2 GB"), null)

        expectThat(result).isNotNull().and {
            get { name }.isEqualTo("Provider X")
            get { url }.isEqualTo(
                "http://127.0.0.1:47390/torrent/Show%20S01?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DShow%2BS01",
            )
            get { description }.isEqualTo("Torrent\n1080p · 2 GB")
            get { this.flags }.isEqualTo(setOf(Flag.IPLocked, Flag.Expires(now + 12 * hour)))
        }
    }

    @Test
    fun `episode links carry season and episode so each gets its own url`() {
        val e1 = rewriter().rewrite(stream(), episode(1, 1))!!.url
        val e2 = rewriter().rewrite(stream(), episode(1, 2))!!.url

        expectThat(e1).isEqualTo("http://127.0.0.1:47390/torrent/Show%20S01?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DShow%2BS01&s=1&e=1")
        expectThat(e2).isEqualTo("http://127.0.0.1:47390/torrent/Show%20S01?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DShow%2BS01&s=1&e=2")
    }

    @Test
    fun `torrent file urls and bare hashes are rewritten with a fallback name`() {
        expectThat(rewriter().rewrite(stream("https://t.example/dl/a.torrent?k=1&x=2"), null)?.url).isEqualTo(
            "http://127.0.0.1:47390/torrent/video?link=https%3A%2F%2Ft.example%2Fdl%2Fa.torrent%3Fk%3D1%26x%3D2",
        )
        expectThat(rewriter().rewrite(stream(hex.uppercase()), null)?.url).isEqualTo(
            "http://127.0.0.1:47390/torrent/video?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex",
        )
    }

    @Test
    fun `proxy not running keeps the original stream with a one hour expiry`() {
        val flags = setOf(Flag.RequiresAuth(mapOf("a" to "b")), Flag.Expires(5L))
        val original = stream(flags = flags, description = "d")

        expectThat(rewriter(port = null).rewrite(original, episode(1, 1))).isEqualTo(
            original.copy(flags = setOf(Flag.RequiresAuth(mapOf("a" to "b")), Flag.Expires(now + hour))),
        )
    }

    @Test
    fun `engine not installed keeps the original stream with a one hour expiry`() {
        val original = stream()

        expectThat(rewriter(installed = false).rewrite(original, null)).isEqualTo(
            original.copy(flags = setOf(Flag.Expires(now + hour))),
        )
    }

    @Test
    fun `a throwing availability check falls back to the original`() {
        val rewriter = TorrentLinkRewriter(proxyPort = { 1 }, engineInstalled = { error("disk") }, clock = { now })

        expectThat(rewriter.rewrite(stream(), null)?.url).isEqualTo(magnet)
    }

    @Test
    fun `the proxy parses back exactly what the rewriter built`() {
        val source = "magnet:?xt=urn:btih:$hex&dn=A%26B+100%25&tr=http%3A%2F%2Ft%3Fa%3D1%2B2"
        val url = rewriter().rewrite(stream(source), episode(3, 12))!!.url

        val request = ProxyUrl.parseQuery(url.substringAfter('?'))

        expectThat(request).isEqualTo(ProxyUrl.Request(TorrentLink.parse(source)!!, 3, 12))
        expectThat(request?.link?.source).isEqualTo(source)
    }
}
