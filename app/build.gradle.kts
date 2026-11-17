import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
val useOfflineTts = useSenseVoice && providers.gradleProperty("malloOfflineTts").orElse("true").get().toBooleanStrict()
val useGenie = providers.gradleProperty("malloGenie").orElse("false").get().toBooleanStrict()
val embedGenieModel = useGenie && providers.gradleProperty("malloEmbedGenieModel").orElse("true").get().toBooleanStrict()
val geniePackageMode = if (embedGenieModel) "embedded" else "split"
if (useGenie) {
    // The incremental APK writer can retain space from removed large ZIP entries.
    // Keep embedded, split and emulator packaging intermediates independent.
    layout.buildDirectory.set(layout.projectDirectory.dir("build/genie-$geniePackageMode"))
}
val qairtSdk = if (useGenie) {
    requireNotNull(providers.gradleProperty("malloQairtSdk")
        .orElse(providers.environmentVariable("MALLO_QAIRT_SDK")).orNull?.takeIf { it.isNotBlank() }) {
        "malloGenie requires malloQairtSdk or MALLO_QAIRT_SDK pointing to QAIRT 2.40.0.251030."
    }
} else null
val genieAssets = layout.projectDirectory.dir("src/main/assets/llm")
val genieArmLibraries = listOf("libGenie.so", "libQnnHtp.so", "libQnnHtpNetRunExtensions.so",
    "libQnnHtpV68Stub.so", "libQnnSystem.so", "libc++_shared.so")
val genieRuntimeAssets = listOf("npu_manifest.json", "tokenizer.json", "genie_config_npu.json",
    "htp_backend_ext_config.json", "libQnnHtpV68Skel.so", "prompt_format.json", "MALLO_system_prompt.txt")
val verifyGenieModel = tasks.register("verifyGenieModel") {
    description = "Checks the supplied Genie assets and verifies the model against its export manifest."
    val assets = genieAssets.asFile
    inputs.dir(assets)
    doLast {
        val model = assets.resolve("llama32_hf_v68.bin")
        val manifestFile = assets.resolve("npu_manifest.json")
        val tokenizer = assets.resolve("tokenizer.json")
        val required = genieArmLibraries + genieRuntimeAssets + model.name
        for (name in required) {
            val file = assets.resolve(name)
            check(file.isFile && file.length() > 0) { "Missing or empty Genie file: $name" }
        }
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        check(model.length() == (manifest["context_bytes"] as Number).toLong() &&
            OfflineAudioFiles.sha256(model) == manifest["context_sha256"]) { "Genie model does not match npu_manifest.json" }
        check(OfflineAudioFiles.sha256(tokenizer) == manifest["tokenizer_sha256"]) {
            "Genie tokenizer does not match npu_manifest.json"
        }
        logger.lifecycle("Verified Genie model: ${model.length()} bytes, SHA-256 ${manifest["context_sha256"]}")
    }
}
val prepareGenieLibraries = qairtSdk?.let {
    tasks.register<Sync>("prepareGenieLibraries") {
        from(genieAssets) {
            include(genieArmLibraries)
            into("arm64-v8a")
        }
        into(layout.buildDirectory.dir("generated/genie/jniLibs"))
    }
}
val prepareOfflineTts = if (useOfflineTts) {
    tasks.register<PrepareOfflineTts>("prepareOfflineTts") {
        downloads.set(layout.projectDirectory.dir("offline-tts/downloads"))
        assets.set(layout.buildDirectory.dir("generated/offline-tts/assets"))
        offline.set(gradle.startParameter.isOffline)
    }
} else null
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
        manifestPlaceholders["malloBundledTts"] = useOfflineTts.toString()
        buildConfigField("boolean", "MALLO_GENIE_NATIVE", useGenie.toString())
        buildConfigField("boolean", "MALLO_GENIE_EMBEDDED_MODEL", embedGenieModel.toString())
        if (qairtSdk != null) {
            ndk.abiFilters.add("arm64-v8a")
            externalNativeBuild.cmake.arguments.addAll(listOf(
                "-DMALLO_QAIRT_SDK=${rootProject.file(qairtSdk).invariantSeparatorsPath}", "-DANDROID_STL=c++_static"
            ))
        }
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
    if (qairtSdk != null) {
        androidResources.noCompress.add("bin")
        androidResources.ignoreAssetsPatterns.addAll(genieArmLibraries + listOf("genie-t2t-run", "run_npu.sh", "prompt.txt"))
        if (!embedGenieModel) androidResources.ignoreAssetsPatterns.add("llama32_hf_v68.bin")
        ndkVersion = "28.2.13676358"
        externalNativeBuild.cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
        packaging.jniLibs.useLegacyPackaging = true
        // Genie loads QNN plugins by name. Keep the vendor binaries intact.
        packaging.jniLibs.keepDebugSymbols.addAll(setOf("**/libGenie.so", "**/libQnn*.so", "**/libc++_shared.so"))
    } else {
        // Drop the vendor bundle during asset merging, before APK compression or signing.
        androidResources.ignoreAssetsPatterns.add("<dir>llm")
    }
    sourceSets.getByName("main") {
        java.srcDir(if (useSenseVoice) "src/sensevoice/java" else "src/platformStt/java")
        if (useOfflineTts) java.srcDir("src/offlineTts/java")
    }
    sourceSets.getByName("debug").java.srcDir("src/developmentNlu/java")
    sourceSets.maybeCreate("testRelease").java.srcDir("src/developmentNlu/java")
}

