import com.flixclusive.model.provider.Author
import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus

dependencies {
    implementation(libs.ktor.http)
    implementation(libs.ksoup)

//noinspection Aligned16KB
    implementation(libs.quickjs)
    //noinspection Aligned16KB
    androidTestImplementation(libs.quickjs)
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

flxProvider {
    description = """
        Phisher98's StreamPlay for Flixclusive: a multi-source aggregator for movies, TV shows and anime.

        The StreamPlay plugin is downloaded the first time the provider is used and can be kept up to date from this provider's settings. Leave "Update automatically" on, or turn it off to get a notification when an update is available.
    """.trimIndent()

    changelog =
        """
        # v1.1.0

        - The StreamPlay plugin is now downloaded on first use instead of being bundled, and can be updated without a new release of this provider.
        - New provider settings: installed and latest version, "Update automatically" (on by default), "Check for updates" and "Update now".
        - With automatic updates off, a notification tells you when an update is available.
        - A new build that fails to load is rolled back and not retried until you choose "Try again".
        - If the downloaded plugin is missing or corrupted, it is downloaded again.
        - Fixed the "No base context" error in sources that use a web view.
        - Provider API updated to core-stubs 1.4.0.
        """.trimIndent()

    versionMajor = 1
    versionMinor = 1
    versionPatch = 0
    versionBuild = 0

    iconUrl = "https://i3.wp.com/yt3.googleusercontent.com/ytc/AIdro_nCBArSmvOc6o-k2hTYpLtQMPrKqGtAw_nC20rxm70akA=s900-c-k-c0x00ffffff-no-rj?ssl=1"

    authors.set(
        listOf(
            Author(
                name = "phisher98",
                socialLink = "https://github.com/phisher98",
                image = "https://github.com/phisher98.png"
            )
        )
    )

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Working
}