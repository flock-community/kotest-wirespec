# Maven Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Maven plugin (`io.kotest.extensions:kotest-extensions-spring-wirespec-maven-plugin`) that gives Maven users the same one-block UX as the existing Gradle plugin: extract Wirespec from Spring controllers, generate the typesafe DSL with `TypesafeDslEmitter`, and register the output as a test source root.

**Architecture:** New Gradle subproject `maven-plugin/` produces a Maven plugin jar. A single mojo `generate` (bound to `generate-test-sources`) delegates to upstream `wirespec-spring-extractor-maven-plugin:extract` and `wirespec-maven-plugin:compile` via [`org.twdata.maven:mojo-executor`](https://github.com/mojohaus/mojo-executor), passing the emitter as a plugin-realm dependency. The plugin descriptor `META-INF/maven/plugin.xml` is hand-written and version-substituted at build time. Verified end-to-end by a JUnit integration test that drives `mvn verify` against a sample Spring project via `maven-invoker`.

**Tech Stack:** Kotlin 2.3.0 (JVM target 21), Gradle build, Maven Plugin API 3.9.6, mojo-executor 2.4.0, Maven Invoker 3.3.0, JUnit 5. Upstream pinned: `wirespec-spring-extractor-maven-plugin:0.0.5`, `wirespec-maven-plugin:0.17.20`.

**Spec:** `docs/superpowers/specs/2026-05-22-maven-plugin-design.md`

---

## File Structure

**New files:**

- `maven-plugin/build.gradle.kts` — Gradle build for the plugin jar + local publish wiring
- `maven-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec/maven/KotestWirespecSpringMojo.kt` — the single `generate` mojo
- `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml` — hand-written descriptor template (token-expanded by Gradle)
- `maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/PluginDescriptorTest.kt` — sanity test for descriptor generation
- `maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/MavenInvokerIT.kt` — end-to-end integration test
- `maven-plugin/src/test/resources/fixture/pom.xml` — fixture Spring app pom
- `maven-plugin/src/test/resources/fixture/src/main/kotlin/example/ExampleApplication.kt`
- `maven-plugin/src/test/resources/fixture/src/main/kotlin/example/PetController.kt`
- `maven-plugin/src/test/resources/fixture/src/test/kotlin/example/PetSmokeSpec.kt`

**Modified files:**

- `settings.gradle.kts` — composite-include `maven-plugin/`
- `emitter/build.gradle.kts` — add `maven-publish`, publish with stable artifactId
- `README.md` — add Maven usage section

---

## Task 1: Make the emitter publishable to mavenLocal

The upstream `wirespec-maven-plugin` will need to load `TypesafeDslEmitter` from its plugin realm. We add it as a `<dependency>` on the upstream plugin via mojo-executor, which means the emitter must be resolvable as a Maven artifact. For local integration testing we publish to `~/.m2/repository`.

**Files:**
- Modify: `emitter/build.gradle.kts`

- [ ] **Step 1: Read the current emitter build to confirm starting state**

Read: `emitter/build.gradle.kts`. Confirm it has `kotlin("jvm")` and `java-library` but no `maven-publish` plugin and no `publishing { }` block.

- [ ] **Step 2: Add maven-publish plugin and publication**

Replace the entire contents of `emitter/build.gradle.kts` with:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
}

group = "io.kotest.extensions"
version = (providers.gradleProperty("version").orNull) ?: "0.0.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotest-extensions-spring-wirespec-emitter"
            pom {
                name.set("Kotest Spring Wirespec Emitter")
                description.set("TypesafeDslEmitter: Wirespec Emitter that produces a typesafe Kotest DSL per endpoint.")
            }
        }
    }
}
```

The `artifactId` must be exactly `kotest-extensions-spring-wirespec-emitter` — the mojo and fixture will reference that coordinate.

- [ ] **Step 3: Run publishToMavenLocal to verify wiring**

Run: `cd /Users/wilmveel/Projects/kotest-spring/emitter && ./gradlew --no-daemon publishToMavenLocal`

Expected output: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify the artifact landed in ~/.m2 with the right coordinates**

Run: `ls ~/.m2/repository/io/kotest/extensions/kotest-extensions-spring-wirespec-emitter/0.0.0-SNAPSHOT/`

Expected output includes:
- `kotest-extensions-spring-wirespec-emitter-0.0.0-SNAPSHOT.jar`
- `kotest-extensions-spring-wirespec-emitter-0.0.0-SNAPSHOT.pom`
- `kotest-extensions-spring-wirespec-emitter-0.0.0-SNAPSHOT-sources.jar`

- [ ] **Step 5: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add emitter/build.gradle.kts
git commit -m "build(emitter): publish to mavenLocal with stable artifactId"
```

---

