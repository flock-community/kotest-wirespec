plugins {
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("io.kotest.extensions.spring.wirespec")
}

group = "io.kotest.extensions.spring.wirespec.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    // Servlet stack so @AutoConfigureMockMvc applies. Spring MVC 5.2+ accepts
    // `suspend` controller methods, so the existing controllers don't need to
    // change.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.25")

    testImplementation(project(":core"))
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-java-parameters")
    }
}

kotestWirespecSpring {
    basePackage.set("io.kotest.extensions.spring.wirespec.example")
}

tasks.withType<Test> {
    useJUnitPlatform {
        // Explicitly include both engines so JUnit Jupiter tests
        // (PetScenariosJUnitTest) run alongside Kotest specs.
        includeEngines("kotest", "junit-jupiter")
    }
}
