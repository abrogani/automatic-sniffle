package com.abrogani.flixhq.api

import com.abrogani.flixhq.extensions.fetchDocument
import com.abrogani.flixhq.parser.FlixHQParser
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.PaginatedMedia
import com.flixclusive.model.provider.Catalog
import com.flixclusive.provider.capability.CatalogProviderApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class FlixHQCatalogApi(
    private val client: OkHttpClient,
    private val providerId: String,
) : CatalogProviderApi {
    @Volatile
    private var genres: List<Catalog>? = null

    override suspend fun getCatalogs(): List<Catalog> = withContext(Dispatchers.IO) {
        FIXED_CATALOGS.map { (name, path) -> catalog(name, path) } + genreCatalogs()
    }

    override suspend fun getCatalogItems(catalog: Catalog, page: Int): PaginatedMedia<PartialMedia> =
        withContext(Dispatchers.IO) {
            val document = client.fetchDocument(catalog.pageUrl(page))
            FlixHQParser.parseListing(document, providerId, page)
        }

    private fun genreCatalogs(): List<Catalog> {
        genres?.let { return it }

        val loaded = try {
            FlixHQParser.parseGenres(client.fetchDocument("$BASE_URL/home"))
                .map { (name, slug) -> catalog(name, slug) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return emptyList()
        }

        return loaded.also { genres = it }
    }

    private fun catalog(name: String, path: String) = Catalog(
        name = name,
        url = path.trim('/'),
        providerId = providerId,
        canPaginate = true,
    )

    private fun Catalog.pageUrl(page: Int): String {
        val base = "$BASE_URL/${url.trim('/')}/"
        return if (page > 1) "${base}page/$page/" else base
    }

    private companion object {
        val FIXED_CATALOGS = listOf(
            "Movies" to "watch-movies-online",
            "TV Shows" to "watch-series-online",
            "Top IMDB" to "top-imdb",
        )
    }
}
