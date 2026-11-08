package com.abrogani.lookmovie.extensions

/**
 * Extracts the JavaScript object assigned to `window['<key>']` in [html], e.g. `movie_storage`
 * or `show_storage`. Braces are balanced (quoted strings are skipped), so nested objects work.
 * */
internal fun extractStorageObject(html: String, key: String): String? {
    val marker = Regex("window\\['${Regex.escape(key)}']\\s*=\\s*\\{").find(html) ?: return null
    val start = marker.range.last // index of the opening brace

    var depth = 0
    var quote: Char? = null
    var i = start
    while (i < html.length) {
        val c = html[i]
        if (quote != null) {
            if (c == '\\') i++ else if (c == quote) quote = null
        } else {
            when (c) {
                '\'', '"' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        i++
    }

    return null
}

/**
 * Parses `window['<key>']` of [html] into [T], or returns null when the page doesn't have it.
 *
 * Gson is lenient by default, which the unquoted keys and single quotes of JS objects need.
 * */
internal inline fun <reified T> parseStorage(html: String, key: String): T? =
    extractStorageObject(html, key)?.let { fromJson<T>(it) }
