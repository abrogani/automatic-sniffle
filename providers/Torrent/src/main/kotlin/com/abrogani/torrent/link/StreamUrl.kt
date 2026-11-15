package com.abrogani.torrent.link

import java.net.URLEncoder

/*
 * Verified against YouROK/TorrServer master (server/web/api/stream.go, server/torr/torrent.go):
 * `/stream/<fname>?link=…&index=…&play`; the fname segment is only cosmetic for `play`. `index` is
 * the 1-based `file_stats[].id`. Without `index`, a single-file torrent plays file 1 and a
 * multi-file one answers 400, so an unresolved link simply omits it rather than guessing.
 * The handler unescapes `link` once more after gin's own decoding, so '%' and '+' in the
 * source are pre-escaped to survive that second pass intact.
 */
internal object StreamUrl {
    private const val FALLBACK_NAME = "video"

    fun build(port: Int, link: TorrentLink, file: TorrentFile?): String {
        val name =
            file?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: link.displayName?.takeIf { it.isNotBlank() }
                ?: FALLBACK_NAME

        return buildString {
            append("http://127.0.0.1:").append(port)
            append("/stream/").append(encodePathSegment(name))
            append("?link=").append(encodeQuery(protectFromSecondUnescape(link.source)))
            file?.let { append("&index=").append(it.id) }
            append("&play")
        }
    }

    private fun protectFromSecondUnescape(value: String): String = value.replace("%", "%25").replace("+", "%2B")

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodePathSegment(value: String): String = encodeQuery(value).replace("+", "%20")
}
