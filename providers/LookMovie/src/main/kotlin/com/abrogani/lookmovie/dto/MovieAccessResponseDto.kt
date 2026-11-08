package com.abrogani.lookmovie.dto

import com.google.gson.JsonElement

/** Response of both the `movie-access` and `episode-access` endpoints. */
internal data class MovieAccessResponseDto(
    val streams: Map<String, String?>,
    val subtitles: List<SubtitleDto>,
)

internal data class SubtitleDto(
    val language: String,
    /**
     * Usually a path like `/captions/ab/cd/ef.vtt`, but some (OpenSubtitles) entries
     * hold an array of metadata instead, which can't be fetched. See [path].
     * */
    val file: JsonElement?,
) {
    /** The subtitle's path, or null when [file] isn't a path. */
    val path: String?
        get() = file?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
}