if (prepareGenieLibraries != null) {
    android.sourceSets.getByName("main").jniLibs.srcDir(prepareGenieLibraries.map { it.destinationDir })
    tasks.named("preBuild").configure { dependsOn(verifyGenieModel, prepareGenieLibraries) }
}

tasks.register("packageGenieDebug") {
    description = "Verifies and copies the Qualcomm debug APK to a board-specific filename."
    if (useGenie) dependsOn("assembleDebug")
    doLast {
        check(useGenie) { "Build this task with -PmalloGenie=true and a QAIRT SDK path." }
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        ZipFile(apk).use { zip ->
            val required = genieRuntimeAssets.map { "assets/llm/$it" } +
                (genieArmLibraries + "libmallo_genie.so").map { "lib/arm64-v8a/$it" }
            for (name in required) {
                check((zip.getEntry(name)?.size ?: 0) > 0) { "Missing or empty APK entry: $name" }
            }
            val model = zip.getEntry("assets/llm/llama32_hf_v68.bin")
            if (embedGenieModel) {
                check(model != null && model.method == ZipEntry.STORED &&
                    model.size == genieAssets.file("llama32_hf_v68.bin").asFile.length()) {
                    "APK must contain the complete Genie model as an uncompressed asset."
                }
            } else {
                check(model == null) { "Split model APK unexpectedly contains llama32_hf_v68.bin." }
            }
        }
        val verified = layout.projectDirectory.file("build/outputs/mallo/app-qcs6490-$geniePackageMode-debug.apk").asFile
        apk.copyTo(verified, overwrite = true)
        logger.lifecycle("Verified Qualcomm APK: ${verified.absolutePath}")
    }
}

if (prepareOfflineStt != null) {
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareOfflineStt, PrepareOfflineStt::assets)
    }
}

if (prepareOfflineTts != null) {
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareOfflineTts, PrepareOfflineTts::assets)
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

    private fun releaseFile(tag: String, name: String, expectedHash: String): File =
        OfflineAudioFiles.verified(downloads.file(name).get().asFile,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/$tag/$name", expectedHash, offline.get(), logger)
}

abstract class PrepareOfflineTts @Inject constructor(
    private val archiveOperations: ArchiveOperations,
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {
    @get:LocalState abstract val downloads: DirectoryProperty
    @get:Input abstract val offline: Property<Boolean>
    @get:OutputDirectory abstract val assets: DirectoryProperty

    @TaskAction
    fun prepare() {
        val model = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        val archive = OfflineAudioFiles.verified(
            downloads.file("$model.tar.bz2").get().asFile,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$model.tar.bz2",
            "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427", offline.get(), logger
        )
        val licenseName = "Supertonic3-upstream-LICENSE.txt"
        val upstreamLicense = OfflineAudioFiles.verified(
            downloads.file(licenseName).get().asFile,
            "https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/LICENSE",
            "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f", offline.get(), logger
        )
        val files = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin", "LICENSE")
        val destination = assets.dir("tts").get().asFile
        fileSystemOperations.sync {
            from(archiveOperations.tarTree(archive)) {
                include(files.map { "$model/$it" })
                includeEmptyDirs = false
                eachFile { path = name }
            }
            from(upstreamLicense)
            into(destination)
        }
        (files + licenseName).forEach { check(destination.resolve(it).length() > 0) { "Missing TTS asset: $it" } }
    }
}

private object OfflineAudioFiles {
    fun verified(
        file: File, url: String, expectedHash: String, offline: Boolean, logger: org.gradle.api.logging.Logger
    ): File {
        val name = file.name
        if (file.isFile && sha256(file) == expectedHash) return file
        if (offline) {
            throw GradleException("Missing or invalid offline audio file: $name. Build once with network access.")
        }

        Files.createDirectories(file.toPath().parent)
        val partial = Files.createTempFile(file.toPath().parent, "$name.", ".part")
        logger.lifecycle("Downloading offline audio file $name")
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

    fun sha256(file: File): String {
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
