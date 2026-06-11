# CI + Maven Central Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions CI pipeline and Maven Central (Central Portal) publishing for all five `kotest-wirespec` modules, modeled on the `wirespec-spring-extractor` reference repo and adapted to this repo's composite-build layout.

**Architecture:** Pin flock dependencies to released versions, move every published module to the `community.flock.wirespec.kotest` namespace, and configure vanniktech's `com.vanniktech.maven.publish.base` per Gradle build (root `subprojects` block for `core`+`spring`; inline in the `emitter`, `maven-plugin`, `gradle-plugin` included builds). Two root aggregator tasks (`checkAll`, `publishToMavenCentralAll`) drive CI and release; the release workflow propagates the version across composite builds via the `ORG_GRADLE_PROJECT_version` env var.

**Tech Stack:** Gradle (Kotlin DSL, composite builds via `includeBuild`), vanniktech maven-publish `0.30.0`, Sonatype Central Portal, GitHub Actions, Temurin JDK 21.

---

## Context for the implementer

This repo is **four separate Gradle builds** stitched together:

- **Root build** (`settings.gradle.kts` at repo root) → subprojects `:core`, `:spring`, `:example`.
- **`emitter/`** → its own build, pulled in with `includeBuild("emitter")`.
- **`maven-plugin/`** → its own build, `includeBuild("maven-plugin")`.
- **`gradle-plugin/`** → its own build, included via `pluginManagement { includeBuild("gradle-plugin") }`; it in turn does `includeBuild("../emitter")`.

Because of this, you **cannot** configure all modules from one root `subprojects {}` block — the three included builds are invisible to it. Each included build is configured in its own `build.gradle.kts`.

Two kinds of "SNAPSHOT" exist; do not confuse them:
- **The project's own `version`** (`0.0.0-SNAPSHOT`) — a dev default. **Leave it.** The release workflow overrides it.
- **Flock dependency versions** (wirespec, extractor) — these must be released coordinates or Central rejects the POMs. **These are what Task 1 changes.**

The base branch already has commit `b2b109c`, which points wirespec/extractor at `0.0.0-SNAPSHOT` + `mavenLocal` for local snapshot dev. This plan flips them back to released versions. **If the feature code fails to compile against `0.19.3-RC.1` (Task 1, Step 3), stop and report it** — it means the SNAPSHOT carried unreleased APIs and the release is blocked until they ship.

Run every Gradle command from the repo root (`/Users/wilmveel/Projects/kotest-wirespec`) unless a step says otherwise. The `-p <dir>` flag runs an included build standalone.

---

## File map

**Modified — dependency/version pins (Task 1):**
- `gradle.properties` — `wirespecVersion`, `wirespecExtractorVersion`
- `core/build.gradle.kts` — `val wirespecVersion`
- `emitter/build.gradle.kts` — `val wirespecVersion`
- `gradle-plugin/build.gradle.kts` — three flock deps
- `maven-plugin/build.gradle.kts` — `processResources` fallbacks

**Modified — namespace + plugin id (Task 2):**
- `gradle.properties`, root `build.gradle.kts`, `emitter/build.gradle.kts`, `maven-plugin/build.gradle.kts`, `gradle-plugin/build.gradle.kts`, `example/build.gradle.kts`, `settings.gradle.kts`
- `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`
- `maven-plugin/src/test/resources/fixture/pom.xml`, `maven-plugin/src/test/resources/fixture-direct/pom.xml`

**Modified — vanniktech publishing (Tasks 3-6):**
- root `build.gradle.kts`, `core/build.gradle.kts`, `spring/build.gradle.kts`
- `emitter/build.gradle.kts`, `maven-plugin/build.gradle.kts`, `gradle-plugin/build.gradle.kts`

**Modified — aggregator tasks (Task 7):**
- root `build.gradle.kts`

**Created — workflows (Tasks 8-9):**
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

---

## Task 1: Pin flock dependencies to released versions

**Files:**
- Modify: `gradle.properties`
- Modify: `core/build.gradle.kts`
- Modify: `emitter/build.gradle.kts`
- Modify: `gradle-plugin/build.gradle.kts`
- Modify: `maven-plugin/build.gradle.kts`

