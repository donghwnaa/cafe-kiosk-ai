plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.usbaudioprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.usbaudioprobe"
        minSdk = 24
        targetSdk = 36
    }
}
