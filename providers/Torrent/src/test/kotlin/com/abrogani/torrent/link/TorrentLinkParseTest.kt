package com.abrogani.torrent.link

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull

class TorrentLinkParseTest {
    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val base32 = "3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4"

    @Test
    fun `magnet with hex hash keeps the source and lowercases the hash`() {
        val magnet = "magnet:?xt=urn:btih:${hex.uppercase()}&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Fexplodie.org%3A6969"

        expectThat(TorrentLink.parse(magnet)).isNotNull().and {
            get { source }.isEqualTo(magnet)
            get { infoHash }.isEqualTo(hex)
            get { fileIndex }.isNull()
            get { displayName }.isEqualTo("Big Buck Bunny")
        }
    }

    @Test
    fun `magnet with base32 hash is normalised to hex`() {
        expectThat(TorrentLink.parse("magnet:?xt=urn:btih:$base32")?.infoHash).isEqualTo(hex)
        expectThat(TorrentLink.parse("magnet:?xt=urn:btih:${base32.lowercase()}")?.infoHash).isEqualTo(hex)
    }

    @Test
    fun `dn is percent decoded including utf-8`() {
        val link = TorrentLink.parse("magnet:?dn=Am%C3%A9lie%20%282001%29%20%5B1080p%5D&xt=urn:btih:$hex")

        expectThat(link?.displayName).isEqualTo("Amélie (2001) [1080p]")
    }

    @Test
    fun `scheme and parameter names are case insensitive and whitespace is trimmed`() {
        val link = TorrentLink.parse("  MAGNET:?XT=URN:BTIH:$hex&DN=Film\n")

        expectThat(link).isNotNull().and {
            get { infoHash }.isEqualTo(hex)
            get { displayName }.isEqualTo("Film")
        }
    }

    @Test
    fun `btih is found among several xt params`() {
        val link = TorrentLink.parse("magnet:?xt=urn:btmh:1220abcdef&xt.1=urn:btih:$hex")

        expectThat(link?.infoHash).isEqualTo(hex)
    }

    @Test
    fun `file hints are not mapped to TorrServer ids`() {
        expectThat(TorrentLink.parse("magnet:?xt=urn:btih:$hex&so=3")?.fileIndex).isNull()
        expectThat(TorrentLink.parse("https://x.org/a.torrent?fileIdx=2")?.fileIndex).isNull()
    }

    @Test
    fun `magnets without a v1 info hash are rejected`() {
        expectThat(TorrentLink.parse("magnet:?xt=urn:btmh:1220$hex")).isNull()
        expectThat(TorrentLink.parse("magnet:?dn=Nothing")).isNull()
        expectThat(TorrentLink.parse("magnet:?xt=urn:btih:abc123")).isNull()
        expectThat(TorrentLink.parse("magnet:")).isNull()
    }

    @Test
    fun `torrent file urls match ignoring query and fragment`() {
        val url = "https://yts.example/torrent/download/ABC.torrent?token=1&x=y#frag"

        expectThat(TorrentLink.parse(url)).isNotNull().and {
            get { source }.isEqualTo(url)
            get { infoHash }.isNull()
            get { fileIndex }.isNull()
        }
        expectThat(TorrentLink.parse("HTTP://host:8080/files/Movie.2020.TORRENT")).isNotNull()
    }

    @Test
    fun `normal http streams never match`() {
        listOf(
            "https://x/foo.torrent.m3u8",
            "https://x/foo.torrent/index.m3u8",
            "https://cdn.example/video.mp4",
            "https://cdn.example/master.m3u8?file=a.torrent",
            "https://x/download.php?file=a.torrent",
            "https://x/.torrent",
            "https://.torrent",
            "ftp://x/a.torrent",
            "",
            "   ",
            "not a url",
        ).forEach { expectThat(TorrentLink.parse(it)).describedAs(it).isNull() }
    }

    @Test
    fun `bare info hashes become magnets`() {
        expectThat(TorrentLink.parse(hex.uppercase())).isNotNull().and {
            get { source }.isEqualTo("magnet:?xt=urn:btih:$hex")
            get { infoHash }.isEqualTo(hex)
            get { displayName }.isNull()
        }
        expectThat(TorrentLink.parse(base32)?.source).isEqualTo("magnet:?xt=urn:btih:$hex")
    }

    @Test
    fun `strings that only resemble hashes are rejected`() {
        expectThat(TorrentLink.parse(hex.drop(1))).isNull()
        expectThat(TorrentLink.parse(hex + "0")).isNull()
        expectThat(TorrentLink.parse("g" + hex.drop(1))).isNull()
        expectThat(TorrentLink.parse(base32.replace('3', '1'))).isNull()
        expectThat(TorrentLink.parse("$hex $hex")).isNull()
    }
}
