pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    // Composite-included build: the Gradle plugin lives at ./gradle-plugin/ as
    // its own Gradle build. Substituting via pluginManagement lets `:example`
    // apply the plugin with
    // `plugins { id("community.flock.wirespec.kotest") }` and pick up the
    // in-source version — no publishToMavenLocal round-trip.
    includeBuild("gradle-plugin")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

includeBuild("emitter")
includeBuild("maven-plugin")

rootProject.name = "kotest-wirespec"

include(":core")
include(":example")
include(":spring")
