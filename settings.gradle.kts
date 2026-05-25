pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // Composite-included build: the Gradle plugin lives at ./gradle-plugin/ as
    // its own Gradle build. Substituting via pluginManagement lets `:example`
    // apply the plugin with
    // `plugins { id("io.kotest.extensions.spring.wirespec") }` and pick up the
    // in-source version — no publishToMavenLocal round-trip.
    includeBuild("gradle-plugin")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("emitter")
includeBuild("maven-plugin")

rootProject.name = "kotest-wirespec"

include(":runtime")
include(":example")
