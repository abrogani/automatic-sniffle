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
        🚨🚨 EXPERIMENTAL!! MIGHT NOT WORK!! This provider uses WebView to scrape content; it might lag. 🚨🚨
        
        ⚠️ The provider will prompt you to finish a captcha if it is required by the website. If you do not want to solve captchas, please don't use this provider ⚠️
        """.trimIndent()

    changelog =
        """
        # LookMovie Provider
        
        This is an experimental attempt for LookMovie web scraping provider.
        """.trimIndent()

    versionMajor = 0
    versionMinor = 0
    versionPatch = 4
    versionBuild = 0

    iconUrl = "https://i.imgur.com/JhbAqBt.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Beta
}
