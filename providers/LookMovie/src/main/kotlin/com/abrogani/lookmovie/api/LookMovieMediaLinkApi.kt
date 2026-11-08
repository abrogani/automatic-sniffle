package com.abrogani.lookmovie.api

import android.content.Context
import android.webkit.CookieManager
import com.abrogani.lookmovie.api.webview.LookMovieWebViewInterceptor
import com.abrogani.lookmovie.dto.MovieAccessResponseDto
import com.abrogani.lookmovie.dto.MovieStorageDto
import com.abrogani.lookmovie.dto.ShowStorageDto
import com.abrogani.lookmovie.extensions.fromJson
import com.abrogani.lookmovie.extensions.parseStorage
import com.abrogani.lookmovie.extensions.toEpochMillis
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
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
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

class LookMovieMediaLinkApi(
    private val context: Context,
    private val client: OkHttpClient,
    private val metadataApi: LookMovieMetadataApi,
) : MediaLinkProviderApi {
    private var interceptor: LookMovieWebViewInterceptor? = null

    override val supportedLinkTypes = setOf(MediaLinkType.STREAMS, MediaLinkType.SUBTITLES)

    override suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        try {
            if (media.isShow) {
                getShowLinks(
                    media = media,
                    episode = episode ?: throw IllegalArgumentException("Episode is required for TV shows"),
                    onLinkFound = onLinkFound
                )
            } else {
                getMovieLinks(media, onLinkFound)
            }
        } finally {
            destroy()
        }
    }

    private suspend fun getMovieLinks(
        media: MediaMetadata,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val videoUrl = media.customProperties[VIDEO_URL_KEY]
            ?: metadataApi.getMovie(media.toPartialMedia()).customProperties[VIDEO_URL_KEY]
            ?: throw IllegalArgumentException("Video URL not found in media metadata")

        // TODO: Restore the cookie/captcha flow below once the WEB requires it again.
        //  For now, the movie_storage is extracted directly, with a WebView fallback.
        //  LookMovieWebView (the captcha solver this used) was removed; see git history before the
        //  "remove unused" cleanup if it's needed again.
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

        val html = fetchHtml(videoUrl, MOVIE_STORAGE_KEY)
        val storage = parseStorage<MovieStorageDto>(html, MOVIE_STORAGE_KEY)
            ?: throw IllegalStateException("$MOVIE_STORAGE_KEY not found in response")

        val access = getAccess(
            url = "$BASE_URL/api/v1/security/movie-access" +
                    "?id_movie=${storage.idMovie}&hash=${storage.hash}&expires=${storage.expires}",
            cookies = CookieManager.getInstance().getCookie(videoUrl)
        )

        createMediaLinks(access, Flag.Expires(expiresOn = storage.expires.toEpochMillis()), onLinkFound)
    }

    private suspend fun getShowLinks(
        media: MediaMetadata,
        episode: Episode,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val playUrl = "$BASE_URL/shows/play/${media.id}"
        val html = fetchHtml(playUrl, SHOW_STORAGE_KEY)

        val storage = parseStorage<ShowStorageDto>(html, SHOW_STORAGE_KEY)
            ?: throw IllegalStateException("$SHOW_STORAGE_KEY not found in response")
        val hash = storage.hash ?: throw IllegalStateException("hash not found in $SHOW_STORAGE_KEY")
        val expires = storage.expires ?: throw IllegalStateException("expires not found in $SHOW_STORAGE_KEY")

        val episodeId = metadataApi.getCachedEpisodeId(media.id, episode.season, episode.number)
            ?: storage.seasons.orEmpty().filterNotNull()
                .firstOrNull {
                    it.season?.toIntOrNull() == episode.season && it.episode?.toIntOrNull() == episode.number
                }
                ?.idEpisode?.toString()
            ?: episode.id

        val access = getAccess(
            url = "$BASE_URL/api/v1/security/episode-access" +
                    "?id_episode=$episodeId&hash=$hash&expires=$expires",
            cookies = CookieManager.getInstance().getCookie(playUrl)
        )

        createMediaLinks(access, Flag.Expires(expiresOn = expires.toEpochMillis()), onLinkFound)
    }

    suspend fun destroy() {
        val driver = interceptor ?: return
        interceptor = null
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

    /**
     * Fetches [url] through a [LookMovieWebViewInterceptor], which only falls back
     * to a WebView if the plain response doesn't have [storageKey].
     * */
    private suspend fun fetchHtml(url: String, storageKey: String): String {
        val webViewInterceptor = withMainContext { LookMovieWebViewInterceptor(context, storageKey) }
        interceptor = webViewInterceptor

        return client
            .addWebViewInterceptor(webViewInterceptor)
            .request(url = url)
            .execute()
            .use { it.body.string() }
    }

    private fun getAccess(url: String, cookies: String?): MovieAccessResponseDto {
        val headers = cookies?.takeIf { it.isNotEmpty() }
            ?.let { mapOf("Cookie" to it) }
            .orEmpty()

        return client.request(
            url = url,
            headers = headers.toHeaders()
        ).execute().fromJson()
    }

    private fun createMediaLinks(
        access: MovieAccessResponseDto,
        expiryFlag: Flag,
        onLinkFound: (MediaLink) -> Unit
    ) {
        access.subtitles.mapNotNull { subtitle ->
            subtitle.path?.let { Subtitle(language = subtitle.language, url = BASE_URL + it) }
        }.forEach(onLinkFound)

        access.streams.forEach { (quality, link) ->
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
