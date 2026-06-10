plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
}

group = "io.kotest.extensions.wirespec"
version = (providers.gradleProperty("version").orNull) ?: "0.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

val wirespecVersion = "0.0.0-SNAPSHOT"
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotest-wirespec-emitter"
            pom {
                name.set("Kotest Spring Wirespec Emitter")
                description.set("TypesafeDslEmitter: Wirespec Emitter that produces a typesafe Kotest DSL per endpoint.")
            }
        }
    }
}
