package com.abrogani.streamplay

import android.os.Build
import com.abrogani.streamplay.util.Mapper.toLinkDataString
import com.abrogani.streamplay.util.Mapper.toStream
import com.abrogani.streamplay.util.Mapper.toSubtitle
import com.flixclusive.core.util.coroutines.asyncCalls
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.media.common.MediaIdSource.Companion.imdbId
import com.flixclusive.model.media.common.MediaIdSource.Companion.tmdbId
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import com.phisher98.StreamPlay
import com.phisher98.StreamPlayAnime

/**
 * This is a set of providers combined into one. It has no catalog of its own: every source
 * resolves links straight from the TMDB/IMDb ids of the media it is handed.
 * */
class StreamPlayApi(
    private val providerId: String
) : MediaLinkProviderApi, CrossMatchProviderApi {
    private val streamPlay = StreamPlay()
    private val streamPlayAnime = StreamPlayAnime()

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    // Nothing to look up: the sources are keyed by the ids the media already carries.
    override suspend fun getById(
        mediaType: MediaType,
        sourceIds: Map<MediaIdSource, String>,
    ): MediaMetadata? = null

    override suspend fun getByFuzzy(media: MediaMetadata): MediaMetadata? {
        if (media.tmdbId == null && media.imdbId == null) {
            return null
        }

        return PartialMedia(
            type = media.type,
            id = media.tmdbId ?: media.imdbId!!,
            title = media.title,
            providerId = providerId,
            posterImage = media.posterImage,
            backdropImage = media.backdropImage,
            releaseDate = media.releaseDate,
            externalIds = media.externalIds,
            language = media.language,
            genres = media.genres,
        )
    }

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        asyncCalls(
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    streamPlay.loadLinks(
                        data = media.toLinkDataString(episode),
                        isCasting = false,
                        subtitleCallback = {
                            onLinkFound(it.toSubtitle())
                        },
                        callback = {
                            onLinkFound(it.toStream())
                        }
                    )
                }
            },
            {
                streamPlayAnime.loadLinks(
                    data = media.toLinkDataString(episode),
                    isCasting = false,
                    subtitleCallback = {
                        onLinkFound(it.toSubtitle())
                    },
                    callback = {
                        onLinkFound(it.toStream())
                    }
                )
            },
        )
    }
}
