package com.abrogani.lookmovie.extensions

import java.text.Normalizer

internal fun String.removeAccents(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
