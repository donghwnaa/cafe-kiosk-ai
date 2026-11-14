import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val sttEngine = providers.gradleProperty("malloStt").orElse("sensevoice").get()
require(sttEngine in setOf("android", "sensevoice")) {
    "malloStt must be android or sensevoice."
}
val useSenseVoice = sttEngine == "sensevoice"
val prepareOfflineStt = if (useSenseVoice) {
    tasks.register<PrepareOfflineStt>("prepareOfflineStt") {
        description = "Downloads and verifies the official sherpa-onnx and SenseVoice release files."
        downloads.set(layout.projectDirectory.dir("offline-stt/downloads"))
        aar.set(layout.buildDirectory.file("generated/offline-stt/sherpa-onnx-2.0.1.aar"))
        assets.set(layout.buildDirectory.dir("generated/offline-stt/assets"))
        offline.set(gradle.startParameter.isOffline)
    }
} else null

android {
    namespace = "com.example.cafe_kiosk_ai"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.cafe_kiosk_ai"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets.getByName("main") {
        java.srcDir(if (useSenseVoice) "src/sensevoice/java" else "src/platformStt/java")
    }
}

if (prepareOfflineStt != null) {
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareOfflineStt, PrepareOfflineStt::assets)
    }
}

dependencies {
    if (prepareOfflineStt != null) implementation(files(prepareOfflineStt.flatMap { it.aar }))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("com.google.code.gson:gson:2.11.0") {
        // Gson's Error Prone annotations are for static analysis only.
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

abstract class PrepareOfflineStt @Inject constructor(
    private val archiveOperations: ArchiveOperations,
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {
    @get:LocalState
    abstract val downloads: DirectoryProperty

    @get:Input
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val aar: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun prepare() {
        val aarArchive = releaseFile(
            "v2.0.1",
            aar.get().asFile.name,
            "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
        )
        val vad = releaseFile(
            "asr-models",
            "silero_vad.onnx",
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        )
        val model = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        val modelArchive = releaseFile(
            "asr-models",
            "$model.tar.bz2",
            "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        )

        val sttAssets = assets.dir("stt").get().asFile
        fileSystemOperations.sync {
            from(archiveOperations.tarTree(modelArchive)) {
                include("$model/model.int8.onnx", "$model/tokens.txt", "$model/LICENSE")
                includeEmptyDirs = false
                eachFile {
                    path = if (name == "LICENSE") "SenseVoice-LICENSE.txt" else name
                }
            }
            from(vad)
            into(sttAssets)
        }
        for (name in listOf("model.int8.onnx", "tokens.txt", "silero_vad.onnx", "SenseVoice-LICENSE.txt")) {
            check(sttAssets.resolve(name).length() > 0) { "Missing STT asset after extraction: $name" }
        }
        aarArchive.copyTo(aar.get().asFile, overwrite = true)
    }

    private fun releaseFile(tag: String, name: String, expectedHash: String): File {
        val file = downloads.file(name).get().asFile
        if (file.isFile && sha256(file) == expectedHash) return file
        if (offline.get()) {
            throw GradleException("Missing or invalid STT file: $name. Run the first build with Gradle offline mode disabled.")
        }

        Files.createDirectories(file.toPath().parent)
        val partial = Files.createTempFile(file.toPath().parent, "$name.", ".part")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$tag/$name"
        logger.lifecycle("Downloading offline STT file $name")
        try {
            val connection = URI.create(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { Files.copy(it, partial, StandardCopyOption.REPLACE_EXISTING) }
            check(sha256(partial.toFile()) == expectedHash) { "SHA-256 mismatch for $name" }
            Files.move(partial, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            throw GradleException("Could not prepare $name from $url. Check the build computer's internet access and retry.", error)
        } finally {
            Files.deleteIfExists(partial)
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            var count = stream.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
