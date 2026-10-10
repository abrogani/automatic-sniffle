package com.abrogani.streamplay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.abrogani.streamplay.loader.PluginIndex
import com.abrogani.streamplay.loader.RemotePlugin
import com.abrogani.streamplay.settings.BAD_VERSION_KEY
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.extensions.getInt
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StreamPlayApiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = OkHttpClient()
    private val settings = PreferenceDataStoreFactory.create {
        File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb")
    }

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
        File(context.filesDir, "streamplay").walkBottomUp().forEach {
            it.setWritable(true)
            it.delete()
        }

        loader = newLoader()
        api = StreamPlayApi(
            context = context,
            providerId = "StreamPlay",
            loader = loader
        )
    }

    private fun newLoader(index: (suspend () -> RemotePlugin)? = null) =
        if (index == null) {
            Cs3PluginLoader(client, settings)
        } else {
            Cs3PluginLoader(client, settings, index)
        }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun freshInstallMatchesTheIndex() = runBlocking {
        val remote = PluginIndex.fetch(client)

        loader.ensureInstalled(context)

        assertEquals(remote.version, loader.installedVersion(context))
        assertFalse(loader.checkForUpdate(context).available)
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
    fun updateSwapsTheFileAndReloads() = runBlocking {
        val real = PluginIndex.fetch(client)
        loader.load(context)

        val newer = newLoader { real.copy(version = real.version + 1) }
        assertTrue(newer.checkForUpdate(context).available)
        assertTrue(newer.update(context))

        assertTrue(newer.load(context).apis.isNotEmpty())
    }

    @Test
    fun updateIsRejectedOnHashMismatch() = runBlocking {
        val real = PluginIndex.fetch(client)
        loader.ensureInstalled(context)

        val tampered = newLoader { real.copy(version = real.version + 1, sha256 = "0".repeat(64)) }
        assertThrows(SecurityException::class.java) { runBlocking { tampered.update(context) } }

        assertEquals(real.version, loader.installedVersion(context))
        assertTrue(loader.load(context).apis.isNotEmpty())
    }

    @Test
    fun brokenUpdateRollsBack() = runBlocking {
        val real = PluginIndex.fetch(client)
        loader.ensureInstalled(context)

        val notAPlugin = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json"
        val body = client.newCall(Request.Builder().url(notAPlugin).build()).execute()
            .use { it.body.bytes() }

        val broken = newLoader {
            RemotePlugin(url = notAPlugin, version = real.version + 1, sha256 = sha256(body))
        }

        assertFalse(broken.update(context))
        assertEquals(real.version, loader.installedVersion(context))
        assertEquals(real.version + 1, settings.getInt(BAD_VERSION_KEY, -1))
        assertFalse(broken.checkForUpdate(context).available)
        assertTrue(loader.load(context).apis.isNotEmpty())
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
