package com.abrogani.flixhq.api

import com.abrogani.flixhq.extensions.fetchDocument
import com.abrogani.flixhq.extensions.toMediaId
import com.abrogani.flixhq.extensions.toPageUrl
import com.abrogani.flixhq.parser.DetailPage
import com.abrogani.flixhq.parser.FlixHQParser
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.media.common.tv.Season
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal class FlixHQMetadataApi(
    private val client: OkHttpClient,
    private val providerId: String,
) : MediaMetadataProviderApi {
    override suspend fun getMovie(media: PartialMedia): Movie = withContext(Dispatchers.IO) {
        val id = media.id.toMediaId()
        val detail = FlixHQParser.parseDetail(client.fetchDocument(id.toPageUrl()), providerId)

        Movie(
            id = id,
            title = detail.title,
            providerId = providerId,
            posterImage = detail.posterImage ?: media.posterImage,
            backdropImage = detail.backdropImage ?: media.backdropImage,
            homePage = id.toPageUrl(),
            overview = detail.overview ?: media.overview,
            rating = detail.rating ?: media.rating,
            runtime = detail.runtime,
            releaseDate = detail.releaseDate ?: media.releaseDate,
            certification = detail.certification,
            language = detail.language,
            genres = detail.genres,
            casts = detail.casts,
            producers = detail.producers,
            recommendations = detail.recommendations,
            externalIds = detail.externalIds(),
        )
    }

    override suspend fun getShow(media: PartialMedia): Show = withContext(Dispatchers.IO) {
        val id = media.id.toMediaId()
        val document = client.fetchDocument(id.toPageUrl())
        val detail = FlixHQParser.parseDetail(document, providerId)
        val seasons = FlixHQParser.parseSeasons(document)

        Show(
            id = id,
            title = detail.title,
            providerId = providerId,
            posterImage = detail.posterImage ?: media.posterImage,
            backdropImage = detail.backdropImage ?: media.backdropImage,
            homePage = id.toPageUrl(),
            overview = detail.overview ?: media.overview,
            rating = detail.rating ?: media.rating,
            runtime = detail.runtime,
            releaseDate = detail.releaseDate ?: media.releaseDate,
            certification = detail.certification,
            language = detail.language,
            genres = detail.genres,
            casts = detail.casts,
            recommendations = detail.recommendations,
            externalIds = detail.externalIds(),
            seasons = seasons,
            totalSeasons = seasons.size,
            totalEpisodes = seasons.sumOf { it.episodeCount },
        )
    }

    override suspend fun getSeason(show: Show, season: Season.Partial): Season.Full? = null

    private fun DetailPage.externalIds(): Map<MediaIdSource, String> =
        imdbId?.let { mapOf(MediaIdSource.IMDB to it) }.orEmpty()
}
