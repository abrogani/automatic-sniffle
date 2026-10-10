import com.flixclusive.gradle.util.android
import com.flixclusive.gradle.util.flxProvider
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.internal.service.ServiceRegistry
import java.io.FileOutputStream
import java.net.URI
import java.net.URL

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.flixclusive.provider) apply false
}

val csJarFolder =
    gradle.gradleUserHomeDir
        .resolve("caches")
        .resolve("cloudstream")

val csJar = csJarFolder.resolve("cloudstream.jar")

// Providers that are CloudStream (CS3) adapters
val cs3Providers = setOf(
    "CodeStream",
    "StreamPlay"
)

subprojects {
    val projectName = name.lowercase()
        .replace("-", "_")

    apply(plugin = "flx-provider")

    plugins.withId("com.android.library") {
        android {
            compileSdk = 36
            namespace = "com.abrogani.$projectName"

            defaultConfig {
                minSdk = 23
                testOptions.targetSdk = 36
            }
        }
    }

    plugins.withId("flx-provider") {
        flxProvider {
            /**
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

            setRepository("https://github.com/abrogani/automatic-sniffle")

            id = "abrogani-$projectName"
        }
    }

    dependencies {
        val compileOnly by configurations
        val implementation by configurations
        val testImplementation by configurations
        val androidTestImplementation by configurations
        val coreLibraryDesugaring by configurations

        coreLibraryDesugaring(rootProject.libs.desugar.jdk.libs)

        // Stubs for all Flixclusive classes. NEVER `implementation`.
        compileOnly(rootProject.libs.core.stubs.provider)

        // Libraries shipped by the host app
        compileOnly(platform(rootProject.libs.compose.bom))
        compileOnly(rootProject.libs.compose.material3)
        compileOnly(rootProject.libs.compose.foundation)
        compileOnly(rootProject.libs.compose.ui)
        compileOnly(rootProject.libs.compose.runtime)
        compileOnly(rootProject.libs.compose.activity)
        compileOnly(rootProject.libs.coil.compose)
        compileOnly(rootProject.libs.datastore)
        compileOnly(rootProject.libs.okhttp)
        compileOnly(rootProject.libs.kotlinx.coroutines)
        compileOnly(rootProject.libs.gson)

        // NOT shipped by the host app, must be bundled in the `.flx`
        implementation(rootProject.libs.jsoup)

        // ============= START: FOR TESTING ===============
        testImplementation(rootProject.libs.core.stubs.provider)
        testImplementation(rootProject.libs.okhttp)
        testImplementation(rootProject.libs.gson)
        testImplementation(rootProject.libs.jsoup)
        testImplementation(rootProject.libs.kotlinx.coroutines.test)
        testImplementation(rootProject.libs.junit)
        testImplementation(rootProject.libs.mockk)
        // ============== END: FOR TESTING ================

        // ================ CS3 Providers =================
        if (cs3Providers.contains(name)) {
            implementation(files(csJar))
            implementation(rootProject.libs.nicehttp)
            implementation(rootProject.libs.jackson.module.kotlin)
            implementation(rootProject.libs.jackson.databind)
            implementation(rootProject.libs.khttp)
            implementation(rootProject.libs.tmdb.java)
            implementation(rootProject.libs.fuzzywuzzy)
            implementation(rootProject.libs.rhino)

            testImplementation(files(csJar))
            testImplementation(rootProject.libs.nicehttp)
            testImplementation(rootProject.libs.jackson.module.kotlin)
            testImplementation(rootProject.libs.jackson.databind)
            testImplementation(rootProject.libs.khttp)
            testImplementation(rootProject.libs.tmdb.java)
            testImplementation(rootProject.libs.fuzzywuzzy)
            testImplementation(rootProject.libs.rhino.android)

            androidTestImplementation(files(csJar))
            androidTestImplementation(rootProject.libs.mockk)
            androidTestImplementation(rootProject.libs.kotlinx.coroutines.test)
            androidTestImplementation(rootProject.libs.androidx.test.ext.junit)
            androidTestImplementation(rootProject.libs.androidx.test.ext.junit.ktx)
            androidTestImplementation(rootProject.libs.androidx.test.runner)
            androidTestImplementation(rootProject.libs.androidx.test.espresso.core)
            androidTestImplementation(rootProject.libs.nicehttp)
            androidTestImplementation(rootProject.libs.jackson.module.kotlin)
            androidTestImplementation(rootProject.libs.jackson.databind)
            androidTestImplementation(rootProject.libs.khttp)
            androidTestImplementation(rootProject.libs.tmdb.java)
            androidTestImplementation(rootProject.libs.fuzzywuzzy)
            androidTestImplementation(rootProject.libs.rhino.android)
        }
        // ================ ------------- =================
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("getCSJar") {
    doFirst {
        logger.lifecycle("Fetching Cloudstream JAR")

        val tag = "pre-release"
        val apkDownloadUrl = URL.of(
            URI("https://github.com/abrogani/cloudstream/releases/download/$tag/classes.jar"),
            null
        )

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
