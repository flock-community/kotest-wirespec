import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.plugin.gradle.CompileWirespecTask
import io.kotest.extensions.spring.wirespec.emitter.TypesafeDslEmitter

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("io.kotest.extensions:kotest-extensions-spring-wirespec-emitter:0.1.0-SNAPSHOT")
    }
}

plugins {
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("community.flock.wirespec.plugin.gradle") version "0.19.0-RC.3"
    // The plugin under test — once Phase 3 lands this will replace the manual
    // Wirespec compile-task wiring below.
    // id("io.kotest.extensions.spring.wirespec")
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
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // Swagger annotations are how `@ApiResponses` on controller methods tell the
    // wirespec-spring-extractor about non-2xx response variants.
    implementation("io.swagger.core.v3:swagger-annotations:2.2.25")

    testImplementation(project(":runtime"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-java-parameters")
    }
}

// --- Wirespec compile wiring (manual until Phase 3 ships the convention plugin) ---

val wirespecGeneratedDir = layout.buildDirectory.dir("generated/wirespec")
val wirespecSourceDir = layout.projectDirectory.dir("src/wirespec")
val wirespecGeneratedPackage = "io.kotest.extensions.spring.wirespec.example.generated"

val wirespecKotlin = tasks.register<CompileWirespecTask>("wirespecKotlin") {
    description = "Generate Kotlin sources from the Wirespec contracts in src/wirespec."
    group = "wirespec"
    input = wirespecSourceDir
    output = wirespecGeneratedDir
    packageName = wirespecGeneratedPackage
    // Use the IR-based Kotlin emitter (vs. the default `languages = [Kotlin]`
    // which selects the simpler LanguageEmitter). The IR variant emits:
    //   - per-type `*Generator.kt` files for kotest-jvm Arb integration
    //     (powers the runtime's `arb.createPetRequest()`-style accessors)
    //   - full destructured Request(path, method, queries, headers, body)
    //     constructors with `toRawRequest` / `fromRawResponse` / `RequestHeaders`
    // BaseWirespecTask's reflective constructor invocation didn't reliably pass
    // the task's `packageName` through to KotlinIrEmitter's defaulted ctor —
    // generated code ended up in the wirespec default package. Subclass with
    // the package hardcoded, matching the showcase pattern.
    emitterClass.set(ExampleClientEmitter::class.java)
}

class ExampleClientEmitter : TypesafeDslEmitter(
    packageName = PackageName("io.kotest.extensions.spring.wirespec.example.generated"),
)

sourceSets {
    test {
        java {
            srcDir(wirespecGeneratedDir)
        }
    }
}

tasks.named("compileTestKotlin") { dependsOn(wirespecKotlin) }

tasks.withType<Test> {
    useJUnitPlatform()
}
