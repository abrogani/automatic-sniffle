import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.Status

dependencies {
    implementation("androidx.core:core:1.15.0")
    /**
     * Custom dependencies for each provider should be implemented here.
     * */
    // implementation( ... )

    // Comment if not implementing own SettingsScreen
    // No need to specify the compose version explicitly
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")

    implementation("io.coil-kt.coil3:coil:3.0.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.0")
    // ================= END: COMPOSE UI =================
}

android {
    buildFeatures {
        compose = true
    }

    defaultConfig {
        vectorDrawables.useSupportLibrary = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
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
        """.trimIndent()

    versionMajor = 1
    versionMinor = 2
    versionPatch = 7
    versionBuild = 0


    // Extra authors for specific provider
    // author(
    //    name = "...",
    //    githubLink = "https://github.com/...",
    // )
    // ===

    iconUrl = "https://i.imgur.com/Hoq93zL.png" // OPTIONAL

    language = Language.Multiple

    providerType = ProviderType("Stremio Addons")

    status = Status.Working

    requiresResources = true
}