- [ ] **Step 1: Edit `gradle.properties`**

Change these two lines:

```properties
wirespecVersion=0.0.0-SNAPSHOT
wirespecExtractorVersion=0.0.0-SNAPSHOT
```

to:

```properties
wirespecVersion=0.19.3-RC.1
wirespecExtractorVersion=0.0.13
```

- [ ] **Step 2: Edit `core/build.gradle.kts`**

Change:

```kotlin
val wirespecVersion = "0.0.0-SNAPSHOT"
```

to:

```kotlin
val wirespecVersion = "0.19.3-RC.1"
```

- [ ] **Step 3: Edit `emitter/build.gradle.kts`**

Change:

```kotlin
val wirespecVersion = "0.0.0-SNAPSHOT"
```

to:

```kotlin
val wirespecVersion = "0.19.3-RC.1"
```

- [ ] **Step 4: Edit `gradle-plugin/build.gradle.kts`**

Change these two dependency lines:

```kotlin
    implementation("community.flock.wirespec.plugin.gradle:community.flock.wirespec.plugin.gradle.gradle.plugin:0.0.0-SNAPSHOT")
    implementation("community.flock.wirespec.spring:wirespec-spring-extractor-gradle-plugin:0.0.0-SNAPSHOT")
```

to:

```kotlin
    implementation("community.flock.wirespec.plugin.gradle:community.flock.wirespec.plugin.gradle.gradle.plugin:0.19.3-RC.1")
    implementation("community.flock.wirespec.spring:wirespec-spring-extractor-gradle-plugin:0.0.13")
```

Leave the `kotest-wirespec-emitter` line untouched for now (Task 2 changes its group; its version is supplied by the `includeBuild` substitution).

- [ ] **Step 5: Edit `maven-plugin/build.gradle.kts`**

In the `processResources` block, change:

```kotlin
            "extractorVersion" to (providers.gradleProperty("wirespecExtractorVersion").orNull ?: "0.0.10"),
            "wirespecVersion" to (providers.gradleProperty("wirespecVersion").orNull ?: "0.0.0-SNAPSHOT"),
```

to:

```kotlin
            "extractorVersion" to (providers.gradleProperty("wirespecExtractorVersion").orNull ?: "0.0.13"),
            "wirespecVersion" to (providers.gradleProperty("wirespecVersion").orNull ?: "0.19.3-RC.1"),
```

- [ ] **Step 6: Verify core + emitter compile against the released wirespec**

Run: `./gradlew :core:compileTestKotlin -p emitter compileTestKotlin --stacktrace`

If that combined form is awkward, run them separately:

Run: `./gradlew :core:test --stacktrace`
Run: `./gradlew -p emitter test --stacktrace`

Expected: BUILD SUCCESSFUL. This proves the scenario-DSL feature code (the `jackson.v2.kotlin` import, `Gen`-accepting slot setters) compiles and passes against `0.19.3-RC.1`.

**If compilation fails on a missing Wirespec symbol:** STOP. The SNAPSHOT had unreleased APIs. Do not work around it — report which symbol is missing so the version pin can be reconsidered.

- [ ] **Step 7: Commit**

```bash
git add gradle.properties core/build.gradle.kts emitter/build.gradle.kts gradle-plugin/build.gradle.kts maven-plugin/build.gradle.kts
git commit -m "build: pin wirespec 0.19.3-RC.1 and extractor 0.0.13"
```

---

## Task 2: Move to the `community.flock.wirespec.kotest` namespace + rename the Gradle plugin id

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle.kts` (root)
- Modify: `emitter/build.gradle.kts`
- Modify: `maven-plugin/build.gradle.kts`
- Modify: `gradle-plugin/build.gradle.kts`
- Modify: `example/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`
- Modify: `maven-plugin/src/test/resources/fixture/pom.xml`
- Modify: `maven-plugin/src/test/resources/fixture-direct/pom.xml`

- [ ] **Step 1: Edit `gradle.properties`**

Change:

```properties
group=io.kotest.extensions.wirespec
```

to:

```properties
group=community.flock.wirespec.kotest
```

- [ ] **Step 2: Edit root `build.gradle.kts`**

Change the `allprojects` group fallback:

```kotlin
    group = (rootProject.findProperty("group") as String?) ?: "io.kotest.extensions.wirespec"
