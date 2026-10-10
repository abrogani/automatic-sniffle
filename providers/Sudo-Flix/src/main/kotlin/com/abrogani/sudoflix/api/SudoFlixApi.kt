package com.abrogani.sudoflix.api

import com.abrogani.sudoflix.api.nsbx.NsbxApi
import com.abrogani.sudoflix.api.nsbx.VidBingeApi
import com.abrogani.sudoflix.api.primewire.PrimeWireApi
import com.abrogani.sudoflix.api.ridomovies.RidoMoviesApi
import com.abrogani.sudoflix.api.vidsrcto.VidSrcToApi
import com.flixclusive.core.util.coroutines.mapAsync
import com.flixclusive.core.util.exception.safeCall
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
import okhttp3.OkHttpClient

/**
 *
 * RIP m-w
 *
 * This is a set of providers combined into one. It has no catalog of its own: every source
 * resolves links straight from the TMDB/IMDb ids of the media it is handed.
 * */
class SudoFlixApi(
    client: OkHttpClient,
    private val providerId: String
) : MediaLinkProviderApi, CrossMatchProviderApi {
    private val providersList = listOf(
        NsbxApi(client),
        VidBingeApi(client),
        RidoMoviesApi(client),
        PrimeWireApi(client),
        VidSrcToApi(client),
    )

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        providersList.mapAsync {
            safeCall {
                it.getLinks(
                    media = media,
                    episode = episode,
                    onLinkFound = onLinkFound
                )
            }
        }
    }

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
        )
    }
}
