package com.abrogani.torrent.proxy

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.io.ByteArrayOutputStream

class HttpExchangeTest {
    private fun read(raw: String, max: Int = 1024) = HttpExchange.read(raw.byteInputStream(Charsets.ISO_8859_1), max)

    @Test
    fun `parses origin and absolute form targets and ignores headers`() {
        expectThat(read("GET /torrent/x?link=a%20b&s=1 HTTP/1.1\r\nHost: h\r\nRange: bytes=0-\r\n\r\nbody")).isEqualTo(
            ReadResult.Ok(HttpRequest("GET", "/torrent/x", "link=a%20b&s=1")),
        )
        expectThat(read("\r\nHEAD http://127.0.0.1:1/torrent-proxy/ping HTTP/1.0\n\n")).isEqualTo(
            ReadResult.Ok(HttpRequest("HEAD", "/torrent-proxy/ping", "")),
        )
    }

    @Test
    fun `malformed, truncated and oversized heads`() {
        expectThat(read("GET /x\r\n\r\n")).isEqualTo(ReadResult.Malformed)
        expectThat(read("get /x HTTP/1.1\r\n\r\n")).isEqualTo(ReadResult.Malformed)
        expectThat(read("GET x HTTP/1.1\r\n\r\n")).isEqualTo(ReadResult.Malformed)
        expectThat(read("GET /x HTTP/2\r\n\r\n")).isEqualTo(ReadResult.Malformed)
        expectThat(read("GET /x HTTP/1.1\r\nHost: h\r\n")).isEqualTo(ReadResult.Closed)
        expectThat(read("")).isEqualTo(ReadResult.Closed)
        expectThat(read("GET /x HTTP/1.1\r\nX: ${"a".repeat(100)}\r\n\r\n", max = 64)).isEqualTo(ReadResult.TooLarge)
    }

    @Test
    fun `writes a closed plain text response, without body for HEAD`() {
        val response = HttpResponse(302, "héllo", listOf("Location" to "http://x/"))
        val get = ByteArrayOutputStream().also { HttpExchange.write(it, response, includeBody = true) }.toString("UTF-8")
        val head = ByteArrayOutputStream().also { HttpExchange.write(it, response, includeBody = false) }.toString("UTF-8")

        val expectedHead =
            "HTTP/1.1 302 Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 6\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\nLocation: http://x/\r\n\r\n"
        expectThat(get).isEqualTo(expectedHead + "héllo")
        expectThat(head).isEqualTo(expectedHead)
    }
}