```

to:

```kotlin
    group = (rootProject.findProperty("group") as String?) ?: "community.flock.wirespec.kotest"
```

- [ ] **Step 3: Edit `emitter/build.gradle.kts`**

Change:

```kotlin
group = "io.kotest.extensions.wirespec"
```

to:

```kotlin
group = "community.flock.wirespec.kotest"
```

- [ ] **Step 4: Edit `maven-plugin/build.gradle.kts`**

Change:

```kotlin
group = "io.kotest.extensions.wirespec"
```

to:

```kotlin
group = "community.flock.wirespec.kotest"
```

- [ ] **Step 5: Edit `gradle-plugin/build.gradle.kts` — group, emitter coordinate, and plugin id**

Change the project group:

```kotlin
group = "io.kotest.extensions.wirespec"
```

to:

```kotlin
group = "community.flock.wirespec.kotest"
```

Change the emitter dependency coordinate (so the `includeBuild` substitution still matches):

```kotlin
    implementation("io.kotest.extensions.wirespec:kotest-wirespec-emitter:0.0.0-SNAPSHOT")
```

to:

```kotlin
    implementation("community.flock.wirespec.kotest:kotest-wirespec-emitter:0.0.0-SNAPSHOT")
```

Change the plugin id inside the `gradlePlugin { plugins { create("kotestWirespec") { ... } } }` block:

```kotlin
            id = "io.kotest.extensions.wirespec"
```

to:

```kotlin
            id = "community.flock.wirespec.kotest"
```

Leave `implementationClass = "io.kotest.extensions.wirespec.gradle.KotestWirespecPlugin"` unchanged — that is an internal class name, not the plugin id.

- [ ] **Step 6: Edit `example/build.gradle.kts`**

Change the applied plugin id in the `plugins { }` block:

```kotlin
    id("io.kotest.extensions.wirespec")
```

to:

```kotlin
    id("community.flock.wirespec.kotest")
```

Leave the `kotestWirespec { basePackage.set("io.kotest.extensions.wirespec.example") }` block unchanged — `kotestWirespec` is the extension name (derived from the registration name `"kotestWirespec"`, which is not changing), and `basePackage` is the consumer's own package.

- [ ] **Step 7: Edit `settings.gradle.kts` comment**

Change the comment line:

```kotlin
    // `plugins { id("io.kotest.extensions.wirespec") }` and pick up the
```

to:

```kotlin
    // `plugins { id("community.flock.wirespec.kotest") }` and pick up the
```

- [ ] **Step 8: Edit `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`**

Change:

```xml
  <groupId>io.kotest.extensions.wirespec</groupId>
```

to:

```xml
  <groupId>community.flock.wirespec.kotest</groupId>
