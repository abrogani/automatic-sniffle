
import com.android.build.gradle.BaseExtension
import com.flixclusive.gradle.FlixclusiveProviderExtension
import com.flixclusive.gradle.getFlixclusive
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.internal.service.ServiceRegistry
import java.io.FileOutputStream
import java.net.URL


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
        classpath("com.github.flixclusiveorg.core-gradle:core-gradle:1.2.7")
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

val csJarFolder =
    gradle.gradleUserHomeDir
        .resolve("caches")
        .resolve("cloudstream")

val csJar = csJarFolder.resolve("cloudstream.jar")

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
        val androidTestImplementation by configurations
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

        // ================ CS3 Providers =================
        val cs3Providers = setOf(
            "CodeStream",
            "StreamPlay"
        )

        if (cs3Providers.contains(name)) {
            val fatImplementation by configurations // <- use when you have non-supported libraries.

            fatImplementation(files(csJar))
            fatImplementation("com.github.Blatzar:NiceHttp:0.4.11") // http library
            fatImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
            fatImplementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
            fatImplementation("io.karn:khttp-android:0.1.2")
            fatImplementation("com.uwetrottmann.tmdb2:tmdb-java:2.11.0")
            fatImplementation("me.xdrop:fuzzywuzzy:1.4.0")
            fatImplementation("org.mozilla:rhino:1.8.0")

            testImplementation("com.github.Blatzar:NiceHttp:0.4.11") // http library
            testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
            testImplementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
            testImplementation("io.karn:khttp-android:0.1.2")
            testImplementation("me.xdrop:fuzzywuzzy:1.4.0")
            testImplementation("com.faendir.rhino:rhino-android:1.6.0")
            testImplementation("com.uwetrottmann.tmdb2:tmdb-java:2.11.0")
            testImplementation(files(csJar))

            androidTestImplementation("io.mockk:mockk:1.14.2")
            androidTestImplementation("androidx.test.ext:junit:1.2.1")
            androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
            androidTestImplementation("androidx.test:runner:1.6.2")
            androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

            androidTestImplementation("com.github.Blatzar:NiceHttp:0.4.11") // http library
            androidTestImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
            androidTestImplementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
            androidTestImplementation("io.karn:khttp-android:0.1.2")
            androidTestImplementation("me.xdrop:fuzzywuzzy:1.4.0")
            androidTestImplementation("com.faendir.rhino:rhino-android:1.6.0")
            androidTestImplementation("com.uwetrottmann.tmdb2:tmdb-java:2.11.0")
            androidTestImplementation(files(csJar))
        }
        // ================ ------------- =================
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("getCSJar") {
    doFirst {
        logger.lifecycle("Fetching Cloudstream JAR")

        val tag = "pre-release"
        val apkDownloadUrl = URL("https://github.com/abrogani/cloudstream/releases/download/$tag/classes.jar")

        if (!csJarFolder.exists()) {
            csJarFolder.mkdirs()
        }

        if (csJar.exists()) {
            csJar.delete()
        }

        apkDownloadUrl.download(
            file = csJar,
            progressLogger =
                createProgressLogger(
                    project = project,
                    loggerCategory = "cloudstream",
                ),
        )
    }
}

fun createProgressLogger(
    project: Project,
    loggerCategory: String,
): ProgressLogger = createProgressLogger((project as ProjectInternal).services, loggerCategory)

fun createProgressLogger(
    services: ServiceRegistry,
    loggerCategory: String,
): ProgressLogger {
    val progressLoggerFactory = services.get(ProgressLoggerFactory::class.java)
    return progressLoggerFactory.newOperation(loggerCategory).also { it.description = loggerCategory }
}

fun URL.download(
    file: File,
    progressLogger: ProgressLogger,
) {
    progressLogger.started()
    try {
        val tempFile = File.createTempFile(file.name, ".part", file.parentFile)
        tempFile.deleteOnExit()

        val connection = this.openConnection()
        val size = connection.contentLengthLong
        val sizeText = toLengthText(size)

        connection.getInputStream().use { inputStream ->
            var finished = false
            var processedBytes: Long = 0
            try {
                FileOutputStream(tempFile).use { os ->
                    val buf = ByteArray(1024 * 10)
                    var read: Int
                    while (inputStream.read(buf).also { read = it } >= 0) {
                        os.write(buf, 0, read)
                        processedBytes += read
                        progressLogger.progress("Downloading ${toLengthText(processedBytes)}/$sizeText")
                    }
                    os.flush()
                    finished = true
                }
            } finally {
                if (finished) {
                    tempFile.renameTo(file)
                } else {
                    tempFile.delete()
                }
            }
        }
    } finally {
        progressLogger.completed()
    }
}

fun toLengthText(bytes: Long): String =
    if (bytes < 1024) {
        "$bytes B"
    } else if (bytes < 1024 * 1024) {
        (bytes / 1024).toString() + " KB"
    } else if (bytes < 1024 * 1024 * 1024) {
        String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
