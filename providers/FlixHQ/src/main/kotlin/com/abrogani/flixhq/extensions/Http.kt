package com.abrogani.flixhq.extensions

import com.abrogani.flixhq.api.BASE_URL
import com.abrogani.flixhq.api.USER_AGENT
import com.flixclusive.core.util.network.okhttp.request
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

internal fun pageHeaders(referer: String = "$BASE_URL/"): Headers =
    Headers.headersOf("Referer", referer, "Accept-Language", "en-US,en;q=0.9")

internal fun OkHttpClient.fetchText(url: String, referer: String = "$BASE_URL/"): String =
    request(url = url, headers = pageHeaders(referer), userAgent = USER_AGENT).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        response.body.string()
    }

internal fun OkHttpClient.fetchDocument(url: String): Document =
    Jsoup.parse(fetchText(url), url)
