plugins {
    kotlin("jvm") version "2.3.0" apply false
}

allprojects {
    group = (rootProject.findProperty("group") as String?) ?: "io.kotest.extensions.wirespec"
    version = (rootProject.findProperty("version") as String?) ?: "0.0.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }
}

// One command for the local-publish round-trip that the Maven integration test
// (and downstream consumers) need in ~/.m2. core + spring live in this build;
// emitter and maven-plugin are separate composite builds wired via includeBuild.
tasks.register("publishToMavenLocalAll") {
    group = "publishing"
    description = "Publishes core, spring, emitter and maven-plugin to the local Maven repository."
    dependsOn(":core:publishToMavenLocal", ":spring:publishToMavenLocal")
    dependsOn(gradle.includedBuild("emitter").task(":publishToMavenLocal"))
    dependsOn(gradle.includedBuild("maven-plugin").task(":publishToMavenLocal"))
}
