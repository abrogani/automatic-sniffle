package com.abrogani.servetorr.engine

import com.abrogani.servetorr.link.TorrentFile
import com.abrogani.servetorr.link.TorrentLink

internal interface TorrentBackend {
    suspend fun ensureRunning(): Int?

    suspend fun files(link: TorrentLink): List<TorrentFile>

    suspend fun infoHash(link: TorrentLink): String?
}
