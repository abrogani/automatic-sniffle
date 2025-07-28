package com.abrogani.lookmovie

import com.abrogani.lookmovie.api.LookMovieApi
import com.abrogani.lookmovie.api.VIDEO_URL_KEY
import com.flixclusive.model.film.Movie
import com.flixclusive.model.film.util.FilmType
import com.flixclusive.model.provider.ProviderManifest
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
    private lateinit var lookMovie: LookMovie
    private lateinit var lookMovieApi: LookMovieApi

    @Before
    fun setUp() {
        lookMovie = LookMovie()
        lookMovie.manifest = ProviderManifest(
            id = "lookmovie",
            name = "LookMovie",
            versionCode = 10000,
            versionName = "1.0.0",
            updateUrl = null,
            providerClassName = "com.abrogani.lookmovie.LookMovie",
            requiresResources = false
        )

        lookMovieApi = LookMovieApi(
            client = OkHttpClient(),
            provider = lookMovie
        )
    }

    @Test
    fun `search should return valid results for Parasite query`() = runTest {
        val searchResponse = lookMovieApi.search(
            title = "Parasite",
            page = 1,
            id = null,
            imdbId = null,
            tmdbId = null,
        )

        expectThat(searchResponse) {
            get { page }.isEqualTo(1)
            get { results }.hasSize(3)
            get { results }.isNotEmpty()
        }

        val firstResult = searchResponse.results.first()
        expectThat(firstResult) {
            get { id }.isNotNull()
            get { title }.isNotEmpty()
            get { filmType }.isEqualTo(FilmType.MOVIE)
            get { providerId }.isEqualTo("lookmovie")
            get { year }.isNotNull().isGreaterThan(0)
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
            println("  Year: ${result.year}")
            println("  Rating: ${result.rating}")
            println("  Poster: ${result.posterImage}")
            println("  Homepage: ${result.homePage}")
            println()
        }
    }

    @Test
    fun `search should handle pagination correctly`() = runTest {
        val firstPage = lookMovieApi.search(
            title = "action",
            page = 1,
            id = null,
            imdbId = null,
            tmdbId = null,
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
    fun `getMetadata should return complete movie information`() = runTest {
        val searchResponse = lookMovieApi.search(
            title = "Parasite",
            page = 1,
            id = null,
            imdbId = null,
            tmdbId = null,
        )

        val firstResult = searchResponse.results.first()
        val metadata = lookMovieApi.getMetadata(firstResult) as Movie

        expectThat(metadata) {
            get { id }.isEqualTo(firstResult.id)
            get { title }.isNotEmpty()
            get { providerId }.isEqualTo("lookmovie")
            get { filmType }.isEqualTo(FilmType.MOVIE)
            get { customProperties[VIDEO_URL_KEY] }.isNotNull()
        }

        println("=== MOVIE METADATA ===")
        println("ID: ${metadata.id}")
        println("Title: ${metadata.title}")
        println("Year: ${metadata.year}")
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
            println("  - ${rec.title} (${rec.year}) - Rating: ${rec.rating}")
        }
        if (metadata.recommendations.size > 5) {
            println("  ... and ${metadata.recommendations.size - 5} more")
        }
        println()
    }

    @Test
    fun `getMetadata should handle movies with minimal information`() = runTest {
        val searchResponse = lookMovieApi.search(
            title = "test",
            page = 1,
            id = null,
            imdbId = null,
            tmdbId = null,
        )

        if (searchResponse.results.isNotEmpty()) {
            val lastResult = searchResponse.results.last()
            val metadata = lookMovieApi.getMetadata(lastResult) as Movie

            expectThat(metadata) {
                get { id }.isNotNull()
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
        val catalog = lookMovieApi.catalogs.first()
        val catalogResponse = lookMovieApi.getCatalogItems(
            catalog = catalog,
            page = 1
        )

        expectThat(catalogResponse) {
            get { page }.isEqualTo(1)
            get { results }.isNotEmpty()
        }

        val firstResult = catalogResponse.results.first()
        expectThat(firstResult) {
            get { id }.isNotNull()
            get { title }.isNotEmpty()
            get { filmType }.isEqualTo(FilmType.MOVIE)
            get { providerId }.isEqualTo("lookmovie")
            get { year }.isNotNull().isGreaterThan(0)
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
            println("  Year: ${result.year}")
            println("  Rating: ${result.rating}")
            println("  Poster: ${result.posterImage}")
            println()
        }
    }

    @Test
    fun `getCatalogItems should handle pagination correctly`() = runTest {
        val catalog = lookMovieApi.catalogs.first()

        val firstPage = lookMovieApi.getCatalogItems(
            catalog = catalog,
            page = 1
        )

        expectThat(firstPage) {
            get { page }.isEqualTo(1)
            get { results }.isNotEmpty()
            get { hasNextPage }.isEqualTo(true)
        }

        val secondPage = lookMovieApi.getCatalogItems(
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
        val catalog = lookMovieApi.catalogs.first()
        val catalogResponse = lookMovieApi.getCatalogItems(
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
        val catalogs = lookMovieApi.catalogs

        expectThat(catalogs) {
            hasSize(1)
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
}