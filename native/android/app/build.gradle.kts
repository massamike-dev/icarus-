import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val webUrl = providers.gradleProperty("ICARUS_WEB_URL").orNull ?: ""
val testWebUrl = providers.gradleProperty("ICARUS_TEST_WEB_URL").orNull.orEmpty()
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\")
    .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
val uploadKeystore = providers.environmentVariable("ICARUS_KEYSTORE_FILE").orNull?.let(::file)
val uploadStorePassword = providers.environmentVariable("ICARUS_KEYSTORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("ICARUS_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("ICARUS_KEY_PASSWORD").orNull
val metaApplicationId =
    providers.gradleProperty("mwdat_application_id").orNull ?: "0"
val metaClientToken =
    providers.gradleProperty("mwdat_client_token").orNull ?: "0"
android {
    namespace = "com.icarusalmighty.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icarusalmighty.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 42
        versionName = "1.6.11"
        manifestPlaceholders["mwdat_application_id"] = metaApplicationId
        manifestPlaceholders["mwdat_client_token"] = metaClientToken
        manifestPlaceholders["icarus_link_scheme"] = "icarus"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "ICARUS_WEB_URL", javaString(webUrl))
        buildConfigField("boolean", "PRIVATE_TEST", "false")
        buildConfigField("String", "ICARUS_LINK_SCHEME", javaString("icarus"))
        buildConfigField("String", "UPDATE_NOTES_URL", "\"https://raw.githubusercontent.com/massamike-dev/icarus-/main/public/android-update-v2.json\"")
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
        create("privateTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["icarus_link_scheme"] = "icarus-test"
            buildConfigField("String", "ICARUS_WEB_URL", javaString(testWebUrl))
            buildConfigField("boolean", "PRIVATE_TEST", "true")
            buildConfigField("String", "ICARUS_LINK_SCHEME", javaString("icarus-test"))
            // There is intentionally no public update feed for private installs.
            buildConfigField("String", "UPDATE_NOTES_URL", javaString(""))
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

// Validate only tasks that use this build type. Ordinary debug/release builds
// keep working without private environment configuration or signing secrets.
val validatePrivateTestConfiguration = tasks.register("validatePrivateTestConfiguration") {
    doLast {
        val endpoint = runCatching { URI(testWebUrl) }.getOrNull()
        val productionHost = runCatching { URI(webUrl).host?.lowercase() }.getOrNull()
        val forbiddenHosts = setOf(
            "icarusassistant.com", "www.icarusassistant.com", "icarus-assistant.onrender.com",
            productionHost,
        )
        check(endpoint != null && endpoint.scheme.equals("https", ignoreCase = true) &&
            !endpoint.host.isNullOrBlank() && endpoint.host.lowercase().trimEnd('.') !in forbiddenHosts &&
            endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null &&
            endpoint.rawPath.orEmpty() in setOf("", "/") &&
            (endpoint.port == -1 || endpoint.port in 1..65535) && testWebUrl == testWebUrl.trim()) {
            "ICARUS_TEST_WEB_URL must be a separate HTTPS origin without credentials, path, query, or fragment. Production URLs are forbidden."
        }
    }
}
val validatePrivateTestSigning = tasks.register("validatePrivateTestSigning") {
    dependsOn(validatePrivateTestConfiguration)
    doLast {
        val host = URI(testWebUrl).host.lowercase().trimEnd('.')
        check(host != "localhost" && listOf("invalid", "example", "test", "localhost").none { host == it || host.endsWith(".$it") }) {
            "Private APK packaging requires a real test deployment, not a validation-only placeholder origin."
        }
        check(android.signingConfigs.findByName("playUpload") != null) {
            "Private APK packaging requires the existing ICARUS upload keystore and signing environment variables."
        }
    }
}
tasks.configureEach {
    if (name.contains("PrivateTest") && name != "validatePrivateTestConfiguration") {
        dependsOn(validatePrivateTestConfiguration)
    }
    if (name in setOf("assemblePrivateTest", "bundlePrivateTest", "packagePrivateTest", "packagePrivateTestBundle", "signPrivateTestBundle")) {
        dependsOn(validatePrivateTestSigning)
    }
}

dependencies {
    implementation(project(":xreal"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.meta.wearable:mwdat-core:0.9.0")
    implementation("com.meta.wearable:mwdat-camera:0.9.0")
    implementation("com.meta.wearable:mwdat-display:0.9.0")
    implementation("com.meta.wearable:mwdat-mockdevice:0.9.0")

    testImplementation("junit:junit:4.13.2")
}
