package com.abrogani.streamplay.loader

import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.network.okhttp.request
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal data class RemotePlugin(
    val url: String,
    val version: Int,
    val sha256: String,
) {
    init {
        require(url.startsWith("https://")) { "Refusing to download over a non-https url" }
    }
}

internal object PluginIndex {
    private const val STATUS_DOWN = 0
    private val HASH = Regex("^sha256-([0-9a-fA-F]{64})$")

    fun parse(json: String, name: String = Cs3Source.PLUGIN_NAME): RemotePlugin {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
        check(root != null && root.isJsonArray) { "plugins.json is malformed" }

        val entry = root.asJsonArray
            .filter { it.isJsonObject }
            .map { it.asJsonObject }
            .filter { it.str("internalName") == name && it.int("version") != null }
            .maxByOrNull { it.int("version")!! }
            ?: throw IllegalStateException("$name not found in plugins.json")

        check((entry.int("status") ?: 1) != STATUS_DOWN) { "$name is marked as down upstream" }

        val url = entry.str("url") ?: throw IllegalStateException("plugins.json entry for $name has no url")
        val hash = entry.str("fileHash")?.let { HASH.matchEntire(it) }
            ?: throw IllegalStateException("plugins.json entry for $name has an unsupported fileHash")

        return RemotePlugin(
            url = url,
            version = entry.int("version")!!,
            sha256 = hash.groupValues[1].lowercase(),
        )
    }

    suspend fun fetch(client: OkHttpClient): RemotePlugin = withIOContext {
        val indexClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

        indexClient.request(url = Cs3Source.PLUGINS_JSON_URL).execute().use { response ->
            check(response.isSuccessful) { "Failed to fetch plugins.json [${response.code}]" }
            parse(response.body.string())
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
}
