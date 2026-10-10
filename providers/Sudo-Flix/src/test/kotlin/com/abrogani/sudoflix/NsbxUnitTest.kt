package com.abrogani.sudoflix

import android.net.Uri
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.core.util.network.okhttp.ignoreAllSSLErrors
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.nsbx.VidBingeApi
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NsbxUnitTest {
    @get:Rule
    val rule = LogRule()

    private lateinit var client: OkHttpClient
    private val api = VidBingeApi(OkHttpClient())

    private val movie = Movie(
        id = "299534",
        externalIds = mapOf(
            MediaIdSource.TMDB to "299534",
            MediaIdSource.IMDB to "tt4154796",
        ),
        title = "Avengers: Endgame",
        posterImage = null,
        homePage = null,
        providerId = "TMDB",
        releaseDate = 1546300800000L // 2019-01-01
    )

    @Before
    fun setup() {
        client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .build()

        mockkStatic(Uri::class)
        every { Uri.encode(any()) } returns "%7B%22title%22%3A%22Avengers%3A%20Endgame%22%2C%22releaseYear%22%3A2019%2C%22tmdbId%22%3A%22299534%22%2C%22imdbId%22%3A%22tt4154796%22%2C%22type%22%3A%22movie%22%7D"
    }

    @Test
    fun `test NSBX Api`() = runTest {
        val links = mutableListOf<MediaLink>()
        api.getLinks(
            media = movie,
            onLinkFound = links::add
        )

        assert(links.isNotEmpty())
    }
}