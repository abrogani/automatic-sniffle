package com.abrogani.torrent.link

import com.flixclusive.model.media.common.tv.Episode
import java.net.URLDecoder

internal object ProxyUrl {
    const val PATH_PREFIX = "/torrent/"
    private const val FALLBACK_NAME = "video"

    data class Request(val link: TorrentLink, val season: Int?, val episode: Int?)

    fun build(port: Int, link: TorrentLink, episode: Episode?): String {
        val name = link.displayName?.replace('/', ' ')?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
        return buildString {
            append("http://127.0.0.1:").append(port)
            append(PATH_PREFIX).append(StreamUrl.encodePathSegment(name))
            append("?link=").append(StreamUrl.encodeQuery(link.source))
            episode?.let { append("&s=").append(it.season).append("&e=").append(it.number) }
        }
    }

    fun parseQuery(rawQuery: String): Request? {
        val params = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('=')) ?: return null
            val value = decode(pair.substringAfter('=', "")) ?: return null
            if (params.put(key, value) != null) return null
        }

        val link = params["link"]?.let(TorrentLink::parse) ?: return null
        val season = params["s"]
        val episode = params["e"]
        if ((season == null) != (episode == null)) return null
        if (season == null || episode == null) return Request(link, null, null)

        val seasonNumber = season.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val episodeNumber = episode.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Request(link, seasonNumber, episodeNumber)
    }

    private fun decode(value: String): String? =
        try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            null
        }
}
