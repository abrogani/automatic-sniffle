plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "automatic-sniffler"

include(
    "CodeStream",
    "StreamPlay",
    "SuperStream",
    "FlixHQ",
    "Sudo-Flix",
    "Stremio",
    "LookMovie",
)

rootProject.children.forEach {
    it.projectDir = file("providers/${it.name}")
}
