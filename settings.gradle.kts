pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "flx-provider") {
                useModule("com.github.flixclusiveorg.core-gradle:core-gradle:${requested.version}")
            }
        }
    }

    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io")
        mavenLocal() // <- For testing
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        mavenLocal() // <- For testing
    }
}

rootProject.name = "automatic-sniffle"

/**
 * This file sets what projects are included.
 * Every time you add a new project, you must add it
 * to the includes below.
 * */
include(
    "CodeStream",
    "StreamPlay",
    "SuperStream",
    "FlixHQ",
    "Sudo-Flix",
    "LookMovie",
)

rootProject.children.forEach {
    it.projectDir = file("providers/${it.name}")
}
