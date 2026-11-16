package com.abrogani.mytorrentclient.link

import java.net.URLEncoder

internal object StreamUrl {
    private const val FALLBACK_NAME = "video"

    fun build(port: Int, infoHash: String, link: TorrentLink, file: TorrentFile): String {
        val name = file.path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: FALLBACK_NAME

        return buildString {
            append("http://127.0.0.1:").append(port)
            append("/stream/").append(encodePathSegment(name))
            append("?link=").append(encodeQuery(protectFromSecondUnescape(magnet(infoHash, link))))
            append("&index=").append(file.id)
            append("&play")
        }
    }

    fun magnet(infoHash: String, link: TorrentLink): String =
        buildString {
            append("magnet:?xt=urn:btih:").append(infoHash)
            link.displayName?.let { append("&dn=").append(encodeQuery(it)) }
            TorrentLinkParser.trackers(link.source).forEach { append("&tr=").append(encodeQuery(it)) }
        }

    private fun protectFromSecondUnescape(value: String): String = value.replace("%", "%25").replace("+", "%2B")

    internal fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    internal fun encodePathSegment(value: String): String = encodeQuery(value).replace("+", "%20")
}
