package com.abrogani.flixhq.extractors

import com.abrogani.flixhq.dto.SubtitleTrackDto
import com.abrogani.flixhq.extensions.gson
import com.abrogani.flixhq.extensions.parseJson

internal data class ByseSource(
    val url: String,
    val label: String?,
)

internal data class ByseCapture(
    val sources: List<ByseSource>,
    val tracks: List<SubtitleTrackDto>,
)

internal object ByseCaptures {
    const val CAPTURE_TIMEOUT_MILLIS = 90_000L
    const val POLL_INTERVAL_MILLIS = 500L

    val EXTRACT_SCRIPT = """
        (function () {
          try {
            var playlist = jwplayer().getPlaylist();
            if (!playlist || !playlist.length) return null;
            var item = playlist[0];
            return JSON.stringify({
              sources: (item.sources || []).map(function (s) { return { file: s.file, label: s.label }; }),
              tracks: (item.tracks || []).map(function (t) { return { file: t.file, label: t.label, kind: t.kind }; })
            });
          } catch (e) {
            return null;
          }
        })()
    """.trimIndent()

    fun isMediaUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    fun fromNetwork(urls: List<String>): ByseCapture? {
        val distinct = urls.distinct()
        if (distinct.isEmpty()) return null

        val preferred = distinct.firstOrNull { "master" in it } ?: distinct.first()
        return ByseCapture(sources = listOf(ByseSource(preferred, null)), tracks = emptyList())
    }

    fun parseCapture(raw: String?): ByseCapture? {
        if (raw.isNullOrEmpty() || raw == "null") return null

        val payload = runCatching { gson.fromJson(raw, String::class.java) }.getOrNull() ?: return null
        val root = runCatching { parseJson(payload) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null

        val sources = root.getAsJsonArray("sources")?.toList().orEmpty().mapNotNull { element ->
            val source = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val file = source.get("file")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            ByseSource(file, source.get("label")?.takeIf { it.isJsonPrimitive }?.asString)
        }
        if (sources.isEmpty()) return null

        val tracks = root.getAsJsonArray("tracks")?.toList().orEmpty().mapNotNull { element ->
            runCatching { gson.fromJson(element, SubtitleTrackDto::class.java) }.getOrNull()
        }

        return ByseCapture(sources, tracks)
    }
}
