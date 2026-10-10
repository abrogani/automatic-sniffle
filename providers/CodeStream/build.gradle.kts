import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.ProviderStatus
import java.util.Properties

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
        buildConfigField("String", "GHOSTX_API", "\"${properties.getProperty("GHOSTX_API")}\"")
        buildConfigField("String", "CINEMATV_API", "\"${properties.getProperty("CINEMATV_API")}\"")
        buildConfigField("String", "SFMOVIES_API", "\"${properties.getProperty("SFMOVIES_API")}\"")
        buildConfigField("String", "ZSHOW_API", "\"${properties.getProperty("ZSHOW_API")}\"")
        buildConfigField("String", "DUMP_API", "\"${properties.getProperty("DUMP_API")}\"")
        buildConfigField("String", "DUMP_KEY", "\"${properties.getProperty("DUMP_KEY")}\"")
        buildConfigField("String", "CRUNCHYROLL_BASIC_TOKEN", "\"${properties.getProperty("CRUNCHYROLL_BASIC_TOKEN")}\"")
        buildConfigField("String", "CRUNCHYROLL_REFRESH_TOKEN", "\"${properties.getProperty("CRUNCHYROLL_REFRESH_TOKEN")}\"")
        buildConfigField("String", "MOVIE_API", "\"${properties.getProperty("MOVIE_API")}\"")
        buildConfigField("String", "MultiMovies_API", "\"${properties.getProperty("MultiMovies_API")}\"")
        buildConfigField("String", "MovieDrive_API", "\"${properties.getProperty("MovieDrive_API")}\"")
        buildConfigField("String", "AsianDrama_API", "\"${properties.getProperty("AsianDrama_API")}\"")
    }
}

flxProvider {
    description = """This is an experimental attempt for a CloudStream adapter."""

    changelog =
        """
        Experimental
        """.trimIndent()

    versionMajor = 1
    versionMinor = 0
    versionPatch = 0
    versionBuild = 0

    iconUrl = "https://i.imgur.com/g4FxRfl.png"

    language = Language.Multiple

    providerType = ProviderType.All

    status = ProviderStatus.Working
}