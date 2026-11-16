import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus

dependencies {

    testImplementation(libs.strikt)
}

android {}

flxProvider {
    description =
        """
        Plays torrent links from your other providers.

        Magnets, .torrent files and info hashes found by other providers are streamed through a local torrent engine, so they play like any other link. Download the engine once from this provider's settings (about 5 MB).

        ⚠️ While a torrent plays, the engine is reachable from other devices on your network without a password.

        🚨 EXPERIMENTAL!! This provider hooks into the app's internals and may stop working after an app update. 🚨
        """.trimIndent()

    changelog =
        """
        # v0.0.2

        - Torrents are only looked up when you press play, so finding links is instant and nothing is downloaded while browsing.
        - Fixed the wrong file (or a sample) playing for many torrents.
        - A season pack without the episode you picked no longer plays a different episode.
        - The engine only starts when a torrent is played.
        - Full engine settings: cache, read-ahead, preload, disk cache, connection and speed limits, peer port, encryption, DHT/PEX/UPnP/TCP/uTP/IPv6, trackers.
        - Fixed interception stopping after updating this provider or switching profiles.
        - Providers without settings no longer open an empty settings page.

        # v0.0.1

        - Streams torrent links from every installed provider through an on-demand TorrServer engine.
        """.trimIndent()

    versionMajor = 0
    versionMinor = 0
    versionPatch = 2
    versionBuild = 0

    requiresResources = true

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Beta
}
