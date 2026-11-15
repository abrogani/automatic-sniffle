package com.abrogani.torrent.link

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.net.URI
import java.net.URLDecoder

class StreamUrlTest {
    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"

    @Test
    fun `builds the play url with file name and index`() {
        val link = TorrentLink.parse("magnet:?xt=urn:btih:$hex")!!
        val file = TorrentFile(id = 3, path = "Pack/Season 1/Show S01E02 [1080p].mkv", length = 1)

        expectThat(StreamUrl.build(8090, link, file)).isEqualTo(
            "http://127.0.0.1:8090/stream/Show%20S01E02%20%5B1080p%5D.mkv" +
                "?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex&index=3&play",
        )
    }

    @Test
    fun `falls back to display name then video and omits an unknown index`() {
        val named = TorrentLink.parse("magnet:?xt=urn:btih:$hex&dn=My+Movie")!!
        val unnamed = TorrentLink.parse(hex)!!

        expectThat(StreamUrl.build(1, named, null)).isEqualTo(
            "http://127.0.0.1:1/stream/My%20Movie?link=" +
                "magnet%3A%3Fxt%3Durn%3Abtih%3A$hex%26dn%3DMy%252BMovie&play",
        )
        expectThat(StreamUrl.build(1, unnamed, null)).isEqualTo(
            "http://127.0.0.1:1/stream/video?link=magnet%3A%3Fxt%3Durn%3Abtih%3A$hex&play",
        )
    }

    @Test
    fun `source survives gin decoding plus TorrServer's second unescape`() {
        val source = "magnet:?xt=urn:btih:$hex&dn=A+B%20C%26D&tr=http%3A%2F%2Ft.org%2Fann%3Fk%3Dv%2B1"
        val link = TorrentLink.parse(source)!!

        val url = StreamUrl.build(8090, link, null)
        val rawLink = URI(url).rawQuery.split('&').first { it.startsWith("link=") }.removePrefix("link=")
        val afterGin = URLDecoder.decode(rawLink, "UTF-8")
        val afterUnescapeQuery = URLDecoder.decode(afterGin, "UTF-8")

        expectThat(afterUnescapeQuery).isEqualTo(source)
    }

    @Test
    fun `torrent url source is encoded whole`() {
        val link = TorrentLink.parse("https://t.example/dl/abc.torrent?passkey=x&y=1")!!

        expectThat(StreamUrl.build(8090, link, TorrentFile(1, "Film.mp4", 1))).isEqualTo(
            "http://127.0.0.1:8090/stream/Film.mp4?link=https%3A%2F%2Ft.example%2Fdl%2Fabc.torrent%3Fpasskey%3Dx%26y%3D1&index=1&play",
        )
    }
}
