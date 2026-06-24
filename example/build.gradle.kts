plugins {
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("community.flock.wirespec.kotest")
}

group = "io.kotest.extensions.wirespec.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

// Matches the kotest version wirespec's kotest-jvm:0.20.0-RC.2 is built against
// (it pins kotest-framework-engine 6.1.4 transitively).
val kotestVersion = "6.1.4"
val wirespecVersion = "0.20.0-RC.2"

dependencies {
    // Servlet stack so the app serves the generated endpoints over real HTTP.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.25")
    // The controllers use `suspend` handler methods; Spring MVC needs the Reactor
    // coroutine adapter to invoke them. (Previously pulled in transitively via the
    // removed `:spring` module.)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Wirespec's Kotest scenario-DSL runtime — the generated `<Endpoint>.call { }`
    // and channel DSL compile against it. `jackson-jvm` supplies `WirespecSerialization`
    // (the `Wirespec.Serialization` the transports/contexts use). `wirespec-jvm` carries
    // the `Wirespec` runtime the generated models compile against — kotest-jvm depends on
    // it only at runtime scope, so it must be on the test compile classpath explicitly.
    testImplementation("community.flock.wirespec.integration:wirespec-jvm:$wirespecVersion")
    testImplementation("community.flock.wirespec.integration:kotest-jvm:$wirespecVersion")
    testImplementation("community.flock.wirespec.integration:jackson-jvm:$wirespecVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-java-parameters")
    }
}

kotestWirespec {
    basePackage.set("io.kotest.extensions.wirespec.example")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
