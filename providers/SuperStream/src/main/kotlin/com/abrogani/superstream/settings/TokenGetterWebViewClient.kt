package com.abrogani.superstream.settings

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flixclusive.provider.extensions.setInt
import com.flixclusive.provider.extensions.setString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TOKEN_COOKIE_NAME = "ui"

class TokenGetterWebViewClient(
    private val settings: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val onTokenReceived: () -> Unit
) : WebViewClient() {
    private val cookieManager = CookieManager.getInstance()

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url != null) {
            val token = getToken(url)

            if (token != null) {
                scope.launch {
                    settings.setInt(
                        key = TOKEN_STATUS_KEY,
                        value = TokenStatus.Online.ordinal
                    )

                    settings.setString(
                        key = TOKEN_KEY,
                        value = token
                    )

                    onTokenReceived()
                }
            }
        }
    }

    private fun getToken(url: String): String? {
        val cookies = cookieManager.getCookie(url)
        if (cookies == null || !cookies.contains("$TOKEN_COOKIE_NAME=")) {
            return null
        }

        return cookies.split("$TOKEN_COOKIE_NAME=")
            .getOrNull(1)
            ?.split(";")
            ?.getOrNull(0)
    }
}