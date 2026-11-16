package com.abrogani.flixhq.extensions

import com.abrogani.flixhq.api.BASE_URL
import com.abrogani.flixhq.api.EPISODE_PREFIX
import java.time.LocalDate
import java.time.ZoneOffset

internal fun String.toMediaId(): String {
    val path = if ("://" in this) substringAfter("://").substringAfter('/', "") else this
    return path.substringBefore('?').substringBefore('#').trim('/')
}

internal fun String.toPageUrl(): String =
    if (startsWith("http")) this else "$BASE_URL/${trim('/')}/"

internal fun String?.toAbsoluteUrl(): String? {
    val value = this?.trim().orEmpty()
    return when {
        value.isEmpty() -> null
        value.startsWith("//") -> "https:$value"
        value.startsWith("http") -> value
        else -> "$BASE_URL/${value.trimStart('/')}"
    }
}

internal fun Int.toReleaseDate(): Long =
    LocalDate.of(this, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun String.toReleaseDateOrNull(): Long? =
    runCatching { LocalDate.parse(trim()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

internal fun Long.toYear(): Int = java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).year

internal fun episodeId(showId: String, season: Int, episode: Int): String =
    "$EPISODE_PREFIX${showId.toMediaId().substringAfter('/')}/s%02d-e%02d".format(season, episode)
