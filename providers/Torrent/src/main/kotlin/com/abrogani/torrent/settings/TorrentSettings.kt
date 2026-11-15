package com.abrogani.torrent.settings

internal const val PORT_KEY = "port"
internal const val INTERCEPT_KEY = "intercept_links"

internal const val DEFAULT_PORT = 8090
internal const val DEFAULT_INTERCEPT = true

internal fun Int.isValidPort(): Boolean = this in 1024..65535
