package com.abrogani.streamplay

import android.content.Context
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
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.lagradost.cloudstream3.MainAPI

/**
 * This is a set of providers combined into one. It has no catalog of its own: every source
 * resolves links straight from the TMDB/IMDb ids of the media it is handed.
 * */
class StreamPlayApi internal constructor(
    private val context: Context,
    private val providerId: String,
    private val loader: Cs3PluginLoader,
) : MediaLinkProviderApi, CrossMatchProviderApi {

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
        val data = media.toLinkDataString(episode)

        // The plugin registers its own providers (the TMDB-based one and the anime one).
        val apis = loader.load(context).apis
        asyncCalls(
            *apis.map<MainAPI, suspend () -> Unit> { api ->
                {
                    // The TMDB-based provider needs API 26+.
                    if (api.name != TMDB_PROVIDER_NAME || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        api.loadLinks(
                            data = data,
                            isCasting = false,
                            subtitleCallback = { onLinkFound(it.toSubtitle()) },
                            callback = { onLinkFound(it.toStream()) }
                        )
                    }
                }
            }.toTypedArray()
        )
    }

    private companion object {
        const val TMDB_PROVIDER_NAME = "StreamPlay"
    }
}
