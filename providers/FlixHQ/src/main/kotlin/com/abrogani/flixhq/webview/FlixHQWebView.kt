package com.abrogani.flixhq.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.abrogani.flixhq.api.FlixHQApi
import com.abrogani.flixhq.api.dto.FlixHQInitialSourceData
import com.abrogani.flixhq.extractors.rabbitstream.dto.VidCloudKey
import com.abrogani.flixhq.webview.util.INJECTOR_SCRIPT
import com.abrogani.flixhq.webview.util.setup
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withDefaultContext
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.exception.safeCall
import com.flixclusive.core.util.log.debugLog
import com.flixclusive.core.util.log.infoLog
import com.abrogani.flixhq.api.util.fromJson
import com.flixclusive.core.util.network.okhttp.UserAgentManager
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.core.util.webview.WebViewDriver
import com.flixclusive.model.media.MediaMetadata
import com.flixclusive.model.media.common.tv.Episode
import com.flixclusive.model.provider.link.MediaLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLDecoder
import kotlin.time.Duration.Companion.seconds

@SuppressLint("ViewConstructor")
@Suppress("SpellCheckingInspection")
class FlixHQWebView(
    private val mClient: OkHttpClient,
    private val api: FlixHQApi,
    context: Context,
) : WebViewDriver(context) {
    override val isHeadless = false
    override val name = "FlixHQ WebView"

    private var key: VidCloudKey? = null
    private var injectorScript = INJECTOR_SCRIPT
    private var extractorBaseUrl = "rabbitstream"
    private var userAgent = UserAgentManager.getRandomUserAgent()

    private val chromeClient = object: WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            return safeCall {
                val message = consoleMessage?.message()
                    ?: return@safeCall false

                if (message.contains("e4Key")) {
                    key = fromJson<VidCloudKey>(message)
                    return@safeCall true
                }

                debugLog("Message = $message")
                false
            } ?: false
        }
    }

    private val client = object: WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            // To block ads
            val allowUrl = request?.url.toString().contains("flixhq")
                || request?.url.toString().contains(extractorBaseUrl)
                || request?.url.toString().contains("javascript:")

            return if (allowUrl) {
                super.shouldOverrideUrlLoading(view, request)
            } else true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            infoLog("[FHQWebView] Injecting script on $url...")
            view?.evaluateJavascript(injectorScript, null)
        }
    }

    init {
        setup(
            client = client,
            chromeClient = chromeClient,
            userAgent = userAgent
        )
    }

    private fun setRabbitUrl(url: String) {
        injectorScript = INJECTOR_SCRIPT
            .replace("__rabbit_url__", url)
            .replace("__user_agent__", userAgent)
    }

    suspend fun getLinks(
        media: MediaMetadata,
        episode: Episode?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        infoLog("[FHQWebView] Getting links for ${media.title}")
        val watchIdToUse = media.id

        val servers = withIOContext {
            api.getEpisodeIdAndServers(
                watchId = watchIdToUse,
                episode = episode?.number,
                season = episode?.season
            )
        }

        val validServers = servers.filter {
            it.first.equals("vidcloud", true)
                || it.first.equals("upcloud", true)
        }

        if (validServers.isEmpty())
            throw Exception("No valid servers found!")

        validServers.forEach { (serverName, serverEmbedUrl) ->
            val initialSourceData = withIOContext {
                mClient.request(
                    url = "${api.baseUrl}/ajax/episode/sources/${serverEmbedUrl.split('.').last()}",
                    userAgent = userAgent
                ).execute().body.string()
            }

            val serverUrl = withDefaultContext {
                URLDecoder.decode(
                    fromJson<FlixHQInitialSourceData>(initialSourceData).link,
                    "UTF-8"
                )
            }
            extractorBaseUrl = getBaseUrl(serverUrl)

            val extractor = api.extractors[serverName.lowercase()]
                ?: throw Exception("Extractor not found!")

            setRabbitUrl(serverUrl)
            withMainContext {
                loadUrl(extractorBaseUrl)
            }

            withTimeout(60.seconds) {
                waitForKeyToBeAttached()
            }

            requireNotNull(key) {
                "Can't find decryption key!"
            }

            infoLog("[FHQWebView] Extracting links...")
            extractor.extract(
                url = serverUrl,
                key = key!!,
                userAgent = userAgent,
                onLinkFound = onLinkFound
            )
        }
    }

    private suspend fun waitForKeyToBeAttached() {
        infoLog("[FHQWebView] Waiting for key to be attached...")
        key = null
        while(key == null)
            delay(500)
    }

    private fun getBaseUrl(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}/"
    }
}