## Task 2: Create the maven-plugin module skeleton

Establish the module on disk, register it in `settings.gradle.kts`, and confirm Gradle picks it up. Empty source dirs at this point — content lands in later tasks.

**Files:**
- Create: `maven-plugin/build.gradle.kts`
- Create: `maven-plugin/settings.gradle.kts`
- Create: `maven-plugin/src/main/kotlin/.gitkeep`
- Create: `maven-plugin/src/main/resources-template/.gitkeep`
- Create: `maven-plugin/src/test/kotlin/.gitkeep`
- Create: `maven-plugin/src/test/resources/.gitkeep`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create the maven-plugin directory layout**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring
mkdir -p maven-plugin/src/main/kotlin
mkdir -p maven-plugin/src/main/resources-template/META-INF/maven
mkdir -p maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven
mkdir -p maven-plugin/src/test/resources
touch maven-plugin/src/main/kotlin/.gitkeep
touch maven-plugin/src/test/kotlin/.gitkeep
touch maven-plugin/src/test/resources/.gitkeep
```

- [ ] **Step 2: Create `maven-plugin/settings.gradle.kts`**

This file allows the plugin's own build to be invoked standalone (and matches `plugin/` and `emitter/`).

Create: `maven-plugin/settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "kotest-extensions-spring-wirespec-maven-plugin"
```

`mavenLocal()` is included so the build can resolve the emitter from `~/.m2` during the integration test.

- [ ] **Step 3: Create `maven-plugin/build.gradle.kts`**

Create: `maven-plugin/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    `maven-publish`
}

group = "io.kotest.extensions"
version = (providers.gradleProperty("version").orNull) ?: "0.0.0-SNAPSHOT"

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

// Token-expand the hand-written plugin descriptor so ${project.version} is
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
    // Pick up any non-template resources untouched (none yet, but future-proof).
    from("src/main/resources")
}

// Mark the published jar's packaging as `maven-plugin` so Maven picks up the
// plugin.xml descriptor.
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
```

Notes on the choices:
- Maven Plugin API / annotations are `compileOnly` — Maven provides them at runtime.
- `mojo-executor` is `implementation` — it must be on the plugin's classpath when Maven invokes us.
- `processResources` reads templates from `src/main/resources-template/` so token-expansion uses Gradle's `expand` (Groovy `SimpleTemplateEngine` style). Files in `src/main/resources` (none right now) are copied verbatim.
- We override `packaging` in the POM. The jar artifact is otherwise a normal jar.

- [ ] **Step 4: Composite-include the new module from the root settings**

Read: `settings.gradle.kts` (the project root file).

Use Edit to modify it. Old text:

```
includeBuild("emitter")

rootProject.name = "kotest-extensions-spring-wirespec"
```

New text:

```
includeBuild("emitter")
includeBuild("maven-plugin")

rootProject.name = "kotest-extensions-spring-wirespec"
```

- [ ] **Step 5: Verify Gradle can configure the new module**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring
./gradlew --no-daemon :maven-plugin:tasks --no-rebuild
```

Wait — composite builds are addressed differently. Run instead:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
./gradlew --no-daemon tasks
```

If `maven-plugin/` has no `gradlew` of its own (it doesn't — Gradle wrapper lives at the project root), use:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon tasks
```

Expected output: a normal `tasks` listing including `compileKotlin`, `processResources`, `publishToMavenLocal`. No errors.

- [ ] **Step 6: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/build.gradle.kts maven-plugin/settings.gradle.kts \
        maven-plugin/src/main/kotlin/.gitkeep maven-plugin/src/test/kotlin/.gitkeep \
        maven-plugin/src/test/resources/.gitkeep settings.gradle.kts
