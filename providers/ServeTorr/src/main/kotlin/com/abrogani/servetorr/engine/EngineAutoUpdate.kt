package com.abrogani.servetorr.engine

import com.flixclusive.core.util.log.errorLog
import com.flixclusive.core.util.log.infoLog
import com.flixclusive.core.util.log.warnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class EngineAutoUpdate(
    private val updateNeeded: suspend () -> Boolean,
    private val install: suspend () -> Unit,
    private val blockReason: suspend () -> String?,
    private val wakeups: Flow<Any?>,
    private val pending: MutableStateFlow<Boolean>,
    private val fallbackMillis: Long = FALLBACK_MILLIS,
) {
    suspend fun run() {
        try {
            if (!updateNeeded()) return
            if (!awaitAllowed()) return
            pending.value = false
            install()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            errorLog("[ServeTorr] Engine update failed")
            errorLog(e)
        } finally {
            pending.value = false
        }
    }

    private suspend fun awaitAllowed(): Boolean = coroutineScope {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val watcher = launch { wakeups.collect { signal.trySend(Unit) } }
        try {
            waitUntilAllowed(signal)
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun waitUntilAllowed(signal: Channel<Unit>): Boolean {
        var first = true
        while (true) {
            if (!first && !updateNeeded()) return false
            first = false
            val reason = currentBlockReason() ?: return true
            if (!pending.value) infoLog("[ServeTorr] Engine update waiting: $reason")
            pending.value = true
            withTimeoutOrNull(fallbackMillis) { signal.receive() }
        }
    }

    private suspend fun currentBlockReason(): String? =
        try {
            blockReason()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            warnLog("[ServeTorr] Couldn't check whether the engine update may download: $e")
            CHECK_FAILED
        }

    companion object {
        const val FALLBACK_MILLIS = 60L * 60 * 1000
        private const val CHECK_FAILED = "network check failed"
    }
}

internal object PluginRegistration {
    suspend fun isRegistered(
        self: Any,
        waitMillis: Long = WAIT_MILLIS,
        stepMillis: Long = STEP_MILLIS,
        plugins: () -> Collection<Any>?,
    ): Boolean {
        var waited = 0L
        while (true) {
            val current = try {
                plugins()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                null
            } ?: return true
            if (current.any { it === self }) return true
            if (waited >= waitMillis) return false
            delay(stepMillis)
            waited += stepMillis
        }
    }

    private const val WAIT_MILLIS = 10_000L
    private const val STEP_MILLIS = 500L
}
