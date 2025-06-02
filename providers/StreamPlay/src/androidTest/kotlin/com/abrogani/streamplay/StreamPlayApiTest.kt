package com.abrogani.streamplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.ProviderApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPlayApiTest {
    private lateinit var api: ProviderApi

    @Before
    fun setUp() {
        api = StreamPlayApi(
            client = OkHttpClient(),
            provider = StreamPlay()
        )
    }

    @Test
    @Throws(Exception::class)
    fun getLinks() = runBlocking {
        val testMovie = api.testFilm

        val links = mutableListOf<MediaLink>()
        api.getLinks(
            testMovie.identifier,
            testMovie,
            null,
            links::add
        )

        assert(links.isNotEmpty())
        links.forEach {
            println("${it.name} - ${it.url.substring(0, 12)}...")
        }
    }
}