package com.abrogani.servetorr.engine

import java.security.MessageDigest

internal object EngineSource {
    const val VERSION = "1"
    const val AAR_URL = "https://github.com/abrogani/servetorr-engine/releases/download/v$VERSION/torrserver.aar"

    const val LIBRARY_NAME = "libgojni.so"
    const val DEX_ENTRY = "classes.dex"
    const val DEX_NAME = "bindings.dex"
    const val DEX_SHA256 = "541bf8a6cf35343c32b39b78707b3e19b3b32cf9a5a6ef0e49a071fba9b5acf1"

    const val SEQ_CLASS = "go.Seq"
    const val SERVER_CLASS = "torrServer.TorrServer"

    val LIBRARY_SHA256 = linkedMapOf(
        "arm64-v8a" to "e8ccc44e1a464bea670b27b53e69a43b31a511a3bcf672fd35d823204a7d8f4b",
        "armeabi-v7a" to "38df68f5ffd6f6e12f3241570f2a28f7f692f988f4862011c8888f4edda7dd92",
        "x86_64" to "c75ec94861d510c31a9f2f67c634f4a242895ca9f8b8f56666a1449110e7f37d",
        "x86" to "9e14803e9c411ec8c10d89230ce938a58048d8db7b5f7c71fce2b2e04d5e2d85",
    )

    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_LIBRARY_BYTES = 48L * 1024 * 1024
    const val MAX_DEX_BYTES = 4L * 1024 * 1024

    data class EngineFile(val entryName: String, val fileName: String, val sha256: String, val maxBytes: Long)

    fun selectAbi(supportedAbis: List<String>): String? =
        supportedAbis.firstOrNull { it in LIBRARY_SHA256 }

    fun processAbis(is64Bit: Boolean, abis64: List<String>, abis32: List<String>): List<String> =
        if (is64Bit) abis64 else abis32

    fun entryName(abi: String) = "jni/$abi/$LIBRARY_NAME"

    fun engineFiles(abi: String): List<EngineFile>? {
        val library = LIBRARY_SHA256[abi] ?: return null
        return listOf(
            EngineFile(entryName(abi), LIBRARY_NAME, library, MAX_LIBRARY_BYTES),
            EngineFile(DEX_ENTRY, DEX_NAME, DEX_SHA256, MAX_DEX_BYTES),
        )
    }

    fun pinFingerprint(abi: String): String? =
        engineFiles(abi)?.let { files -> fingerprint(VERSION, files.map { it.fileName to it.sha256 }) }

    fun fingerprint(version: String, hashes: List<Pair<String, String>>): String {
        val text = buildString {
            append(version)
            hashes.sortedBy { it.first }.forEach { (name, sha256) -> append('\n').append(name).append('=').append(sha256.lowercase()) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "https://tracker2.ctix.cn/announce",
        "https://tracker1.520.jp:443/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://explodie.org:6969/announce",
        "https://tracker.gbitt.info:443/announce",
        "http://tracker.gbitt.info:80/announce",
        "udp://uploads.gamecoast.net:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.dump.cl:6969/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://opentracker.io:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
    )
}
