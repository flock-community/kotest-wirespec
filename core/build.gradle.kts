plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

val wirespecVersion = "0.19.0-RC.3"
val kotestVersion = "6.1.11"

dependencies {
    // Wirespec runtime — exposes `Wirespec`, `Wirespec.Endpoint`, the typed
    // response sealed family, and `Transportation`. Required by anyone using
    // the generated endpoint objects, so it's `api`.
    api("community.flock.wirespec.integration:wirespec-jvm:$wirespecVersion")
    api("community.flock.wirespec.integration:jackson-jvm:$wirespecVersion")

    // Kotest core + property-based testing — public surface (consumers write
    // `checkAll { scenario(ctx) { ... } }`, use `Arb`, write `shouldBe…` assertions).
    api("io.kotest:kotest-runner-junit5:$kotestVersion")
    api("io.kotest:kotest-property:$kotestVersion")
    api("io.kotest:kotest-assertions-core:$kotestVersion")

    // The Wirespec ↔ Kotest adapter (`kotestWirespecKotlinGenerator`) is what
    // the `arb` receiver wraps; consumers see `Arb<T>` for generated shapes.
    api("community.flock.wirespec.integration:kotest-jvm:$wirespecVersion")

    api("org.jetbrains.kotlin:kotlin-reflect:2.3.0")

    // jackson-module-kotlin gives us `jacksonObjectMapper()`, the ObjectMapper
    // the WirespecSerialization needs in order to handle Kotlin data classes.
    // Pinned explicitly so the published Maven POM carries a version — without
    // it, Maven consumers see an invalid POM and lose all transitive deps from
    // this artifact.
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-java-parameters")
    }
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotest-wirespec"
            pom {
                name.set("Kotest Wirespec Runtime")
                description.set("Property-based scenario DSL for validating endpoints against Wirespec contracts.")
            }
        }
    }
}
