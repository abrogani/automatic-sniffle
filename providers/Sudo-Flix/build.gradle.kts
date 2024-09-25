import com.flixclusive.model.provider.Language
import com.flixclusive.model.provider.ProviderType
import com.flixclusive.model.provider.Status

flxProvider {
    description.set("""
        A forked clone of the old movie-web.
        
        This is a set of providers. All source code references belong to sudo-flix.
    """.trimIndent())

    changelog.set("""
        # v1.4.0
        
        Fixed some extractors:
        - StreamWish
        - FileLions
        - MixDrop
        - VTube (updated)
        - CloseLoad (subtitles issue)
    """.trimIndent())

    versionMajor = 1
    versionMinor = 4
    versionPatch = 1
    versionBuild = 1

    // Extra authors for specific provider
     author(
        name = "sussy-code",
        socialLink = "https://github.com/sussy-code",
     )
    // ===

    iconUrl.set("https://i.imgur.com/dBgb2CR.png") // OPTIONAL

    language.set(Language.Multiple)

    providerType.set(ProviderType.All)

    status.set(Status.Working)
}

