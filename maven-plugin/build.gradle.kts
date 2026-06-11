plugins {
    kotlin("jvm") version "2.3.0"
    id("com.vanniktech.maven.publish.base") version "0.30.0"
}

group = "community.flock.wirespec.kotest"
version = (providers.gradleProperty("version").orNull) ?: "0.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

val mavenApiVersion = "3.9.6"
val mavenPluginAnnotationsVersion = "3.13.1"
val mojoExecutorVersion = "2.4.0"
val mavenInvokerVersion = "3.3.0"

dependencies {
    compileOnly("org.apache.maven:maven-plugin-api:$mavenApiVersion")
    compileOnly("org.apache.maven:maven-core:$mavenApiVersion")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:$mavenPluginAnnotationsVersion")

    implementation("org.twdata.maven:mojo-executor:$mojoExecutorVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.apache.maven.shared:maven-invoker:$mavenInvokerVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Token-expand the hand-written plugin descriptor so ${projectVersion} is
// replaced with the build version before it lands in the jar.
tasks.named<Copy>("processResources") {
    from("src/main/resources-template") {
        include("**/*.xml")
        expand(
            "projectVersion" to project.version.toString(),
            "extractorVersion" to (providers.gradleProperty("wirespecExtractorVersion").orNull ?: "0.0.13"),
            "wirespecVersion" to (providers.gradleProperty("wirespecVersion").orNull ?: "0.19.3-RC.1"),
        )
    }
    from("src/main/resources")
}

mavenPublishing {
    configureBasedOnAppliedPlugins()
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true,
    )
    signAllPublications()
    coordinates(group.toString(), "kotest-wirespec-maven-plugin", version.toString())
    pom {
        name.set("kotest-wirespec-maven-plugin")
        description.set("Extracts Wirespec contracts from Spring controllers and generates a typesafe Kotest DSL.")
        packaging = "maven-plugin"
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

// The integration test invokes `mvn verify` against a fixture that pulls
// emitter, runtime, and this plugin from ~/.m2. Install all three first.
// Shell out to the outer Gradle wrapper because emitter is its own composite
// build and runtime lives in the outer composite — neither is addressable
// from maven-plugin/'s task graph. Gradle 9 removed Project.exec from build
// scripts, so we wire dedicated Exec tasks instead.
val outerRoot = project.rootDir.parentFile
val outerGradlew = outerRoot.resolve("gradlew").absolutePath

val publishEmitterToMavenLocal by tasks.registering(Exec::class) {
    workingDir = outerRoot.resolve("emitter")
    commandLine(outerGradlew, "--no-daemon", "publishToMavenLocal")
}

val publishCoreToMavenLocal by tasks.registering(Exec::class) {
    workingDir = outerRoot
    commandLine(outerGradlew, "--no-daemon", ":core:publishToMavenLocal")
}

val publishSpringToMavenLocal by tasks.registering(Exec::class) {
    workingDir = outerRoot
    commandLine(outerGradlew, "--no-daemon", ":spring:publishToMavenLocal")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(
        publishEmitterToMavenLocal,
        publishCoreToMavenLocal,
        publishSpringToMavenLocal,
        tasks.named("publishToMavenLocal"),
    )
}
