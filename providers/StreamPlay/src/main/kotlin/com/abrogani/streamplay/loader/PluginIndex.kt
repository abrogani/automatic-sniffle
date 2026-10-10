package com.abrogani.streamplay.loader

import com.flixclusive.core.util.coroutines.FlxDispatchers.Companion.withIOContext
import com.flixclusive.core.util.network.okhttp.request
import com.google.gson.JsonParser
import okhttp3.OkHttpClient

internal data class RemotePlugin(
    val url: String,
    val version: Int,
    val sha256: String,
)

internal object PluginIndex {
    private const val STATUS_DOWN = 0

    fun parse(json: String, name: String = Cs3Source.PLUGIN_NAME): RemotePlugin {
        val entry = JsonParser.parseString(json).asJsonArray
            .map { it.asJsonObject }
            .firstOrNull { it.get("internalName")?.asString == name }
            ?: throw IllegalStateException("$name not found in plugins.json")

        check((entry.get("status")?.asInt ?: 1) != STATUS_DOWN) { "$name is marked as down upstream" }

        return RemotePlugin(
            url = entry.get("url").asString,
            version = entry.get("version").asInt,
            sha256 = entry.get("fileHash").asString.removePrefix("sha256-").lowercase(),
        )
    }

    suspend fun fetch(client: OkHttpClient): RemotePlugin = withIOContext {
        client.request(url = Cs3Source.PLUGINS_JSON_URL).execute().use { response ->
            check(response.isSuccessful) { "Failed to fetch plugins.json [${response.code}]" }
            parse(response.body.string())
        }
    }
}
