package com.abrogani.servetorr.engine

import com.abrogani.servetorr.engine.EngineStartPolicy.Decision
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue

class EngineStartPolicyTest {
    private val properties = HashMap<String, String>()
    private val flags = newFlags()

    private fun newFlags() = ProcessFlags(properties::get) { key, value -> properties[key] = value }

    private fun decide(
        flags: ProcessFlags = this.flags,
        version: String = VERSION,
        alive: Boolean = false,
        bindings: Boolean = false,
    ) = EngineStartPolicy.decide(flags, version, alive, bindings)

    private fun settled(
        flags: ProcessFlags = this.flags,
        version: String = VERSION,
        port: Int? = null,
        bindings: Boolean = false,
        installed: Boolean = true,
    ) = EngineStartPolicy.settledState(flags, version, port, bindings, installed)

    @Test
    fun `fresh process starts the engine`() {
        expectThat(decide()).isEqualTo(Decision.Start)
        expectThat(settled()).isEqualTo(EngineState.Stopped)
        expectThat(settled(installed = false)).isEqualTo(EngineState.NotInstalled)
    }

    @Test
    fun `failed once means no retry in this process`() {
        expectThat(EngineStartPolicy.recordStart(flags, -1)).isNull()
        expectThat(flags.startFailed).isTrue()

        repeat(2) {
            expectThat(decide(bindings = true))
                .isEqualTo(Decision.RestartRequired(EngineStartPolicy.START_FAILED_MESSAGE))
        }
        expectThat(settled(bindings = true))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.START_FAILED_MESSAGE))
    }

    @Test
    fun `failed start after loading requires a restart`() {
        flags.recordLoaded(VERSION)
        EngineStartPolicy.recordStart(flags, -1)

        expectThat(decide(bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.START_FAILED_MESSAGE))
        expectThat(settled(bindings = true))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.START_FAILED_MESSAGE))
    }

    @Test
    fun `failure is shared with a reloaded copy`() {
        EngineStartPolicy.recordStart(flags, -1)

        expectThat(decide(flags = newFlags()))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.START_FAILED_MESSAGE))
        expectThat(settled(flags = newFlags()))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.START_FAILED_MESSAGE))
    }

    @Test
    fun `failure without installed files settles as not installed`() {
        EngineStartPolicy.recordStart(flags, -1)

        expectThat(settled(installed = false)).isEqualTo(EngineState.NotInstalled)
    }

    @Test
    fun `a started engine that answers is reused`() {
        flags.recordLoaded(VERSION)
        expectThat(EngineStartPolicy.recordStart(flags, 41234)).isEqualTo(41234)
        expectThat(flags.startFailed).isFalse()

        expectThat(decide(alive = true)).isEqualTo(Decision.Reuse(41234))
        expectThat(settled(bindings = true)).isEqualTo(EngineState.Running(41234))
    }

    @Test
    fun `a started engine that stopped answering is not restarted`() {
        flags.recordLoaded(VERSION)
        EngineStartPolicy.recordStart(flags, 41234)

        expectThat(decide(bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.STOPPED_MESSAGE))
    }

    @Test
    fun `this copy's port wins over the process flag`() {
        flags.recordLoaded(VERSION)
        EngineStartPolicy.recordStart(flags, 41234)

        expectThat(settled(port = 50000, bindings = true)).isEqualTo(EngineState.Running(50000))
    }

    @Test
    fun `library loaded by an unreachable class loader can't be started again`() {
        flags.recordLoaded(VERSION)

        expectThat(decide(bindings = false))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.UNREACHABLE_MESSAGE))
        expectThat(settled(bindings = false))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.UNREACHABLE_MESSAGE))
        expectThat(decide(bindings = true)).isEqualTo(Decision.Start)
        expectThat(settled(bindings = true)).isEqualTo(EngineState.Stopped)
    }

    @Test
    fun `stopped engine of the same version can start again in process`() {
        flags.recordLoaded(VERSION)
        EngineStartPolicy.recordStart(flags, 41234)
        flags.startedPort = null

        expectThat(decide(bindings = true)).isEqualTo(Decision.Start)
        expectThat(settled(bindings = true)).isEqualTo(EngineState.Stopped)
    }

    @Test
    fun `remove and reinstall of the same version doesn't require a restart`() {
        flags.recordLoaded(VERSION)
        EngineStartPolicy.recordStart(flags, 41234)
        flags.startedPort = null

        expectThat(settled(bindings = true, installed = false)).isEqualTo(EngineState.NotInstalled)
        expectThat(settled(bindings = true, installed = true)).isEqualTo(EngineState.Stopped)
        expectThat(decide(bindings = true)).isEqualTo(Decision.Start)
    }

    @Test
    fun `installing a new version over a loaded one requires a restart`() {
        flags.recordLoaded(OLD_VERSION)

        expectThat(settled(bindings = true, installed = true))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
        expectThat(decide(bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
    }

    @Test
    fun `a running engine of another version is not reused`() {
        flags.recordLoaded(OLD_VERSION)
        EngineStartPolicy.recordStart(flags, 41234)

        expectThat(decide(alive = true, bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
        expectThat(settled(bindings = true))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
    }

    @Test
    fun `a reloaded copy with a new version sees the old loaded version`() {
        flags.recordLoaded(OLD_VERSION)

        expectThat(settled(flags = newFlags(), bindings = true))
            .isEqualTo(EngineState.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
        expectThat(decide(flags = newFlags(), bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
        expectThat(settled(flags = newFlags(), version = OLD_VERSION, bindings = true))
            .isEqualTo(EngineState.Stopped)
    }

    @Test
    fun `version mismatch without installed files settles as not installed`() {
        flags.recordLoaded(OLD_VERSION)

        expectThat(settled(installed = false)).isEqualTo(EngineState.NotInstalled)
    }

    @Test
    fun `version mismatch wins over a failed start`() {
        flags.recordLoaded(OLD_VERSION)
        EngineStartPolicy.recordStart(flags, -1)

        expectThat(decide(bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
    }

    @Test
    fun `loaded without a recorded version counts as another version`() {
        flags.loaded = true

        expectThat(flags.loadedVersion).isNull()
        expectThat(decide(bindings = true))
            .isEqualTo(Decision.RestartRequired(EngineStartPolicy.VERSION_CHANGED_MESSAGE))
    }

    @Test
    fun `recording a load stores the version and the loaded flag`() {
        flags.recordLoaded(VERSION)

        expectThat(newFlags().loaded).isTrue()
        expectThat(newFlags().loadedVersion).isEqualTo(VERSION)
        expectThat(EngineStartPolicy.versionMismatch(newFlags(), VERSION)).isFalse()
        expectThat(EngineStartPolicy.versionMismatch(newFlags(), OLD_VERSION)).isTrue()
    }

    @Test
    fun `out of range results count as failures`() {
        expectThat(EngineStartPolicy.recordStart(flags, 0)).isNull()
        expectThat(flags.startFailed).isTrue()
        expectThat(flags.startedPort).isNull()
    }

    private companion object {
        const val VERSION = "new"
        const val OLD_VERSION = "old"
    }
}