```

- [ ] **Step 9: Edit the two test fixture POMs**

In `maven-plugin/src/test/resources/fixture/pom.xml`, change the **three** `<groupId>io.kotest.extensions.wirespec</groupId>` occurrences (lines ~52, ~58, ~112 — the dependency and plugin references to our own artifacts) to `<groupId>community.flock.wirespec.kotest</groupId>`.

In `maven-plugin/src/test/resources/fixture-direct/pom.xml`, change the **three** `<groupId>io.kotest.extensions.wirespec</groupId>` occurrences (lines ~42, ~48, ~96) the same way.

**Do NOT change** the line-5 `<groupId>io.kotest.extensions.wirespec.fixture</groupId>` in either file — that is each fixture project's own coordinates, unrelated to resolving our artifacts.

Use this to confirm only the intended six lines remain after editing:

Run: `grep -rn ">io.kotest.extensions.wirespec<" maven-plugin/src/test/resources/`
Expected: no output (all six artifact references changed; the two `.fixture` lines do not match this pattern).

- [ ] **Step 10: Verify the root build still compiles and `:example` applies the renamed plugin**

Run: `./gradlew :example:compileTestKotlin --stacktrace`

Expected: BUILD SUCCESSFUL. This exercises the renamed plugin id end-to-end: `:example` applies `community.flock.wirespec.kotest` (resolved from the `gradle-plugin` included build), which depends on `kotest-wirespec-emitter` (resolved from the `emitter` included build via the updated coordinate).

- [ ] **Step 11: Verify the Maven integration test resolves the renamed artifacts**

Run: `./gradlew -p maven-plugin test --stacktrace`

Expected: BUILD SUCCESSFUL. The integration test publishes `core`/`spring`/`emitter`/`maven-plugin` to `mavenLocal` and runs `mvn` against the fixtures, which now reference the `community.flock.wirespec.kotest` group.

- [ ] **Step 12: Commit**

```bash
git add gradle.properties build.gradle.kts emitter/build.gradle.kts maven-plugin/build.gradle.kts gradle-plugin/build.gradle.kts example/build.gradle.kts settings.gradle.kts maven-plugin/src/main/resources-template maven-plugin/src/test/resources
git commit -m "build: move to community.flock.wirespec.kotest namespace and plugin id"
```

---

## Task 3: vanniktech publishing for `core` and `spring` (root build)

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `core/build.gradle.kts`
- Modify: `spring/build.gradle.kts`

This uses the reference repo's pattern: declare the plugin `apply false` on the root, apply `.base` in each subproject, and hold the shared config in a root `subprojects { plugins.withId(...) }` block.

- [ ] **Step 1: Edit root `build.gradle.kts` plugins block**

Change:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0" apply false
}
```

to:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("com.vanniktech.maven.publish.base") version "0.30.0" apply false
}
```

- [ ] **Step 2: Add the shared publishing config to root `build.gradle.kts`**

Add this block at the end of the file (after the existing `tasks.register("publishToMavenLocalAll")` block):

```kotlin
subprojects {
    plugins.withId("com.vanniktech.maven.publish.base") {
        val publishedArtifactId = when (name) {
            "core"   -> "kotest-wirespec"
            "spring" -> "kotest-wirespec-spring"
            else     -> error("vanniktech applied to unexpected subproject: $name")
        }
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            configureBasedOnAppliedPlugins()
            publishToMavenCentral(
                com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
                automaticRelease = true,
            )
            signAllPublications()
            coordinates(
                groupId = project.group.toString(),
                artifactId = publishedArtifactId,
                version = project.version.toString(),
            )
            pom {
                name.set(publishedArtifactId)
                description.set(provider { project.description ?: publishedArtifactId })
                url.set("https://github.com/flock-community/kotest-wirespec")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("wilmveel")
                        name.set("Willem Veelenturf")
                        email.set("willem.veelenturf@flock.community")
                        organization.set("Flock. Community")
                        organizationUrl.set("https://flock.community")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/flock-community/kotest-wirespec.git")
                    developerConnection.set("scm:git:ssh://github.com:flock-community/kotest-wirespec.git")
                    url.set("https://github.com/flock-community/kotest-wirespec")
                }
            }
        }
    }
}
```

- [ ] **Step 3: Edit `core/build.gradle.kts`**

In the `plugins { }` block, replace `` `maven-publish` `` with the vanniktech base plugin:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    id("com.vanniktech.maven.publish.base")
}
```

In the `java { }` block, remove `withSourcesJar()` (vanniktech adds the sources + javadoc jars itself):

```kotlin
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
```

Add a project `description` near the top (just under the `plugins`/`java` blocks, before `val wirespecVersion`) so the shared POM block can read it:

```kotlin
description = "Property-based scenario DSL for validating endpoints against Wirespec contracts."
```

Delete the entire trailing `publishing { publications { create<MavenPublication>("maven") { ... } } }` block — vanniktech now creates the publication.

- [ ] **Step 4: Edit `spring/build.gradle.kts`**

