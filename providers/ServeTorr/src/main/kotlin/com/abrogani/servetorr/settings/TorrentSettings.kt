package com.abrogani.servetorr.settings

internal const val PORT_KEY = "port"
internal const val WIFI_ONLY_KEY = "wifi_only"
internal const val IDLE_STOP_MINUTES_KEY = "idle_stop_minutes"

internal const val ICON_URL = "https://i.imgur.com/8DkFv24.png"

internal const val DEFAULT_PORT = 47390
internal const val DEFAULT_WIFI_ONLY = false
internal const val DEFAULT_IDLE_STOP_MINUTES = 5

internal fun Int.isValidPort(): Boolean = this in 1024..65535
