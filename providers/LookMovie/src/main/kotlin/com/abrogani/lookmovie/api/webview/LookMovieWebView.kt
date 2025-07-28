package com.abrogani.lookmovie.api.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.abrogani.lookmovie.api.LookMovieApi
import com.abrogani.lookmovie.api.VIDEO_URL_KEY
import com.abrogani.lookmovie.dto.MovieAccessResponseDto
import com.abrogani.lookmovie.dto.MovieStorageDto
import com.flixclusive.core.util.coroutines.AppDispatchers.Companion.withMainContext
import com.flixclusive.core.util.coroutines.mapAsync
import com.flixclusive.core.util.exception.safeCall
import com.flixclusive.core.util.log.verboseLog
import com.flixclusive.core.util.network.okhttp.UserAgentManager
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.film.FilmMetadata
import com.flixclusive.model.film.Movie
import com.flixclusive.model.film.common.tv.Episode
import com.flixclusive.model.film.util.FilmType
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import com.flixclusive.provider.webview.ProviderWebView
import com.google.gson.Gson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import kotlin.coroutines.resume

@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class LookMovieWebView(
    context: Context,
    private val baseApi: LookMovieApi
) : ProviderWebView(context) {
    private var userAgent = UserAgentManager.getRandomUserAgent()

    override val name: String = "LookMovie Captcha Solver"
    override val isHeadless: Boolean = false

    private val gson = Gson()
    private var captchaCookies: String? = null

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
    }

    companion object {
        private const val CAPTCHA_TIMEOUT_MILLIS = 120_000L // 2 minutes
    }

    override suspend fun getLinks(
        watchId: String,
        film: FilmMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        // TODO("Implement support for TV shows")
        if (film.filmType == FilmType.TV_SHOW) {
            throw IllegalStateException("This provide does not support TV shows YET!")
        }

        val videoUrl = film.customProperties[VIDEO_URL_KEY]
            ?: baseApi.getMetadata((film as Movie).copy(id = watchId)).customProperties[VIDEO_URL_KEY] // TODO(Check for TV shows as well)
            ?: throw IllegalArgumentException("Video URL not found in film metadata")

        try {
            // Step 1: Check if we already have valid cookies and can access movie_storage directly
            val existingCookies = cookieManager.getCookie(videoUrl)
            val movieStorage = if (existingCookies?.isNotEmpty() == true) {
                safeCall { extractMovieStorage(videoUrl, existingCookies) }
            } else {
                null
            }

            val finalMovieStorage = if (movieStorage != null) {
                // Use existing valid cookies
                captchaCookies = existingCookies
                movieStorage
            } else {
                // Show toast message that captcha needs to be solved
                withMainContext {
                    Toast.makeText(context, "Please solve the captcha to continue", Toast.LENGTH_LONG).show()
                }

                // Need to solve captcha with timeout
                captchaCookies = withTimeout(CAPTCHA_TIMEOUT_MILLIS) {
                    withMainContext {
                        solveCaptcha(videoUrl)
                    }
                }

                withMainContext { destroy() }
                extractMovieStorage(videoUrl, captchaCookies!!)
            }

            // Step 4: Get movie access with hash and expires
            val movieAccess = getMovieAccess(finalMovieStorage)

            // Parse streams and create MediaLinks
            createMediaLinks(
                movieAccess = movieAccess,
                expiryFlag = Flag.Expires(expiresOn = finalMovieStorage.expires),
                onLinkFound = onLinkFound
            )
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Captcha solving timed out after ${CAPTCHA_TIMEOUT_MILLIS / 1000} seconds", e)
        }
    }

    private suspend fun solveCaptcha(videoUrl: String): String = suspendCancellableCoroutine { continuation ->
        var captchaRedirectUrl: String? = null

        webChromeClient = object: WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage?.message() ?: return false
                val level = consoleMessage.messageLevel().name

                verboseLog("LookMovieWebView Console Message: $level - $message")
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // To block ads
                val allowUrl = url.contains("google") || url.contains("lookmovie")

                return !allowUrl
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Detect captcha redirect (Step 2)
                if (url.contains("/threat-protection/captcha")) {
                    captchaRedirectUrl = url
                }

                // Detect successful return to video URL (Step 3)
                if (captchaRedirectUrl != null && url == videoUrl) {
                    val cookies = cookieManager.getCookie(url)
                    if (cookies?.isNotEmpty() == true) {
                        if (continuation.isActive) {
                            continuation.resume(cookies)
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        loadUrl(videoUrl)
    }

    private fun extractMovieStorage(videoUrl: String, cookies: String): MovieStorageDto {
        val headers = mapOf("Cookie" to cookies)

        val response = baseApi.client.request(
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

    private fun getMovieAccess(movieStorage: MovieStorageDto): MovieAccessResponseDto {
        val accessUrl = "${baseApi.baseUrl}/api/v1/security/movie-access" +
                "?id_movie=${movieStorage.idMovie}" +
                "&hash=${movieStorage.hash}" +
                "&expires=${movieStorage.expires}"

        val headers = mapOf("Cookie" to captchaCookies!!)

        val response = baseApi.client.request(
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
        val streams = movieAccess.streams
        val subtitles = movieAccess.subtitles

        subtitles.mapAsync {
            val subtitleUrl = baseApi.baseUrl + it.file
            onLinkFound(
                Subtitle(
                    language = it.language,
                    url = subtitleUrl,
                )
            )
        }

        streams.forEach { (quality, link) ->
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