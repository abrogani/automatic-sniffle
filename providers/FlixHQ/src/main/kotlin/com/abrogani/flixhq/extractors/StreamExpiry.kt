package com.abrogani.flixhq.extractors

import com.flixclusive.model.provider.link.Flag
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object StreamExpiry {
    fun of(url: String): Flag.Expires? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val issuedAt = parsed.queryParameter("s")?.toLongOrNull() ?: return null
        val lifetime = parsed.queryParameter("e")?.toLongOrNull() ?: return null
        return Flag.Expires((issuedAt + lifetime) * 1000)
    }
}
