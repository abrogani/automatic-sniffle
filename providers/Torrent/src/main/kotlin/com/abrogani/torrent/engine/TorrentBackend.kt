package com.abrogani.torrent.engine

import com.abrogani.torrent.link.TorrentFile
import com.abrogani.torrent.link.TorrentLink

internal interface TorrentBackend {
    /** Starts the engine if installed and not yet running. Returns the port it serves on, or null when not installed. Throws on start failure. */
    suspend fun ensureRunning(): Int?

    /** Adds the torrent (not saved to TorrServer's db) and waits for its metadata, returning its files. Throws on timeout/HTTP error. */
    suspend fun files(link: TorrentLink): List<TorrentFile>
}
