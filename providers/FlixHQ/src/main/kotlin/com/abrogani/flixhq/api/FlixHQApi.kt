package com.abrogani.flixhq.api

import com.abrogani.flixhq.api.util.TvShowCacheData
import com.abrogani.flixhq.api.util.getEpisodeId
import com.abrogani.flixhq.api.util.getSeasonId
import com.abrogani.flixhq.api.util.getServerName
import com.abrogani.flixhq.api.util.getServerUrl
import com.abrogani.flixhq.api.util.replaceWhitespaces
import com.abrogani.flixhq.api.util.toPartialMedia
import com.abrogani.flixhq.api.util.toReleaseDate
import com.abrogani.flixhq.api.util.year
import com.abrogani.flixhq.extractors.rabbitstream.UpCloud
import com.abrogani.flixhq.extractors.rabbitstream.VidCloud
import com.abrogani.flixhq.webview.util.removeAccents
import com.flixclusive.core.util.coroutines.mapAsync
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.media.common.tv.Season
import com.flixclusive.provider.capability.CrossMatchProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.filter.FilterList
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class FlixHQApi(
    internal val client: OkHttpClient,
    private val providerId: String,
) : SearchProviderApi, MediaMetadataProviderApi, CrossMatchProviderApi {
    val baseUrl: String = "https://flixhq.to"
    private var tvCacheData: TvShowCacheData = TvShowCacheData()

    override val filters: FilterList = FilterList()

    @Suppress("SpellCheckingInspection")
    internal val extractors = mapOf(
        "vidcloud" to VidCloud(client),
        "upcloud" to UpCloud(client),
    )

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterList,
    ): PaginatedMedia<PartialMedia> {
        val query = query.removeAccents()

        client.request(
            url = "${baseUrl}/search/${
                query.replaceWhitespaces("-")
            }?page=$page"
        ).execute().body?.string()?.let { data ->
            val doc = Jsoup.parse(data)
            val navSelector = "div.pre-pagination:nth-child(3) > nav:nth-child(1) > ul:nth-child(1)"
            val hasNextPage = doc.select(navSelector).size > 0 && doc.select(navSelector).last()
                ?.hasClass("active") == false

            val results = doc.select(".film_list-wrap > div.flw-item").map { element ->
                element.toPartialMedia(
                    baseUrl = baseUrl,
                    providerId = providerId
                )
            }

            return PaginatedMedia(
                page = page,
                hasNextPage = hasNextPage,
                results = results
            )
        }

        return PaginatedMedia()
    }

    override suspend fun getMovie(media: PartialMedia): Movie {
        val (doc, uid) = fetchDetailsPage(media)

        val movie = Movie(
            id = media.watchPath(),
            providerId = media.providerId,
            title = doc.select(".heading-name > a:nth-child(1)").text().ifEmpty { media.title },
            posterImage = media.posterImage,
            backdropImage = media.backdropImage,
            homePage = media.homePage,
            releaseDate = doc.getReleaseDate(),
        )

        tvCacheData = TvShowCacheData(id = uid, movie)
        return movie
    }

    override suspend fun getShow(media: PartialMedia): Show {
        val (doc, uid) = fetchDetailsPage(media)
        val seasons = fetchSeasons(uid)

        val show = Show(
            id = media.watchPath(),
            providerId = media.providerId,
            title = doc.select(".heading-name > a:nth-child(1)").text().ifEmpty { media.title },
            posterImage = media.posterImage,
            backdropImage = media.backdropImage,
            homePage = media.homePage,
            releaseDate = doc.getReleaseDate(),
            // Episodes are resolved lazily when links are requested, so only the season count is known.
            seasons = emptyList(),
            totalSeasons = seasons.size,
            totalEpisodes = 0,
        )

        tvCacheData = TvShowCacheData(id = uid, show, seasons = seasons)
        return show
    }

    override suspend fun getSeason(show: Show, season: Season.Partial): Season.Full? = null

    // FlixHQ has no external ids to look up, so only fuzzy matching is possible.
    override suspend fun getById(
        mediaType: MediaType,
        sourceIds: Map<MediaIdSource, String>,
    ): MediaMetadata? = null

    override suspend fun getByFuzzy(media: MediaMetadata): MediaMetadata? {
        val mediaYear = media.releaseDate?.year()
        val maxPage = 3

        for (page in 1..maxPage) {
            val searchResponse = search(
                query = media.title,
                page = page
            )

            if (searchResponse.results.isEmpty())
                return null

            for (result in searchResponse.results) {
                val titleMatches = result.title.equals(media.title, ignoreCase = true)
                val typeMatches = result.type == media.type
                if (!titleMatches || !typeMatches) continue

                if (media.isShow) {
                    val showInfo = getShow(result)

                    val yearMatches = mediaYear != null && showInfo.releaseDate?.year() == mediaYear
                    val seasonCountMatches = (media as? Show)?.totalSeasons == showInfo.totalSeasons

                    if (yearMatches || seasonCountMatches) {
                        return showInfo
                    }
                } else if (mediaYear != null && result.releaseDate?.year() == mediaYear) {
                    return getMovie(result)
                }
            }

            if (!searchResponse.hasNextPage) break
        }

        return null
    }

    private fun PartialMedia.watchPath(): String = id.removePrefix("$baseUrl/")

    private fun Document.getReleaseDate(): Long? {
        val releaseDate = select("div.row-line:nth-child(3)").text()
            .replace("Released: ", "").trim()

        return releaseDate.toReleaseDate()
    }

    private fun fetchDetailsPage(media: PartialMedia): Pair<Document, String> {
        val response = client.request(url = "$baseUrl/${media.watchPath()}").execute()
        val data = response.body?.string()
            ?: throw NullPointerException("Media info is null!")

        val doc = Jsoup.parse(data)
        return doc to doc.select(".watch_block").attr("data-id")
    }

    private fun fetchSeasons(uid: String) =
        Jsoup.parse(
            client.request(url = "$baseUrl/ajax/season/list/$uid").execute().body?.string()
                ?: throw Exception("Failed to fetch season data from provider")
        ).select(".dropdown-menu > a")

    internal fun getEpisodeId(
        watchId: String,
        episode: Int,
        season: Int,
    ): String {
        val watchIdToUse = watchId.split("-").last()
        val isSameId = tvCacheData.id == watchIdToUse

        val ajaxReqUrl: (String, Boolean) -> String = { id, isSeasons ->
            "$baseUrl/ajax/season/${if (isSeasons) "list" else "episodes"}/$id"
        }

        if (isSameId) {
            tvCacheData.episodes
                ?.getEpisodeId(episode)
                ?.let {
                    return it
                }
        }

        if (tvCacheData.seasons == null || !isSameId) {
            val responseSeasons =
                client.request(url = ajaxReqUrl(watchIdToUse, true)).execute()
            val dataSeasons = responseSeasons.body?.string()
                ?: throw Exception("Failed to fetch season data from provider")

            val seasonsDoc = Jsoup.parse(dataSeasons)
                .select(".dropdown-menu > a")

            tvCacheData = if (isSameId) {
                tvCacheData.copy(seasons = seasonsDoc)
            } else {
                TvShowCacheData(id = watchIdToUse, seasons = seasonsDoc)
            }
        }

        val seasons = tvCacheData.seasons!!

        if (seasons.size < season)
            throw Exception("Season $season is not available.")

        val seasonId =
            seasons.getSeasonId(season) ?: throw Exception("Season $season could not be found!")

        val responseEpisodes = client.request(url = ajaxReqUrl(seasonId, false)).execute()
        val dataEpisodes = responseEpisodes.body?.string()
            ?: throw Exception("Failed to fetch episode id from provider")

        val docEpisodes = Jsoup.parse(dataEpisodes)
        val episodes = docEpisodes.select(".nav > li")

        tvCacheData = if (isSameId) {
            tvCacheData.copy(episodes = episodes)
        } else {
            TvShowCacheData(
                id = watchIdToUse,
                seasons = seasons,
                episodes = episodes
            )
        }

        return episodes.getEpisodeId(episode) ?: throw Exception("Cannot find episode id!")
    }

    internal suspend fun getEpisodeIdAndServers(
        watchId: String,
        episode: Int?,
        season: Int?,
    ): List<Pair<String, String>> {
        val isTvShow = season != null && episode != null

        val episodeId = if (isTvShow) {
            getEpisodeId(
                watchId = watchId,
                episode = episode!!,
                season = season!!
            )
        } else watchId.split("-").last()

        val fetchServerUrl =
            if (watchId.contains("movie")) {
                "$baseUrl/ajax/episode/list/$episodeId"
            } else {
                "$baseUrl/ajax/episode/servers/$episodeId"
            }

        val response = client.request(url = fetchServerUrl).execute()
        val data = response.body?.string()
            ?: throw IllegalStateException("No available servers for this film")

        val doc = Jsoup.parse(data)
        return doc.select(".nav > li")
            .mapAsync { element ->
                val anchorElement = element.select("a")

                anchorElement.getServerName(watchId) to anchorElement.getServerUrl(baseUrl, watchId)
            }
    }
}