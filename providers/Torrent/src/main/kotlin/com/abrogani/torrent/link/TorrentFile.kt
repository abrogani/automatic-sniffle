package com.abrogani.torrent.link

/** A file inside a torrent as TorrServer numbers it (`file_stats[].id`). */
internal data class TorrentFile(val id: Int, val path: String, val length: Long)
