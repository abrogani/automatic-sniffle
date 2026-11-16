package com.abrogani.mytorrentclient.engine

import com.abrogani.mytorrentclient.link.TorrentFile
import com.abrogani.mytorrentclient.link.TorrentLink

internal interface TorrentBackend {
    suspend fun ensureRunning(): Int?

    suspend fun files(link: TorrentLink): List<TorrentFile>

    suspend fun infoHash(link: TorrentLink): String?
}
