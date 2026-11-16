package com.abrogani.servetorr.link

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

class ProxyUrlTest {
    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val encodedMagnet = "magnet%3A%3Fxt%3Durn%3Abtih%3A$hex"

    @Test
    fun `parses link with and without an episode`() {
        val link = TorrentLink.parse("magnet:?xt=urn:btih:$hex")!!

        expectThat(ProxyUrl.parseQuery("link=$encodedMagnet")).isEqualTo(ProxyUrl.Request(link, null, null))
        expectThat(ProxyUrl.parseQuery("link=$encodedMagnet&s=0&e=7")).isEqualTo(ProxyUrl.Request(link, 0, 7))
        expectThat(ProxyUrl.parseQuery("e=7&s=2&link=$encodedMagnet&")).isEqualTo(ProxyUrl.Request(link, 2, 7))
    }

    @Test
    fun `rejects invalid requests`() {
        listOf(
            "",
            "s=1&e=1",
            "link=",
            "link=https%3A%2F%2Fcdn%2Fvideo.mp4",
            "link=$encodedMagnet&s=1",
            "link=$encodedMagnet&e=1",
            "link=$encodedMagnet&s=x&e=1",
            "link=$encodedMagnet&s=-1&e=1",
            "link=$encodedMagnet&s=1&e=99999999999",
            "link=$encodedMagnet&link=$encodedMagnet",
            "link=%ZZ",
        ).forEach { query -> expectThat(ProxyUrl.parseQuery(query)).describedAs(query).isNull() }
    }
}
