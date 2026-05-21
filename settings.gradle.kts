pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // Composite-included build: the Gradle plugin lives at ./plugin/ as its own
    // Gradle build. Substituting via pluginManagement lets `:example` apply the
    // plugin with `plugins { id("io.kotest.extensions.spring.wirespec") }` and
    // pick up the in-source version — no publishToMavenLocal round-trip.
    includeBuild("plugin")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("emitter")

rootProject.name = "kotest-extensions-spring-wirespec"

include(":runtime")
include(":example")
