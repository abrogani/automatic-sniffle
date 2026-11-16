package com.abrogani.mytorrentclient.engine

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

    var startFailed: Boolean
        get() = read(FAILED_PROPERTY) == "true"
        set(value) = write(FAILED_PROPERTY, value.toString())

    companion object {
        private const val PORT_PROPERTY = "abrogani.mytorrentclient.engine.port"
        private const val LOADED_PROPERTY = "abrogani.mytorrentclient.engine.loaded"
        private const val FAILED_PROPERTY = "abrogani.mytorrentclient.engine.failed"

        val system = ProcessFlags(System::getProperty) { key, value -> System.setProperty(key, value) }
    }
}

internal object EngineStartPolicy {
    const val START_FAILED_MESSAGE = "The torrent engine couldn't start. Restart the app to try again."
    const val STOPPED_MESSAGE = "The torrent engine stopped responding. Restart the app to start it again."

    sealed interface Decision {
        data class Reuse(val port: Int) : Decision
        data object Start : Decision
        data class Fail(val message: String) : Decision
    }

    fun decide(flags: ProcessFlags, startedPortAlive: Boolean, bindingsAvailable: Boolean): Decision {
        val started = flags.startedPort
        return when {
            started != null && startedPortAlive -> Decision.Reuse(started)
            started != null -> Decision.Fail(STOPPED_MESSAGE)
            flags.startFailed -> Decision.Fail(START_FAILED_MESSAGE)
            flags.loaded && !bindingsAvailable -> Decision.Fail(STOPPED_MESSAGE)
            else -> Decision.Start
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
