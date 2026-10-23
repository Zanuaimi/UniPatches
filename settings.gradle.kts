rootProject.name = "UniPatches"

pluginManagement {
    repositories {
        // Local development builds of the Morphe Gradle plugin are published here
        // with `publishToMavenLocal` (the plugin itself is only distributed through
        // GitHub Packages, which requires authentication).
        mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.4"
}
