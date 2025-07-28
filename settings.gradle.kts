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
