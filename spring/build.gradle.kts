plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    id("com.vanniktech.maven.publish.base")
}

description = "Spring transports (MockMvc, WebClient, EmbeddedKafka) and auto-registered ContextProvider for kotest-wirespec."

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

val springBootVersion = "3.4.1"
val kotestVersion = "6.1.11"

dependencies {
    api(project(":core"))

    api("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    api("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")

    // EmbeddedKafkaMessageTransport is the only consumer; channel users opt in
    // by adding spring-kafka(-test) themselves. compileOnly here matches the
    // historical runtime/build.gradle.kts decision.
    compileOnly("org.springframework.kafka:spring-kafka:3.3.0")
    compileOnly("org.springframework.kafka:spring-kafka-test:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka-test:3.3.0")

    api("jakarta.servlet:jakarta.servlet-api:6.0.0")
    api("io.kotest:kotest-extensions-spring-jvm:$kotestVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

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
