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
        Catalogs, search, metadata and cross-match for flixhqz.net, streaming through Vidmoly and Byse.

        ⚠️ The Byse server runs in a WebView and may ask you to tap play to verify you're human before its stream loads.
        """.trimIndent()

    changelog =
        """
        # v2.0.0
        - rewrite from scratch for the 1.4.0 SDK
        - add catalogs, search, metadata, IMDB cross-match
        - add Vidmoly extractor
        - add Byse extractor (WebView capture)
        """.trimIndent()

    versionMajor = 2
    versionMinor = 0
    versionPatch = 0
    versionBuild = 0

    iconUrl = "https://i.imgur.com/LNtqPTi.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Working
}
