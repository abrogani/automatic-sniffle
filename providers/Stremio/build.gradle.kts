import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus

android {
    defaultConfig {
        vectorDrawables.useSupportLibrary = false
    }
}

flxProvider {
    description =
        """
        A flixclusive adapter for Stremio addons. Torrent addons, such as Torrentio, don't work without debrid accounts.
        """.trimIndent()

    changelog =
        """
        ### Fixes:
        - Remove built-in opensubs-v3 addon
        - Allow subtitles addons
        - Fix default metadata loading (Cinemata)
        
        #### 1.2.7-1
        - Fix resource not found error when opening provider settings
        """.trimIndent()

    versionMajor = 1
    versionMinor = 2
    versionPatch = 7
    versionBuild = 1

// Extra authors for specific provider
    // author(
    //    name = "...",
    //    githubLink = "https://github.com/...",
    // )
    // ===

    iconUrl = "https://i.imgur.com/Hoq93zL.png" // OPTIONAL

    language = Language.Multiple

    providerType = ProviderType("Stremio Addons")

    status = ProviderStatus.Working

    requiresResources = true
}
