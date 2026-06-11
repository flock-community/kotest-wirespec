# Configurable `wirespecPath` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable `wirespecPath` setting to both the Gradle and Maven plugins so users can point Wirespec generation at a `.ws` source folder directly, as an explicit alternative to the Spring extractor.

**Architecture:** Keep the existing `spring` boolean as the extractor on/off toggle. Add one new `wirespecPath` property. When set, it always becomes the Wirespec compile input; with `spring = true` the extractor also writes into it. When unset, the input defaults to the extractor output dir (`spring = true`) or `src/test/wirespec` (`spring = false`). The Maven `spring = false` default input moves from `build/wirespec` to `src/test/wirespec` to match Gradle.

**Tech Stack:** Kotlin, Gradle plugin API (`Property`/`DirectoryProperty`, `ProjectBuilder` test fixtures), Maven Mojo API (`@Parameter`), `maven-invoker` integration test, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-10-configurable-wirespec-path-design.md`

---

## File Structure

- `gradle-plugin/.../KotestWirespecExtension.kt` — add `wirespecPath: DirectoryProperty`.
- `gradle-plugin/.../KotestWirespecPlugin.kt` — resolve compile input + extractor output through `wirespecPath`.
- `gradle-plugin/src/test/kotlin/.../KotestWirespecPluginTest.kt` — **new**: ProjectBuilder unit tests for the input-resolution branches.
- `maven-plugin/.../KotestWirespecMojo.kt` — add `wirespecPath` parameter + input/output resolution.
- `maven-plugin/src/test/resources/fixture-direct/` — **new**: direct-mode Maven fixture (pom + hand-authored `.ws`).
- `maven-plugin/src/test/kotlin/.../MavenInvokerIT.kt` — parametrize `locateFixture`, add a direct-mode test.
- `README.md` — document `wirespecPath` in the Gradle and Maven sections.

A note on Gradle test coverage: the new unit tests cover the two `spring = false` branches (default `src/test/wirespec` and explicit `wirespecPath`). The `spring = true` extractor wiring is **not** unit-tested here — it requires the upstream Spring-extractor plugin and `extractWirespec` task, which the `example/` project already exercises in a real build. This is a deliberate scope boundary, not an oversight.

---

## Task 1: Gradle — add `wirespecPath` to the extension

**Files:**
- Modify: `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecExtension.kt`

- [ ] **Step 1: Add the `wirespecPath` property**

Replace the entire file with:

```kotlin
package io.kotest.extensions.wirespec.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory

abstract class KotestWirespecExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Base package whose `@RestController`-annotated classes the extractor scans.
     * Required when [spring] is `true`.
     */
    abstract val basePackage: Property<String>

    /**
     * Package name for the generated Kotlin sources. Defaults to `<basePackage>.generated`.
     */
    abstract val generatedPackage: Property<String>

    /**
     * Enable the Wirespec Spring extractor (scan `@RestController`s in
     * [basePackage], emit `.ws` files, then compile them). Defaults to `true`
     * when `org.springframework.boot` is applied to this project, otherwise
     * `false`. When `false`, the plugin compiles `.ws` files from
     * [wirespecPath] (or `src/test/wirespec` if unset).
     */
    abstract val spring: Property<Boolean>

    /**
     * Folder of `.ws` contracts to compile. When set, this is always the
     * compile input — even with [spring] `true`, in which case the extractor
     * writes its emitted `.ws` files here before compilation (use a dedicated
     * directory; the extractor overwrites it on each run). When unset, the
     * input defaults to the extractor output dir (`spring = true`) or
     * `src/test/wirespec` (`spring = false`).
     */
    abstract val wirespecPath: DirectoryProperty
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :gradle-plugin:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecExtension.kt
git commit -m "feat(gradle-plugin): add wirespecPath extension property"
```

---

## Task 2: Gradle — failing test for input resolution

**Files:**
- Create: `gradle-plugin/src/test/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPluginTest.kt`

- [ ] **Step 1: Write the failing tests**

Create the file with:

```kotlin
package io.kotest.extensions.wirespec.gradle

