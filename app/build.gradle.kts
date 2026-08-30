import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.icarusalmighty.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.icarusalmighty.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "BASE44_URL", "\"${localProperties.getProperty(\"BASE44_URL\", \"\")}\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://icarusassistant.com/android-update.json\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/sherpa/kotlin"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
}
