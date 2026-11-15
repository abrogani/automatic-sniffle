package com.abrogani.torrent.engine

/**
 * Where the TorrServer engine comes from: CloudStream's gomobile build of TorrServer
 * (`com.github.recloudstream:torrentserver:7861970` on jitpack). Its licence is unstated (upstream
 * TorrServer is GPL-3.0), so the native library is downloaded on demand and never bundled.
 *
 * `assets/torrserver/bindings.dex` holds the AAR's Java bindings (`go.Seq`, `torrServer.TorrServer`). They
 * can't live in the .flx dex because `go.Seq.load()` calls `System.loadLibrary("gojni")`, which resolves through
 * the defining class loader. Produced from the AAR's `classes.jar` (sha256 792029709516fde134cd8c2eead8da2aa6c9eca27185107172917d088e3c3b22) with
 * `d8 --release --min-api 23 --lib android-36/android.jar --output out classes.jar` (build-tools 36.1.0).
 */
internal object EngineSource {
    const val VERSION = "7861970"
    const val AAR_URL = "https://jitpack.io/com/github/recloudstream/torrentserver/7861970/torrentserver-7861970.aar"

    const val LIBRARY_NAME = "libgojni.so"
    const val BINDINGS_DEX_SHA256 = "5e66968e978532355dc8c43032f01bfca87a93aa63afbc4bd86425ab2f608667"

    const val SEQ_CLASS = "go.Seq"
    const val SERVER_CLASS = "torrServer.TorrServer"

    /** sha256 of the inflated `jni/<abi>/libgojni.so`. */
    val LIBRARY_SHA256 = linkedMapOf(
        "arm64-v8a" to "4e956a0a410cba3edafa22573721c1be66fdec0253c85ecf014109826c941079",
        "armeabi-v7a" to "33307814802a7f4342be67837234f3f247defb74b352d8620b43ccefed0156ee",
        "x86_64" to "fc328f34f4eeab38147ee1f981a0dd7937339e4711ed9acc8ee1688e31946054",
        "x86" to "a2202b06ed31d180a019c712da9d4f93917132169692f1b1d3ac96126d4ed63f",
    )

    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_LIBRARY_BYTES = 32L * 1024 * 1024

    /** First ABI in the device's preference order that the engine ships, or null. */
    fun selectAbi(supportedAbis: List<String>): String? =
        supportedAbis.firstOrNull { it in LIBRARY_SHA256 }

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
