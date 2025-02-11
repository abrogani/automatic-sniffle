import com.android.build.gradle.BaseExtension
import com.flixclusive.gradle.FlixclusiveProviderExtension
import com.flixclusive.gradle.getFlixclusive


buildscript {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io")
        mavenLocal() // <- For testing
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Flixclusive gradle plugin which makes everything work and builds providers
        classpath("com.github.flixclusiveorg.core-gradle:core-gradle:1.2.5")
        // Kotlin support. Remove if you want to use Java
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

fun Project.flxProvider(configuration: FlixclusiveProviderExtension.() -> Unit)
        = extensions.getFlixclusive().configuration()

fun Project.android(configuration: BaseExtension.() -> Unit)
        = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    val projectName = name.lowercase()
        .replace("-", "_")

    apply(plugin = "flx-provider")
    apply(plugin = "kotlin-android") // Remove if using Java

    // Fill out with your info
    flxProvider {
        /**
         *
         * Add the author(s) of this repository.
         *
         * Optionally, you can add your
         * own github profile link
         * */
        author(
            name = "abrogani",
            socialLink = "https://github.com/abrogani",
            image = "https://github.com/abrogani.png"
        )
        // author( ... )
        // author( ... )

        setRepository("https://github.com/abrogani/automatic-sniffle")

        id = "abrogani-$projectName"
    }

    android {
        namespace = "com.abrogani.$projectName"
    }

    dependencies {
        val implementation by configurations
        // val fatImplementation by configurations // <- use when you have non-supported libraries.
        val testImplementation by configurations
        val coreLibraryDesugaring by configurations

        coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
        implementation("androidx.compose.runtime:runtime")

        val coreStubsModule = "com.github.flixclusiveorg.core-stubs:provider"
        val coreStubsVersion = "1.2.5"

        // Stubs for all Flixclusive classes
        implementation("$coreStubsModule:$coreStubsVersion")

        // ============= START: FOR TESTING ===============
        testImplementation("$coreStubsModule:$coreStubsVersion")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
        testImplementation("junit:junit:4.13.2")
        testImplementation("io.mockk:mockk:1.13.16")
        // ============== END: FOR TESTING ================
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
