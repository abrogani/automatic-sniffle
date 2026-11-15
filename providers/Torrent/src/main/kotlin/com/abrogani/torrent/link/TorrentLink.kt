package com.abrogani.torrent.link

/** A playable torrent reference found in some provider's Stream. */
internal data class TorrentLink(
    val source: String,
    val infoHash: String?,
    val fileIndex: Int?,
    val displayName: String?,
) {
    companion object {
        fun parse(url: String): TorrentLink? = TorrentLinkParser.parse(url)
    }
}
