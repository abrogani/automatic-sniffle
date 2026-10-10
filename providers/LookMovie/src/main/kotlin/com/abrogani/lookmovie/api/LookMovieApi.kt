package com.abrogani.lookmovie.api

import com.abrogani.lookmovie.extensions.removeAccents
import com.abrogani.lookmovie.extensions.toPartialMedia
import com.abrogani.lookmovie.extensions.toReleaseDate
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.Genre
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.media.common.tv.Season
import com.flixclusive.model.provider.Catalog
import com.flixclusive.provider.capability.CatalogProviderApi
import com.flixclusive.provider.capability.MediaMetadataProviderApi
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.filter.FilterList
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

internal const val VIDEO_URL_KEY = "videoUrl"

/**
 *
 * For LookMovie WebView API integration and testing purposes.
 * */
class LookMovieApi(
    val client: OkHttpClient,
    private val providerId: String
) : CatalogProviderApi, SearchProviderApi, MediaMetadataProviderApi {
    val baseUrl: String = "https://www.lookmovie2.to"

    override val filters: FilterList = FilterList()

    override suspend fun getCatalogs(): List<Catalog> = listOf(
        Catalog(
            name = "Latest Movies",
            url = "$baseUrl/movies",
            canPaginate = true,
            image = "https://i.imgur.com/JhbAqBt.png",
            providerId = providerId
        )
    )

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterList,
    ): PaginatedMedia<PartialMedia> {
        val query = query.removeAccents()

        val data = client.request(
            url = "${baseUrl}/movies/search/?q=$query&page=$page"
        ).execute().body.string()

        val results = mutableListOf<PartialMedia>()

        val document = Jsoup.parse(data)
        val movieElements = document.select("div.flex-wrap-movielist div.movie-item-style-1")

        if (movieElements.isNotEmpty()) {
            movieElements.forEach { element ->
                val item = element.toPartialMedia(
                    providerId = providerId,
                    baseUrl = baseUrl,
                    type = MediaType.MOVIE // TODO(support TV shows in the future?)
                )

                results.add(item)
            }
        }

        val pagination = document.select("ul.pagination li")
        val lastPageElement = pagination.find { it.hasClass("last") }
        val hasNextPage = if (lastPageElement != null) {
            val lastLink = lastPageElement.select("a").first()
            val href = lastLink?.attr("href") ?: ""
            val lastPageNumber = href.substringAfter("page=").toIntOrNull() ?: page
            page < lastPageNumber
        } else {
            false
        }

        return PaginatedMedia(
            page = page,
            hasNextPage = hasNextPage,
            results = results
        )
    }

    // TODO(Implement support for TV shows)
    override suspend fun getShow(media: PartialMedia): Show =
        throw UnsupportedOperationException("This provider does not support TV shows YET!")

    override suspend fun getSeason(show: Show, season: Season.Partial): Season.Full? = null

    override suspend fun getMovie(media: PartialMedia): Movie {
        val data = client.request(
            url = "$baseUrl/movies/view/${media.id}"
        ).execute().body.string()

        val document = Jsoup.parse(data)

        // Extract basic movie information
        val titleElement = document.select("h1.bd-hd").first()
        val title = titleElement?.text()?.substringBefore(" ")?.trim() ?: media.title
        val year = titleElement?.select("span")?.text()?.toIntOrNull()

        // Extract rating
        val rating = document.select("div.rate p span").first()?.text()?.toDoubleOrNull()

        // Extract poster image
        val posterUrl = document.select("p.movie__poster").attr("data-background-image")
        val posterImage = if (posterUrl.isNotEmpty()) baseUrl + posterUrl else null

        // Extract backdrop image
        val backdropUrl = document.select("div.hero.mv-single-hero").attr("data-background-image")
        val backdropImage = if (backdropUrl.isNotEmpty()) baseUrl + backdropUrl else null

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
        val playLink = document.select("a.round-button").attr("href")
        val videoUrl = if (playLink.isNotEmpty()) baseUrl + playLink else null

        // Extract recommendations
        val recommendations = mutableListOf<PartialMedia>()
        document.select("div.may-also-like div.movie-item").forEach { recommendationElement ->
            runCatching {
                val recommendation = recommendationElement.toPartialMedia(
                    baseUrl = baseUrl,
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

    override suspend fun getCatalogItems(
        catalog: Catalog,
        page: Int
    ): PaginatedMedia<PartialMedia> {
        val data = client.request(
            url = "${baseUrl}/movies/page/$page"
        ).execute().body.string()

        val results = mutableListOf<PartialMedia>()

        val document = Jsoup.parse(data)
        val movieElements = document.select("div.flex-wrap-movielist div.movie-item-style-1")

        if (movieElements.isNotEmpty()) {
            movieElements.forEach { element ->
                val item = element.toPartialMedia(
                    providerId = providerId,
                    baseUrl = baseUrl,
                    type = MediaType.MOVIE // TODO(support TV shows in the future?)
                )

                results.add(item)
            }
        }

        // Extract pagination info from "Page X of Y" format
        val paginationText = document.select("div.pagination__right").text()
        val hasNextPage = if (paginationText.isNotEmpty()) {
            val paginationRegex = Regex("Page (\\d+) of (\\d+)")
            val match = paginationRegex.find(paginationText)
            if (match != null) {
                val currentPage = match.groupValues[1].toIntOrNull() ?: page
                val totalPages = match.groupValues[2].toIntOrNull() ?: page
                currentPage < totalPages
            } else {
                false
            }
        } else {
            false
        }

        return PaginatedMedia(
            page = page,
            hasNextPage = hasNextPage,
            results = results
        )
    }
}