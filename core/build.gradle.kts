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
    api("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    api("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")

    // Spring Kafka — only the transport types (KafkaProducer / KafkaConsumer
    // through spring-kafka's transitive kafka-clients dep, EmbeddedKafkaBroker
    // via spring-kafka-test). compileOnly so HTTP-only consumers of this
    // library don't drag spring-kafka onto their test classpath. Consumers who
    // write channel tests add spring-kafka(-test) themselves.
    compileOnly("org.springframework.kafka:spring-kafka:3.3.0")
    compileOnly("org.springframework.kafka:spring-kafka-test:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka-test:3.3.0")

    // Servlet API for the MockMvc transport. spring-test bundles
    // MockHttpServletRequest/Response but doesn't expose jakarta.servlet-api
    // transitively, so it has to be declared explicitly to keep
    // MockMvcTransportation compilable.
    api("jakarta.servlet:jakarta.servlet-api:6.0.0")

    // Official Kotest ↔ Spring bridge. Provides `SpringExtension`, which drives
    // Spring's `TestContextManager` from a Kotest spec — i.e. honours
    // `@SpringBootTest` on the class. Exposed as `api` so consumers can mount
    // it in their own specs without re-declaring the dep.
    //
    // 1.3.0 was compiled against Kotest 5.x and carries a transitive
    // `kotest-framework-api:5.8.1`. Kotest 6 dropped that artifact and moved
    // `SpecRef` into `kotest-framework-engine`; both jars declare the same
    // FQCN, so leaving 5.8.1 on the classpath shadows the 6.x SpecRef and
    // produces `NoSuchMethodError: SpecRef$Reference.<init>(KClass, String)`
    // during JUnit-platform discovery. Excluding the 5.x API jar keeps only
    // the 6.x class. SpringExtension itself only touches stable extension
    // interfaces (`MountableExtension`, `BeforeSpec/AfterSpec/TestCase`), all
    // of which Kotest 6 still ships.
    api("io.kotest:kotest-extensions-spring-jvm:6.1.11")
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
            artifactId = "kotest-wirespec"
            pom {
                name.set("Kotest Spring Wirespec Runtime")
                description.set("Property-based scenario DSL for validating Spring endpoints against Wirespec contracts.")
            }
        }
    }
}
