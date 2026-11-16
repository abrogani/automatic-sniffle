package com.abrogani.servetorr.link

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.net.URI
import java.net.URLDecoder

class StreamUrlTest {
    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"

    private fun engineLink(url: String): String {
        val raw = URI(url).rawQuery.split('&').first { it.startsWith("link=") }.removePrefix("link=")
        return URLDecoder.decode(URLDecoder.decode(raw, "UTF-8"), "UTF-8")
    }

    @Test
    fun `builds the play url with file name and 0-based index`() {
        val link = TorrentLink.parse("magnet:?xt=urn:btih:$hex")!!
        val file = TorrentFile(id = 3, path = "Pack/Season 1/Show S01E02 [1080p].mkv", length = 1)

        expectThat(StreamUrl.build(8090, hex, link, file)).isEqualTo(
            "http://127.0.0.1:8090/stream/Show%20S01E02%20%5B1080p%5D.mkv" +
                "?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex&index=3&play",
        )
    }

    @Test
    fun `the engine's file id is always sent as the index`() {
        val link = TorrentLink.parse(hex)!!

        expectThat(StreamUrl.build(1, hex, link, TorrentFile(0, "a.mkv", 1))).isEqualTo(
            "http://127.0.0.1:1/stream/a.mkv?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex&index=0&play",
        )
    }

    @Test
    fun `magnet keeps the source's name and trackers, drops everything else`() {
        val source =
            "magnet:?xt=urn:btih:$hex&dn=A+B%20C%26D&tr=http%3A%2F%2Ft.org%2Fann%3Fk%3Dv%2B1" +
                "&tr.1=udp://open.tracker:1337&tr=udp://open.tracker:1337&xl=123&so=2"
        val link = TorrentLink.parse(source)!!

        expectThat(StreamUrl.magnet(hex, link)).isEqualTo(
            "magnet:?xt=urn:btih:$hex&dn=A+B+C%26D" +
                "&tr=http%3A%2F%2Ft.org%2Fann%3Fk%3Dv%2B1&tr=udp%3A%2F%2Fopen.tracker%3A1337",
        )
    }

    @Test
    fun `magnet survives gin decoding plus the engine's second unescape`() {
        val source = "magnet:?xt=urn:btih:$hex&dn=100%25+Pure%2BGold&tr=http%3A%2F%2Ft.org%2Fann%3Fk%3Dv%2B1%26x%3D%2525"
        val link = TorrentLink.parse(source)!!

        val url = StreamUrl.build(8090, hex, link, TorrentFile(1, "Film.mp4", 1))

        expectThat(engineLink(url)).isEqualTo(StreamUrl.magnet(hex, link))
        val magnetQuery = engineLink(url).substringAfter('?').split('&').map {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        expectThat(magnetQuery).isEqualTo(
            listOf(
                "xt" to "urn:btih:$hex",
                "dn" to "100% Pure+Gold",
                "tr" to "http://t.org/ann?k=v+1&x=%25",
            ),
        )
    }

    @Test
    fun `a torrent file source is addressed by its info hash only`() {
        val link = TorrentLink.parse("https://t.example/dl/abc.torrent?passkey=x&y=1")!!

        expectThat(StreamUrl.build(8090, hex, link, TorrentFile(1, "Dir/Film.mp4", 1))).isEqualTo(
            "http://127.0.0.1:8090/stream/Film.mp4?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex&index=1&play",
        )
    }
}