Same three edits. Plugins block:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    id("com.vanniktech.maven.publish.base")
}
```

`java { }` block — remove `withSourcesJar()`:

```kotlin
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
```

Add the description near the top:

```kotlin
description = "Spring transports (MockMvc, WebClient, EmbeddedKafka) and auto-registered ContextProvider for kotest-wirespec."
```

Delete the trailing `publishing { publications { create<MavenPublication>("maven") { ... } } }` block.

- [ ] **Step 5: Verify local publish + POM coordinates for core and spring**

Run: `./gradlew :core:publishToMavenLocal :spring:publishToMavenLocal --stacktrace`

Expected: BUILD SUCCESSFUL with no signing prompt (vanniktech skips signing for `publishToMavenLocal`).

Run: `cat ~/.m2/repository/community/flock/wirespec/kotest/kotest-wirespec/0.0.0-SNAPSHOT/kotest-wirespec-0.0.0-SNAPSHOT.pom`

Expected: `<groupId>community.flock.wirespec.kotest</groupId>`, `<artifactId>kotest-wirespec</artifactId>`, the Apache-2.0 license, the developer block, and wirespec dependencies at `0.19.3-RC.1` (no `-SNAPSHOT`).

Run: `ls ~/.m2/repository/community/flock/wirespec/kotest/kotest-wirespec/0.0.0-SNAPSHOT/`
Expected: a `-sources.jar` and `-javadoc.jar` are present alongside the main jar.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts core/build.gradle.kts spring/build.gradle.kts
git commit -m "build: publish core and spring to Maven Central via vanniktech"
```

---

## Task 4: vanniktech publishing for `emitter` (included build)

**Files:**
- Modify: `emitter/build.gradle.kts`

- [ ] **Step 1: Edit the `emitter/build.gradle.kts` plugins block**

Replace `` `maven-publish` `` with the vanniktech base plugin (with explicit version, since this is a standalone build):

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    id("com.vanniktech.maven.publish.base") version "0.30.0"
}
```

- [ ] **Step 2: Remove `withSourcesJar()` from the `java { }` block**

```kotlin
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
```

- [ ] **Step 3: Replace the `publishing { }` block with vanniktech config**

Delete the entire trailing `publishing { publications { create<MavenPublication>("maven") { ... } } }` block and add:

```kotlin
mavenPublishing {
    configureBasedOnAppliedPlugins()
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true,
    )
    signAllPublications()
    coordinates(group.toString(), "kotest-wirespec-emitter", version.toString())
    pom {
        name.set("kotest-wirespec-emitter")
        description.set("TypesafeDslEmitter: Wirespec Emitter that produces a typesafe Kotest DSL per endpoint.")
        url.set("https://github.com/flock-community/kotest-wirespec")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("wilmveel")
                name.set("Willem Veelenturf")
                email.set("willem.veelenturf@flock.community")
                organization.set("Flock. Community")
                organizationUrl.set("https://flock.community")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/flock-community/kotest-wirespec.git")
            developerConnection.set("scm:git:ssh://github.com:flock-community/kotest-wirespec.git")
            url.set("https://github.com/flock-community/kotest-wirespec")
        }
    }
}
```

- [ ] **Step 4: Verify local publish + POM**

Run: `./gradlew -p emitter publishToMavenLocal --stacktrace`

Expected: BUILD SUCCESSFUL, no signing prompt.

Run: `cat ~/.m2/repository/community/flock/wirespec/kotest/kotest-wirespec-emitter/0.0.0-SNAPSHOT/kotest-wirespec-emitter-0.0.0-SNAPSHOT.pom`

Expected: group `community.flock.wirespec.kotest`, artifactId `kotest-wirespec-emitter`, wirespec deps at `0.19.3-RC.1`, plus a `-sources.jar` and `-javadoc.jar` in the directory.

- [ ] **Step 5: Commit**

```bash
git add emitter/build.gradle.kts
git commit -m "build: publish emitter to Maven Central via vanniktech"
```

---

## Task 5: vanniktech publishing for `maven-plugin` (included build)

**Files:**
- Modify: `maven-plugin/build.gradle.kts`

- [ ] **Step 1: Edit the `maven-plugin/build.gradle.kts` plugins block**

Replace `` `maven-publish` `` with the vanniktech base plugin:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.vanniktech.maven.publish.base") version "0.30.0"
}
```

