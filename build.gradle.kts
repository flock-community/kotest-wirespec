plugins {
    kotlin("jvm") version "2.3.0" apply false
}

allprojects {
    group = (rootProject.findProperty("group") as String?) ?: "io.kotest.extensions.wirespec"
    version = (rootProject.findProperty("version") as String?) ?: "0.0.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }
}
