package com.abrogani.flixhq.extensions

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser

internal val gson = Gson()

internal fun parseJson(json: String): JsonElement = JsonParser.parseString(json)

internal inline fun <reified T> JsonElement.toList(): List<T> {
    val array: JsonArray = when {
        isJsonArray -> asJsonArray
        isJsonObject -> JsonArray().also { it.add(this) }
        else -> return emptyList()
    }
    return array.mapNotNull { runCatching { gson.fromJson(it, T::class.java) }.getOrNull() }
}
