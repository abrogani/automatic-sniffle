package com.abrogani.mytorrentclient.link

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
