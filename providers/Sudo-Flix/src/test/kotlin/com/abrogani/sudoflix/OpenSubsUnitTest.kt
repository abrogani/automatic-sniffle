package com.abrogani.sudoflix

import com.flixclusive.core.util.network.okhttp.ignoreAllSSLErrors
import com.abrogani.sudoflix.api.opensubs.SubtitleUtil.fetchSubtitles
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test

class OpenSubsUnitTest {
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .build()
    }

    @Test
    fun fetchOpenSubtitlesTest() = runTest {
        val imdbId = "tt5140878"

        client.fetchSubtitles(
            imdbId = imdbId,
            onSubtitleLoaded = {}
        )
    }
}