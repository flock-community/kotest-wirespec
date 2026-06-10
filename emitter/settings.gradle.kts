pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

// Must match the published artifactId in build.gradle.kts. Otherwise composite
// builds advertise a default capability of `io.kotest.extensions:<rootProjectName>`
// and will hijack any consumer dependency on `io.kotest.extensions:<rootProjectName>`
// — including the unrelated `io.kotest.extensions:kotest-extensions-spring`
// SpringExtension artifact that the runtime depends on.
rootProject.name = "kotest-wirespec-emitter"
