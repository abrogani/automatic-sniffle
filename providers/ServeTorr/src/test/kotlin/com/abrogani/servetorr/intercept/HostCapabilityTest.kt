package com.abrogani.servetorr.intercept

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `toggle maps an unreadable capability to interception on`() {
        expectThat(StreamsToggle.of(true)).isEqualTo(StreamsToggle.ON)
        expectThat(StreamsToggle.of(false)).isEqualTo(StreamsToggle.OFF)
        expectThat(StreamsToggle.of(null)).isEqualTo(StreamsToggle.UNREADABLE)
        expectThat(StreamsToggle.ON.intercepts).isTrue()
        expectThat(StreamsToggle.UNREADABLE.intercepts).isTrue()
        expectThat(StreamsToggle.OFF.intercepts).isFalse()
    }

    @Test
    fun `memo reuses a value until it expires`() {
        var now = 0L
        var loads = 0
        val memo = Memo<StreamsToggle>(ttlMillis = 1_500, clock = { now })
        val load: suspend () -> StreamsToggle = {
            loads++
            if (loads == 1) StreamsToggle.ON else StreamsToggle.OFF
        }

        runBlocking {
            expectThat(memo.get(load)).isEqualTo(StreamsToggle.ON)
            now = 1_499
            expectThat(memo.get(load)).isEqualTo(StreamsToggle.ON)
            expectThat(loads).isEqualTo(1)
            now = 1_500
            expectThat(memo.get(load)).isEqualTo(StreamsToggle.OFF)
            expectThat(loads).isEqualTo(2)
        }
    }

    @Test
    fun `memo takes pushed values without loading`() {
        var now = 0L
        var loads = 0
        val memo = Memo<StreamsToggle>(ttlMillis = 1_500, clock = { now })
        memo.put(StreamsToggle.OFF)

        runBlocking {
            now = 1_000
            expectThat(memo.get { loads++; StreamsToggle.ON }).isEqualTo(StreamsToggle.OFF)
        }
        expectThat(loads).isEqualTo(0)
    }

    @Test
    fun `retry gate lets one attempt through per interval`() {
        var now = 0L
        val gate = RetryGate(intervalMillis = 5_000, clock = { now })

        expectThat(gate.tryPass()).isTrue()
        expectThat(gate.tryPass()).isFalse()
        now = 4_999
        expectThat(gate.tryPass()).isFalse()
        now = 5_000
        expectThat(gate.tryPass()).isTrue()
        expectThat(gate.tryPass()).isFalse()
    }
}
