import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus

flxProvider {
    description =
        """
        A forked clone of the old movie-web.
        
        This is a set of providers. All source code references belong to sudo-flix.
        """.trimIndent()

    changelog =
        """
        # 1.4.1
        
        - update compose BOM to 2024.09.02
        """.trimIndent()

    versionMajor = 1
    versionMinor = 4
    versionPatch = 1
    versionBuild = 2

    iconUrl = "https://i.imgur.com/dBgb2CR.png" // OPTIONAL

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Working
}
