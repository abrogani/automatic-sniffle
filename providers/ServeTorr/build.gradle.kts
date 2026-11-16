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

        Magnets, .torrent files and info hashes found by other providers are streamed through a local torrent engine, so they play like any other link. Download the engine once from this provider's settings (about 9 MB).

        🚨 EXPERIMENTAL!! This provider hooks into the app's internals and may stop working after an app update. 🚨
        """.trimIndent()

    changelog =
        """
        # v0.1.0

        - Renamed to ServeTorr.
        - Own engine build, based on the latest TorrServer. It only accepts connections from this device and ignores web pages.
        - New look for the settings, with a green theme and the less common options under "Advanced".
        - Asks you to restart the app when an engine update needs it.
        - Local peer discovery setting (off by default).
        - Wi-Fi only: the engine won't start on mobile data and stops if Wi-Fi drops.
        - The engine stops by itself after a few idle minutes (configurable).
        - New torrents list: see every torrent the engine holds, with peers and speed, and remove them one by one.
        - "Clear cache" removes every torrent and cached data, including leftovers from older engines.
        - Old torrent links the app had cached before the engine was installed are cleaned up, so they play now.
        - Engine files are removed when you uninstall the provider.
        - New engine versions download automatically if you had the engine installed.

        # v0.0.2

        - Torrents are only looked up when you press play, so finding links is instant and nothing is downloaded while browsing.
        - Fixed the wrong file (or a sample) playing for many torrents.
        - A season pack without the episode you picked no longer plays a different episode.
        - The engine only starts when a torrent is played, and can be stopped from the settings.
        - "Remove engine" and "Stop all torrents" now work while the engine runs and say what they did.
        - Full engine settings: cache, read-ahead, preload, disk cache, connection and speed limits, peer port, encryption, DHT/PEX/UPnP/TCP/uTP/IPv6, trackers.
        - Fixed interception stopping after updating this provider or switching profiles.
        - Providers without settings no longer open an empty settings page.

        # v0.0.1

        - Streams torrent links from every installed provider through an on-demand TorrServer engine.
        """.trimIndent()

    versionMajor = 0
    versionMinor = 1
    versionPatch = 0
    versionBuild = 0

    iconUrl = "https://i.imgur.com/8DkFv24.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Beta
}
