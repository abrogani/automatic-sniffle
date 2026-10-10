package com.abrogani.streamplay

import android.content.Context
import android.os.Build
import com.abrogani.streamplay.util.Mapper.toLinkDataString
import com.abrogani.streamplay.util.Mapper.toStream
import com.abrogani.streamplay.util.Mapper.toSubtitle
import com.flixclusive.core.util.coroutines.asyncCalls
import com.flixclusive.core.util.log.errorLog
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

class StreamPlayApi internal constructor(
    private val context: Context,
    private val providerId: String,
    private val loader: Cs3PluginLoader,
) : MediaLinkProviderApi, CrossMatchProviderApi {

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

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

        val apis = loader.load(context).apis
        val failures = java.util.Collections.synchronizedList(mutableListOf<LinkageError>())

        asyncCalls(
            *apis.map<MainAPI, suspend () -> Unit> { api ->
                {
                    if (api.name != TMDB_PROVIDER_NAME || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            api.loadLinks(
                                data = data,
                                isCasting = false,
                                subtitleCallback = { onLinkFound(it.toSubtitle()) },
                                callback = { onLinkFound(it.toStream()) }
                            )
                        } catch (e: LinkageError) {
                            errorLog("[StreamPlay] ${api.name} is incompatible with this host: $e")
                            failures.add(e)
                        }
                    }
                }
            }.toTypedArray()
        )

        if (failures.size == apis.size) throw failures.first()
    }

    private companion object {
        const val TMDB_PROVIDER_NAME = "StreamPlay"
    }
}
