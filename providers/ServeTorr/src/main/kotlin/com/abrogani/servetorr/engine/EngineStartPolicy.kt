package com.abrogani.servetorr.engine

internal class ProcessFlags(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    var startedPort: Int?
        get() = read(PORT_PROPERTY)?.toIntOrNull()
        set(value) = write(PORT_PROPERTY, value?.toString().orEmpty())

    var loaded: Boolean
        get() = read(LOADED_PROPERTY) == "true"
        set(value) = write(LOADED_PROPERTY, value.toString())

    var loadedVersion: String?
        get() = read(LOADED_VERSION_PROPERTY)?.takeIf { it.isNotEmpty() }
        set(value) = write(LOADED_VERSION_PROPERTY, value.orEmpty())

    var startFailed: Boolean
        get() = read(FAILED_PROPERTY) == "true"
        set(value) = write(FAILED_PROPERTY, value.toString())

    fun recordLoaded(version: String) {
        loadedVersion = version
        loaded = true
    }

    companion object {
        private const val PORT_PROPERTY = "abrogani.servetorr.engine.port"
        private const val LOADED_PROPERTY = "abrogani.servetorr.engine.loaded"
        private const val LOADED_VERSION_PROPERTY = "abrogani.servetorr.engine.loadedVersion"
        private const val FAILED_PROPERTY = "abrogani.servetorr.engine.failed"

        val system = ProcessFlags(System::getProperty) { key, value -> System.setProperty(key, value) }
    }
}

internal class EngineRestartRequired(val reason: String) : IllegalStateException(reason)

internal object EngineStartPolicy {
    const val START_FAILED_MESSAGE = "The torrent engine couldn't start. Restart the app to try again."
    const val STOPPED_MESSAGE = "The torrent engine stopped responding. Restart the app to start it again."
    const val VERSION_CHANGED_MESSAGE =
        "A different version of the torrent engine is already loaded. Restart the app to use the installed one."
    const val UNREACHABLE_MESSAGE =
        "The torrent engine was loaded by an older copy of this provider. Restart the app to start it again."

    sealed interface Decision {
        data class Reuse(val port: Int) : Decision
        data object Start : Decision
        data class RestartRequired(val reason: String) : Decision
    }

    fun versionMismatch(flags: ProcessFlags, version: String): Boolean =
        flags.loaded && flags.loadedVersion != version

    fun restartReason(flags: ProcessFlags, version: String, bindingsAvailable: Boolean): String? =
        when {
            versionMismatch(flags, version) -> VERSION_CHANGED_MESSAGE
            flags.startFailed -> START_FAILED_MESSAGE
            flags.loaded && !bindingsAvailable -> UNREACHABLE_MESSAGE
            else -> null
        }

    fun decide(
        flags: ProcessFlags,
        version: String,
        startedPortAlive: Boolean,
        bindingsAvailable: Boolean,
    ): Decision {
        val started = flags.startedPort
        if (versionMismatch(flags, version)) return Decision.RestartRequired(VERSION_CHANGED_MESSAGE)
        return when {
            started != null && startedPortAlive -> Decision.Reuse(started)
            started != null -> Decision.RestartRequired(STOPPED_MESSAGE)
            else -> restartReason(flags, version, bindingsAvailable)?.let(Decision::RestartRequired) ?: Decision.Start
        }
    }

    fun settledState(
        flags: ProcessFlags,
        version: String,
        port: Int?,
        bindingsAvailable: Boolean,
        installed: Boolean,
    ): EngineState {
        val running = port ?: flags.startedPort
        return when {
            versionMismatch(flags, version) ->
                if (installed) EngineState.RestartRequired(VERSION_CHANGED_MESSAGE) else EngineState.NotInstalled
            running != null -> EngineState.Running(running)
            !installed -> EngineState.NotInstalled
            else -> restartReason(flags, version, bindingsAvailable)?.let(EngineState::RestartRequired)
                ?: EngineState.Stopped
        }
    }

    fun recordStart(flags: ProcessFlags, result: Long): Int? =
        if (result in 1..65535) {
            result.toInt().also { flags.startedPort = it }
        } else {
            flags.startFailed = true
            null
        }
}
