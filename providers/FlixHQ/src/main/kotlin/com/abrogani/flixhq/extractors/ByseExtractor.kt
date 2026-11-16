package com.abrogani.flixhq.extractors

import android.content.Context
import com.abrogani.flixhq.api.USER_AGENT
import com.abrogani.flixhq.dto.ServerDto
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.webview.WebViewDriverManager
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import com.flixclusive.model.provider.link.Subtitle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class ByseExtractor(
    private val context: Context,
) : EmbedExtractor {
    @Volatile
    private var driver: ByseWebView? = null

    override val name = "Byse"

    override fun matches(server: ServerDto): Boolean =
        server.name.equals("FlixHQ", ignoreCase = true) ||
            server.link?.substringBefore('?')?.substringAfter("://")?.substringAfter('/')?.startsWith("e/") == true

    override suspend fun extract(server: ServerDto, onLink: (MediaLink) -> Unit) {
        val embedUrl = requireNotNull(server.link) { "Byse server has no link" }
        val origin = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }

        val webView = withMainContext {
            ByseWebView(context).also {
                driver = it
                WebViewDriverManager.register(it)
            }
        }

        try {
            val capture = withMainContext { webView.capture(embedUrl) }
                ?: throw IllegalStateException("Byse returned no streams")

            val headers = mapOf("Referer" to "$origin/", "Origin" to origin, "User-Agent" to USER_AGENT)

            capture.sources.forEach { source ->
                onLink(
                    Stream(
                        name = source.label?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name,
                        url = source.url,
                        flags = setOf(Flag.RequiresAuth(headers)),
                    ),
                )
            }

            capture.tracks
                .filter { it.kind == "captions" && it.file?.startsWith("http") == true }
                .forEach { track ->
                    onLink(Subtitle(language = track.label?.takeIf { it.isNotBlank() } ?: "Unknown", url = track.file!!))
                }
        } finally {
            destroy()
        }
    }

    suspend fun destroy() {
        val current = driver ?: return
        driver = null
        withContext(NonCancellable) { withMainContext { current.destroy() } }
    }
}
