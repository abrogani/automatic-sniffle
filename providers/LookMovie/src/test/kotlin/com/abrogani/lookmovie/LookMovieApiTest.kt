package com.abrogani.lookmovie

import com.abrogani.lookmovie.api.LookMovieCatalogApi
import com.abrogani.lookmovie.api.LookMovieMetadataApi
import com.abrogani.lookmovie.api.LookMovieSearchApi
import com.abrogani.lookmovie.api.VIDEO_URL_KEY
import com.flixclusive.model.media.common.MediaType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull

class LookMovieApiTest {
    private lateinit var catalogApi: LookMovieCatalogApi
    private lateinit var searchApi: LookMovieSearchApi
    private lateinit var metadataApi: LookMovieMetadataApi

    @Before
    fun setUp() {
        val client = OkHttpClient()
        catalogApi = LookMovieCatalogApi(client = client, providerId = "lookmovie")
        searchApi = LookMovieSearchApi(client = client, providerId = "lookmovie")
        metadataApi = LookMovieMetadataApi(client = client, providerId = "lookmovie")
    }

    @Test
    fun `search should return valid results for Parasite query`() = runTest {
        val searchResponse = searchApi.search(
            query = "Parasite",
            page = 1,
        )

        expectThat(searchResponse) {
            get { page }.isEqualTo(1)
            get { results }.isNotEmpty()
        }

        val firstResult = searchResponse.results.first()
        expectThat(firstResult) {
            get { id }.isNotEmpty()
            get { title }.isNotEmpty()
            get { type }.isEqualTo(MediaType.MOVIE)
            get { providerId }.isEqualTo("lookmovie")
            get { releaseDate }.isNotNull().isGreaterThan(0L)
        }

        println("=== SEARCH RESULTS ===")
        println("Page: ${searchResponse.page}")
        println("Has Next Page: ${searchResponse.hasNextPage}")
        println("Results Count: ${searchResponse.results.size}")
        println()

        searchResponse.results.forEachIndexed { index, result ->
            println("Result ${index + 1}:")
            println("  ID: ${result.id}")
            println("  Title: ${result.title}")
            println("  Year: ${result.releaseDate}")
            println("  Rating: ${result.rating}")
            println("  Poster: ${result.posterImage}")
            println("  Homepage: ${result.homePage}")
            println()
        }
    }

    @Test
    fun `search should handle pagination correctly`() = runTest {
        val firstPage = searchApi.search(
            query = "action",
            page = 1,
        )

        expectThat(firstPage) {
            get { page }.isEqualTo(1)
            get { results }.isNotEmpty()
        }

        println("=== PAGINATION TEST ===")
        println("First Page Results: ${firstPage.results.size}")
        println("Has Next Page: ${firstPage.hasNextPage}")
        println()
    }

    @Test
    fun `getMovie should return complete movie information`() = runTest {
        val searchResponse = searchApi.search(
            query = "Parasite",
            page = 1,
        )

        val firstResult = searchResponse.results.first()
        val metadata = metadataApi.getMovie(firstResult)

        expectThat(metadata) {
            get { id }.isEqualTo(firstResult.id)
            get { title }.isNotEmpty()
            get { providerId }.isEqualTo("lookmovie")
            get { type }.isEqualTo(MediaType.MOVIE)
            get { customProperties[VIDEO_URL_KEY] }.isNotNull()
        }

        println("=== MOVIE METADATA ===")
        println("ID: ${metadata.id}")
        println("Title: ${metadata.title}")
        println("Rating: ${metadata.rating}")
        println("Runtime: ${metadata.runtime} minutes")
        println("Release Date: ${metadata.releaseDate}")
        println("Overview: ${metadata.overview}")
        println()

        println("Poster Image: ${metadata.posterImage}")
        println("Backdrop Image: ${metadata.backdropImage}")
        println("Video URL: ${metadata.customProperties[VIDEO_URL_KEY]}")
        println()

        println("Genres (${metadata.genres.size}):")
        metadata.genres.forEach { genre ->
            println("  - ${genre.name}")
        }
        println()

        println("Recommendations (${metadata.recommendations.size}):")
        metadata.recommendations.take(5).forEach { rec ->
            println("  - ${rec.title} (${rec.releaseDate}) - Rating: ${rec.rating}")
        }
        if (metadata.recommendations.size > 5) {
            println("  ... and ${metadata.recommendations.size - 5} more")
        }
        println()
    }

    @Test
    fun `getMovie should handle movies with minimal information`() = runTest {
        val searchResponse = searchApi.search(
            query = "test",
            page = 1,
        )

        if (searchResponse.results.isNotEmpty()) {
            val lastResult = searchResponse.results.last()
            val metadata = metadataApi.getMovie(lastResult)

            expectThat(metadata) {
                get { id }.isNotEmpty()
                get { title }.isNotEmpty()
                get { providerId }.isEqualTo("lookmovie")
            }

            println("=== MINIMAL METADATA TEST ===")
            println("Movie ID: ${metadata.id}")
            println("Title: ${metadata.title}")
            println("Has Overview: ${metadata.overview != null}")
            println("Has Rating: ${metadata.rating != null}")
            println("Has Runtime: ${metadata.runtime != null}")
            println("Video URL: ${metadata.customProperties[VIDEO_URL_KEY]}")
            println("Genre Count: ${metadata.genres.size}")
            println("Recommendations Count: ${metadata.recommendations.size}")
            println()
        }
    }

