package com.abrogani.torrent.intercept

import com.abrogani.torrent.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import kotlinx.coroutines.CancellationException

internal class InterceptingLinkApi(
    val inner: MediaLinkProviderApi,
    private val rewriter: TorrentLinkRewriter,
    private val isEnabled: suspend () -> Boolean,
) : MediaLinkProviderApi {
    override val supportedLinkTypes: Set<MediaLinkType>
        get() = inner.supportedLinkTypes

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit,
    ) {
        if (!enabled()) {
            inner.getLinks(media, episode, onLinkFound)
            return
        }

        inner.getLinks(media, episode) { link ->
            onLinkFound(if (link is Stream) rewrite(link, episode) else link)
        }
    }

    private fun rewrite(stream: Stream, episode: Episode?): Stream =
        try {
            rewriter.rewrite(stream, episode) ?: stream
        } catch (e: Exception) {
            errorLog("[Torrent] Couldn't rewrite a torrent link, passing it through")
            errorLog(e)
            stream
        }

    private suspend fun enabled(): Boolean =
        try {
            isEnabled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorLog("[Torrent] Couldn't read the interception toggle, leaving links untouched")
            errorLog(e)
            false
        }
}
