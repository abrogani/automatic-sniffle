package com.abrogani.sudoflix

import android.util.Base64
import com.flixclusive.core.util.log.LogRule
import com.flixclusive.core.util.log.debugLog
import com.flixclusive.model.media.Movie
import com.flixclusive.model.media.Show
import com.flixclusive.model.media.common.MediaIdSource
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.vidsrcto.VidSrcToApi
import com.abrogani.sudoflix.api.vidsrcto.extractor.F2Cloud
import com.abrogani.sudoflix.api.vidsrcto.extractor.Filemoon
import com.abrogani.sudoflix.api.vidsrcto.util.VidSrcToDecryptionUtil.decodeUrl
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VidSrcToTest {
    @get:Rule
    val rule = LogRule()

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.decode(any(ByteArray::class), any()) } answers {
            java.util.Base64.getDecoder().decode(args[0] as ByteArray)
        }
        every { Base64.encode(any(ByteArray::class), any()) } answers {
            java.util.Base64.getEncoder().encode(args[0] as ByteArray)
        }
    }

    private val movie = Movie(
        id = "299534",
        externalIds = mapOf(
            MediaIdSource.TMDB to "299534",
            MediaIdSource.IMDB to "tt4154796",
        ),
        title = "Avengers: Endgame",
        posterImage = null,
        backdropImage = "/orjiB3oUIsyz60hoEqkiGpy5CeO.jpg",
        homePage = null,
        providerId = "TMDB"
    )

    private val tvShow = Show(
        id = "94605",
        externalIds = mapOf(
            MediaIdSource.TMDB to "94605",
            MediaIdSource.IMDB to "tt11126994",
        ),
        title = "Arcane",
        posterImage = null,
        homePage = null,
        providerId = "TMDB",
        seasons = emptyList(),
        totalSeasons = 2,
        totalEpisodes = 18
    )

    @Test
    fun `VidSrcToApi test`() = runTest {
        val api = VidSrcToApi(OkHttpClient())

        val links = mutableListOf<MediaLink>()

        api.getLinks(
            media = tvShow,
            episode = Episode(id = "1", number = 1, season = 1, isReleased = true),
            onLinkFound = links::add
        )

        assert(links.isNotEmpty())
    }

    @Test
    fun `URL Decryption test`() {
        val input = "yB5_gMJfMGtHJCTb6P4aLZpaYL1-mwKBA34hACvsB0Gh_LJ9-kycuKWndxIy28QvylZpMg3nOA6slKQjREe0u1jXD5GM3e7PQEguWx7wZdJnKtK_tOWSJra1QPlww2RmSXdOqCVus2TzgGvyvukhuDijnJTuVDwbNxDSgZUss6h2SOCjnruO4c7GbFxHNv91w0UMH9XctsGtW5Gk9vVybiPtR7s_iSuJvMSDtk-f3IoMGQtrUh76C4nyKLxoZu3o2I-r1vX7DC2fyw=="

        val output = decodeUrl(input)
        debugLog(output)
        assert(output.isNotEmpty())
    }

    @Test
    fun `Filemoon test`() = runTest {
        val filemoon = Filemoon(OkHttpClient())
        val url = "https://kerapoxy.cc/e/7dz1jxlhlige/?sub.info=https%3A%2F%2Fvidsrc.to%2Fajax%2Fembed%2Fepisode%2Fo_RkO8Ng%2Fsubtitles&t=4xjRAPYmB1QOzQ%3D%3D&ads=0&src=vidsrc"

        val links = mutableListOf<MediaLink>()
        filemoon.extract(
            url = url,
            onLinkFound = links::add
        )

        assert(links.isNotEmpty())
    }

    @Test
    fun `F2Cloud test`() = runTest {
        val f2cloud = F2Cloud(OkHttpClient())
        val url = "https://vid2v11.site/e/0DY8XN539JR4?sub.info=https%3A%2F%2Fvidsrc.to%2Fajax%2Fembed%2Fepisode%2Fo_RkO8Ng%2Fsubtitles&t=4xjRAPYmAlILyQ%3D%3D&ads=0&src=vidsrc"

        val links = mutableListOf<MediaLink>()
        f2cloud.extract(
            url = url,
            onLinkFound = links::add
        )

        assert(links.isNotEmpty())
    }
}