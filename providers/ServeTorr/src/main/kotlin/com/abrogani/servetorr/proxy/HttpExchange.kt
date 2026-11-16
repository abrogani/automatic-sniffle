package com.abrogani.servetorr.proxy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal data class HttpRequest(val method: String, val path: String, val rawQuery: String)

internal sealed interface ReadResult {
    data class Ok(val request: HttpRequest) : ReadResult
    data object Malformed : ReadResult
    data object TooLarge : ReadResult
    data object Closed : ReadResult
}

internal data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: List<Pair<String, String>> = emptyList(),
)

internal object HttpExchange {
    private val REQUEST_LINE = Regex("""^([A-Z]+) (\S+) HTTP/1\.[01]$""")

    fun read(input: InputStream, maxBytes: Int): ReadResult {
        var requestLine: String? = null
        var total = 0
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return ReadResult.Closed
            if (++total > maxBytes) return ReadResult.TooLarge
            if (byte != '\n'.code) {
                line.write(byte)
                continue
            }

            val text = line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            line.reset()
            when {
                requestLine == null && text.isEmpty() -> Unit
                requestLine == null -> requestLine = text
                text.isEmpty() -> return parse(requestLine)
            }
        }
    }

    private fun parse(requestLine: String): ReadResult {
        val match = REQUEST_LINE.matchEntire(requestLine) ?: return ReadResult.Malformed
        val (method, rawTarget) = match.destructured
        val target =
            when {
                rawTarget.startsWith("/") -> rawTarget
                rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true) ->
                    "/" + rawTarget.substringAfter("://").substringAfter('/', "")
                else -> return ReadResult.Malformed
            }.substringBefore('#')

        return ReadResult.Ok(
            HttpRequest(
                method = method,
                path = target.substringBefore('?'),
                rawQuery = target.substringAfter('?', ""),
            ),
        )
    }

    fun write(output: OutputStream, response: HttpResponse, includeBody: Boolean) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head =
            buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (includeBody) output.write(body)
        output.flush()
    }

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            302 -> "Found"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status"
        }
}
