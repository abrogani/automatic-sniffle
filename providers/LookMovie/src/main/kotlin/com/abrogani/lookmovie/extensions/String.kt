package com.abrogani.lookmovie.extensions

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneOffset

internal fun String.removeAccents(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")

/** The site only exposes a release year, so pin it to January 1st (UTC). */
internal fun Int.toReleaseDate(): Long =
    LocalDate.of(this, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Prefixes [baseUrl] to a site-relative path. Returns null when there is no path. */
internal fun String?.toAbsoluteUrl(baseUrl: String): String? = when {
    isNullOrEmpty() -> null
    startsWith("http") -> this
    else -> baseUrl + this
}

/** The id of a media is the last path segment of its link, without any query params. */
internal fun String.toMediaId(): String = substringBefore("?").substringAfterLast("/")
