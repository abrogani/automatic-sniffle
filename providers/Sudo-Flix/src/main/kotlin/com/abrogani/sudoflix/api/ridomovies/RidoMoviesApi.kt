package com.abrogani.sudoflix.api.ridomovies

import com.abrogani.sudoflix.api.util.fromJson
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.MediaIdSource.Companion.imdbId
import com.flixclusive.model.media.common.MediaIdSource.Companion.tmdbId
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import com.abrogani.sudoflix.api.ridomovies.RidoMoviesConstant.RIDO_MOVIES_BASE_URL
import com.abrogani.sudoflix.api.ridomovies.dto.RidoMoviesEmbedDto
import com.abrogani.sudoflix.api.ridomovies.dto.RidoMoviesSearchDto
import com.abrogani.sudoflix.api.ridomovies.extractor.CloseLoad
import com.abrogani.sudoflix.api.ridomovies.extractor.Ridoo
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class RidoMoviesApi(
    private val client: OkHttpClient
) : MediaLinkProviderApi {
    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    private val name = "RidoMovies"
    private val baseUrl  = RIDO_MOVIES_BASE_URL

    private val closeLoad = CloseLoad(client)
    private val ridoo = Ridoo(client)

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val fullSlug = getFullSlug(
            imdbId = media.imdbId ?: media.title,
            tmdbId = media.tmdbId
        )

        val episodeId: String
        val iframeSourceUrl = if (media.isShow) {
            episodeId = getEpisodeId(
                fullSlug = fullSlug,
                season = episode!!.season,
                episode = episode.number
            )

            "$baseUrl/core/api/episodes/$episodeId/videos"
        } else "$baseUrl/core/api/$fullSlug/videos"

        val embedUrl = getEmbedUrl(urlQuery = iframeSourceUrl)

        when {
            embedUrl.contains("closeload") -> {
                closeLoad.extract(
                    url = embedUrl,
                    onLinkFound = onLinkFound
                )
            }
            embedUrl.contains("ridoo") -> {
                ridoo.extract(
                    url = embedUrl,
                    onLinkFound = onLinkFound
                )
            }
        }
    }

    private fun getFullSlug(
        imdbId: String,
        tmdbId: String?,
    ): String {
        val initialResponse = client.request(
            url = "$baseUrl/core/api/search?q=$imdbId"
        ).execute()
            .use {
                val stringResponse = it.body?.string()

                if (!it.isSuccessful || stringResponse == null) {
                    throw IllegalStateException("[$name]> Could not get full slug")
                }

                fromJson<RidoMoviesSearchDto>(stringResponse)
            }

        val noSearchResultsException = IllegalStateException("[$name]> Could not find full slug")
        if (initialResponse.data.items.isEmpty()) {
            throw noSearchResultsException
        }

        initialResponse.data.items.forEach {
            if (it.contentTable.tmdbId.toString() == tmdbId || it.contentTable.imdbId == imdbId) {
                return it.fullSlug
            }
        }

        throw noSearchResultsException
    }

    private fun getEpisodeId(
        fullSlug: String,
        season: Int,
        episode: Int,
    ): String {
        val fullEpisodeSlug = "season-$season/episode-$episode"

        val regexPattern = Regex(
            """
                \\"id\\":\\"(\d+)\\"(?=.*?\\"fullSlug\\":\\"[^"]*$fullEpisodeSlug[^"]*\\")
            """.trimIndent()
        )

        val episodeIds = client.request(
            url = "$baseUrl/$fullSlug"
        ).execute()
            .use {
                val responseStr = it.body?.string()

                if (!it.isSuccessful || responseStr == null) {
                    throw IllegalStateException("[$name]> Could not get episode ids")
                }

                regexPattern.findAll(responseStr)
                    .map { match ->
                        match.groupValues[1]
                    }.toList()
            }

        val noEpisodeIdFoundException = IllegalStateException("[$name]> Could not find the episode id")
        if (episodeIds.isEmpty()) {
            throw noEpisodeIdFoundException
        }

        val episodeId = episodeIds.lastOrNull()
            ?: throw noEpisodeIdFoundException

        return episodeId
    }

    private fun getEmbedUrl(
        urlQuery: String
    ): String {
        return client.request(
            url = urlQuery
        ).execute()
            .use {
                val stringResponse = it.body?.string()

                val noEmbedDetailsException = IllegalStateException("[$name]> Could not get embed details")
                if (!it.isSuccessful || stringResponse == null) {
                    throw noEmbedDetailsException
                }

                val iFrame = fromJson<RidoMoviesEmbedDto>(stringResponse)
                    .data.getOrNull(0)
                    ?.get("url")
                    ?: throw noEmbedDetailsException

                Jsoup.parse(iFrame).select("iframe").attr("data-src")
            }
    }
}