- [ ] **Step 2: Remove `withSourcesJar()` from the `java { }` block**

```kotlin
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
```

- [ ] **Step 3: Replace the `publishing { }` block with vanniktech config**

Delete the entire trailing `publishing { publications { create<MavenPublication>("maven") { ... packaging = "maven-plugin" ... } } }` block and add:

```kotlin
mavenPublishing {
    configureBasedOnAppliedPlugins()
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true,
    )
    signAllPublications()
    coordinates(group.toString(), "kotest-wirespec-maven-plugin", version.toString())
    pom {
        name.set("kotest-wirespec-maven-plugin")
        description.set("Extracts Wirespec contracts from Spring controllers and generates a typesafe Kotest DSL.")
        packaging = "maven-plugin"
        url.set("https://github.com/flock-community/kotest-wirespec")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("wilmveel")
                name.set("Willem Veelenturf")
                email.set("willem.veelenturf@flock.community")
                organization.set("Flock. Community")
                organizationUrl.set("https://flock.community")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/flock-community/kotest-wirespec.git")
            developerConnection.set("scm:git:ssh://github.com:flock-community/kotest-wirespec.git")
            url.set("https://github.com/flock-community/kotest-wirespec")
        }
    }
}
```

Note: the existing `tasks.named<Copy>("processResources")` block and the `publishEmitterToMavenLocal` / `publishCoreToMavenLocal` / `publishSpringToMavenLocal` Exec tasks and the `tasks.test { dependsOn(...) }` wiring stay exactly as they are — do not touch them. The `tasks.named("publishToMavenLocal")` they reference is still provided by vanniktech.

- [ ] **Step 4: Verify the full maven-plugin build (integration test included)**

Run: `./gradlew -p maven-plugin build --stacktrace`

Expected: BUILD SUCCESSFUL. This runs the `mvn`-based integration test, which depends on unsigned `publishToMavenLocal` of core/spring/emitter/maven-plugin — the key confirmation that vanniktech does not break local publishing in CI.

Run: `cat ~/.m2/repository/community/flock/wirespec/kotest/kotest-wirespec-maven-plugin/0.0.0-SNAPSHOT/kotest-wirespec-maven-plugin-0.0.0-SNAPSHOT.pom`
Expected: `<packaging>maven-plugin</packaging>`, group `community.flock.wirespec.kotest`.

- [ ] **Step 5: Commit**

```bash
git add maven-plugin/build.gradle.kts
git commit -m "build: publish maven-plugin to Maven Central via vanniktech"
```

---

## Task 6: vanniktech publishing for `gradle-plugin` (included build)

**Files:**
- Modify: `gradle-plugin/build.gradle.kts`

- [ ] **Step 1: Edit the `gradle-plugin/build.gradle.kts` plugins block**

Add the vanniktech base plugin alongside the existing plugins (keep `com.gradle.plugin-publish` for the Gradle Plugin Portal):

```kotlin
plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.0"
    id("com.vanniktech.maven.publish.base") version "0.30.0"
}
```

- [ ] **Step 2: Add vanniktech config at the end of `gradle-plugin/build.gradle.kts`**

Append after the existing `tasks.test { useJUnitPlatform() }` block:

```kotlin
mavenPublishing {
    configureBasedOnAppliedPlugins()
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true,
    )
    signAllPublications()
    coordinates(group.toString(), "kotest-wirespec-gradle-plugin", version.toString())
    pom {
        name.set("kotest-wirespec-gradle-plugin")
        description.set("Extracts Wirespec contracts from Spring controllers and exposes a property-based scenario DSL for Kotest.")
        url.set("https://github.com/flock-community/kotest-wirespec")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("wilmveel")
                name.set("Willem Veelenturf")
                email.set("willem.veelenturf@flock.community")
                organization.set("Flock. Community")
                organizationUrl.set("https://flock.community")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/flock-community/kotest-wirespec.git")
            developerConnection.set("scm:git:ssh://github.com:flock-community/kotest-wirespec.git")
            url.set("https://github.com/flock-community/kotest-wirespec")
        }
    }
}
```

