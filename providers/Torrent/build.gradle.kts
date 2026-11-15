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

        Torrent links (magnets, .torrent files and info hashes) that other providers find are streamed through a local torrent engine, so they play like any other link. The engine is downloaded once from this provider's settings (about 5 MB).

        🚨 EXPERIMENTAL!! This provider hooks into the app's internals and may stop working after an app update. 🚨
        """.trimIndent()

    changelog =
        """
        # Torrent Provider

        - Streams torrent links from every installed provider through an on-demand TorrServer engine.
        """.trimIndent()

    versionMajor = 0
    versionMinor = 0
    versionPatch = 1
    versionBuild = 0

    requiresResources = true

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Beta
}
