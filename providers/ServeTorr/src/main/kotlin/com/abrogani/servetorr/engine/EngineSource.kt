package com.abrogani.servetorr.engine

internal object EngineSource {
    const val VERSION = "st-1"
    const val AAR_URL = "https://github.com/abrogani/automatic-sniffle/releases/download/servetorr-engine-$VERSION/torrserver.aar"

    const val LIBRARY_NAME = "libgojni.so"
    const val BINDINGS_DEX_SHA256 = "541bf8a6cf35343c32b39b78707b3e19b3b32cf9a5a6ef0e49a071fba9b5acf1"

    const val SEQ_CLASS = "go.Seq"
    const val SERVER_CLASS = "torrServer.TorrServer"

    val LIBRARY_SHA256 = linkedMapOf(
        "arm64-v8a" to "c8da771eaceb1f76be47bd6d524198e03a00fd60307e9f1c674466a42f6846cc",
        "armeabi-v7a" to "0f8ea8aebf10b60c495262d3b0781fae6e7960280bde35c73680be1161482b62",
        "x86_64" to "403b8c71ad9ec6cd1884cbcf69952b5db8189beaece62ff51b18ec1b3b8c8f7e",
        "x86" to "82eeb7860ca3c71e73ec8db28d3e012220548f73dbe9076aaa1775b407498488",
    )

    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_LIBRARY_BYTES = 48L * 1024 * 1024

    fun selectAbi(supportedAbis: List<String>): String? =
        supportedAbis.firstOrNull { it in LIBRARY_SHA256 }

    fun processAbis(is64Bit: Boolean, abis64: List<String>, abis32: List<String>): List<String> =
        if (is64Bit) abis64 else abis32

    fun entryName(abi: String) = "jni/$abi/$LIBRARY_NAME"

    val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://opentracker.io:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "https://tracker.tamersunion.org:443/announce",
    )
}