For a `java-gradle-plugin` project, `configureBasedOnAppliedPlugins()` (detecting `com.gradle.plugin-publish`) sets up both the plugin-marker publication (`community.flock.wirespec.kotest:community.flock.wirespec.kotest.gradle.plugin`) and the main library publication, each with sources + javadoc.

- [ ] **Step 3: Verify local publish + POM (incl. the emitter dependency version)**

Run: `./gradlew -p gradle-plugin publishToMavenLocal --stacktrace`

Expected: BUILD SUCCESSFUL.

Run: `cat ~/.m2/repository/community/flock/wirespec/kotest/kotest-wirespec-gradle-plugin/0.0.0-SNAPSHOT/kotest-wirespec-gradle-plugin-0.0.0-SNAPSHOT.pom`

Expected: group `community.flock.wirespec.kotest`; a dependency on `community.flock.wirespec.kotest:kotest-wirespec-emitter` (resolved via `includeBuild` substitution); the extractor dependency at `0.0.13` and `community.flock.wirespec.plugin.gradle` at `0.19.3-RC.1`.

Run: `ls ~/.m2/repository/community/flock/wirespec/kotest/community.flock.wirespec.kotest.gradle.plugin/`
Expected: the plugin-marker artifact directory exists.

- [ ] **Step 4: Commit**

```bash
git add gradle-plugin/build.gradle.kts
git commit -m "build: publish gradle-plugin to Maven Central via vanniktech"
```

---

## Task 7: Root aggregator tasks (`checkAll`, `publishToMavenCentralAll`)

**Files:**
- Modify: `build.gradle.kts` (root)

These give CI a single build command and the release workflow a single publish command that reach across all four builds (the existing `publishToMavenLocalAll` is the template).

> **Why `gradle-plugin` is not in these aggregators:** `emitter` and `maven-plugin` are included with top-level `includeBuild(...)`, so they are reachable via `gradle.includedBuild(...)`. `gradle-plugin` is included via `pluginManagement { includeBuild("gradle-plugin") }`, and pluginManagement-included builds are **not** returned by `gradle.includedBuild(...)` — this is why the existing `publishToMavenLocalAll` lists only `emitter` and `maven-plugin`. So `gradle-plugin` is driven by a separate `-p gradle-plugin` invocation in the CI and release workflows (Tasks 8 and 9).

- [ ] **Step 1: Add `checkAll` to root `build.gradle.kts`**

Append:

```kotlin
// Aggregates `check` across the root build (core, spring, example) plus the
// emitter and maven-plugin included builds so CI runs their tests in one
// invocation. gradle-plugin is checked separately (see workflows).
tasks.register("checkAll") {
    group = "verification"
    description = "Runs check for the root build plus the emitter and maven-plugin included builds."
    dependsOn(subprojects.map { ":${it.name}:check" })
    dependsOn(gradle.includedBuild("emitter").task(":check"))
    dependsOn(gradle.includedBuild("maven-plugin").task(":check"))
}
```

- [ ] **Step 2: Add `publishToMavenCentralAll` to root `build.gradle.kts`**

Append:

```kotlin
// One command to publish the root-build + top-level-included modules to Maven
// Central (Central Portal). The version is supplied via
// ORG_GRADLE_PROJECT_version (an env var), which — unlike -Pversion —
// propagates into the included builds. gradle-plugin is published separately
// (see the release workflow).
tasks.register("publishToMavenCentralAll") {
    group = "publishing"
    description = "Publishes core, spring, emitter and maven-plugin to Maven Central."
    dependsOn(":core:publishAndReleaseToMavenCentral", ":spring:publishAndReleaseToMavenCentral")
    dependsOn(gradle.includedBuild("emitter").task(":publishAndReleaseToMavenCentral"))
    dependsOn(gradle.includedBuild("maven-plugin").task(":publishAndReleaseToMavenCentral"))
}
```

- [ ] **Step 3: Verify `checkAll` runs the root + emitter + maven-plugin tests**

