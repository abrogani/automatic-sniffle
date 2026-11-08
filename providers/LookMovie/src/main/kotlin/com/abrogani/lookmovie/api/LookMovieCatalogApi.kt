package com.abrogani.lookmovie.api

import com.abrogani.lookmovie.extensions.toPartialMedia
import com.abrogani.lookmovie.extensions.toShowPartialMedia
import com.abrogani.lookmovie.extensions.toSlidePartialMedia
import com.abrogani.lookmovie.extensions.fetch
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.provider.Catalog
import com.flixclusive.provider.capability.CatalogProviderApi
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

private const val CATALOG_IMAGE = "https://i.imgur.com/JhbAqBt.png"

class LookMovieCatalogApi(
    private val client: OkHttpClient,
    private val providerId: String,
) : CatalogProviderApi {
    override suspend fun getCatalogs(): List<Catalog> = listOf(
        catalog(name = "Latest Movies", url = "$BASE_URL/movies", canPaginate = true),
        catalog(name = "What's hot", url = BASE_URL, canPaginate = false),
        catalog(name = "Latest Shows", url = "$BASE_URL/shows", canPaginate = true),
    )

    private fun catalog(name: String, url: String, canPaginate: Boolean) = Catalog(
        name = name,
        url = url,
        canPaginate = canPaginate,
        image = CATALOG_IMAGE,
        providerId = providerId
    )

    override suspend fun getCatalogItems(
        catalog: Catalog,
        page: Int
    ): PaginatedMedia<PartialMedia> {
        return when {
            catalog.url.trimEnd('/') == BASE_URL -> getWhatsHot(page)
            catalog.url.trimEnd('/').endsWith("/shows") -> getShowsCatalog(page)
            else -> getMoviesCatalog(page)
        }
    }

    private fun getWhatsHot(page: Int): PaginatedMedia<PartialMedia> {
        val results = Jsoup.parse(client.fetch(BASE_URL))
            .select("div.sliderPresentation div.slide-item")
            .map { it.toSlidePartialMedia(baseUrl = BASE_URL, providerId = providerId) }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }

        return PaginatedMedia(
            page = page,
            hasNextPage = false,
            results = results
        )
    }

    private fun getShowsCatalog(page: Int): PaginatedMedia<PartialMedia> {
        val document = Jsoup.parse(client.fetch("$BASE_URL/shows?page=$page"))

        // The listing is made of episodes, so the same show shows up many times.
        val results = document.select("div.tv-episodes-list div.episode-item")
            .map { it.toShowPartialMedia(baseUrl = BASE_URL, providerId = providerId) }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }

        val nextButton = document.select("ul.pagination li.next").first()
        val hasNextPage = nextButton != null && !nextButton.hasClass("disabled")

        return PaginatedMedia(
            page = page,
            hasNextPage = hasNextPage,
            results = results
        )
    }

    private fun getMoviesCatalog(page: Int): PaginatedMedia<PartialMedia> {
        val document = Jsoup.parse(client.fetch("$BASE_URL/movies/page/$page"))

        val results = document.select("div.flex-wrap-movielist div.movie-item-style-1").map {
            it.toPartialMedia(
                providerId = providerId,
                baseUrl = BASE_URL,
                type = MediaType.MOVIE
            )
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