git commit -m "build(maven-plugin): bootstrap module skeleton"
```

---

## Task 3: Hand-write the plugin descriptor template

Maven loads plugins by reading `META-INF/maven/plugin.xml` from the jar. Since we have exactly one mojo, hand-writing is simpler than pulling `maven-plugin-plugin` into our Gradle build. The template uses Gradle's `expand` placeholders: `${projectVersion}`, `${extractorVersion}`, `${wirespecVersion}`. The extractor/wirespec versions are not strictly needed in plugin.xml (the mojo embeds them as constants), but having them surface in metadata is useful for debugging.

**Files:**
- Create: `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`

- [ ] **Step 1: Write the descriptor**

Create: `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plugin>
  <name>Kotest Spring Wirespec Maven Plugin</name>
  <description>Extracts Wirespec contracts from Spring controllers and generates a typesafe Kotest DSL.</description>
  <groupId>io.kotest.extensions</groupId>
  <artifactId>kotest-extensions-spring-wirespec-maven-plugin</artifactId>
  <version>${projectVersion}</version>
  <goalPrefix>kotest-wirespec</goalPrefix>
  <isolatedRealm>false</isolatedRealm>
  <inheritedByDefault>true</inheritedByDefault>
  <requiredJavaVersion>21</requiredJavaVersion>
  <requiredMavenVersion>3.9.0</requiredMavenVersion>
  <mojos>
    <mojo>
      <goal>generate</goal>
      <description>Extract Wirespec from Spring controllers and generate the typesafe Kotest DSL.</description>
      <requiresDependencyResolution>test</requiresDependencyResolution>
      <requiresDirectInvocation>false</requiresDirectInvocation>
      <requiresProject>true</requiresProject>
      <requiresReports>false</requiresReports>
      <aggregator>false</aggregator>
      <requiresOnline>false</requiresOnline>
      <inheritedByDefault>true</inheritedByDefault>
      <phase>generate-test-sources</phase>
      <implementation>io.kotest.extensions.spring.wirespec.maven.KotestWirespecSpringMojo</implementation>
      <language>java</language>
      <instantiationStrategy>per-lookup</instantiationStrategy>
      <executionStrategy>once-per-session</executionStrategy>
      <threadSafe>true</threadSafe>
      <parameters>
        <parameter>
          <name>basePackage</name>
          <type>java.lang.String</type>
          <required>true</required>
          <editable>true</editable>
          <description>Base package whose @RestController classes are scanned.</description>
        </parameter>
        <parameter>
          <name>generatedPackage</name>
          <type>java.lang.String</type>
          <required>false</required>
          <editable>true</editable>
          <description>Package for generated Kotlin sources. Defaults to {basePackage}.generated.</description>
        </parameter>
        <parameter>
          <name>extractedDir</name>
          <type>java.io.File</type>
          <required>false</required>
          <editable>true</editable>
          <description>Directory where extracted .ws files are written.</description>
        </parameter>
        <parameter>
          <name>generatedDir</name>
          <type>java.io.File</type>
          <required>false</required>
          <editable>true</editable>
          <description>Directory where generated Kotlin DSL sources are written and added as test source root.</description>
        </parameter>
        <parameter>
          <name>project</name>
          <type>org.apache.maven.project.MavenProject</type>
          <required>true</required>
          <editable>false</editable>
          <description>The Maven project (injected).</description>
        </parameter>
        <parameter>
          <name>session</name>
          <type>org.apache.maven.execution.MavenSession</type>
          <required>true</required>
          <editable>false</editable>
          <description>The Maven session (injected).</description>
        </parameter>
        <parameter>
          <name>pluginManager</name>
          <type>org.apache.maven.plugin.BuildPluginManager</type>
          <required>true</required>
          <editable>false</editable>
          <description>Build plugin manager (injected).</description>
        </parameter>
      </parameters>
      <configuration>
        <basePackage implementation="java.lang.String">${'$'}{kotestWirespecSpring.basePackage}</basePackage>
        <generatedPackage implementation="java.lang.String">${'$'}{kotestWirespecSpring.generatedPackage}</generatedPackage>
        <extractedDir implementation="java.io.File" default-value="${'$'}{project.build.directory}/wirespec/extracted"/>
        <generatedDir implementation="java.io.File" default-value="${'$'}{project.build.directory}/generated-sources/wirespec"/>
        <project implementation="org.apache.maven.project.MavenProject" default-value="${'$'}{project}"/>
        <session implementation="org.apache.maven.execution.MavenSession" default-value="${'$'}{session}"/>
      </configuration>
    </mojo>
  </mojos>
</plugin>
```

Two subtleties:
- `${'$'}{project.build.directory}` is the Gradle `expand` escape for a literal `${project.build.directory}` — the dollar sign is *not* a Gradle placeholder; it's a Maven property that Maven resolves at runtime.
- `${projectVersion}` (no escape) IS a Gradle placeholder — it gets replaced by the build version.

- [ ] **Step 2: Run processResources and inspect the output**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon processResources
cat build/resources/main/META-INF/maven/plugin.xml | head -10
```

Expected: `<version>0.0.0-SNAPSHOT</version>` (or whatever `version` is set to), and `${project.build.directory}` lines still containing the literal `${project.build.directory}`.

If you see `${projectVersion}` unresolved, the `expand` wiring is wrong — revisit Task 2 Step 3.

If you see `${project.build.directory}` resolved to a Gradle build path, the `${'$'}` escape didn't work — fix the template.

