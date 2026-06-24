plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("com.vanniktech.maven.publish.base") version "0.30.0" apply false
}

allprojects {
    group = (rootProject.findProperty("group") as String?) ?: "community.flock.wirespec.kotest"
    version = (rootProject.findProperty("version") as String?) ?: "0.0.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

// One command for the local-publish round-trip that the Maven integration test
// (and downstream consumers) need in ~/.m2. The runtime now lives in wirespec's
// own published artifacts; this build only ships the maven-plugin (a separate
// composite build wired via includeBuild). gradle-plugin is published
// separately (pluginManagement-included; see the release workflow).
tasks.register("publishToMavenLocalAll") {
    group = "publishing"
    description = "Publishes the maven-plugin to the local Maven repository."
    dependsOn(gradle.includedBuild("maven-plugin").task(":publishToMavenLocal"))
}

// Aggregates `check` across the root build (example) plus the maven-plugin
// included build so CI runs their tests in one invocation. gradle-plugin is a
// pluginManagement-included build, not reachable via gradle.includedBuild();
// CI checks it with a separate `-p gradle-plugin`.
tasks.register("checkAll") {
    group = "verification"
    description = "Runs check for the root build plus the maven-plugin included build."
    dependsOn(subprojects.map { ":${it.name}:check" })
    dependsOn(gradle.includedBuild("maven-plugin").task(":check"))
}

// One command to publish the top-level-included modules to Maven Central
// (Central Portal). The version is supplied via ORG_GRADLE_PROJECT_version (an
// env var), which — unlike -Pversion — propagates into the included builds.
// gradle-plugin is published separately (pluginManagement-included; see the
// release workflow).
tasks.register("publishToMavenCentralAll") {
    group = "publishing"
    description = "Publishes the maven-plugin to Maven Central."
    dependsOn(gradle.includedBuild("maven-plugin").task(":publishAndReleaseToMavenCentral"))
}
