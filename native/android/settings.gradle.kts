import java.util.Properties
import org.gradle.api.initialization.resolve.RepositoriesMode

val localProperties = Properties().apply {
    val propertiesFile = file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()

        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "x-access-token"
                password =
                    providers.environmentVariable("GITHUB_TOKEN").orNull
                        ?: localProperties.getProperty("github_token")
            }
        }
    }
}

rootProject.name = "ICARUSNative"
include(":app")
