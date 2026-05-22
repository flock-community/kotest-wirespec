plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

val springBootVersion = "3.4.1"
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

    // Spring Boot test infrastructure — boots @SpringBootTest with RANDOM_PORT
    // and provides `WebClient`. `api` so consumers' test code sees it.
    //
    // Note: kotest-extensions-spring 1.3.x is built against Kotest 5.x and
    // conflicts at runtime with kotest-runner-junit5:6.1.x (SpecRef.Reference
    // arity mismatch). Until a 6.x-compatible release is available we wire the
    // Spring boot lifecycle manually via SpringTestContext + SpringWirespecExtension.
    api("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    api("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    api("org.jetbrains.kotlin:kotlin-reflect:2.3.0")

    // jackson-module-kotlin gives us `jacksonObjectMapper()`, the ObjectMapper
    // the WirespecSerialization needs in order to handle Kotlin data classes.
    // The Spring Boot Jackson starter doesn't pull this in transitively.
    // Pinned explicitly (rather than letting Spring's BOM resolve it) so the
    // published Maven POM carries a version — without it, Maven consumers see
    // an invalid POM and lose all transitive deps from this artifact.
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
            artifactId = "kotest-extensions-spring-wirespec"
            pom {
                name.set("Kotest Spring Wirespec Runtime")
                description.set("Property-based scenario DSL for validating Spring endpoints against Wirespec contracts.")
            }
        }
    }
}
