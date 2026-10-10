import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus

android {}

flxProvider {
    description =
        """
        NOTICE: This provider uses WebView to scrape content. This might lag.
        
        Stream seamlessly in adjustable HD resolution (1080p) with blazing-fast loading speeds. Offers HLS media.
        """.trimIndent()

    changelog =
        """
        # v1.0.3 - patch2
        - update compose BOM to 2024.09.02
        """.trimIndent()

    versionMajor = 1
    versionMinor = 0
    versionPatch = 3
    versionBuild = 2

    iconUrl = "https://i.imgur.com/LNtqPTi.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Down
}
