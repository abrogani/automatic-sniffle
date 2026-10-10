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
