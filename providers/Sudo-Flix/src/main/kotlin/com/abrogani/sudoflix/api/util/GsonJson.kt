package com.abrogani.sudoflix.api.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import okhttp3.Response

/**
 * The SDK's `fromJson` is kotlinx-serialization based and these DTOs are Gson-annotated
 * (not `@Serializable`) or use untyped maps, so parse them with Gson directly.
 */
internal val gson = Gson()

internal inline fun <reified T> fromJson(json: String): T =
    gson.fromJson(json, object : TypeToken<T>() {}.type)

internal inline fun <reified T> fromJson(element: JsonElement): T =
    gson.fromJson(element, object : TypeToken<T>() {}.type)

internal inline fun <reified T> Response.fromJson(
    errorMessage: String = "Response must not be empty.",
): T {
    val string = body.string().takeIf { it.isNotEmpty() }

    requireNotNull(string) { errorMessage }
    return gson.fromJson(string, object : TypeToken<T>() {}.type)
}
