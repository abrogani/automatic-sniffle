package com.abrogani.flixhq.api

import com.abrogani.flixhq.extensions.fetchDocument
import com.abrogani.flixhq.parser.FlixHQParser
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.provider.capability.SearchProviderApi
import com.flixclusive.provider.filter.Filter
import com.flixclusive.provider.filter.FilterGroup
import com.flixclusive.provider.filter.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder

internal class FlixHQSearchApi(
    private val client: OkHttpClient,
    private val providerId: String,
) : SearchProviderApi {
    override val filters = FilterList(
        FilterGroup(
            "Filters",
            Filter.Select("Type", options = TYPE_OPTIONS),
        ),
    )

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterList,
    ): PaginatedMedia<PartialMedia> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext PaginatedMedia(page = page)

        val typeIndex = (filters.firstOrNull()?.firstOrNull() as? Filter.Select<*>)?.state ?: 0
        val url = "$SEARCH_URL?keyword=${URLEncoder.encode(keyword, "UTF-8")}&page=$page"

        val result = FlixHQParser.parseListing(client.fetchDocument(url), providerId, page)

        when (typeIndex) {
            1 -> result.copy(results = result.results.filter { it.type == MediaType.MOVIE })
            2 -> result.copy(results = result.results.filter { it.type == MediaType.SHOW })
            else -> result
        }
    }

    private companion object {
        val TYPE_OPTIONS = listOf("All", "Movies", "TV Shows")
    }
}
