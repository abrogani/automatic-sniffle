package com.abrogani.flixhq.extractors

import com.abrogani.flixhq.dto.ServerDto
import com.flixclusive.model.provider.link.MediaLink

internal interface EmbedExtractor {
    val name: String

    fun matches(server: ServerDto): Boolean

    suspend fun extract(server: ServerDto, onLink: (MediaLink) -> Unit)
}
