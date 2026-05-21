plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

val wirespecVersion = "0.19.0-RC.3"
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
