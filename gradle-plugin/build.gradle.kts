plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.0"
}

group = "community.flock.wirespec.kotest"
version = (providers.gradleProperty("version").orNull) ?: "0.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    mavenLocal()
}

dependencies {
    implementation(gradleApi())
    implementation("community.flock.wirespec.plugin.gradle:community.flock.wirespec.plugin.gradle.gradle.plugin:0.19.3-RC.1")
    implementation("community.flock.wirespec.spring:wirespec-spring-extractor-gradle-plugin:0.0.13")
    implementation("community.flock.wirespec.kotest:kotest-wirespec-emitter:0.0.0-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    website.set("https://github.com/kotest/kotest-wirespec")
    vcsUrl.set("https://github.com/kotest/kotest-wirespec.git")
    plugins {
        create("kotestWirespec") {
            id = "community.flock.wirespec.kotest"
            displayName = "Kotest Wirespec"
            description = "Extracts Wirespec contracts from Spring controllers and exposes a property-based scenario DSL for Kotest."
            tags.set(listOf("kotest", "spring", "wirespec", "property-based", "contract-testing"))
            implementationClass = "io.kotest.extensions.wirespec.gradle.KotestWirespecPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
