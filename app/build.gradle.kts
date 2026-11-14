plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val sttEngine = providers.gradleProperty("malloStt").orElse("sensevoice").get()
require(sttEngine in setOf("android", "sensevoice")) {
    "malloStt must be android or sensevoice."
}
val useSenseVoice = sttEngine == "sensevoice"
val offlineStt = layout.projectDirectory.dir("offline-stt")
val sherpaAar = offlineStt.file("sherpa-onnx-2.0.1.aar")

if (useSenseVoice) {
    val requiredFiles = listOf(
        sherpaAar,
        offlineStt.file("assets/stt/model.int8.onnx"),
        offlineStt.file("assets/stt/tokens.txt"),
        offlineStt.file("assets/stt/silero_vad.onnx")
    )
    val missing = requiredFiles.filterNot { it.asFile.isFile && it.asFile.length() > 0 }
    check(missing.isEmpty()) {
        "Run tools/setup-offline-stt.ps1, or set malloStt=android to use SpeechRecognizer. " +
            "Missing: ${missing.joinToString { it.asFile.name }}"
    }
}

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
        if (useSenseVoice) assets.srcDir(offlineStt.dir("assets"))
    }
}

dependencies {
    if (useSenseVoice) implementation(files(sherpaAar))
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
