
import com.flixclusive.model.provider.Author
import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.Status
import org.jetbrains.kotlin.konan.properties.Properties

dependencies {
    implementation("androidx.core:core:1.15.0")

    // Comment if not implementing own SettingsScreen
    // No need to specify the compose version explicitly
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    // ================= END: COMPOSE UI =================


    //noinspection Aligned16KB
    fatImplementation("app.cash.quickjs:quickjs-android:0.9.2")
    //noinspection Aligned16KB
    androidTestImplementation("app.cash.quickjs:quickjs-android:0.9.2")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())


        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
        buildConfigField("String", "CINEMATV_API", "\"${properties.getProperty("CINEMATV_API")}\"")
        buildConfigField("String", "SFMOVIES_API", "\"${properties.getProperty("SFMOVIES_API")}\"")
        buildConfigField("String", "ZSHOW_API", "\"${properties.getProperty("ZSHOW_API")}\"")
        buildConfigField("String", "DUMP_API", "\"${properties.getProperty("DUMP_API")}\"")
        buildConfigField("String", "DUMP_KEY", "\"${properties.getProperty("DUMP_KEY")}\"")
        buildConfigField("String", "CRUNCHYROLL_BASIC_TOKEN", "\"${properties.getProperty("CRUNCHYROLL_BASIC_TOKEN")}\"")
        buildConfigField("String", "CRUNCHYROLL_REFRESH_TOKEN", "\"${properties.getProperty("CRUNCHYROLL_REFRESH_TOKEN")}\"")
        buildConfigField("String", "MOVIE_API", "\"${properties.getProperty("MOVIE_API")}\"")
        buildConfigField("String", "ANICHI_API", "\"${properties.getProperty("ANICHI_API")}\"")
        buildConfigField("String", "Whvx_API", "\"${properties.getProperty("Whvx_API")}\"")
        buildConfigField("String", "CatflixAPI", "\"${properties.getProperty("CatflixAPI")}\"")
        buildConfigField("String", "ConsumetAPI", "\"${properties.getProperty("ConsumetAPI")}\"")
        buildConfigField("String", "FlixHQAPI", "\"${properties.getProperty("FlixHQAPI")}\"")
        buildConfigField("String", "WhvxAPI", "\"${properties.getProperty("WhvxAPI")}\"")
        buildConfigField("String", "WhvxT", "\"${properties.getProperty("WhvxT")}\"")
        buildConfigField("String", "SharmaflixApikey", "\"${properties.getProperty("SharmaflixApikey")}\"")
        buildConfigField("String", "SharmaflixApi", "\"${properties.getProperty("SharmaflixApi")}\"")
        buildConfigField("String", "Theyallsayflix", "\"${properties.getProperty("Theyallsayflix")}\"")
        buildConfigField("String", "GojoAPI", "\"${properties.getProperty("GojoAPI")}\"")
        buildConfigField("String", "HianimeAPI", "\"${properties.getProperty("HianimeAPI")}\"")
        buildConfigField("String", "Vidsrccc", "\"${properties.getProperty("Vidsrccc")}\"")
        buildConfigField("String", "WASMAPI", "\"${properties.getProperty("WASMAPI")}\"")
        buildConfigField("String", "KissKh", "\"${properties.getProperty("KissKh")}\"")
        buildConfigField("String", "KisskhSub", "\"${properties.getProperty("KisskhSub")}\"")
        buildConfigField("String", "SUPERSTREAM_FIRST_API", "\"${properties.getProperty("SUPERSTREAM_FIRST_API")}\"")
        buildConfigField("String", "SUPERSTREAM_SECOND_API", "\"${properties.getProperty("SUPERSTREAM_SECOND_API")}\"")
        buildConfigField("String", "StreamPlayAPI", "\"${properties.getProperty("StreamPlayAPI")}\"")
        buildConfigField("String", "PROXYAPI", "\"${properties.getProperty("PROXYAPI")}\"")
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

    status = Status.Working
}