import community.flock.wirespec.plugin.gradle.CompileWirespecTask
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class KotestWirespecPluginTest {

    private fun evaluatedProject(configure: (Project, KotestWirespecExtension) -> Unit): Project {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(KotestWirespecPlugin::class.java)
        val ext = project.extensions.getByType(KotestWirespecExtension::class.java)
        ext.basePackage.set("com.example")
        configure(project, ext)
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun compileInput(project: Project): File =
        project.tasks.named("wirespecKotlin", CompileWirespecTask::class.java)
            .get().input.get().asFile

    @Test
    fun `spring false without path reads src test wirespec`() {
        val project = evaluatedProject { _, ext -> ext.spring.set(false) }
        assertEquals(
            project.layout.projectDirectory.dir("src/test/wirespec").asFile,
            compileInput(project),
        )
    }

    @Test
    fun `wirespecPath overrides the compile input`() {
        val project = evaluatedProject { p, ext ->
            ext.spring.set(false)
            ext.wirespecPath.set(p.layout.projectDirectory.dir("contracts"))
        }
        assertEquals(
            project.layout.projectDirectory.dir("contracts").asFile,
            compileInput(project),
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :gradle-plugin:test --tests "io.kotest.extensions.wirespec.gradle.KotestWirespecPluginTest"`
Expected: FAIL — `wirespecPath overrides the compile input` fails because the plugin ignores `wirespecPath` (input still resolves to `src/test/wirespec`). The first test may already pass; that is fine.

- [ ] **Step 3: Commit the failing test**

```bash
git add gradle-plugin/src/test/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPluginTest.kt
git commit -m "test(gradle-plugin): cover wirespecPath input resolution"
```

---

## Task 3: Gradle — wire `wirespecPath` into the plugin

**Files:**
- Modify: `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPlugin.kt`

- [ ] **Step 1: Resolve the compile input through `wirespecPath`**

In `KotestWirespecPlugin.kt`, find the `compileTask` registration and change the `task.input.set(defaultInputDir)` line. Replace this block:

```kotlin
                    task.description = "Generate Kotlin sources + typesafe DSL from the extracted Wirespec contracts."
                    task.group = "wirespec"
                    // Default input is used when spring=false; afterEvaluate
                    // below overrides it to the extracted dir when spring=true.
                    task.input.set(defaultInputDir)
                    task.output.set(generatedDir)
```

with:

```kotlin
                    task.description = "Generate Kotlin sources + typesafe DSL from the extracted Wirespec contracts."
                    task.group = "wirespec"
                    // wirespecPath, when set, always wins. Otherwise this
                    // default applies when spring=false; the afterEvaluate
                    // block below overrides it to the extracted dir when
                    // spring=true and wirespecPath is unset.
                    task.input.set(extension.wirespecPath.orElse(defaultInputDir))
                    task.output.set(generatedDir)
```

- [ ] **Step 2: Resolve the extractor output + spring input through `wirespecPath`**

In the same file, replace the `afterEvaluate` Spring branch. Replace this block:

```kotlin
                project.pluginManager.apply("community.flock.wirespec.spring.extractor")
                val extractorExt = project.extensions.getByType(WirespecExtractorExtension::class.java)
                extractorExt.outputDir.set(extractedDir)
                extractorExt.basePackage.set(extension.basePackage)

                val extractTask = project.tasks.named("extractWirespec", ExtractWirespecTask::class.java)
                compileTask.configure(
                    object : Action<CompileWirespecTask> {
                        override fun execute(task: CompileWirespecTask) {
                            task.input.set(extractedDir)
                            task.dependsOn(extractTask)
                        }
                    },
                )
```

with:

```kotlin
                project.pluginManager.apply("community.flock.wirespec.spring.extractor")
                val extractorExt = project.extensions.getByType(WirespecExtractorExtension::class.java)
                // wirespecPath, when set, is both where the extractor writes
                // and where the compile task reads; otherwise the build dir.
                extractorExt.outputDir.set(extension.wirespecPath.orElse(extractedDir))
                extractorExt.basePackage.set(extension.basePackage)

                val extractTask = project.tasks.named("extractWirespec", ExtractWirespecTask::class.java)
                compileTask.configure(
                    object : Action<CompileWirespecTask> {
                        override fun execute(task: CompileWirespecTask) {
                            task.input.set(extension.wirespecPath.orElse(extractedDir))
                            task.dependsOn(extractTask)
                        }
                    },
                )
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `./gradlew :gradle-plugin:test --tests "io.kotest.extensions.wirespec.gradle.KotestWirespecPluginTest"`
Expected: PASS (both tests)

- [ ] **Step 4: Commit**

```bash
git add gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPlugin.kt
git commit -m "feat(gradle-plugin): route compile input + extractor output through wirespecPath"
```

---

## Task 4: Maven — add `wirespecPath` parameter and resolve input

**Files:**
- Modify: `maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt`

- [ ] **Step 1: Add the `wirespecPath` parameter**

In `KotestWirespecMojo.kt`, after the `generatedDir` parameter declaration:

```kotlin
    @Parameter(defaultValue = "\${project.build.directory}/generated-sources/wirespec")
    lateinit var generatedDir: File
```

add:

```kotlin
    /**
     * Folder of `.ws` contracts to compile. When set, this is always the
     * compile input — even with spring=true, in which case the extractor
     * writes its emitted `.ws` files here (use a dedicated directory). When
     * unset, the input defaults to [extractedDir] (spring=true) or
     * `src/test/wirespec` (spring=false).
     */
    @Parameter(property = "kotestWirespec.wirespecPath")
    var wirespecPath: File? = null
```

- [ ] **Step 2: Resolve extractor output + compile input through `wirespecPath`**

In `execute()`, replace this block:

```kotlin
        val springEnabled = spring ?: hasSpringBootOnClasspath()

        if (springEnabled) {
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
        } else {
            log.info(
                "Skipping wirespec-spring-extractor (kotestWirespec.spring = false). " +
                    "Using pre-existing .ws files at ${extractedDir.absolutePath}.",
            )
        }
```

with:

```kotlin
        val springEnabled = spring ?: hasSpringBootOnClasspath()

        val extractorOutput = wirespecPath ?: extractedDir
        val inputDir = wirespecPath
            ?: if (springEnabled) extractedDir else File(project.basedir, "src/test/wirespec")

        if (springEnabled) {
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
                    element("output", extractorOutput.absolutePath),
                ),
                env,
            )
        } else {
            log.info(
                "Skipping wirespec-spring-extractor (kotestWirespec.spring = false). " +
                    "Using .ws files at ${inputDir.absolutePath}.",
            )
        }
```

- [ ] **Step 3: Use `inputDir` for the compile goal**

In the same method, in the second `executeMojo` (the `compile` goal), replace:

```kotlin
            configuration(
                element("input", extractedDir.absolutePath),
                element("output", generatedDir.absolutePath),
```

with:

```kotlin
            configuration(
                element("input", inputDir.absolutePath),
                element("output", generatedDir.absolutePath),
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :maven-plugin:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt
git commit -m "feat(maven-plugin): add wirespecPath parameter and resolve compile input"
```

---

## Task 5: Maven — direct-mode fixture

**Files:**
- Create: `maven-plugin/src/test/resources/fixture-direct/pom.xml`
- Create: `maven-plugin/src/test/resources/fixture-direct/src/test/wirespec/pet.ws`

- [ ] **Step 1: Create the hand-authored contract**

Create `maven-plugin/src/test/resources/fixture-direct/src/test/wirespec/pet.ws`:

```
endpoint GetPet GET /api/pets/{id: String} -> {
  200 -> PetResponse
}

type PetResponse {
  id: String,
  name: String
}
```

- [ ] **Step 2: Create the direct-mode pom**

Create `maven-plugin/src/test/resources/fixture-direct/pom.xml`. This is the standard fixture pom with `<spring>false</spring>` and a `<wirespecPath>` pointing at the checked-in `.ws` folder:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.kotest.extensions.wirespec.fixture</groupId>
    <artifactId>maven-fixture-direct</artifactId>
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
            <artifactId>spring-boot-starter-web</artifactId>
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
            <groupId>io.kotest.extensions.wirespec</groupId>
            <artifactId>kotest-wirespec</artifactId>
            <version>${wirespec.runtime.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.kotest.extensions.wirespec</groupId>
            <artifactId>kotest-wirespec-spring</artifactId>
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
                        <id>test-compile</id>
                        <phase>process-test-sources</phase>
                        <goals><goal>test-compile</goal></goals>
                        <configuration>
                            <sourceDirs>
                                <sourceDir>${project.build.directory}/generated-sources/wirespec</sourceDir>
                            </sourceDirs>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>io.kotest.extensions.wirespec</groupId>
                <artifactId>kotest-wirespec-maven-plugin</artifactId>
                <version>${wirespec.plugin.version}</version>
                <executions>
                    <execution>
                        <goals><goal>generate</goal></goals>
                        <configuration>
                            <basePackage>example</basePackage>
                            <spring>false</spring>
                            <wirespecPath>${project.basedir}/src/test/wirespec</wirespecPath>
                        </configuration>
                    </execution>
                </executions>
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

Note: this fixture has no `src/test/kotlin` sources and no surefire config — the IT runs only up to `test-compile` to prove generation + compilation of the generated DSL. The `spring` compiler plugin and Spring Boot parent are kept only to match the standard fixture's toolchain; the extractor is never invoked because `spring=false`.

- [ ] **Step 3: Commit**

```bash
git add maven-plugin/src/test/resources/fixture-direct
git commit -m "test(maven-plugin): add direct-mode wirespecPath fixture"
```

---

## Task 6: Maven — direct-mode integration test

**Files:**
- Modify: `maven-plugin/src/test/kotlin/io/kotest/extensions/wirespec/maven/MavenInvokerIT.kt`

- [ ] **Step 1: Parametrize `locateFixture` by name**

In `MavenInvokerIT.kt`, replace the `locateFixture` method:

```kotlin
    private fun locateFixture(): File {
        val onClasspath = javaClass.getResource("/fixture/pom.xml")
        if (onClasspath != null) {
            return File(onClasspath.toURI()).parentFile
        }
        val module = File(System.getProperty("user.dir"))
        return module.resolve("src/test/resources/fixture")
    }
```

with:

```kotlin
    private fun locateFixture(name: String = "fixture"): File {
        val onClasspath = javaClass.getResource("/$name/pom.xml")
        if (onClasspath != null) {
            return File(onClasspath.toURI()).parentFile
        }
        val module = File(System.getProperty("user.dir"))
        return module.resolve("src/test/resources/$name")
    }
```

- [ ] **Step 2: Add the direct-mode test**

In the same class, after the existing `fixture builds end-to-end with mvn verify` test method, add:

```kotlin
    @Test
    fun `direct mode generates from wirespecPath without running the extractor`() {
        val fixtureSrc = locateFixture("fixture-direct")
        val workDir = Files.createTempDirectory("kotest-wirespec-direct")
        copyDir(fixtureSrc.toPath(), workDir)

        val request = DefaultInvocationRequest().apply {
            baseDirectory = workDir.toFile()
            goals = listOf("test-compile")
            isBatchMode = true
            javaHome = File(System.getProperty("java.home"))
        }

        val invoker = DefaultInvoker().apply {
            mavenHome = resolveMavenHome()
                ?: error("Could not locate Maven. Set MAVEN_HOME or ensure mvn is on PATH.")
        }

        val result: InvocationResult = invoker.execute(request)
        assertEquals(0, result.exitCode, "mvn test-compile failed in direct-mode fixture (see logs above)")

        val target = workDir.resolve("target")

        // Extractor must NOT have run: no build/wirespec output dir.
        val extracted = target.resolve("wirespec").toFile()
        assertTrue(
            !extracted.exists(),
            "Expected no extractor output at $extracted in direct mode, but it exists",
        )

        // DSL generated from the hand-authored pet.ws.
        val generated = target.resolve("generated-sources/wirespec").toFile()
        val generatedFiles = generated.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            generatedFiles.any { it.path.contains("/endpoint/") },
            "Expected a generated endpoint .kt file; saw ${generatedFiles.map { it.path }}",
        )
        assertTrue(
            generatedFiles.any { it.path.contains("/kotest/") },
            "Expected a generated DSL .kt file; saw ${generatedFiles.map { it.path }}",
        )
    }
```

- [ ] **Step 3: Run the new test**

Run: `./gradlew :maven-plugin:test --tests "io.kotest.extensions.wirespec.maven.MavenInvokerIT.direct mode generates from wirespecPath without running the extractor"`
Expected: PASS. (Requires a local Maven and the `0.0.0-SNAPSHOT` artifacts published to mavenLocal — same prerequisite as the existing IT; see the wirespec-snapshot-toolchain memory note.)

- [ ] **Step 4: Run the full Maven IT to confirm no regression**

Run: `./gradlew :maven-plugin:test --tests "io.kotest.extensions.wirespec.maven.MavenInvokerIT"`
Expected: PASS (both the extractor and direct-mode tests).

- [ ] **Step 5: Commit**

```bash
git add maven-plugin/src/test/kotlin/io/kotest/extensions/wirespec/maven/MavenInvokerIT.kt
git commit -m "test(maven-plugin): cover direct wirespecPath mode end-to-end"
```

---

## Task 7: Documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the Gradle config comment**

In `README.md`, replace line 18:

```kotlin
    // spring = false  // opt out of Spring extraction; supply .ws files under src/test/wirespec/
```

with:

```kotlin
    // spring = false                                  // opt out of Spring extraction
    // wirespecPath.set(file("src/test/wirespec"))     // compile .ws files from this folder
```

- [ ] **Step 2: Update the explanatory paragraph**

Replace line 27:

```markdown
By default the plugin auto-detects whether `org.springframework.boot` is applied and wires the Spring extractor accordingly. Set `kotestWirespec { spring = false }` to skip extraction and supply hand-authored `.ws` files via `src/test/wirespec/` (or configure the upstream `community.flock.wirespec.plugin.gradle` extension).
```

with:

```markdown
By default the plugin auto-detects whether `org.springframework.boot` is applied and wires the Spring extractor accordingly. Set `kotestWirespec { spring = false }` to skip extraction and compile hand-authored `.ws` files instead — from `src/test/wirespec/` by default, or from a folder you set via `wirespecPath`. `wirespecPath`, when set, is always the compile input; with `spring = true` the extractor writes its emitted `.ws` files there before compiling (point it at a dedicated directory).
```

- [ ] **Step 3: Update the Maven config snippet**

Replace line 41:

```xml
                <!-- <spring>false</spring> -->
```

with:

```xml
                <!-- <spring>false</spring> -->
                <!-- <wirespecPath>${project.basedir}/src/test/wirespec</wirespecPath> -->
```

- [ ] **Step 4: Verify the README renders sensibly**

Run: `git diff README.md`
Expected: the three edits above, no stray changes.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: document wirespecPath option for gradle and maven plugins"
```

---

## Final verification

- [ ] **Step 1: Build and test the plugin modules**

Run: `./gradlew :gradle-plugin:test :maven-plugin:test`
Expected: BUILD SUCCESSFUL — Gradle unit tests pass; both Maven ITs pass.

- [ ] **Step 2: Confirm the example project still builds (spring=true path unchanged)**

Run: `./gradlew :example:compileTestKotlin`
Expected: BUILD SUCCESSFUL — extractor mode still wires generated sources.
