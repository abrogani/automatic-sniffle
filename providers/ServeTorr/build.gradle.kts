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
        # v0.0.2

        - Torrent engine updated to v1.

        # v0.0.1

        First release.

        - Plays torrent links (magnets, .torrent files, info hashes) from all your other providers.
        - Torrents are only fetched when you press play; the right file or episode is picked for you.
        - Torrent engine downloaded on demand, only reachable from this device. Stops when idle, and on Wi-Fi only if you want.
        - Torrent list, cache cleanup and full engine settings.
        - Engine updates download automatically.
        """.trimIndent()

    versionMajor = 0
    versionMinor = 0
    versionPatch = 2
    versionBuild = 0

    iconUrl = "https://i.imgur.com/8DkFv24.png"

    language = Language.Multiple

    providerType = ProviderType("Torrent")

    status = ProviderStatus.Beta
}
