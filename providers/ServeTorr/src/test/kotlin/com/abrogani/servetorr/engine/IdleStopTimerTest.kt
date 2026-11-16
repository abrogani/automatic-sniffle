package com.abrogani.servetorr.engine

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class IdleStopTimerTest {
    private var now = 1_000_000L
    private val timer = IdleStopTimer { now }

    private fun advanceMinutes(minutes: Double) {
        now += (minutes * IdleStopTimer.MINUTE_MILLIS).toLong()
    }

    @Test
    fun `stops after being empty for the configured minutes`() {
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isFalse()
        advanceMinutes(4.9)
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isFalse()
        advanceMinutes(0.1)
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isTrue()
    }

    @Test
    fun `a torrent resets the timer`() {
        timer.shouldStop(busy = false, minutes = 5)
        advanceMinutes(4.0)
        expectThat(timer.shouldStop(busy = true, minutes = 5)).isFalse()
        advanceMinutes(0.5)
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isFalse()
        advanceMinutes(4.9)
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isFalse()
        advanceMinutes(0.1)
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isTrue()
    }

    @Test
    fun `zero minutes never stops`() {
        repeat(100) {
            expectThat(timer.shouldStop(busy = false, minutes = 0)).isFalse()
            advanceMinutes(10.0)
        }
    }

    @Test
    fun `turning auto-stop on starts counting from then`() {
        timer.shouldStop(busy = false, minutes = 0)
        advanceMinutes(60.0)
        expectThat(timer.shouldStop(busy = false, minutes = 1)).isFalse()
        advanceMinutes(1.0)
        expectThat(timer.shouldStop(busy = false, minutes = 1)).isTrue()
    }

    @Test
    fun `lowering the limit applies to the idle time so far`() {
        timer.shouldStop(busy = false, minutes = 30)
        advanceMinutes(10.0)
        expectThat(timer.shouldStop(busy = false, minutes = 30)).isFalse()
        expectThat(timer.shouldStop(busy = false, minutes = 5)).isTrue()
    }
}