- [ ] **Step 3: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml
git commit -m "build(maven-plugin): hand-written plugin descriptor template"
```

---

## Task 4: Add a sanity test for the descriptor

Tiny unit test that loads the descriptor from the test classpath and asserts the `${projectVersion}` token was substituted and the mojo class FQCN matches what we'll implement next. Catches build-wiring regressions instantly.

**Files:**
- Create: `maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/PluginDescriptorTest.kt`

- [ ] **Step 1: Write the failing test**

Create: `maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/PluginDescriptorTest.kt`

```kotlin
package io.kotest.extensions.spring.wirespec.maven

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginDescriptorTest {

    @Test
    fun `descriptor is on the classpath`() {
        val xml = readDescriptor()
        assertNotNull(xml, "META-INF/maven/plugin.xml not found on test classpath")
    }

    @Test
    fun `projectVersion token has been substituted`() {
        val xml = readDescriptor()!!
        assertFalse(
            xml.contains("\${projectVersion}"),
            "Expected \${projectVersion} to be substituted by Gradle expand",
        )
        assertTrue(
            xml.contains("<version>"),
            "Descriptor should declare a <version>",
        )
    }

    @Test
    fun `mojo implementation FQCN matches the Kotlin class`() {
        val xml = readDescriptor()!!
        assertTrue(
            xml.contains("<implementation>io.kotest.extensions.spring.wirespec.maven.KotestWirespecSpringMojo</implementation>"),
            "Mojo implementation FQCN drifted from descriptor",
        )
    }

    @Test
    fun `goal name and phase are wired correctly`() {
        val xml = readDescriptor()!!
        assertTrue(xml.contains("<goal>generate</goal>"))
        assertTrue(xml.contains("<phase>generate-test-sources</phase>"))
        assertTrue(xml.contains("<goalPrefix>kotest-wirespec</goalPrefix>"))
    }

    private fun readDescriptor(): String? =
        javaClass.getResource("/META-INF/maven/plugin.xml")?.readText()
}
```

- [ ] **Step 2: Run the test to verify it passes (descriptor is already wired)**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon test --tests '*PluginDescriptorTest*'
```

Expected: all four tests pass. The descriptor was produced by Task 3, so this test is *post-hoc* verification rather than the usual fail-first TDD — we're guarding against future regressions to the `processResources` wiring.

If a test fails, fix the descriptor template (Task 3) or the `expand` block (Task 2 Step 3) before continuing.

- [ ] **Step 3: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/PluginDescriptorTest.kt
git commit -m "test(maven-plugin): assert descriptor token substitution and mojo wiring"
```

---

## Task 5: Implement the `generate` mojo

The mojo itself. All it does: delegate to two upstream mojos via `mojo-executor`, then register the generated dir as a test source root. No business logic to TDD in isolation — the integration test in Task 8 is the real verification.

**Files:**
- Create: `maven-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec/maven/KotestWirespecSpringMojo.kt`

- [ ] **Step 1: Write the mojo**

Create: `maven-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec/maven/KotestWirespecSpringMojo.kt`

```kotlin
package io.kotest.extensions.spring.wirespec.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.twdata.maven.mojoexecutor.MojoExecutor.artifactId
import org.twdata.maven.mojoexecutor.MojoExecutor.configuration
import org.twdata.maven.mojoexecutor.MojoExecutor.dependency
import org.twdata.maven.mojoexecutor.MojoExecutor.element
import org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo
import org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment
import org.twdata.maven.mojoexecutor.MojoExecutor.goal
import org.twdata.maven.mojoexecutor.MojoExecutor.groupId
import org.twdata.maven.mojoexecutor.MojoExecutor.plugin
import org.twdata.maven.mojoexecutor.MojoExecutor.version
import java.io.File

@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true,
)
class KotestWirespecSpringMojo : AbstractMojo() {

    @Parameter(property = "kotestWirespecSpring.basePackage", required = true)
    lateinit var basePackage: String

    @Parameter(property = "kotestWirespecSpring.generatedPackage")
    var generatedPackage: String? = null

    @Parameter(defaultValue = "\${project.build.directory}/wirespec/extracted")
    lateinit var extractedDir: File

    @Parameter(defaultValue = "\${project.build.directory}/generated-sources/wirespec")
    lateinit var generatedDir: File

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    lateinit var session: MavenSession

    @Component
    lateinit var pluginManager: BuildPluginManager

    override fun execute() {
        val env = executionEnvironment(project, session, pluginManager)
        val effectivePackage = generatedPackage?.takeIf { it.isNotBlank() }
            ?: "$basePackage.generated"

        log.info("Extracting Wirespec from package $basePackage")
        executeMojo(
            plugin(
                groupId(EXTRACTOR_GROUP),
                artifactId(EXTRACTOR_ARTIFACT),
                version(EXTRACTOR_VERSION),
            ),
            goal("extract"),
            configuration(
                element("basePackage", basePackage),
                element("output", extractedDir.absolutePath),
            ),
            env,
        )

        log.info("Generating typesafe Kotest DSL into $generatedDir (package $effectivePackage)")
        executeMojo(
            plugin(
                groupId(WIRESPEC_GROUP),
                artifactId(WIRESPEC_ARTIFACT),
                version(WIRESPEC_VERSION),
                listOf(
                    dependency(EMITTER_GROUP, EMITTER_ARTIFACT, EMITTER_VERSION),
                ),
            ),
            goal("compile"),
            configuration(
                element("input", extractedDir.absolutePath),
                element("output", generatedDir.absolutePath),
                element("packageName", effectivePackage),
                element("emitterClass", EMITTER_FQCN),
                element("languages", element("language", "Kotlin")),
            ),
            env,
        )

        project.addTestCompileSourceRoot(generatedDir.absolutePath)
    }

