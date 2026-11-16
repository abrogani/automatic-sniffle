package com.abrogani.flixhq.extractors

import com.abrogani.flixhq.dto.ServerDto
import com.abrogani.flixhq.extensions.fetchText
import com.flixclusive.model.provider.link.Flag
import com.flixclusive.model.provider.link.MediaLink
import com.flixclusive.model.provider.link.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal class VidmolyExtractor(
    private val client: OkHttpClient,
) : EmbedExtractor {
    override val name = "Vidmoly"

    override fun matches(server: ServerDto): Boolean {
        val host = server.link?.toHttpUrlOrNull()?.host.orEmpty()
        return server.name.equals(name, ignoreCase = true) ||
            host.contains("vidmoly") ||
            host.contains("kaembed")
    }

    override suspend fun extract(server: ServerDto, onLink: (MediaLink) -> Unit) {
        val embedUrl = requireNotNull(server.link) { "Vidmoly server has no link" }
        val html = withContext(Dispatchers.IO) { client.fetchText(embedUrl) }

        val url = parseSource(html) ?: throw IllegalStateException("Vidmoly stream not found")

        onLink(
            Stream(
                name = name,
                url = url,
                description = "Auto",
                flags = expiryOf(url)?.let { setOf(it) },
            ),
        )
    }

    companion object {
        private val SOURCES = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]""")
        private val PLAYLIST = Regex("""https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""")

        fun parseSource(html: String): String? =
            SOURCES.find(html)?.groupValues?.get(1)
                ?: PLAYLIST.find(html)?.value

        fun expiryOf(url: String): Flag.Expires? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val issuedAt = parsed.queryParameter("s")?.toLongOrNull() ?: return null
            val lifetime = parsed.queryParameter("e")?.toLongOrNull() ?: return null
            return Flag.Expires((issuedAt + lifetime) * 1000)
        }
    }
}
