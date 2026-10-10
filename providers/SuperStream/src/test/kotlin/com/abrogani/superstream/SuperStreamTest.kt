package com.abrogani.superstream

import android.util.Base64
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.core.util.log.debugLog
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.MediaType
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.superstream.util.CipherUtil.decrypt
import com.abrogani.superstream.util.Constants
import com.abrogani.superstream.util.CustomCertificateClient
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream


class SuperStreamTest {
    @get:Rule
    val logRule = LogRule()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var api: SuperStreamTestApi

    private val movie = PartialMedia(
        type = MediaType.MOVIE,
        id = "",
        title = "Avengers: Endgame",
        providerId = "TMDB",
        posterImage = null,
    )

    private val tvShow = PartialMedia(
        type = MediaType.SHOW,
        id = "",
        title = "Arcane",
        providerId = "TMDB",
        posterImage = null,
    )

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            val byteArray = arg<ByteArray>(0)
            java.util.Base64.getEncoder().encodeToString(byteArray)
        }
        every { Base64.encode(any(), any()) } answers {
            val byteArray = arg<String>(0).toByteArray()
            java.util.Base64.getEncoder().encode(byteArray)
        }
        every { Base64.decode(any<String>(), any()) } answers {
            val byteArray = arg<String>(0).toByteArray()
            java.util.Base64.getDecoder().decode(byteArray)
        }
        every { Base64.decode(any<ByteArray>(), any()) } answers {
            java.util.Base64.getDecoder().decode(arg<ByteArray>(0))
        }

        val certFile = File("src/test/certs/cert.crt")
        val keyFile = File("src/test/certs/key.key")
        val caFile = File("src/test/certs/ca.crt")
        val ca4File = File("src/test/certs/ca4.cer")

        val certStream = FileInputStream(certFile)
        val keyStream = FileInputStream(keyFile)
        val caStream = FileInputStream(caFile)
        val ca4Stream = FileInputStream(ca4File)

        val customCertificate = CustomCertificateClient()
        val client = customCertificate.createOkHttpClient(
            clientCertStream = certStream,
            caCertStreams = listOf(caStream, ca4Stream),
            privateKeyInputStream = keyStream,
            privateKeyPassword = null
        )

        api = SuperStreamTestApi(client)
    }

    @Test
    fun `get movie links`() = scope.runTest {
        val watchId = getWatchId(movie)
        assertNotNull(watchId) {
            "WatchId cannot be null"
        }
        debugLog("WatchId: $watchId")

        val links = mutableListOf<MediaLink>()
        api.getLinks(
            media = movie.copy(id = watchId!!),
            onLinkFound = links::add
        )

        debugLog("Link: $links")
        assert(links.size > 0) {
            "No links loaded"
        }
    }

    @Test
    fun `get tv show links`() = scope.runTest {
        val watchId = getWatchId(tvShow)
        assertNotNull(watchId) {
            "WatchId cannot be null"
        }
        debugLog("WatchId: $watchId")

        val links = mutableListOf<MediaLink>()
        api.getLinks(
            media = tvShow.copy(id = watchId!!),
            episode = Episode(id = "$watchId-s1e1", season = 1, number = 1, isReleased = true),
            onLinkFound = links::add
        )

        debugLog("Link: $links")
        assert(links.size > 0) {
            "No links loaded"
        }
    }

    @Test
    fun `get movie details`() = scope.runTest {
        val watchId = getWatchId(movie)
        assertNotNull(watchId) {
            "Watch ID cannot be null"
        }
        val metadata = api.getMovie(movie.copy(id = watchId!!))

        assert(metadata is Movie)
        println(metadata)
    }

    @Test
    fun `get tv show details`() = scope.runTest {
        val watchId = getWatchId(tvShow)
        assertNotNull(watchId) {
            "Watch ID cannot be null"
        }
        val metadata = api.getShow(tvShow.copy(id = watchId!!))

        assert(metadata is Show)
        println(metadata)
    }

    @Test
    fun `search for Avengers Endgame`() = scope.runTest {
        val response = api.search(query = movie.title)

        assert(response.results.isNotEmpty()) {
            "No search results found for ${movie.title}"
        }

        debugLog("Search results: ${response.results}")
    }

    private suspend fun getWatchId(media: MediaMetadata): String? {
        return api.search(query = media.title).results.firstOrNull { it.type == media.type }?.id
    }

    @Test
    fun decryption() {
        var data = "eyJhcHBfa2V5IjoiNjQwNWMyZTYwNDVmNjU5YjdmZGNkOTU3ZmU1MmZlOWEiLCJ2ZXJpZnkiOiI4YWY0ZDBmNjBiN2RlYmRkMjdmMjUyMjIyODk4NjQ1MCIsImVuY3J5cHRfZGF0YSI6IkZER2kwcGV3R2MyUklWdTJKM2ltWVR0Wm5aOWFtTFFqQVBCV3lPY3d4SlpxSXgwUVN3WWN2Nzc3Qy9WSTVHVmVQUWx4M0phNVBick9lZG9tSFRoSWNTYWY1Y3p6U2dqTHpnVWlVUDBKL3E1dFBZTzhqTnZZby9HTit4K0M0V1hJUmw2UmNwa2NpY01BWEdTMnRKUi81VVkzbTFwZm90R2tRcUxKV1JxTkw3cTIwZ1ZOMXlzRWtQSll0ZW9PRkNTVEVYcWRiU05XM2M5aUdGbDEra09LYVJBRU9HK2dwVmYwOFpTeUYxdEtZTUQwMFd4NW1kRzFIS25tcTllT1AzMk9IeC9BdHBxQXRwdTVxd25VdE5aa3ZYT0ZaaitVY1dleW0rVTh5eTdydFM2Q0pzdU81Y01TcDY3cFI4RkNWNlI5cEZzTDZISVhQbTU1VytOTDBKQ2c2QmhqL0RpaUZ2bEsifQ"

        if (data.startsWith("ey")) {
            val decodedJson = String(java.util.Base64.getDecoder().decode(data))

            val regex = """"encrypt_data":"(.*?)"""".toRegex()
            val matchResult = regex.find(decodedJson)

            data = matchResult?.groups?.get(1)?.value!!
        }

        val result = decrypt(data, Constants.API_KEY, Constants.API_IV)
        print(result)

        assertNotNull(result)
    }
}