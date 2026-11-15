package com.abrogani.torrent.intercept

import com.abrogani.torrent.link.TorrentLink
import com.abrogani.torrent.link.TorrentLinkRewriter
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Wraps another provider's link api, swapping its torrent [Stream]s for ones served by the local engine. */
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

        // Returning only after every rewrite finished matters: the host reads its link cache right after getLinks returns.
        coroutineScope {
            inner.getLinks(media, episode) { link ->
                // A provider that emits after its getLinks returned would hit a completed scope; pass those through.
                if (link is Stream && isActive && TorrentLink.parse(link.url) != null) {
                    launch { onLinkFound(rewriter.rewrite(link, episode) ?: link) }
                } else {
                    onLinkFound(link)
                }
            }
        }
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