Run: `./gradlew checkAll --stacktrace`

Expected: BUILD SUCCESSFUL, with test tasks from `:core`, `:spring`, `:example`, `emitter`, and `maven-plugin` executed. (gradle-plugin is verified separately below.)

Run: `./gradlew -p gradle-plugin check --stacktrace`
Expected: BUILD SUCCESSFUL (gradle-plugin's own tests, plus its included emitter compile).

- [ ] **Step 4: Verify the publish task graph wires up without publishing**

Run: `./gradlew publishToMavenCentralAll --dry-run`

Expected: the printed task graph includes `:core:publishAndReleaseToMavenCentral`, `:spring:publishAndReleaseToMavenCentral`, and the `:emitter` and `:maven-plugin` `publishAndReleaseToMavenCentral` tasks. Nothing is uploaded (`--dry-run` only prints).

Run: `./gradlew -p gradle-plugin publishAndReleaseToMavenCentral --dry-run`
Expected: the graph includes the gradle-plugin's `publishAndReleaseToMavenCentral` (and its plugin-marker publication). Nothing uploaded.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add checkAll and publishToMavenCentralAll aggregator tasks"
```

---

## Task 8: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew checkAll --stacktrace
      - run: ./gradlew -p gradle-plugin check --stacktrace
```

- [ ] **Step 2: Validate the workflow YAML parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))" && echo OK`

Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions build pipeline"
```

---

## Task 9: Release workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create `.github/workflows/release.yml`**

```yaml
name: Release

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.release.tag_name }}
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to Maven Central
        env:
          # ORG_GRADLE_PROJECT_version (env, not -Pversion) so the version
          # reaches the emitter / maven-plugin / gradle-plugin included builds.
          ORG_GRADLE_PROJECT_version: ${{ github.event.release.tag_name }}
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.SONATYPE_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.SONATYPE_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          export ORG_GRADLE_PROJECT_version="$VERSION"
          ./gradlew publishToMavenCentralAll --no-configuration-cache --stacktrace
          ./gradlew -p gradle-plugin publishAndReleaseToMavenCentral --no-configuration-cache --stacktrace
```

Notes:
- `ORG_GRADLE_PROJECT_version` is set in `env:` (raw tag) and then `export`ed inline in `run:` with the `v`-stripped `$VERSION`; the exported value is the effective one for both Gradle invocations. This keeps the tag-stripping identical to the reference repo while using the env-var propagation our composite layout needs (it reaches the `emitter` build that `gradle-plugin` includes, so the `gradle-plugin` POM records the emitter at `$VERSION`).
- `gradle-plugin` is published in a second `-p gradle-plugin` invocation because it is a pluginManagement-included build (see Task 7). The signing/Central credentials in `env:` apply to both invocations.

- [ ] **Step 2: Validate the workflow YAML parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))" && echo OK`

Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add Maven Central release workflow"
```

---

## Post-implementation: required GitHub configuration (manual, not code)

These are **not** code steps — note them for the repo owner. The release workflow will fail without them:

1. Repository secrets: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` (Central Portal user token), `GPG_PRIVATE_KEY` (ASCII-armored secret key), `GPG_PASSPHRASE`.
2. The `community.flock.wirespec.kotest` namespace must be verified on central.sonatype.com.
3. First release: cut a GitHub Release with a tag (e.g. `v0.1.0`); the workflow publishes five separate deployments, each auto-releasing.

## Final verification (whole plan)

- [ ] Run `./gradlew checkAll --stacktrace` and `./gradlew -p gradle-plugin check --stacktrace` → both BUILD SUCCESSFUL (all modules' tests).
- [ ] Run `./gradlew publishToMavenLocalAll --stacktrace` → BUILD SUCCESSFUL; spot-check one POM under `~/.m2/repository/community/flock/wirespec/kotest/` for the new group and no `-SNAPSHOT` wirespec deps.
- [ ] Run `./gradlew publishToMavenCentralAll --dry-run` and `./gradlew -p gradle-plugin publishAndReleaseToMavenCentral --dry-run` → task graphs cover all five published modules across the four builds.
