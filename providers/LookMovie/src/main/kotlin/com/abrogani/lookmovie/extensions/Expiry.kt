package com.abrogani.lookmovie.extensions

/**
 * Anything below this is a Unix timestamp in seconds. 1e11 ms is 1973, while 1e11 s is year ~5138,
 * so no realistic expiry is ambiguous.
 */
private const val MAX_EPOCH_SECONDS = 100_000_000_000L

/** LookMovie reports `expires` in epoch seconds; Flixclusive's `Flag.Expires` wants epoch milliseconds. */
internal fun Long.toEpochMillis(): Long =
    if (this < MAX_EPOCH_SECONDS) this * 1000L else this
