package com.abrogani.lookmovie

import com.abrogani.lookmovie.extensions.toEpochMillis
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class ExpiryTest {
    @Test
    fun `seconds are converted to milliseconds`() {
        expectThat(1_790_000_000L.toEpochMillis()).isEqualTo(1_790_000_000_000L)
    }

    @Test
    fun `milliseconds are left untouched`() {
        expectThat(1_790_000_000_000L.toEpochMillis()).isEqualTo(1_790_000_000_000L)
    }
}
