package com.abrogani.servetorr

import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType

internal object PlaceholderLinkApi : MediaLinkProviderApi {
    override val supportedLinkTypes: Set<MediaLinkType> = setOf(MediaLinkType.STREAMS)

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit,
    ) = Unit
}
