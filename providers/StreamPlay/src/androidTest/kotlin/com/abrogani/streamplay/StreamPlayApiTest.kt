package com.abrogani.streamplay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abrogani.streamplay.loader.Cs3PluginLoader
import com.abrogani.streamplay.loader.PluginIndex
import com.abrogani.streamplay.loader.RemotePlugin
import com.abrogani.streamplay.settings.BAD_VERSION_KEY
import com.flixclusive.model.media.Movie
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.CryptographyProviderContainer
import dev.whyoleg.cryptography.algorithms.SHA256
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.provider.extensions.getInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun defaultCryptographyProviderIsAvailableAfterLoad() = runBlocking {
        loader.load(context)

        val digest = CryptographyProvider.Default.get(SHA256).hasher().hashBlocking("a".encodeToByteArray())

        assertEquals(32, digest.size)
    }

    @OptIn(CryptographyProviderApi::class)
    @Test
    fun registeringBeforeReadingProvidersMakesTheDefaultAvailable() {
        val system = newCryptographySystem()
        register(system)

        val provider = system.javaClass.getMethod("getDefaultProvider").invoke(system) as CryptographyProvider
        val digest = provider.get(SHA256).hasher().hashBlocking("a".encodeToByteArray())
        assertEquals(32, digest.size)
    }

    @Test
    fun registeringAfterReadingProvidersIsRefused() {
        val system = newCryptographySystem()
        system.javaClass.getMethod("getRegisteredProviders").invoke(system)

        val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { register(system) }
        assertTrue(error.cause is IllegalStateException)
    }

    private fun newCryptographySystem(): Any =
        Class.forName("dev.whyoleg.cryptography.CryptographySystemImpl")
            .getDeclaredConstructor()
            .newInstance()

    @OptIn(CryptographyProviderApi::class)
    private fun register(system: Any) {
        val container = jdkContainer()
        system.javaClass.getMethod("registerProvider", Lazy::class.java, Int::class.javaPrimitiveType)
            .invoke(system, container.provider, container.priority)
    }

    @OptIn(CryptographyProviderApi::class)
    private fun jdkContainer() =
        Class.forName("dev.whyoleg.cryptography.providers.jdk.JdkCryptographyProviderContainer")
            .getDeclaredConstructor()
            .newInstance() as CryptographyProviderContainer

    @Test
    fun updateSwapsTheFileAndReloads() = runBlocking {
        val real = PluginIndex.fetch(client)
        loader.load(context)

        val newer = newLoader { real.copy(version = real.version + 1) }
        assertTrue(newer.checkForUpdate(context).available)
        assertEquals(Cs3PluginLoader.UpdateResult.Updated, newer.update(context))

        assertTrue(newer.load(context).apis.isNotEmpty())
        assertFalse(newer.checkForUpdate(context).available)
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

        assertEquals(Cs3PluginLoader.UpdateResult.RolledBack, broken.update(context))
        assertEquals(real.version, loader.installedVersion(context))
        assertEquals(real.version + 1, settings.getInt(BAD_VERSION_KEY, -1))

        val status = broken.checkForUpdate(context)
        assertFalse(status.available)
        assertTrue(status.skipped)
        assertEquals(Cs3PluginLoader.UpdateResult.UpToDate, broken.update(context))
        assertEquals(Cs3PluginLoader.UpdateResult.RolledBack, broken.update(context, force = true))
        assertTrue(loader.load(context).apis.isNotEmpty())
    }

    private val pluginFile get() = File(context.filesDir, "streamplay/StreamPlay.cs3")
    private val backupFile get() = File(context.filesDir, "streamplay/StreamPlay.cs3.previous")

    @Test
    fun deletedFileIsDownloadedAgain() = runBlocking {
        loader.ensureInstalled(context)
        val real = PluginIndex.fetch(client)

        pluginFile.setWritable(true)
        assertTrue(pluginFile.delete())
        newLoader().ensureInstalled(context)

        assertEquals(real.version, loader.installedVersion(context))
        assertTrue(newLoader().load(context).apis.isNotEmpty())
    }

    @Test
    fun corruptFileIsReplaced() = runBlocking {
        loader.ensureInstalled(context)
        val real = PluginIndex.fetch(client)

        pluginFile.setWritable(true)
        pluginFile.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(null, newLoader().installedVersion(context))
        newLoader().ensureInstalled(context)

        assertEquals(real.version, loader.installedVersion(context))
    }

    @Test
    fun interruptedSwapRestoresTheBackup() = runBlocking {
        loader.ensureInstalled(context)
        val real = PluginIndex.fetch(client)

        pluginFile.setWritable(true)
        assertTrue(pluginFile.renameTo(backupFile))

        assertEquals(real.version, newLoader().installedVersion(context))
        assertTrue(pluginFile.exists())
        assertFalse(backupFile.exists())
    }

    @Test
    fun concurrentUpdatesInstallOnce() = runBlocking {
        val real = PluginIndex.fetch(client)
        loader.ensureInstalled(context)

        val newer = newLoader { real.copy(version = real.version + 1) }
        val results = listOf(
            async { newer.update(context) },
            async { newer.update(context) },
        ).awaitAll()

        assertEquals(1, results.count { it == Cs3PluginLoader.UpdateResult.Updated })
        assertEquals(1, results.count { it == Cs3PluginLoader.UpdateResult.UpToDate })
        assertNotNull(newer.load(context))
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
