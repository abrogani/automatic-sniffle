package com.abrogani.sudoflix.api.util

import com.flixclusive.model.media.MediaMetadata
import java.time.Instant
import java.time.ZoneOffset

/** The path segment the upstream sites use for this media type. */
internal val MediaMetadata.urlType: String
    get() = if (isMovie) "movie" else "tv"

internal val MediaMetadata.year: Int?
    get() = releaseDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }
