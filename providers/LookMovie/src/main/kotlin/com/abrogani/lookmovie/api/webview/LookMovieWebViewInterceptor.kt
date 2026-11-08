package com.abrogani.lookmovie.api.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.abrogani.lookmovie.extensions.extractStorageObject
import com.abrogani.lookmovie.extensions.fromJson
import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withMainContext
import com.flixclusive.core.util.log.verboseLog
import com.flixclusive.core.util.network.okhttp.UserAgentManager
import com.flixclusive.core.util.network.okhttp.WebViewInterceptor
import com.flixclusive.core.util.webview.WebViewDriverManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * An [okhttp3.OkHttpClient] interceptor that proceeds with the plain request first. If the response
 * has no `window['<storageKey>']` property (e.g., `movie_storage`), it loads the page in a visible WebView
 * and returns the rendered HTML once it has the property.
 *
 * If the site shows a captcha, the user can solve it in the WebView. The interceptor keeps
 * waiting until the page redirects back with the property or [timeoutMillis] elapses.
 * */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class LookMovieWebViewInterceptor(
    private val appContext: Context,
    private val storageKey: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : WebViewInterceptor(appContext) {
    override val name: String = "LookMovie Captcha Solver"
    override val isHeadless: Boolean = false

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = UserAgentManager.getRandomUserAgent()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Try the plain request first. Only fall back to the WebView if movie_storage is missing.
        val plainResponse = chain.proceed(request)
        val contentType = plainResponse.body.contentType()
        val plainHtml = plainResponse.use { it.body.string() }

        if (extractStorageObject(plainHtml, storageKey) != null) {
            return plainResponse.newBuilder()
                .body(plainHtml.toResponseBody(contentType))
                .build()
        }

        val html = try {
            runBlocking {
                withTimeout(timeoutMillis.milliseconds) {
                    withMainContext { loadAndAwaitMovieStorage(request.url.toString()) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after ${timeoutMillis / 1000} seconds waiting for $storageKey", e)
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private suspend fun loadAndAwaitMovieStorage(url: String): String {
        WebViewDriverManager.register(this)

        return suspendCancellableCoroutine { continuation ->
            var toastShown = false

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val message = consoleMessage?.message() ?: return false
                    verboseLog("LookMovieWebViewInterceptor Console: ${consoleMessage.messageLevel().name} - $message")
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return false

                    // To block ads
                    val allowUrl = target.contains("google") || target.contains("lookmovie")
                    return !allowUrl
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val target = request?.url?.toString() ?: return null

                    if (!toastShown && target.contains("/threat-protection/captcha")) {
                        toastShown = true
                        view?.post {
                            Toast.makeText(appContext, "Please solve the captcha to continue", Toast.LENGTH_LONG).show()
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    view?.evaluateJavascript("document.documentElement.outerHTML") { result ->
                        if (!continuation.isActive || result.isNullOrEmpty() || result == "null") return@evaluateJavascript

                        // evaluateJavascript returns a JSON-encoded string
                        val html = runCatching { fromJson<String>(result) }.getOrNull()
                        if (html != null && extractStorageObject(html, storageKey) != null) {
                            continuation.resume(html)
                        }
                    }
                }
            }

            loadUrl(url)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L // 2 minutes
    }
}
