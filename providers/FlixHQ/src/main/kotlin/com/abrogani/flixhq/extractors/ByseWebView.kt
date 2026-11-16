package com.abrogani.flixhq.extractors

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.abrogani.flixhq.api.USER_AGENT
import com.flixclusive.core.util.webview.WebViewDriver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
internal class ByseWebView(context: Context) : WebViewDriver(context) {
    override val name = "FlixHQ Byse"
    override val isHeadless = false

    private val handler = Handler(Looper.getMainLooper())
    private val networkCaptures = CopyOnWriteArrayList<String>()

    @Volatile
    private var polling = false

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = USER_AGENT
    }

    suspend fun capture(embedUrl: String): ByseCapture? {
        networkCaptures.clear()

        val result = withTimeoutOrNull(ByseCaptures.CAPTURE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<ByseCapture> { continuation ->
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        request?.url?.toString()
                            ?.takeIf(ByseCaptures::isMediaUrl)
                            ?.let(networkCaptures::add)
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (polling) return
                        polling = true
                        view?.post {
                            Toast.makeText(context, "Tap play to verify and load the stream", Toast.LENGTH_LONG).show()
                        }
                        poll(continuation)
                    }
                }

                continuation.invokeOnCancellation { polling = false }
                loadUrl(embedUrl)
            }
        }

        polling = false
        handler.removeCallbacksAndMessages(null)
        return result ?: ByseCaptures.fromNetwork(networkCaptures)
    }

    private fun poll(continuation: CancellableContinuation<ByseCapture>) {
        if (!polling || !continuation.isActive) return

        evaluateJavascript(ByseCaptures.EXTRACT_SCRIPT) { raw ->
            val capture = ByseCaptures.parseCapture(raw) ?: ByseCaptures.fromNetwork(networkCaptures)

            if (capture != null) {
                if (continuation.isActive) continuation.resume(capture)
            } else {
                handler.postDelayed({ poll(continuation) }, ByseCaptures.POLL_INTERVAL_MILLIS)
            }
        }
    }

    override fun destroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        super.destroy()
    }
}
