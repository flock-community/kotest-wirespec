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
// (and downstream consumers) need in ~/.m2. core + spring live in this build;
// emitter and maven-plugin are separate composite builds wired via includeBuild.
tasks.register("publishToMavenLocalAll") {
    group = "publishing"
    description = "Publishes core, spring, emitter and maven-plugin to the local Maven repository."
    dependsOn(":core:publishToMavenLocal", ":spring:publishToMavenLocal")
    dependsOn(gradle.includedBuild("emitter").task(":publishToMavenLocal"))
    dependsOn(gradle.includedBuild("maven-plugin").task(":publishToMavenLocal"))
}

// Aggregates `check` across the root build (core, spring, example) plus the
// emitter and maven-plugin included builds so CI runs their tests in one
// invocation. gradle-plugin is a pluginManagement-included build, not reachable
// via gradle.includedBuild(); CI checks it with a separate `-p gradle-plugin`.
tasks.register("checkAll") {
    group = "verification"
    description = "Runs check for the root build plus the emitter and maven-plugin included builds."
    dependsOn(subprojects.map { ":${it.name}:check" })
    dependsOn(gradle.includedBuild("emitter").task(":check"))
    dependsOn(gradle.includedBuild("maven-plugin").task(":check"))
}

// One command to publish the root-build + top-level-included modules to Maven
// Central (Central Portal). The version is supplied via
// ORG_GRADLE_PROJECT_version (an env var), which — unlike -Pversion —
// propagates into the included builds. gradle-plugin is published separately
// (pluginManagement-included; see the release workflow).
tasks.register("publishToMavenCentralAll") {
    group = "publishing"
    description = "Publishes core, spring, emitter and maven-plugin to Maven Central."
    dependsOn(":core:publishAndReleaseToMavenCentral", ":spring:publishAndReleaseToMavenCentral")
    dependsOn(gradle.includedBuild("emitter").task(":publishAndReleaseToMavenCentral"))
    dependsOn(gradle.includedBuild("maven-plugin").task(":publishAndReleaseToMavenCentral"))
}

// Maven Central publishing for the two root-build library subprojects. Each
// applies the vanniktech `.base` plugin itself; this block holds the shared
// config (Central Portal destination, signing, POM metadata) so it is not
// repeated. The three included builds configure vanniktech inline in their own
// build.gradle.kts — a root `subprojects {}` block cannot reach them.
subprojects {
    plugins.withId("com.vanniktech.maven.publish.base") {
        val publishedArtifactId = when (name) {
            "core"   -> "kotest-wirespec"
            "spring" -> "kotest-wirespec-spring"
            else     -> error("vanniktech applied to unexpected subproject: $name")
        }
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            configureBasedOnAppliedPlugins()
            publishToMavenCentral(
                com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
                automaticRelease = true,
            )
            signAllPublications()
            coordinates(
                groupId = project.group.toString(),
                artifactId = publishedArtifactId,
                version = project.version.toString(),
            )
            pom {
                name.set(publishedArtifactId)
                description.set(provider { project.description ?: publishedArtifactId })
                url.set("https://github.com/flock-community/kotest-wirespec")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("wilmveel")
                        name.set("Willem Veelenturf")
                        email.set("willem.veelenturf@flock.community")
                        organization.set("Flock. Community")
                        organizationUrl.set("https://flock.community")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/flock-community/kotest-wirespec.git")
                    developerConnection.set("scm:git:ssh://github.com:flock-community/kotest-wirespec.git")
                    url.set("https://github.com/flock-community/kotest-wirespec")
                }
            }
        }
    }
}
