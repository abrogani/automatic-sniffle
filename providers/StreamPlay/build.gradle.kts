import com.flixclusive.model.provider.Author
import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus

dependencies {
    // Used by the pre-built StreamPlay plugin; CloudStream's app normally supplies them.
    implementation(libs.ktor.http)
    implementation(libs.ksoup)

    // The plugin is compiled against coroutines 1.11 (plain `runBlocking` becomes `runBlockingK`),
    // but the host ships 1.10.2. Bundled here on purpose; the rest of the repo compiles against
    // the host's version. Whether the bundled copy wins depends on the host's class loader.
    implementation(libs.kotlinx.coroutines.bundled)

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
        A Flixclusive version of Phisher98's StreamPlay.
        
        REMINDER: Only works on PR-c62ada6 versions and above. WILL NOT WORK ON v2.1.3 stable!!!!!!
    """.trimIndent()

    changelog =
        """
        # WILL NOT WORK ON v2.1.3 STABLE!!!!!!
        """.trimIndent()

    versionMajor = 1
    versionMinor = 0
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