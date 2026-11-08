package com.abrogani.lookmovie.dto

/** Response of both the `movie-access` and `episode-access` endpoints. */
internal data class MovieAccessResponseDto(
    val streams: Map<String, String?>,
    val subtitles: List<SubtitleDto>,
)

internal data class SubtitleDto(
    val language: String,
    val file: String,
)
