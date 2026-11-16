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
        ⚠️ The provider MIGHT prompt you to finish a captcha if it is required by the website. If you do not want to solve captchas, please don't use this provider ⚠️
        """.trimIndent()

    versionMajor = 0
    versionMinor = 0
    versionPatch = 5
    versionBuild = 0

    iconUrl = "https://i.imgur.com/JhbAqBt.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Working
}
