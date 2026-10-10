package com.abrogani.lookmovie.api.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.flixclusive.core.util.log.verboseLog
import com.flixclusive.core.util.network.okhttp.UserAgentManager
import com.flixclusive.core.util.webview.WebViewDriver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A visible WebView used to let the user solve LookMovie's captcha.
 * Must be created and driven on the main thread.
 * */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class LookMovieWebView(context: Context) : WebViewDriver(context) {
    private val userAgent = UserAgentManager.getRandomUserAgent()

    override val name: String = "LookMovie Captcha Solver"
    override val isHeadless: Boolean = false

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
    }

    /**
     * Loads [videoUrl] and suspends until the captcha is solved and the site
     * redirects back to it. Returns the cookies of the solved session.
     * */
    suspend fun solveCaptcha(videoUrl: String): String = suspendCancellableCoroutine { continuation ->
        var captchaRedirectUrl: String? = null

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage?.message() ?: return false
                val level = consoleMessage.messageLevel().name

                verboseLog("LookMovieWebView Console Message: $level - $message")
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // To block ads
                val allowUrl = url.contains("google") || url.contains("lookmovie")

                return !allowUrl
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Detect captcha redirect (Step 2)
                if (url.contains("/threat-protection/captcha")) {
                    captchaRedirectUrl = url
                }

                // Detect successful return to video URL (Step 3)
                if (captchaRedirectUrl != null && url == videoUrl) {
                    val cookies = cookieManager?.getCookie(url)
                    if (cookies?.isNotEmpty() == true) {
                        if (continuation.isActive) {
                            continuation.resume(cookies)
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        loadUrl(videoUrl)
    }
}
