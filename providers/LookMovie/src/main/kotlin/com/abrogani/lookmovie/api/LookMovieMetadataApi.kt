package com.abrogani.lookmovie.api

import com.abrogani.lookmovie.dto.EpisodeListResponseDto
import com.abrogani.lookmovie.dto.ShowStorageDto
import com.abrogani.lookmovie.extensions.fetch
import com.abrogani.lookmovie.extensions.fromJson
import com.abrogani.lookmovie.extensions.parseMovieTitle
import com.abrogani.lookmovie.extensions.parseStorage
import com.abrogani.lookmovie.extensions.toAbsoluteUrl
import com.abrogani.lookmovie.extensions.toPartialMedia
import com.abrogani.lookmovie.extensions.toReleaseDate
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.Genre
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.media.common.tv.Season
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

class LookMovieMetadataApi(
    private val client: OkHttpClient,
    private val providerId: String,
) : MediaMetadataProviderApi {
    /** show slug -> season number -> episode number -> episode id */
    private val episodeIds = ConcurrentHashMap<String, Map<Int, Map<Int, String>>>()

    /**
     * Returns the episode id of the given show's episode that was seen by [getShow], if any.
     * */
    fun getCachedEpisodeId(showSlug: String, season: Int, episode: Int): String? =
        episodeIds[showSlug]?.get(season)?.get(episode)

    override suspend fun getShow(media: PartialMedia): Show {
        val slug = media.id
        val (storage, document, episodes) = fetchShowPage(slug)

        val seasons = episodes.toSortedMap().map { (seasonNumber, list) ->
            Season.Full(
                id = "$slug-s$seasonNumber",
                number = seasonNumber,
                isReleased = true,
                title = "Season $seasonNumber",
                episodes = list.sortedBy { it.number }.map {
                    Episode(
                        id = it.id,
                        number = it.number,
                        season = seasonNumber,
                        isReleased = true,
                        title = it.title,
                    )
                }
            )
        }

        episodeIds[slug] = seasons.associate { season ->
            season.number to season.episodes.associate { it.number to it.id }
        }

        return Show(
            id = slug,
            title = storage.title ?: media.title,
            posterImage = storage.posterMedium.toAbsoluteUrl(BASE_URL) ?: media.posterImage,
            homePage = media.homePage ?: "$BASE_URL/shows/view/$slug",
            backdropImage = storage.backdropHuge.toAbsoluteUrl(BASE_URL) ?: media.backdropImage,
            releaseDate = storage.year?.toIntOrNull()?.toReleaseDate() ?: media.releaseDate,
            rating = media.rating,
            providerId = providerId,
            overview = document.select("meta[name=description]").attr("content")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: media.overview,
            seasons = seasons,
            totalSeasons = seasons.size,
            totalEpisodes = storage.totalEpisodes ?: seasons.sumOf { it.episodeCount },
        )
    }

    /**
     * Skips `/shows/view/` since the play page already has the seasons in its show_storage,
     * unless the play page requires a captcha. Then, the show id is taken from the view page
     * and the list of episode ids is requested instead.
     * */
    private fun fetchShowPage(slug: String): ShowPage {
        val playHtml = client.fetch("$BASE_URL/shows/play/$slug")
        parseStorage<ShowStorageDto>(playHtml, SHOW_STORAGE_KEY)?.let { storage ->
            return ShowPage(storage, Jsoup.parse(playHtml), storage.toEpisodes())
        }

        val viewHtml = client.fetch("$BASE_URL/shows/view/$slug")
        val storage = parseStorage<ShowStorageDto>(viewHtml, SHOW_STORAGE_KEY)
            ?: throw IllegalStateException("$SHOW_STORAGE_KEY not found for show: $slug")

        val episodeList = fromJson<EpisodeListResponseDto>(
            client.fetch("$BASE_URL/api/v2/download/episode/list?id=${storage.idShow}")
        )

        return ShowPage(storage, Jsoup.parse(viewHtml), episodeList.toEpisodes())
    }

    private fun ShowStorageDto.toEpisodes(): Map<Int, List<EpisodeEntry>> =
        seasons.orEmpty()
            .filterNotNull()
            .mapNotNull { dto ->
                val season = dto.season?.toIntOrNull() ?: return@mapNotNull null
                val number = dto.episode?.toIntOrNull() ?: return@mapNotNull null
                val id = dto.idEpisode ?: return@mapNotNull null

                season to EpisodeEntry(number, dto.title, id.toString())
            }
            .groupBy({ it.first }, { it.second })

    private fun EpisodeListResponseDto.toEpisodes(): Map<Int, List<EpisodeEntry>> =
        list.orEmpty()
            .mapNotNull { (season, seasonEpisodes) ->
                val seasonNumber = season.toIntOrNull() ?: return@mapNotNull null

                seasonNumber to seasonEpisodes.mapNotNull { (episode, dto) ->
                    EpisodeEntry(episode.toIntOrNull() ?: return@mapNotNull null, null, dto.idEpisode)
                }
            }
            .toMap()

    // Seasons are already fetched entirely by [getShow]
    override suspend fun getSeason(show: Show, season: Season.Partial): Season.Full? = null

    override suspend fun getMovie(media: PartialMedia): Movie {
        val document = Jsoup.parse(client.fetch("$BASE_URL/movies/view/${media.id}"))

        // Extract basic movie information
        val titleElement = document.select("h1.bd-hd").first()
        val title = document.parseMovieTitle() ?: media.title
        val year = titleElement?.select("span")?.text()?.toIntOrNull()

        // Extract rating
        val rating = document.select("div.rate p span").first()?.text()?.toDoubleOrNull()

        // Extract poster image
        val posterImage = document.select("p.movie__poster").attr("data-background-image")
            .toAbsoluteUrl(BASE_URL)

        // Extract backdrop image
        val backdropImage = document.select("div.hero.mv-single-hero").attr("data-background-image")
            .toAbsoluteUrl(BASE_URL)

        // Extract overview/description
        val overview = document.select("p.description").text().trim().takeIf { it.isNotEmpty() }

        // Extract runtime
        val runtimeText = document.select("div.movie-description__duration span").text()
        val runtime = runtimeText.replace(" min.", "").toIntOrNull()

        // Extract genres
        val genresText = document.select("div.genres span").getOrNull(1)?.text() ?: ""
        val genres = genresText.split(",")
            .drop(1) // Skip the year
            .map { Genre(name = it.trim()) }
            .filter { it.name.isNotEmpty() }

        // Extract video URL for WebView captcha flow
        val videoUrl = document.select("a.round-button").attr("href").toAbsoluteUrl(BASE_URL)

        // Extract recommendations
        val recommendations = mutableListOf<PartialMedia>()
        document.select("div.may-also-like div.movie-item").forEach { recommendationElement ->
            runCatching {
                val recommendation = recommendationElement.toPartialMedia(
                    baseUrl = BASE_URL,
                    providerId = providerId,
                    type = MediaType.MOVIE
                )
                recommendations.add(recommendation)
            }
        }

        return Movie(
            id = media.id,
            title = title,
            posterImage = posterImage,
            homePage = media.homePage,
            backdropImage = backdropImage,
            releaseDate = year?.toReleaseDate(),
            rating = rating,
            providerId = providerId,
            runtime = runtime,
            overview = overview,
            genres = genres,
            recommendations = recommendations,
            customProperties = mapOf(
                VIDEO_URL_KEY to videoUrl
            )
        )
    }

    private class EpisodeEntry(val number: Int, val title: String?, val id: String)

    private data class ShowPage(
        val storage: ShowStorageDto,
        val document: Document,
        val episodes: Map<Int, List<EpisodeEntry>>,
    )
}
