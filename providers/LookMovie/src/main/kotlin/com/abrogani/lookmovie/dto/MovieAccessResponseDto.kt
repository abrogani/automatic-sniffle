package com.abrogani.lookmovie.dto

data class MovieAccessResponseDto(
    val streams: Map<String, String?>,
    val subtitles: List<SubtitleDto>,
)

data class SubtitleDto(
    val language: String,
    val file: String,
    val kind: String
)
