package com.abrogani.flixhq.webview.util

import java.text.Normalizer

internal fun String.removeAccents(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
}

internal fun String.toReferer(): String {
    return replaceFirst("^(movie|tv)/".toRegex(), "/watch-$1/")
}
