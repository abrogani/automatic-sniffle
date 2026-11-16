package com.abrogani.torrent.engine

import com.abrogani.torrent.link.TorrentFile
import com.abrogani.torrent.link.TorrentLink

internal interface TorrentBackend {
    suspend fun ensureRunning(): Int?

    suspend fun files(link: TorrentLink): List<TorrentFile>

    suspend fun infoHash(link: TorrentLink): String?
}
