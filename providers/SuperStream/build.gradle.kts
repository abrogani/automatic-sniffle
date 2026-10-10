import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus
import java.util.Properties

dependencies {

    implementation(libs.bouncycastle)
    testImplementation(libs.bouncycastle)
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "SUPERSTREAM_FIRST_API", "\"${properties.getProperty("SUPERSTREAM_FIRST_API")}\"")
        buildConfigField("String", "SUPERSTREAM_SECOND_API", "\"${properties.getProperty("SUPERSTREAM_SECOND_API")}\"")
        buildConfigField("String", "SUPERSTREAM_FOURTH_API", "\"${properties.getProperty("SUPERSTREAM_FOURTH_API")}\"")
        buildConfigField("String", "SUPERSTREAM_FIFTH_API", "\"${properties.getProperty("SUPERSTREAM_FIFTH_API")}\"")
    }
}

flxProvider {
    description =
        """
        REMINDER: This provider needs configuration to work!! Go to its provider settings and configure it there.
        
        A classic streaming service with a large library of movies and TV shows, some even in 4K. Majority of the content included on this provider offers non-HLS streaming.
        """.trimIndent()

    changelog =
        """
        # v1.6.1
        
        - revert + update `getLinks` logic.
        - no more WebView Cloudflare interaction.
        - fix TV shows support
        
        **this might be my last update :')**
        """.trimIndent()

    versionMajor = 1
    versionMinor = 6
    versionPatch = 1
    versionBuild = 1

    iconUrl = "https://i.imgur.com/KgMakl9.png" // OPTIONAL

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Working

    requiresResources = true
}
