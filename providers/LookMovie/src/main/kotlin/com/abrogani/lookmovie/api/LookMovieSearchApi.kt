package com.abrogani.lookmovie.api

import com.abrogani.lookmovie.dto.SearchItemDto
import com.abrogani.lookmovie.dto.SearchResponseDto
import com.abrogani.lookmovie.extensions.fromJson
import com.abrogani.lookmovie.extensions.removeAccents
import com.abrogani.lookmovie.extensions.toAbsoluteUrl
import com.abrogani.lookmovie.extensions.toReleaseDate
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.filter.Filter
import com.flixclusive.provider.filter.FilterGroup
import com.flixclusive.provider.filter.FilterList
import okhttp3.OkHttpClient
import java.net.URLEncoder

private const val TYPE_FILTER_NAME = "Type"
private const val TYPE_MOVIES_INDEX = 0
private const val TYPE_SHOWS_INDEX = 1

class LookMovieSearchApi(
    private val client: OkHttpClient,
    private val providerId: String,
) : SearchProviderApi {
    override val filters: FilterList = FilterList(
        FilterGroup(
            name = TYPE_FILTER_NAME,
            Filter.Select(
                name = TYPE_FILTER_NAME,
                options = listOf("Movies", "Shows"),
                state = TYPE_MOVIES_INDEX
            )
        )
    )

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterList,
    ): PaginatedMedia<PartialMedia> {
        // The do-search endpoints aren't paginated
        if (page > 1) {
            return PaginatedMedia(page = page, hasNextPage = false, results = emptyList())
        }

        val selectedType = filters
            .flatten()
            .filterIsInstance<Filter.Select<*>>()
            .firstOrNull { it.name == TYPE_FILTER_NAME }
            ?.state
            ?: TYPE_MOVIES_INDEX

        val (endpoint, type) = when (selectedType) {
            TYPE_SHOWS_INDEX -> "shows" to MediaType.SHOW
            else -> "movies" to MediaType.MOVIE
        }

        val encodedQuery = URLEncoder.encode(query.removeAccents(), "UTF-8")
        val response = client.request(
            url = "$BASE_URL/api/v1/$endpoint/do-search/?q=$encodedQuery"
        ).execute().fromJson<SearchResponseDto>()

        val results = response.result.orEmpty().map { it.toPartialMedia(type) }

        return PaginatedMedia(
            page = page,
            hasNextPage = false,
            results = results
        )
    }

    private fun SearchItemDto.toPartialMedia(type: MediaType): PartialMedia {
        val path = if (type.isShow) "shows" else "movies"

        return PartialMedia(
            id = slug,
            title = title,
            posterImage = (poster?.get("w300") ?: poster?.values?.firstOrNull()).toAbsoluteUrl(BASE_URL),
            rating = imdbRating?.toDoubleOrNull(),
            releaseDate = year?.toReleaseDate(),
            overview = description,
            type = type,
            providerId = providerId,
            homePage = "$BASE_URL/$path/view/$slug",
        )
    }
}
