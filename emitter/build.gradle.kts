plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    id("com.vanniktech.maven.publish.base") version "0.30.0"
}

group = "community.flock.wirespec.kotest"
version = (providers.gradleProperty("version").orNull) ?: "0.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

val wirespecVersion = "0.19.3-RC.1"
val kotestVersion = "6.1.11"

dependencies {
    api("community.flock.wirespec.compiler.emitters:kotlin-jvm:$wirespecVersion")
    api("community.flock.wirespec.compiler:core-jvm:$wirespecVersion")
    api("io.arrow-kt:arrow-core:1.2.4")

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    configureBasedOnAppliedPlugins()
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true,
    )
    signAllPublications()
    coordinates(group.toString(), "kotest-wirespec-emitter", version.toString())
    pom {
        name.set("kotest-wirespec-emitter")
        description.set("TypesafeDslEmitter: Wirespec Emitter that produces a typesafe Kotest DSL per endpoint.")
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
