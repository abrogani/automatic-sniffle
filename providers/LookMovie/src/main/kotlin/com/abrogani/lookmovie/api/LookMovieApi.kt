package com.abrogani.lookmovie.api

import com.abrogani.lookmovie.extensions.removeAccents
import com.abrogani.lookmovie.extensions.toFilmSearchItem
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.film.Film
import com.flixclusive.model.film.FilmMetadata
import com.flixclusive.model.film.FilmSearchItem
import com.flixclusive.model.film.Genre
import com.flixclusive.model.film.Movie
import com.flixclusive.model.film.SearchResponseData
import com.flixclusive.model.film.util.FilmType
import com.flixclusive.model.provider.ProviderCatalog
import com.flixclusive.provider.Provider
import com.flixclusive.provider.ProviderApi
import com.flixclusive.provider.filter.FilterList
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

internal const val VIDEO_URL_KEY = "videoUrl"

/**
 *
 * For LookMovie WebView API integration and testing purposes.
 * */
class LookMovieApi(
    client: OkHttpClient,
    provider: Provider
) : ProviderApi(
    client = client,
    provider = provider
) {
    override val baseUrl: String = "https://www.lookmovie2.to"
    override val testFilm: FilmMetadata = Movie(
        id = "6751668-parasite-2019",
        title = "Parasite",
        posterImage = "$baseUrl/images/p/w500/ac2cdc696393461180f2ca831e217e24.webp",
        homePage = "$baseUrl/movies/view/6751668-parasite-2019",
        providerId = provider.manifest.id,
        year = 2019,
        genres = listOf(Genre(id = -1, name = "Drama")),
        customProperties = mapOf(
            VIDEO_URL_KEY to "$baseUrl/movies/play/1696508424-parasite-2019"
        )
    )

    override val catalogs: List<ProviderCatalog>
        get() = listOf(
            ProviderCatalog(
                name = "Latest Movies",
                url = "$baseUrl/movies",
                canPaginate = true,
                image = "https://i.imgur.com/JhbAqBt.png",
                providerId = provider.manifest.id
            )
        )

    override suspend fun search(
        title: String,
        page: Int,
        id: String?,
        imdbId: String?,
        tmdbId: Int?,
        filters: FilterList,
    ): SearchResponseData<FilmSearchItem> {
        val query = title.removeAccents()

        val data = client.request(
            url = "${baseUrl}/movies/search/?q=$query&page=$page"
        ).execute().body.string()

        val results = mutableListOf<FilmSearchItem>()

        val document = Jsoup.parse(data)
        val movieElements = document.select("div.flex-wrap-movielist div.movie-item-style-1")

        if (movieElements.isNotEmpty()) {
            movieElements.forEach { element ->
                val item = element.toFilmSearchItem(
                    providerId = provider.manifest.id,
                    baseUrl = baseUrl,
                    filmType = FilmType.MOVIE // TODO(support TV shows in the future?)
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

        return SearchResponseData(
            page = page,
            hasNextPage = hasNextPage,
            results = results
        )
    }

    override suspend fun getMetadata(film: Film): FilmMetadata {
        // TODO("Implement support for TV shows")
        if (film.filmType == FilmType.TV_SHOW) {
            throw IllegalStateException("This provide does not support TV shows YET!")
        }

        val data = client.request(
            url = "$baseUrl/movies/view/${film.id}"
        ).execute().body.string()

        val document = Jsoup.parse(data)

        // Extract basic movie information
        val titleElement = document.select("h1.bd-hd").first()
        val title = titleElement?.text()?.substringBefore(" ")?.trim() ?: film.title
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
            .map { Genre(id = -1, name = it.trim()) }
            .filter { it.name.isNotEmpty() }

        // Extract video URL for WebView captcha flow
        val playLink = document.select("a.round-button").attr("href")
        val videoUrl = if (playLink.isNotEmpty()) baseUrl + playLink else null

        // Extract recommendations
        val recommendations = mutableListOf<FilmSearchItem>()
        document.select("div.may-also-like div.movie-item").forEach { recommendationElement ->
            runCatching {
                val recommendation = recommendationElement.toFilmSearchItem(
                    baseUrl = baseUrl,
                    providerId = provider.manifest.id,
                    filmType = FilmType.MOVIE
                )
                recommendations.add(recommendation)
            }
        }

        return Movie(
            id = film.id,
            title = title,
            posterImage = posterImage,
            homePage = film.homePage,
            backdropImage = backdropImage,
            releaseDate = year?.toString(),
            rating = rating,
            providerId = provider.manifest.id,
            runtime = runtime,
            overview = overview,
            year = year,
            genres = genres,
            recommendations = recommendations,
            customProperties = mapOf(
                VIDEO_URL_KEY to videoUrl
            )
        )
    }

    override suspend fun getCatalogItems(
        catalog: ProviderCatalog,
        page: Int
    ): SearchResponseData<FilmSearchItem> {
        val data = client.request(
            url = "${baseUrl}/movies/page/$page"
        ).execute().body.string()

        val results = mutableListOf<FilmSearchItem>()

        val document = Jsoup.parse(data)
        val movieElements = document.select("div.flex-wrap-movielist div.movie-item-style-1")

        if (movieElements.isNotEmpty()) {
            movieElements.forEach { element ->
                val item = element.toFilmSearchItem(
                    providerId = provider.manifest.id,
                    baseUrl = baseUrl,
                    filmType = FilmType.MOVIE // TODO(support TV shows in the future?)
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

        return SearchResponseData(
            page = page,
            hasNextPage = hasNextPage,
            results = results
        )
    }
}