    private companion object {
        const val EXTRACTOR_GROUP = "community.flock.wirespec.spring"
        const val EXTRACTOR_ARTIFACT = "wirespec-spring-extractor-maven-plugin"
        const val EXTRACTOR_VERSION = "0.0.5"

        const val WIRESPEC_GROUP = "community.flock.wirespec.plugin.maven"
        const val WIRESPEC_ARTIFACT = "wirespec-maven-plugin"
        const val WIRESPEC_VERSION = "0.17.20"

        const val EMITTER_GROUP = "io.kotest.extensions"
        const val EMITTER_ARTIFACT = "kotest-extensions-spring-wirespec-emitter"
        const val EMITTER_VERSION = "0.0.0-SNAPSHOT"
        const val EMITTER_FQCN =
            "io.kotest.extensions.spring.wirespec.emitter.TypesafeDslEmitter"
    }
}
```

Notes:
- `EMITTER_VERSION` is intentionally hard-coded to `0.0.0-SNAPSHOT` for the local-only iteration. Once a release goes out, this becomes a constant matching the published version. We do *not* try to read the Gradle project version at runtime — the constant is what gets compiled into the jar, and the jar is what end-users run.
- `languages` is passed as `<languages><language>Kotlin</language></languages>` — the upstream `wirespec-maven-plugin:compile` mojo accepts a `java.util.List` for that parameter.
- `effectivePackage` collapses empty/blank to the default rather than letting Maven inject an empty string.

- [ ] **Step 2: Compile the module to catch any reference errors**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon compileKotlin
```

Expected: `BUILD SUCCESSFUL`. If any `mojo-executor` API symbol is unresolved, double-check `import` lines — mojo-executor 2.4.0's API is in `org.twdata.maven.mojoexecutor.MojoExecutor`.

- [ ] **Step 3: Rerun the descriptor test to confirm the mojo class now exists**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon test --tests '*PluginDescriptorTest*'
```

Expected: still passes — the descriptor referenced `KotestWirespecSpringMojo` as a string, and the class now exists. (The test checks the string, not class loading.)

- [ ] **Step 4: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec/maven/KotestWirespecSpringMojo.kt
git commit -m "feat(maven-plugin): generate mojo delegating to extractor + wirespec compile"
```

---

## Task 6: Build the integration test fixture (Spring app + Kotest spec)

A minimal Spring Boot Maven project that the integration test will compile and run. Mirrors `example/` in shape, but with one trimmed-down endpoint to keep test runtime small.

The fixture POM declares: Spring Boot Maven plugin, Kotlin Maven plugin, our new wrapper plugin, and the runtime artifact (`kotest-extensions-spring-wirespec`) as a test dep.

**Files:**
- Create: `maven-plugin/src/test/resources/fixture/pom.xml`
- Create: `maven-plugin/src/test/resources/fixture/src/main/kotlin/example/ExampleApplication.kt`
- Create: `maven-plugin/src/test/resources/fixture/src/main/kotlin/example/PetController.kt`
- Create: `maven-plugin/src/test/resources/fixture/src/test/kotlin/example/PetSmokeSpec.kt`

