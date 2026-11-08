package com.abrogani.lookmovie.extensions

import com.flixclusive.core.util.network.okhttp.request
import okhttp3.OkHttpClient

/** Fetches [url] and returns its body as a string. The response is always closed. */
internal fun OkHttpClient.fetch(url: String): String =
    request(url = url).execute().use { it.body.string() }
