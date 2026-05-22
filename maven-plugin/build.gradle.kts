plugins {
    kotlin("jvm") version "2.3.0"
    `maven-publish`
}

group = "io.kotest.extensions"
version = (providers.gradleProperty("version").orNull) ?: "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

val mavenApiVersion = "3.9.6"
val mavenPluginAnnotationsVersion = "3.13.1"
val mojoExecutorVersion = "2.4.0"
val mavenInvokerVersion = "3.3.0"

dependencies {
    compileOnly("org.apache.maven:maven-plugin-api:$mavenApiVersion")
    compileOnly("org.apache.maven:maven-core:$mavenApiVersion")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:$mavenPluginAnnotationsVersion")

    implementation("org.twdata.maven:mojo-executor:$mojoExecutorVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.apache.maven.shared:maven-invoker:$mavenInvokerVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Token-expand the hand-written plugin descriptor so ${projectVersion} is
// replaced with the build version before it lands in the jar.
tasks.named<Copy>("processResources") {
    from("src/main/resources-template") {
        include("**/*.xml")
        expand(
            "projectVersion" to project.version.toString(),
            "extractorVersion" to (providers.gradleProperty("wirespecExtractorVersion").orNull ?: "0.0.5"),
            "wirespecVersion" to (providers.gradleProperty("wirespecVersion").orNull ?: "0.19.0-RC.3"),
        )
    }
    from("src/main/resources")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotest-extensions-spring-wirespec-maven-plugin"
            pom {
                name.set("Kotest Spring Wirespec Maven Plugin")
                description.set("Extracts Wirespec contracts from Spring controllers and generates a typesafe Kotest DSL.")
                packaging = "maven-plugin"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
