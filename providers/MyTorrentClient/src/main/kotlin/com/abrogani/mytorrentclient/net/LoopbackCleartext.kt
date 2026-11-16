package com.abrogani.mytorrentclient.net

import android.content.Context
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import okhttp3.Protocol
import okhttp3.internal.platform.ContextAwarePlatform
import okhttp3.internal.platform.Platform
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal object LoopbackCleartext {
    @Volatile
    private var installed: LoopbackPlatform? = null

    @Synchronized
    fun install() {
        if (installed != null) return

        try {
            val current = Platform.get()
            if (current is LoopbackPlatform) return

            val wrapper = LoopbackPlatform(innermost(current))
            Platform.resetForTests(wrapper)
            installed = wrapper
            infoLog("[MyTorrentClient] Allowed cleartext HTTP to loopback")
        } catch (e: Throwable) {
            warnLog("[MyTorrentClient] Could not allow cleartext HTTP to loopback; torrent links may not play: $e")
        }
    }

    @Synchronized
    fun uninstall() {
        val wrapper = installed ?: return
        installed = null

        if (Platform.get() === wrapper) {
            runCatching { Platform.resetForTests(wrapper.delegate) }
                .onFailure { warnLog("[MyTorrentClient] Could not restore OkHttp's platform: $it") }
        }
    }

    internal fun isLoopback(hostname: String): Boolean {
        val host = hostname.removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host == "::1") return true
        val octets = host.split('.')
        return octets.size == 4 &&
            octets[0] == "127" &&
            octets.all { octet -> octet.length in 1..3 && octet.all(Char::isDigit) && octet.toInt() <= 255 }
    }

    internal fun isLoopbackWrapper(platform: Any): Boolean = platform.javaClass.name == WRAPPER_CLASS_NAME

    internal fun innermost(platform: Platform): Platform {
        var current = platform
        repeat(MAX_UNWRAP_DEPTH) {
            if (!isLoopbackWrapper(current)) return current
            current = delegateOf(current) ?: return current
        }
        return current
    }

    private fun delegateOf(wrapper: Any): Platform? =
        runCatching {
            val method = wrapper.javaClass.getDeclaredMethod("getDelegate")
            method.isAccessible = true
            method.invoke(wrapper) as? Platform
        }.getOrNull()

    private const val MAX_UNWRAP_DEPTH = 16
    private val WRAPPER_CLASS_NAME: String = LoopbackPlatform::class.java.name
}

internal class LoopbackPlatform(val delegate: Platform) : Platform(), ContextAwarePlatform {
    override fun isCleartextTrafficPermitted(hostname: String): Boolean =
        LoopbackCleartext.isLoopback(hostname) || delegate.isCleartextTrafficPermitted(hostname)

    override var applicationContext: Context?
        get() = (delegate as? ContextAwarePlatform)?.applicationContext
        set(value) {
            (delegate as? ContextAwarePlatform)?.applicationContext = value
        }

    override fun newSSLContext(): SSLContext = delegate.newSSLContext()

    override fun platformTrustManager(): X509TrustManager = delegate.platformTrustManager()

    override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
        delegate.trustManager(sslSocketFactory)

    override fun configureTlsExtensions(
        sslSocket: SSLSocket,
        hostname: String?,
        protocols: List<Protocol>,
    ) = delegate.configureTlsExtensions(sslSocket, hostname, protocols)

    override fun afterHandshake(sslSocket: SSLSocket) = delegate.afterHandshake(sslSocket)

    override fun getSelectedProtocol(sslSocket: SSLSocket): String? = delegate.getSelectedProtocol(sslSocket)

    override fun getHandshakeServerNames(sslSocket: SSLSocket): List<String> =
        delegate.getHandshakeServerNames(sslSocket)

    override fun connectSocket(
        socket: Socket,
        address: InetSocketAddress,
        connectTimeout: Int,
    ) = delegate.connectSocket(socket, address, connectTimeout)

    override fun log(
        message: String,
        level: Int,
        t: Throwable?,
    ) = delegate.log(message, level, t)

    override fun getStackTraceForCloseable(closer: String): Any? = delegate.getStackTraceForCloseable(closer)

    override fun logCloseableLeak(
        message: String,
        stackTrace: Any?,
    ) = delegate.logCloseableLeak(message, stackTrace)

    override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
        delegate.buildCertificateChainCleaner(trustManager)

    override fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex =
        delegate.buildTrustRootIndex(trustManager)

    override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory =
        delegate.newSslSocketFactory(trustManager)

    override fun toString(): String = delegate.toString()
}
