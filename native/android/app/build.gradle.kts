plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val webUrl = providers.gradleProperty("ICARUS_WEB_URL").orNull ?: ""

android {
    namespace = "com.icarusalmighty.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.icarusalmighty.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.3"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "ICARUS_WEB_URL", "\"${webUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

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
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
}