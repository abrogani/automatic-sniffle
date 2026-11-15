package com.abrogani.torrent.link

import java.net.URLDecoder

/*
 * File hints are deliberately not turned into [TorrentLink.fileIndex]. BEP 53 `so=` and the
 * `index=`/`fileIdx=` params other apps emit (Stremio, torrent-stream) count files in the
 * torrent's info-dict order, from 0. TorrServer numbers `file_stats[].id` from 1 after sorting
 * the files by path (server/torr/torrent.go, Status(): `sort.Slice(files, CompareStrings)` then
 * `Id: i + 1`), so the two only coincide by luck, and a wrong guess plays the wrong episode.
 * Leaving fileIndex null makes the rewriter resolve the file list instead.
 */
internal object TorrentLinkParser {
    private val HEX_HASH = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32_HASH = Regex("^[A-Za-z2-7]{32}$")
    private val XT_KEY = Regex("^xt\\.\\d+$")
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

    /** Lowercase 40-char hex for a 40-hex or 32-base32 BitTorrent v1 info hash, else null. */
    fun normalizeInfoHash(hash: String): String? =
        when {
            HEX_HASH.matches(hash) -> hash.lowercase()
            BASE32_HASH.matches(hash) -> base32ToHex(hash.uppercase())
            else -> null
        }

    private fun parseMagnet(url: String): TorrentLink? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null

        val params =
            query
                .substringBefore('#')
                .split('&')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }

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