- [ ] **Step 1: Create the fixture directory structure**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring
mkdir -p maven-plugin/src/test/resources/fixture/src/main/kotlin/example
mkdir -p maven-plugin/src/test/resources/fixture/src/test/kotlin/example
```

- [ ] **Step 2: Write the fixture pom.xml**

Create: `maven-plugin/src/test/resources/fixture/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.kotest.extensions.spring.wirespec.fixture</groupId>
    <artifactId>maven-fixture</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.1</version>
        <relativePath/>
    </parent>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <kotlin.version>2.3.0</kotlin.version>
        <kotest.version>6.1.11</kotest.version>
        <wirespec.runtime.version>0.0.0-SNAPSHOT</wirespec.runtime.version>
        <wirespec.plugin.version>0.0.0-SNAPSHOT</wirespec.plugin.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.module</groupId>
            <artifactId>jackson-module-kotlin</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-reflect</artifactId>
            <version>${kotlin.version}</version>
        </dependency>
        <dependency>
            <groupId>io.swagger.core.v3</groupId>
            <artifactId>swagger-annotations</artifactId>
            <version>2.2.25</version>
        </dependency>

        <dependency>
            <groupId>io.kotest.extensions</groupId>
            <artifactId>kotest-extensions-spring-wirespec</artifactId>
            <version>${wirespec.runtime.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <testSourceDirectory>src/test/kotlin</testSourceDirectory>

        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <configuration>
                    <jvmTarget>21</jvmTarget>
                    <args>
                        <arg>-Xjsr305=strict</arg>
                        <arg>-java-parameters</arg>
                    </args>
                    <compilerPlugins>
                        <plugin>spring</plugin>
                    </compilerPlugins>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-allopen</artifactId>
                        <version>${kotlin.version}</version>
                    </dependency>
                </dependencies>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>process-sources</phase>
                        <goals><goal>compile</goal></goals>
                    </execution>
                    <execution>
                        <id>test-compile</id>
                        <phase>process-test-sources</phase>
                        <goals><goal>test-compile</goal></goals>
                        <configuration>
                            <sourceDirs>
                                <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
                                <sourceDir>${project.build.directory}/generated-sources/wirespec</sourceDir>
                            </sourceDirs>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>io.kotest.extensions</groupId>
                <artifactId>kotest-extensions-spring-wirespec-maven-plugin</artifactId>
                <version>${wirespec.plugin.version}</version>
                <executions>
                    <execution>
                        <goals><goal>generate</goal></goals>
                        <configuration>
                            <basePackage>example</basePackage>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/*Spec.*</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <repositories>
        <repository>
            <id>maven-central</id>
            <url>https://repo.maven.apache.org/maven2</url>
        </repository>
    </repositories>
    <pluginRepositories>
        <pluginRepository>
            <id>maven-central</id>
            <url>https://repo.maven.apache.org/maven2</url>
        </pluginRepository>
    </pluginRepositories>
</project>
```

Two important wiring details inside this POM:

1. The Kotlin Maven plugin's `test-compile` execution explicitly lists `${project.build.directory}/generated-sources/wirespec` as an additional source dir. Unlike Gradle's Kotlin plugin (which honors `addTestCompileSourceRoot`), the Kotlin Maven plugin does not auto-discover Maven source roots — it compiles only what's listed in `<sourceDirs>`. Our mojo's `addTestCompileSourceRoot` is still useful (IDE imports respect it, and the standard Maven compiler would pick it up), but for Kotlin we have to be explicit.

2. The wrapper plugin's execution binds to `generate-test-sources`, which runs *before* `process-test-sources` (where Kotlin's `test-compile` is bound). So extraction → DSL generation → Kotlin test compile all fire in the correct order.

- [ ] **Step 3: Write the Spring Boot application**

Create: `maven-plugin/src/test/resources/fixture/src/main/kotlin/example/ExampleApplication.kt`

```kotlin
package example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExampleApplication

fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args)
}
```

- [ ] **Step 4: Write the controller (one endpoint to keep the smoke fast)**

Create: `maven-plugin/src/test/resources/fixture/src/main/kotlin/example/PetController.kt`

```kotlin
package example

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/pets")
class PetController {

    data class PetResponse(val id: String, val name: String)
    data class ErrorResponse(val code: String, val message: String)

    @GetMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = PetResponse::class))]),
        ApiResponse(responseCode = "404", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun getPet(@PathVariable id: String): ResponseEntity<Any> =
        if (id == "missing") {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("not_found", "pet $id not found"))
        } else {
            ResponseEntity.ok(PetResponse(id = id, name = "Rex"))
        }
}
```

- [ ] **Step 5: Write the smoke spec**

Create: `maven-plugin/src/test/resources/fixture/src/test/kotlin/example/PetSmokeSpec.kt`

```kotlin
package example

import example.generated.endpoint.GetPet
import example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.SpringScenarioSpec

class PetSmokeSpec : SpringScenarioSpec(ExampleApplication::class, {

    scenario("getPet round-trips", iterations = 3) {
        getPet
            .path("existing")
            .expecting<GetPet.Response200>()
    }
})
```

Both `GetPet.Response200` and the `getPet` DSL receiver are generated. If the build chain works end-to-end, this compiles and runs.

- [ ] **Step 6: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/src/test/resources/fixture
git commit -m "test(maven-plugin): integration fixture with controller and smoke spec"
```

---

## Task 7: Wire the test task to depend on all required `publishToMavenLocal` invocations

Before `MavenInvokerIT` can run, three artifacts must exist in `~/.m2/repository`:
1. The emitter (so `wirespec-maven-plugin` can load `TypesafeDslEmitter`).
2. The runtime (so the fixture's `SpringScenarioSpec` is resolvable).
3. The plugin itself (so the fixture's POM can apply it).

Add explicit `dependsOn` wiring on the maven-plugin's `test` task.

**Files:**
- Modify: `maven-plugin/build.gradle.kts`

- [ ] **Step 1: Add the publish-chain wiring to `tasks.test`**

Read `maven-plugin/build.gradle.kts`, then use Edit to replace the existing `tasks.test { useJUnitPlatform() }` block.

Old text:

```kotlin
tasks.test {
    useJUnitPlatform()
}
```

New text:

```kotlin
tasks.test {
    useJUnitPlatform()

    // The integration test invokes `mvn verify` against a fixture that pulls
    // emitter, runtime, and this plugin from ~/.m2. Install all three first.
    // We shell out to the outer Gradle wrapper because `runtime` is a regular
    // subproject of the outer build (not composite-included here), so its
    // tasks are not addressable from maven-plugin/'s build graph.
    val outerRoot = project.rootDir.parentFile
    val outerGradlew = outerRoot.resolve("gradlew").absolutePath

    doFirst {
        // Emitter — separate composite build sibling to maven-plugin.
        exec {
            workingDir = outerRoot.resolve("emitter")
            commandLine(outerGradlew, "--no-daemon", "publishToMavenLocal")
        }
        // Runtime — subproject of the outer composite.
        exec {
            workingDir = outerRoot
            commandLine(outerGradlew, "--no-daemon", ":runtime:publishToMavenLocal")
        }
        // This plugin itself.
        exec {
            workingDir = project.projectDir
            commandLine(outerGradlew, "--no-daemon", "publishToMavenLocal")
        }
    }
}
```

- [ ] **Step 2: Smoke-run the publish chain to confirm it works**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon publishToMavenLocal
ls ~/.m2/repository/io/kotest/extensions/kotest-extensions-spring-wirespec-maven-plugin/0.0.0-SNAPSHOT/
ls ~/.m2/repository/io/kotest/extensions/kotest-extensions-spring-wirespec/0.0.0-SNAPSHOT/
ls ~/.m2/repository/io/kotest/extensions/kotest-extensions-spring-wirespec-emitter/0.0.0-SNAPSHOT/
```

Expected: each `ls` shows a `.jar` and a `.pom`. If `kotest-extensions-spring-wirespec` (runtime) isn't there, run `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew --no-daemon :runtime:publishToMavenLocal` once manually — it's installed once and stays.

- [ ] **Step 3: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/build.gradle.kts
git commit -m "build(maven-plugin): install emitter, runtime, and plugin to mavenLocal before integration test"
```

---

## Task 8: Write the maven-invoker integration test

Drives `mvn verify` on the fixture and asserts the build passed and the expected generated files exist.

**Files:**
- Create: `maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/MavenInvokerIT.kt`

- [ ] **Step 1: Write the failing test**

Create: `maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/MavenInvokerIT.kt`

```kotlin
package io.kotest.extensions.spring.wirespec.maven

import org.apache.maven.shared.invoker.DefaultInvocationRequest
import org.apache.maven.shared.invoker.DefaultInvoker
import org.apache.maven.shared.invoker.InvocationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MavenInvokerIT {

    @Test
    fun `fixture builds end-to-end with mvn verify`() {
        val fixtureSrc = locateFixture()
        val workDir = Files.createTempDirectory("kotest-wirespec-fixture")
        copyDir(fixtureSrc.toPath(), workDir)

        val request = DefaultInvocationRequest().apply {
            baseDirectory = workDir.toFile()
            goals = listOf("verify")
            isBatchMode = true
            // Force a no-op Maven user settings so the test is hermetic and
            // ~/.m2/repository is used as the local repository.
            javaHome = File(System.getProperty("java.home"))
        }

        val invoker = DefaultInvoker().apply {
            // mvn is expected on PATH. If running on CI without mvn, the test
            // should fail loudly here rather than skipping silently.
            val mavenHome = System.getenv("MAVEN_HOME") ?: System.getenv("M2_HOME")
            if (mavenHome != null) {
                this.mavenHome = File(mavenHome)
            }
        }

        val result: InvocationResult = invoker.execute(request)
        assertEquals(0, result.exitCode, "mvn verify failed in fixture (see logs above)")

        val target = workDir.resolve("target")
        val extracted = target.resolve("wirespec/extracted").toFile()
        assertTrue(extracted.exists() && (extracted.listFiles()?.any { it.extension == "ws" } == true),
            "Expected at least one .ws file under $extracted")

        val generated = target.resolve("generated-sources/wirespec").toFile()
        val generatedFiles = generated.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(generatedFiles.any { it.path.contains("/endpoint/") },
            "Expected at least one generated endpoint .kt file")
        assertTrue(generatedFiles.any { it.path.contains("/kotest/") },
            "Expected at least one generated DSL .kt file")
    }

    private fun locateFixture(): File {
        // src/test/resources/fixture is copied into build/resources/test by Gradle.
        val onClasspath = javaClass.getResource("/fixture/pom.xml")
        if (onClasspath != null) {
            return File(onClasspath.toURI()).parentFile
        }
        // Fallback: resolve relative to the module dir.
        val module = File(System.getProperty("user.dir"))
        return module.resolve("src/test/resources/fixture")
    }

    private fun copyDir(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = target.resolve(source.relativize(src))
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
```

Notes:
- Requires `mvn` on the developer's PATH (or `MAVEN_HOME` env var). If not present, the test fails — that's a deliberate "tell me up front" rather than skip.
- Copies the fixture into a temp dir to keep the original sources clean and to give Maven a writable `target/`.
- Asserts both `.ws` extraction and `.kt` generation outputs to catch a partial-success regression where only the extractor ran.

- [ ] **Step 2: Run the integration test**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-spring/maven-plugin
/Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon test --tests '*MavenInvokerIT*'
```

This will be slow — it kicks off a real `mvn verify` cycle, downloading Spring Boot dependencies on first run. Budget ~3-5 minutes.

Expected outcome on success: test passes; Surefire output in the temp dir shows `PetSmokeSpec` ran one scenario (with 3 iterations) and passed.

Common failure modes and what to check:
- `mvn: command not found` → install Maven, or set `MAVEN_HOME`.
- `Could not resolve io.kotest.extensions:kotest-extensions-spring-wirespec-emitter` → emitter wasn't published to `~/.m2`. Re-run `:emitter:publishToMavenLocal` from the outer build.
- `ClassNotFoundException: io.kotest.extensions.spring.wirespec.emitter.TypesafeDslEmitter` → the `dependencies(...)` arg to the upstream plugin didn't propagate the emitter into its realm. Fallback per the spec's open-risks section: change the fixture POM to add the emitter as a `<dependency>` on the wrapper plugin block (i.e., make the user declare it — still one block, with one extra child).
- `Compile failure: SpringScenarioSpec not found` → runtime artifact missing from `~/.m2`. Re-run `:runtime:publishToMavenLocal`.

- [ ] **Step 3: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven/MavenInvokerIT.kt
git commit -m "test(maven-plugin): end-to-end maven-invoker integration test"
```

---

## Task 9: Update README with Maven usage

Document the Maven entry point alongside the existing Gradle one.

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Read the current README**

Read: `README.md`.

- [ ] **Step 2: Insert the Maven section right after the Gradle snippet**

Locate the Gradle `plugins { … }` snippet near the top of the README (under the heading "What you get for one `plugins { … }` line"). Immediately after the closing fence of *that* code block, insert a new heading and two new code blocks.

The exact insertion point is the blank line between the Gradle `kotestWirespecSpring { … }` block's closing fence and the line `That's the whole setup. Each `gradle test` now does:`.

Use Edit with this old_string (find by the unique anchor `That's the whole setup`):

```
}
```

That's the whole setup. Each `gradle test` now does:
```

…and this new_string:

```
}
```

…or, in Maven:

```xml
<plugin>
    <groupId>io.kotest.extensions</groupId>
    <artifactId>kotest-extensions-spring-wirespec-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <basePackage>com.example.api</basePackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Maven users also need to point the Kotlin Maven plugin at the generated dir as a test source root — unlike Gradle's Kotlin plugin, it doesn't auto-discover Maven test source roots:

```xml
<plugin>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>test-compile</id>
            <goals><goal>test-compile</goal></goals>
            <configuration>
                <sourceDirs>
                    <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
                    <sourceDir>${project.build.directory}/generated-sources/wirespec</sourceDir>
                </sourceDirs>
            </configuration>
        </execution>
    </executions>
</plugin>
```

That's the whole setup. Each `gradle test` now does:
```

The unique anchor `That's the whole setup` makes the old_string match exactly once.

- [ ] **Step 3: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add README.md
git commit -m "docs(readme): document Maven usage alongside Gradle"
```

---

## Self-review checklist (run after the last task)

- [ ] All 9 tasks committed.
- [ ] `git status` clean.
- [ ] `cd maven-plugin && /Users/wilmveel/Projects/kotest-spring/gradlew --no-daemon test` passes (both unit and integration tests).
- [ ] `ls ~/.m2/repository/io/kotest/extensions/kotest-extensions-spring-wirespec-maven-plugin/0.0.0-SNAPSHOT/` shows the published artifact.
- [ ] Re-read the spec's "Non-goals" section — confirm we did not silently expand scope (no Central publishing, no clean mojo, no example migration).
