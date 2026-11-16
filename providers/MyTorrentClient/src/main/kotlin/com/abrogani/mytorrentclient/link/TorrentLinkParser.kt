package com.abrogani.mytorrentclient.link

import java.net.URLDecoder

internal object TorrentLinkParser {
    private val HEX_HASH = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32_HASH = Regex("^[A-Za-z2-7]{32}$")
    private val XT_KEY = Regex("^xt\\.\\d+$")
    private val TR_KEY = Regex("^tr(\\.\\d+)?$")
    private val TORRENT_FILE = Regex("^.+\\.torrent$", RegexOption.IGNORE_CASE)
    private const val BTIH_PREFIX = "urn:btih:"
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun parse(url: String): TorrentLink? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

        return when {
            trimmed.startsWith("magnet:", ignoreCase = true) -> parseMagnet(trimmed)
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> parseTorrentUrl(trimmed)
            else -> parseBareHash(trimmed)
        }
    }

    fun normalizeInfoHash(hash: String): String? =
        when {
            HEX_HASH.matches(hash) -> hash.lowercase()
            BASE32_HASH.matches(hash) -> base32ToHex(hash.uppercase())
            else -> null
        }

    fun trackers(url: String): List<String> {
        if (!url.trim().startsWith("magnet:", ignoreCase = true)) return emptyList()
        return magnetParams(url.trim())
            .filter { (key, _) -> TR_KEY.matches(key) }
            .map { (_, value) -> decode(value).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun magnetParams(url: String): List<Pair<String, String>> =
        url
            .substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }

    private fun parseMagnet(url: String): TorrentLink? {
        val params = magnetParams(url)
        if (params.isEmpty()) return null

        val infoHash =
            params
                .asSequence()
                .filter { (key, _) -> key == "xt" || XT_KEY.matches(key) }
                .map { (_, value) -> decode(value) }
                .filter { it.startsWith(BTIH_PREFIX, ignoreCase = true) }
                .firstNotNullOfOrNull { normalizeInfoHash(it.substring(BTIH_PREFIX.length)) }
                ?: return null

        val displayName =
            params
                .firstOrNull { (key, _) -> key == "dn" }
                ?.let { (_, value) -> decode(value).trim() }
                ?.takeIf { it.isNotEmpty() }

        return TorrentLink(
            source = url,
            infoHash = infoHash,
            fileIndex = null,
            displayName = displayName,
        )
    }

    private fun parseTorrentUrl(url: String): TorrentLink? {
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null

        val fileName =
            afterScheme
                .removePrefix(authority)
                .substringBefore('#')
                .substringBefore('?')
                .substringAfterLast('/')
        if (!TORRENT_FILE.matches(fileName)) return null

        return TorrentLink(
            source = url,
            infoHash = null,
            fileIndex = null,
            displayName = null,
        )
    }

    private fun parseBareHash(value: String): TorrentLink? {
        val hex = normalizeInfoHash(value) ?: return null
        return TorrentLink(
            source = "magnet:?xt=$BTIH_PREFIX$hex",
            infoHash = hex,
            fileIndex = null,
            displayName = null,
        )
    }

    private fun base32ToHex(value: String): String? {
        val bytes = ByteArray(20)
        var buffer = 0L
        var bits = 0
        var index = 0
        for (char in value) {
            val digit = BASE32_ALPHABET.indexOf(char)
            if (digit < 0) return null
            buffer = (buffer shl 5) or digit.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes[index++] = (buffer shr bits and 0xFF).toByte()
            }
        }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
