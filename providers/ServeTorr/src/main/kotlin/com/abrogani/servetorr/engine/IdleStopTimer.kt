package com.abrogani.servetorr.engine

internal class IdleStopTimer(private val clock: () -> Long) {
    private var idleSince: Long? = null

    fun shouldStop(busy: Boolean, minutes: Int): Boolean {
        if (busy || minutes <= 0) {
            idleSince = null
            return false
        }
        val now = clock()
        val since = idleSince ?: now.also { idleSince = it }
        return now - since >= minutes * MINUTE_MILLIS
    }

    companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
