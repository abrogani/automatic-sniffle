import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.Status

dependencies {
    implementation("androidx.core:core:1.15.0")

    // Comment if not implementing own SettingsScreen
    // No need to specify the compose version explicitly
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    // ================= END: COMPOSE UI =================

    testImplementation("io.strikt:strikt-core:0.33.0")
}

android {
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
}

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
    versionPatch = 1
    versionBuild = 0

    iconUrl = "https://i.imgur.com/JhbAqBt.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = Status.Beta
}
