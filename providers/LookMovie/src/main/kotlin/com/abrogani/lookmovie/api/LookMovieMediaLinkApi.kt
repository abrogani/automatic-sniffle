package com.abrogani.lookmovie.api

import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import com.abrogani.lookmovie.api.webview.LookMovieWebView
import com.abrogani.lookmovie.dto.MovieAccessResponseDto
import com.abrogani.lookmovie.dto.MovieStorageDto
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.coroutines.mapAsync
import com.flixclusive.core.util.exception.safeCall
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.core.util.webview.WebViewDriverManager
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.PartialMedia
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.provider.capability.MediaLinkProviderApi
import com.flixclusive.provider.capability.MediaLinkType
import com.google.gson.Gson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

class LookMovieMediaLinkApi(
    private val context: Context,
    private val client: OkHttpClient,
    private val metadataApi: LookMovieApi,
) : MediaLinkProviderApi {
    private val gson = Gson()
    private var webView: LookMovieWebView? = null

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    private companion object {
        const val CAPTCHA_TIMEOUT_MILLIS = 120_000L // 2 minutes
    }

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        // TODO("Implement support for TV shows")
        if (media.isShow) {
            throw IllegalStateException("This provider does not support TV shows YET!")
        }

        val videoUrl = media.customProperties[VIDEO_URL_KEY]
            ?: metadataApi.getMovie(media.toPartialMedia()).customProperties[VIDEO_URL_KEY]
            ?: throw IllegalArgumentException("Video URL not found in media metadata")

        try {
            // Step 1: Check if we already have valid cookies and can access movie_storage directly
            val existingCookies = CookieManager.getInstance().getCookie(videoUrl)
            val existingStorage = if (existingCookies?.isNotEmpty() == true) {
                safeCall { extractMovieStorage(videoUrl, existingCookies) }
            } else {
                null
            }

            val cookies: String
            val movieStorage: MovieStorageDto
            if (existingStorage != null) {
                cookies = existingCookies!!
                movieStorage = existingStorage
            } else {
                withMainContext {
                    Toast.makeText(context, "Please solve the captcha to continue", Toast.LENGTH_LONG).show()
                }

                // Need to solve captcha with timeout
                cookies = withTimeout(CAPTCHA_TIMEOUT_MILLIS) {
                    withMainContext {
                        val driver = LookMovieWebView(context).also { webView = it }
                        WebViewDriverManager.register(driver)
                        driver.solveCaptcha(videoUrl)
                    }
                }

                destroy()
                movieStorage = extractMovieStorage(videoUrl, cookies)
            }

            // Step 4: Get movie access with hash and expires
            val movieAccess = getMovieAccess(movieStorage, cookies)

            // Parse streams and create MediaLinks
            createMediaLinks(
                movieAccess = movieAccess,
                expiryFlag = Flag.Expires(expiresOn = movieStorage.expires),
                onLinkFound = onLinkFound
            )
        } catch (e: TimeoutCancellationException) {
            destroy()
            throw IllegalStateException("Captcha solving timed out after ${CAPTCHA_TIMEOUT_MILLIS / 1000} seconds", e)
        }
    }

    suspend fun destroy() {
        val driver = webView ?: return
        webView = null
        withMainContext { driver.destroy() }
    }

    private fun MediaMetadata.toPartialMedia() = PartialMedia(
        type = type,
        id = id,
        title = title,
        providerId = providerId,
        posterImage = posterImage,
        homePage = homePage,
    )

    private fun extractMovieStorage(videoUrl: String, cookies: String): MovieStorageDto {
        val headers = mapOf("Cookie" to cookies)

        val response = client.request(
            url = videoUrl,
            headers = headers.toHeaders()
        ).execute()

        val html = response.body.string()

        // Extract movie_storage JavaScript object
        val storageRegex = Regex("window\\['movie_storage']\\s*=\\s*(\\{[^}]+\\})")
        val match = storageRegex.find(html)
            ?: throw IllegalStateException("movie_storage not found in response")

        val jsonString = match.groupValues[1]
        return gson.fromJson(jsonString, MovieStorageDto::class.java)
    }

    private fun getMovieAccess(movieStorage: MovieStorageDto, cookies: String): MovieAccessResponseDto {
        val accessUrl = "${metadataApi.baseUrl}/api/v1/security/movie-access" +
                "?id_movie=${movieStorage.idMovie}" +
                "&hash=${movieStorage.hash}" +
                "&expires=${movieStorage.expires}"

        val headers = mapOf("Cookie" to cookies)

        val response = client.request(
            url = accessUrl,
            headers = headers.toHeaders()
        ).execute()

        val responseBody = response.body.string()
        return gson.fromJson(responseBody, MovieAccessResponseDto::class.java)
    }

    private suspend fun createMediaLinks(
        movieAccess: MovieAccessResponseDto,
        expiryFlag: Flag,
        onLinkFound: (MediaLink) -> Unit
    ) {
        movieAccess.subtitles.mapAsync {
            onLinkFound(
                Subtitle(
                    language = it.language,
                    url = metadataApi.baseUrl + it.file,
                )
            )
        }

        movieAccess.streams.forEach { (quality, link) ->
            link?.let {
                onLinkFound(
                    Stream(
                        name = quality,
                        url = it,
                        flags = setOf(expiryFlag)
                    )
                )
            }
        }
    }
}
