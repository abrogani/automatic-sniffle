package com.abrogani.servetorr.intercept

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue

class HostCapabilityTest {
    private val rows = listOf(
        ProviderRow(id = "servetorr", ownerId = "a", mediaLinkEnabled = false),
        ProviderRow(id = "servetorr", ownerId = "b", mediaLinkEnabled = true),
        ProviderRow(id = "other", ownerId = "a", mediaLinkEnabled = true),
    )

    @Test
    fun `uses the current user's row`() {
        expectThat(HostCapability.decide(rows, "servetorr", currentUser = "a")).isEqualTo(false)
        expectThat(HostCapability.decide(rows, "servetorr", currentUser = "b")).isEqualTo(true)
    }

    @Test
    fun `without a known user it stays on if any profile has links enabled`() {
        expectThat(HostCapability.decide(rows, "servetorr", currentUser = null)!!).isTrue()
        expectThat(HostCapability.decide(rows.take(1), "servetorr", currentUser = "zzz")!!).isFalse()
    }

    @Test
    fun `unknown provider is undecided`() {
        expectThat(HostCapability.decide(rows, "missing", currentUser = "a")).isNull()
    }
}
