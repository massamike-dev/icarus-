// LEGACY ONLY: the canonical ICARUS Android project is native/android/.
// This root Gradle project is retained temporarily for historical comparison.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "IcarusLegacyBridge"
include(":app")
