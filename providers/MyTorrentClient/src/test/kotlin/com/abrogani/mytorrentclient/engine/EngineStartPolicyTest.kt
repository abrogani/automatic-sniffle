package com.abrogani.mytorrentclient.engine

import com.abrogani.mytorrentclient.engine.EngineStartPolicy.Decision
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue

class EngineStartPolicyTest {
    private val properties = HashMap<String, String>()
    private val flags = ProcessFlags(properties::get) { key, value -> properties[key] = value }

    @Test
    fun `fresh process starts the engine`() {
        expectThat(EngineStartPolicy.decide(flags, startedPortAlive = false, bindingsAvailable = false))
            .isEqualTo(Decision.Start)
    }

    @Test
    fun `failed once means no retry in this process`() {
        expectThat(EngineStartPolicy.recordStart(flags, -1)).isNull()
        expectThat(flags.startFailed).isTrue()

        repeat(2) {
            expectThat(EngineStartPolicy.decide(flags, startedPortAlive = false, bindingsAvailable = true))
                .isEqualTo(Decision.Fail(EngineStartPolicy.START_FAILED_MESSAGE))
        }
    }

    @Test
    fun `failure is shared with a reloaded copy`() {
        EngineStartPolicy.recordStart(flags, -1)
        val reloaded = ProcessFlags(properties::get) { key, value -> properties[key] = value }

        expectThat(EngineStartPolicy.decide(reloaded, startedPortAlive = false, bindingsAvailable = false))
            .isEqualTo(Decision.Fail(EngineStartPolicy.START_FAILED_MESSAGE))
    }

    @Test
    fun `a started engine that answers is reused`() {
        expectThat(EngineStartPolicy.recordStart(flags, 41234)).isEqualTo(41234)
        expectThat(flags.startFailed).isFalse()

        expectThat(EngineStartPolicy.decide(flags, startedPortAlive = true, bindingsAvailable = false))
            .isEqualTo(Decision.Reuse(41234))
    }

    @Test
    fun `a started engine that stopped answering is not restarted`() {
        EngineStartPolicy.recordStart(flags, 41234)

        expectThat(EngineStartPolicy.decide(flags, startedPortAlive = false, bindingsAvailable = true))
            .isEqualTo(Decision.Fail(EngineStartPolicy.STOPPED_MESSAGE))
    }

    @Test
    fun `library loaded by an unreachable class loader can't be started again`() {
        flags.loaded = true

        expectThat(EngineStartPolicy.decide(flags, startedPortAlive = false, bindingsAvailable = false))
            .isEqualTo(Decision.Fail(EngineStartPolicy.STOPPED_MESSAGE))
        expectThat(EngineStartPolicy.decide(flags, startedPortAlive = false, bindingsAvailable = true))
            .isEqualTo(Decision.Start)
    }

    @Test
    fun `out of range results count as failures`() {
        expectThat(EngineStartPolicy.recordStart(flags, 0)).isNull()
        expectThat(flags.startFailed).isTrue()
        expectThat(flags.startedPort).isNull()
    }
}
