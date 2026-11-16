package com.abrogani.torrent.link

import com.flixclusive.core.util.log.warnLog
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.Stream

internal class TorrentLinkRewriter(
    private val proxyPort: () -> Int?,
    private val engineInstalled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun rewrite(stream: Stream, episode: Episode?): Stream? {
        val link = TorrentLink.parse(stream.url) ?: return null
        val port = availablePort()
            ?: return stream.copy(flags = withoutExpiry(stream.flags) + Flag.Expires(clock() + UNAVAILABLE_LIFETIME_MILLIS))

        return Stream(
            name = stream.name,
            url = ProxyUrl.build(port, link, episode),
            description = describe(stream.description),
            flags = withoutExpiry(stream.flags).filterNot { it is Flag.RequiresAuth }.toSet() +
                Flag.Expires(clock() + LINK_LIFETIME_MILLIS),
        )
    }

    private fun availablePort(): Int? =
        try {
            proxyPort()?.takeIf { engineInstalled() }
        } catch (e: Exception) {
            warnLog("[Torrent] Couldn't check the torrent proxy: $e")
            null
        }

    private fun withoutExpiry(flags: Set<Flag>?): Set<Flag> = flags.orEmpty().filterNot { it is Flag.Expires }.toSet()

    private fun describe(original: String?): String =
        if (original.isNullOrBlank()) TORRENT_LABEL else "$TORRENT_LABEL\n$original"

    private companion object {
        const val TORRENT_LABEL = "Torrent"

        const val LINK_LIFETIME_MILLIS = 12L * 60 * 60 * 1000
        const val UNAVAILABLE_LIFETIME_MILLIS = 60L * 60 * 1000
    }
}
