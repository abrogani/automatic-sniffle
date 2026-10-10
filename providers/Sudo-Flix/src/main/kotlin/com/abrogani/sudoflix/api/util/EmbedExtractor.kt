package com.abrogani.sudoflix.api.util

import com.flixclusive.model.provider.link.MediaLink
import okhttp3.OkHttpClient

/**
 * Base class for the embed extractors in this provider.
 * (The SDK's own `EmbedExtractor` was removed in 1.4.0.)
 */
internal abstract class EmbedExtractor(
    protected val client: OkHttpClient
) {
    abstract val baseUrl: String
    abstract val name: String

    abstract suspend fun extract(
        url: String,
        customHeaders: Map<String, String>? = null,
        onLinkFound: (MediaLink) -> Unit
    )
}
