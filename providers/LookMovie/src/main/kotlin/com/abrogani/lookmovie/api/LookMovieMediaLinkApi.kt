package com.abrogani.lookmovie.api

import android.content.Context
import android.webkit.CookieManager
import com.abrogani.lookmovie.api.webview.LookMovieWebViewInterceptor
import com.abrogani.lookmovie.dto.MovieAccessResponseDto
import com.abrogani.lookmovie.dto.MovieStorageDto
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.coroutines.mapAsync
import com.flixclusive.core.util.network.okhttp.WebViewInterceptor.Companion.addWebViewInterceptor
import com.flixclusive.core.util.network.okhttp.request
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
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

class LookMovieMediaLinkApi(
    private val context: Context,
    private val client: OkHttpClient,
    private val metadataApi: LookMovieApi,
) : MediaLinkProviderApi {
    private val gson = Gson()
    private var webView: LookMovieWebViewInterceptor? = null

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

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

        // TODO: Restore the cookie/captcha flow below once the WEB requires it again.
        //  For now, the movie_storage is extracted directly, with a WebView fallback.
        // try {
        //     // Step 1: Check if we already have valid cookies and can access movie_storage directly
        //     val existingCookies = CookieManager.getInstance().getCookie(videoUrl)
        //     val existingStorage = if (existingCookies?.isNotEmpty() == true) {
        //         safeCall { extractMovieStorage(videoUrl, existingCookies) }
        //     } else {
        //         null
        //     }
        //
        //     val cookies: String
        //     val movieStorage: MovieStorageDto
        //     if (existingStorage != null) {
        //         cookies = existingCookies!!
        //         movieStorage = existingStorage
        //     } else {
        //         withMainContext {
        //             Toast.makeText(context, "Please solve the captcha to continue", Toast.LENGTH_LONG).show()
        //         }
        //
        //         // Need to solve captcha with timeout
        //         cookies = withTimeout(CAPTCHA_TIMEOUT_MILLIS.milliseconds) {
        //             withMainContext {
        //                 val driver = LookMovieWebView(context).also { webView = it }
        //                 WebViewDriverManager.register(driver)
        //                 driver.solveCaptcha(videoUrl)
        //             }
        //         }
        //
        //         destroy()
        //         movieStorage = extractMovieStorage(videoUrl, cookies)
        //     }
        //     ...
        // } catch (e: TimeoutCancellationException) {
        //     destroy()
        //     throw IllegalStateException("Captcha solving timed out after ${CAPTCHA_TIMEOUT_MILLIS / 1000} seconds", e)
        // }

        try {
            // Step 1: Fetch the page. The interceptor falls back to a WebView
            // if the plain response has no movie_storage.
            val interceptor = withMainContext { LookMovieWebViewInterceptor(context) }
            webView = interceptor

            val html = client
                .addWebViewInterceptor(interceptor)
                .request(url = videoUrl)
                .execute()
                .use { it.body.string() }

            val movieStorage = parseMovieStorage(html)
                ?: throw IllegalStateException("movie_storage not found in response")

            // Step 2: Get movie access with hash and expires
            val cookies = CookieManager.getInstance().getCookie(videoUrl)
            val movieAccess = getMovieAccess(movieStorage, cookies)

            // Parse streams and create MediaLinks
            createMediaLinks(
                movieAccess = movieAccess,
                expiryFlag = Flag.Expires(expiresOn = movieStorage.expires),
                onLinkFound = onLinkFound
            )
        } finally {
            destroy()
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

    private fun parseMovieStorage(html: String): MovieStorageDto? {
        // Extract movie_storage JavaScript object
        val storageRegex = Regex("window\\['movie_storage']\\s*=\\s*(\\{[^}]+\\})")
        val match = storageRegex.find(html) ?: return null

        return gson.fromJson(match.groupValues[1], MovieStorageDto::class.java)
    }

    private fun getMovieAccess(movieStorage: MovieStorageDto, cookies: String?): MovieAccessResponseDto {
        val accessUrl = "${metadataApi.baseUrl}/api/v1/security/movie-access" +
                "?id_movie=${movieStorage.idMovie}" +
                "&hash=${movieStorage.hash}" +
                "&expires=${movieStorage.expires}"

        val headers = cookies?.takeIf { it.isNotEmpty() }
            ?.let { mapOf("Cookie" to it) }
            .orEmpty()

        val responseBody = client.request(
            url = accessUrl,
            headers = headers.toHeaders()
        ).execute().use { it.body.string() }
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
