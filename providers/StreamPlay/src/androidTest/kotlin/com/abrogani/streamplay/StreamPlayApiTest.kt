package com.abrogani.streamplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.provider.link.MediaLink
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPlayApiTest {
    private lateinit var api: StreamPlayApi

    private val testMovie = Movie(
        id = "299534",
        title = "Avengers: Endgame",
        posterImage = null,
        backdropImage = "/orjiB3oUIsyz60hoEqkiGpy5CeO.jpg",
        homePage = null,
        providerId = "TMDB",
        externalIds = mapOf(
            MediaIdSource.TMDB to "299534",
            MediaIdSource.IMDB to "tt4154796",
        ),
    )

    @Before
    fun setUp() {
        api = StreamPlayApi(providerId = "StreamPlay")
    }

    @Test
    @Throws(Exception::class)
    fun getLinks() = runBlocking {
        val links = mutableListOf<MediaLink>()
        api.getLinks(
            media = testMovie,
            episode = null,
            onLinkFound = links::add
        )

        assert(links.isNotEmpty())
        links.forEach {
            println("${it.name} - ${it.url.substring(0, 12)}...")
        }
    }
}
