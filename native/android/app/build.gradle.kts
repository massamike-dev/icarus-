import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val webUrl = providers.gradleProperty("ICARUS_WEB_URL").orNull ?: ""
val uploadKeystore = providers.environmentVariable("ICARUS_KEYSTORE_FILE").orNull?.let(::file)
val uploadStorePassword = providers.environmentVariable("ICARUS_KEYSTORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("ICARUS_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("ICARUS_KEY_PASSWORD").orNull

android {
    namespace = "com.icarusalmighty.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icarusalmighty.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.1"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "ICARUS_WEB_URL", "\"${webUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "UPDATE_NOTES_URL", "\"https://icarusassistant.com/android-update.json\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (uploadKeystore?.exists() == true &&
            !uploadStorePassword.isNullOrBlank() &&
            !uploadKeyAlias.isNullOrBlank() &&
            !uploadKeyPassword.isNullOrBlank()
        ) {
            create("playUpload") {
                storeFile = uploadKeystore
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("playUpload")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/sherpa/kotlin"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}