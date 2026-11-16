package com.abrogani.flixhq.dto

import com.google.gson.annotations.SerializedName

internal data class ServerDto(
    val name: String? = null,
    val link: String? = null,
    @SerializedName("en_sub") val enSub: Int? = null,
)

internal data class SubtitleTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

internal data class PlayerToken(
    val key: String,
    val value: String,
)
