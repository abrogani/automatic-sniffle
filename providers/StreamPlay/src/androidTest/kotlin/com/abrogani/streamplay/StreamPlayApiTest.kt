package com.abrogani.streamplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.provider.link.MediaLink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPlayApiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var loader: Cs3PluginLoader
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
        loader = Cs3PluginLoader(OkHttpClient())
        api = StreamPlayApi(
            context = context,
            providerId = "StreamPlay",
            loader = loader
        )
    }

    @Test
    fun loadsPluginFromDex() = runBlocking {
        val loaded = loader.load(context)

        println("Loaded providers: ${loaded.apis.map { it.name }}")
        assertTrue(loaded.apis.isNotEmpty())
        assertTrue(loaded.apis.any { it.name == "StreamPlay" })
        assertEquals("com.phisher98.StreamPlayPlugin", loaded.plugin.javaClass.name)
    }

    @Test
    fun setsCloudStreamContext() = runBlocking {
        loader.load(context)

        val holder = Class.forName("com.lagradost.api.ContextHelper_androidKt")
            .getMethod("getContext")
            .invoke(null)
        assertTrue("CloudStream context not set", holder is android.content.Context)
    }

    @Test
    @Throws(Exception::class)
    fun getLinks() = runBlocking {
        val links = mutableListOf<MediaLink>()
        api.getLinks(
            media = testMovie,
            episode = null,
            onLinkFound = { synchronized(links) { links.add(it) } }
        )

        assertTrue("No links found", links.isNotEmpty())
        links.forEach {
            println("${it.name} - ${it.url.take(40)}...")
        }
    }
}
