package com.abrogani.streamplay

import com.abrogani.streamplay.util.Mapper.toLinkDataString
import com.abrogani.streamplay.util.Mapper.toStream
import com.abrogani.streamplay.util.Mapper.toSubtitle
import com.flixclusive.core.util.coroutines.asyncCalls
import com.flixclusive.model.film.FilmMetadata
import com.flixclusive.model.film.FilmSearchItem
import com.flixclusive.model.film.Movie
import com.flixclusive.model.film.SearchResponseData
import com.flixclusive.model.film.common.tv.Episode
import com.flixclusive.model.film.util.FilmType
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.Provider
import com.flixclusive.provider.ProviderApi
import com.flixclusive.provider.filter.FilterList
import com.phisher98.StreamPlay
import com.phisher98.StreamPlayAnime
import okhttp3.OkHttpClient

class StreamPlayApi(
    client: OkHttpClient,
    provider: Provider
) : ProviderApi(
    client = client,
    provider = provider
) {
    private val streamPlay = StreamPlay()
    private val streamPlayAnime = StreamPlayAnime()

    override val testFilm: FilmMetadata
        get() = Movie(
            tmdbId = 299534,
            imdbId = "tt4154796",
            title = "Avengers: Endgame",
            posterImage = null,
            backdropImage = "/orjiB3oUIsyz60hoEqkiGpy5CeO.jpg",
            homePage = null,
            id = null,
            providerId = "TMDB"
        )

    override suspend fun search(
        title: String,
        page: Int,
        id: String?,
        imdbId: String?,
        tmdbId: Int?,
        filters: FilterList
    ): SearchResponseData<FilmSearchItem> {
        val identifier = id ?: tmdbId?.toString() ?: imdbId
        if (identifier == null) {
            throw IllegalStateException("${provider.name} is not a searchable provider. It is a set of providers combined into one.")
        }

        return SearchResponseData(
            results = listOf(
                FilmSearchItem(
                    id = identifier,
                    title = title,
                    providerId = provider.name,
                    filmType = FilmType.MOVIE,
                    posterImage = null,
                    backdropImage = null,
                    homePage = null
                )
            )
        )
    }

    override suspend fun getLinks(
        watchId: String,
        film: FilmMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        asyncCalls(
            {
                streamPlay.loadLinks(
                    data = film.toLinkDataString(episode),
                    isCasting = false,
                    subtitleCallback = {
                        onLinkFound(it.toSubtitle())
                    },
                    callback = {
                        onLinkFound(it.toStream())
                    }
                )

                // app.baseClient.connectionPool.evictAll()
            },
            {
                streamPlayAnime.loadLinks(
                    data = film.toLinkDataString(episode),
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