    @Test
    fun `getCatalogItems should return valid movies from catalog`() = runTest {
        val catalog = catalogApi.getCatalogs().first()
        val catalogResponse = catalogApi.getCatalogItems(
            catalog = catalog,
            page = 1
        )

        expectThat(catalogResponse) {
            get { page }.isEqualTo(1)
            get { results }.isNotEmpty()
        }

        val firstResult = catalogResponse.results.first()
        expectThat(firstResult) {
            get { id }.isNotEmpty()
            get { title }.isNotEmpty()
            get { type }.isEqualTo(MediaType.MOVIE)
            get { providerId }.isEqualTo("lookmovie")
            get { releaseDate }.isNotNull().isGreaterThan(0L)
        }

        println("=== CATALOG RESULTS ===")
        println("Catalog Name: ${catalog.name}")
        println("Page: ${catalogResponse.page}")
        println("Has Next Page: ${catalogResponse.hasNextPage}")
        println("Results Count: ${catalogResponse.results.size}")
        println()

        catalogResponse.results.take(5).forEachIndexed { index, result ->
            println("Catalog Item ${index + 1}:")
            println("  ID: ${result.id}")
            println("  Title: ${result.title}")
            println("  Year: ${result.releaseDate}")
            println("  Rating: ${result.rating}")
            println("  Poster: ${result.posterImage}")
            println()
        }
    }

    @Test
    fun `getCatalogItems should handle pagination correctly`() = runTest {
        val catalog = catalogApi.getCatalogs().first()

        val firstPage = catalogApi.getCatalogItems(
            catalog = catalog,
            page = 1
        )

        expectThat(firstPage) {
            get { page }.isEqualTo(1)
            get { results }.isNotEmpty()
            get { hasNextPage }.isEqualTo(true)
        }

        val secondPage = catalogApi.getCatalogItems(
            catalog = catalog,
            page = 2
        )

        expectThat(secondPage) {
            get { page }.isEqualTo(2)
            get { results }.isNotEmpty()
        }

        // Verify different pages return different content
        val firstPageIds = firstPage.results.map { it.id }.toSet()
        val secondPageIds = secondPage.results.map { it.id }.toSet()
        val hasUniqueContent = firstPageIds.intersect(secondPageIds).isEmpty()

        expectThat(hasUniqueContent).isEqualTo(true)

        println("=== CATALOG PAGINATION TEST ===")
        println("First Page Results: ${firstPage.results.size}")
        println("First Page Has Next: ${firstPage.hasNextPage}")
        println("Second Page Results: ${secondPage.results.size}")
        println("Second Page Has Next: ${secondPage.hasNextPage}")
        println("Unique Content Between Pages: $hasUniqueContent")
        println()
    }

    @Test
    fun `getCatalogItems should parse pagination text correctly`() = runTest {
        val catalog = catalogApi.getCatalogs().first()
        val catalogResponse = catalogApi.getCatalogItems(
            catalog = catalog,
            page = 1
        )

        expectThat(catalogResponse) {
            get { page }.isEqualTo(1)
            get { hasNextPage }.isEqualTo(true)
        }

        println("=== CATALOG PAGINATION PARSING ===")
        println("Catalog: ${catalog.name}")
        println("Current Page: ${catalogResponse.page}")
        println("Has Next Page: ${catalogResponse.hasNextPage}")
        println("Total Results on Page: ${catalogResponse.results.size}")
        println()
    }

    @Test
    fun `catalogs property should return available catalogs`() = runTest {
        val catalogs = catalogApi.getCatalogs()

        expectThat(catalogs) {
            hasSize(3)
            get { first().name }.isEqualTo("Latest Movies")
            get { first().canPaginate }.isEqualTo(true)
            get { first().providerId }.isEqualTo("lookmovie")
        }

        println("=== AVAILABLE CATALOGS ===")
        catalogs.forEachIndexed { index, catalog ->
            println("Catalog ${index + 1}:")
            println("  Name: ${catalog.name}")
            println("  URL: ${catalog.url}")
            println("  Can Paginate: ${catalog.canPaginate}")
            println("  Provider ID: ${catalog.providerId}")
            println("  Image: ${catalog.image}")
            println()
        }
    }

    @Test
    fun `search with the shows filter returns shows`() = runTest {
        val showsFilters = searchApi.filters.also {
            (it.flatten().first() as com.flixclusive.provider.filter.Filter.Select<*>).state = 1
        }

        val response = searchApi.search(query = "dexter", page = 1, filters = showsFilters)

        expectThat(response.results).isNotEmpty()
        expectThat(response.results.first().type).isEqualTo(MediaType.SHOW)
    }

    @Test
    fun `catalogs return items`() = runTest {
        val catalogs = catalogApi.getCatalogs()
        expectThat(catalogs.map { it.name }).isEqualTo(listOf("Latest Movies", "What's hot", "Latest Shows"))

        catalogs.forEach { catalog ->
            val items = catalogApi.getCatalogItems(catalog, 1).results
            expectThat(items).isNotEmpty()
            expectThat(items.map { it.id }.distinct().size).isEqualTo(items.size)
        }
    }

    @Test
    fun `getShow returns seasons with episode ids`() = runTest {
        val partial = searchApi.search(
            query = "dexter",
            page = 1,
            filters = searchApi.filters.also {
                (it.flatten().first() as com.flixclusive.provider.filter.Filter.Select<*>).state = 1
            }
        ).results.first { it.title == "Dexter" }

        val show = metadataApi.getShow(partial)

        expectThat(show.seasons).isNotEmpty()
        expectThat(show.totalEpisodes).isGreaterThan(0)
        expectThat(metadataApi.getCachedEpisodeId(show.id, 1, 1)).isNotNull()
    }
}
