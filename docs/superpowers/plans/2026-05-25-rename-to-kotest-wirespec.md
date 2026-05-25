# Rename to `kotest-wirespec` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the project from `kotest-extensions-spring-wirespec` to `kotest-wirespec` (group `io.kotest.extensions.wirespec`, package `io.kotest.extensions.wirespec.*`), split the Spring code into a separate auto-loaded module, switch to the Kotest-6-native upstream Spring extension, and add a `spring` boolean to the Gradle/Maven plugins that defaults to auto-detection.

**Architecture:**
- Core module `kotest-wirespec` owns the framework-neutral DSL, scenario runner, and a `ServiceLoader`-based `ContextProvider` SPI.
- New `kotest-wirespec-spring` module ships the Spring transports (`MockMvc`, `WebClient`, `EmbeddedKafka`) and a `SpringContextProvider` that registers itself via `META-INF/services` and mounts the upstream `io.kotest:kotest-extensions-spring-jvm:6.1.11` `SpringExtension`.
- One user-facing base class `WirespecSpec` in core; Spring lifecycle and contexts auto-wire when the spring module is on the test classpath.

**Tech Stack:** Kotlin 2.3.0 on JDK 21, Gradle 9 + composite builds, Maven 3.9 for the maven plugin tests, Kotest 6.1.11, Wirespec 0.19.0-RC.3/RC.4, Spring Boot 3.4.1.

**Spec:** `docs/superpowers/specs/2026-05-25-rename-to-kotest-wirespec-design.md` (commit `dc60874`).

**Approach:** The plan is staged so `:example` (or its Maven fixture equivalent) is green at every checkpoint. Each phase ends with a full test pass and a commit. The phases:

1. **Phase 1 — Upstream Spring extension swap.** Drop the project-local `SpringSpecExtension` wrapper by moving to `io.kotest:kotest-extensions-spring-jvm:6.1.11`. No structural change.
2. **Phase 2 — Mechanical rename.** Package + artifactId + groupId renames in a single pass. Same module structure, same files (modulo locations).
3. **Phase 3 — Split core/spring + introduce SPI.** Extract Spring code into a new `spring/` module, add the `ContextProvider` SPI in core, replace `SpringWirespecSpec` with `WirespecSpec`, migrate `example/`.
4. **Phase 4 — Gradle plugin `spring` flag.**
5. **Phase 5 — Maven plugin `spring` flag.**
6. **Phase 6 — Docs + memory.**

---

## Phase 1: Switch to Kotest 6 native upstream Spring extension

The current `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringSpecExtension.kt` is a wrapper that exists solely because `io.kotest.extensions:kotest-extensions-spring:1.3.0` was built against Kotest 5 and its `TestCaseExtension` codepath references symbols Kotest 6 removed. The newly discovered `io.kotest:kotest-extensions-spring-jvm:6.1.11` is Kotest 6 native — we can register `SpringExtension` directly and delete the wrapper.

### Task 1.1: Establish a clean green baseline

**Files:**
- Read-only: nothing modified.

- [ ] **Step 1: Verify working tree is clean of unrelated edits**

Run: `git status -s`

Expected: any pre-existing modifications are familiar (they exist in the initial conversation state). If new edits appeared since this plan was written, decide whether to stash them.

- [ ] **Step 2: Run the full Gradle test build to baseline**

Run: `./gradlew test --no-daemon`

Expected: BUILD SUCCESSFUL. If anything fails, fix or document it before starting Phase 1 — you don't want to chase pre-existing failures during the rename.

- [ ] **Step 3: Run the emitter sub-build tests**

Run: `(cd emitter && ./../gradlew test --no-daemon)`

Expected: BUILD SUCCESSFUL. The emitter is an `includeBuild`, so it doesn't always pick up from the root `:test` task; verify directly.

- [ ] **Step 4: Run the maven plugin integration test**

Run: `(cd maven-plugin && ./../gradlew test --no-daemon)`

Expected: BUILD SUCCESSFUL. This installs the runtime + emitter to `~/.m2` and shells out to `mvn verify` against the fixture; it's the slowest task in the suite (~2 min on a warm cache). If it fails on a clean baseline, *stop here* — the rest of the plan assumes this works.

### Task 1.2: Replace `kotest-extensions-spring:1.3.0` with `kotest-extensions-spring-jvm:6.1.11`

**Files:**
- Modify: `gradle.properties`
- Modify: `runtime/build.gradle.kts`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt`
- Delete: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringSpecExtension.kt`

- [ ] **Step 1: Bump the version property**

Edit `gradle.properties` line `kotestSpringExtensionVersion=1.3.0` → `kotestSpringExtensionVersion=6.1.11`.

- [ ] **Step 2: Swap the dependency coordinates in `runtime/build.gradle.kts`**

Find the block:

```kotlin
    api("io.kotest.extensions:kotest-extensions-spring:1.3.0") {
        exclude(group = "io.kotest", module = "kotest-framework-api")
        exclude(group = "io.kotest", module = "kotest-framework-api-jvm")
    }
```

Replace with:

```kotlin
    api("io.kotest:kotest-extensions-spring-jvm:6.1.11")
```

(The `kotest-framework-api` exclusions are no longer needed — they were a Kotest 5 vs 6 classpath shadowing workaround that is gone in the 6.x artifact.)

- [ ] **Step 3: Inline the wrapper at the one usage site**

Edit `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt`:

Change the import:

```kotlin
import io.kotest.extensions.spring.wirespec.kotest.SpringSpecExtension
```

to:

```kotlin
import io.kotest.extensions.spring.SpringExtension
```

In the `init { … }` block, change:

```kotlin
extension(SpringSpecExtension)
```

to:

```kotlin
extension(SpringExtension)
```

(`SpringExtension` is an `object` in the upstream 6.1.11 artifact, registered the same way.)

- [ ] **Step 4: Delete the wrapper file**

Run: `git rm runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringSpecExtension.kt`

Run: `rmdir runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest 2>/dev/null || true`

(The `kotest/` directory becomes empty; remove if so.)

- [ ] **Step 5: Run runtime tests**

Run: `./gradlew :runtime:test --no-daemon`

Expected: BUILD SUCCESSFUL. Failure modes:
- `NoClassDefFoundError: io/kotest/extensions/spring/SpringExtension` → bad coordinate, double-check Step 2.
- `Unresolved reference: SpringSpecExtension` → leftover import in `SpringWirespecSpec.kt`; re-do Step 3.

- [ ] **Step 6: Run the example tests (real Spring boot, real assertions)**

Run: `./gradlew :example:test --no-daemon`

Expected: BUILD SUCCESSFUL. This is the strongest signal that the upstream extension works end-to-end with `@SpringBootTest`, `@AutoConfigureMockMvc`, `@EmbeddedKafka`, etc.

- [ ] **Step 7: Commit**

```bash
git add gradle.properties runtime/build.gradle.kts runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt
git rm runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringSpecExtension.kt
git commit -m "$(cat <<'EOF'
refactor(runtime): drop SpringSpecExtension wrapper, use Kotest 6 native upstream

Switches from io.kotest.extensions:kotest-extensions-spring:1.3.0 (Kotest 5 era)
to io.kotest:kotest-extensions-spring-jvm:6.1.11, which is Kotest 6 native and
no longer triggers NoSuchMethodError on the TestCaseExtension codepath. Inlines
the use site to extension(SpringExtension) and removes the wrapper plus the
kotest-framework-api classpath exclusions that the old artifact required.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2: Mechanical rename of packages, artifactIds, and group

This phase is one large, mechanical find-and-replace plus directory moves. No behaviour changes. The goal at the end of the phase: identical functionality, new names everywhere. Tests should pass without modification (since they reference symbols by package, the imports update automatically).

Use `git mv` for directory renames so file history is preserved.

### Task 2.1: Update group and root project name

**Files:**
- Modify: `gradle.properties`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Bump group in `gradle.properties`**

Edit `gradle.properties`:

```
group=io.kotest.extensions.wirespec
```

(was `group=io.kotest.extensions`)

- [ ] **Step 2: Rename root project**

Edit `settings.gradle.kts` line `rootProject.name = "kotest-extensions-spring-wirespec"` → `rootProject.name = "kotest-wirespec"`.

- [ ] **Step 3: Verify the build still resolves (group changes propagate to publish coords only)**

Run: `./gradlew :runtime:dependencies --no-daemon | head -40`

Expected: command completes, no `Could not resolve` errors. Group changes affect only `publishToMavenLocal` coordinates, not internal project deps.

- [ ] **Step 4: Commit**

```bash
git add gradle.properties settings.gradle.kts
git commit -m "$(cat <<'EOF'
build: rename group to io.kotest.extensions.wirespec and project to kotest-wirespec

First step of the larger rename; package directories and artifactIds are
updated in follow-up commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.2: Rename runtime module directory to `core`

**Files:**
- Move: `runtime/` → `core/`
- Modify: `settings.gradle.kts` (project include + name)
- Modify: `example/build.gradle.kts` (project dependency)
- Modify: `maven-plugin/build.gradle.kts` (the `publishRuntimeToMavenLocal` task path)

- [ ] **Step 1: Move the directory with git**

Run: `git mv runtime core`

- [ ] **Step 2: Update root settings include**

Edit `settings.gradle.kts`:

```kotlin
include(":core")
include(":example")
```

(was `include(":runtime")`)

- [ ] **Step 3: Update `example/build.gradle.kts` test dependency**

Change `testImplementation(project(":runtime"))` → `testImplementation(project(":core"))`.

- [ ] **Step 4: Update `maven-plugin/build.gradle.kts` runtime publish task and rename the val**

Find:

```kotlin
val publishRuntimeToMavenLocal by tasks.registering(Exec::class) {
    workingDir = outerRoot
    commandLine(outerGradlew, "--no-daemon", ":runtime:publishToMavenLocal")
}
```

Replace with:

```kotlin
val publishCoreToMavenLocal by tasks.registering(Exec::class) {
    workingDir = outerRoot
    commandLine(outerGradlew, "--no-daemon", ":core:publishToMavenLocal")
}
```

Then update the `tasks.test { dependsOn(...) }` block below: change `publishRuntimeToMavenLocal` → `publishCoreToMavenLocal` in the dependency list. (Later tasks in this plan assume the val is named `publishCoreToMavenLocal`.)

- [ ] **Step 5: Run tests to verify the rename is consistent**

Run: `./gradlew :core:test :example:test --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts example/build.gradle.kts maven-plugin/build.gradle.kts core/
git commit -m "$(cat <<'EOF'
build: rename runtime module to core

Pure directory rename via git mv; references in settings.gradle.kts,
example/build.gradle.kts, and the maven-plugin runtime-publish task updated to
match. Package structure inside the module unchanged at this point — that's
the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.3: Rename package directories from `io/kotest/extensions/spring/wirespec/` to `io/kotest/extensions/wirespec/`

**Files:**
- Move: `core/src/main/kotlin/io/kotest/extensions/spring/wirespec/` → `core/src/main/kotlin/io/kotest/extensions/wirespec/` (and the `test/` mirror)
- Move: same in `emitter/`, `gradle-plugin/`, `maven-plugin/`, `example/`

- [ ] **Step 1: Move core/src/main**

Run:
```bash
mkdir -p core/src/main/kotlin/io/kotest/extensions/wirespec
git mv core/src/main/kotlin/io/kotest/extensions/spring/wirespec/* core/src/main/kotlin/io/kotest/extensions/wirespec/
rmdir core/src/main/kotlin/io/kotest/extensions/spring/wirespec
rmdir core/src/main/kotlin/io/kotest/extensions/spring
```

- [ ] **Step 2: Move core/src/test**

Run:
```bash
mkdir -p core/src/test/kotlin/io/kotest/extensions/wirespec
git mv core/src/test/kotlin/io/kotest/extensions/spring/wirespec/* core/src/test/kotlin/io/kotest/extensions/wirespec/
rmdir core/src/test/kotlin/io/kotest/extensions/spring/wirespec
rmdir core/src/test/kotlin/io/kotest/extensions/spring
```

- [ ] **Step 3: Repeat for emitter (main + test + resources/golden imports)**

Run:
```bash
mkdir -p emitter/src/main/kotlin/io/kotest/extensions/wirespec
git mv emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter
rmdir emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec
rmdir emitter/src/main/kotlin/io/kotest/extensions/spring

mkdir -p emitter/src/test/kotlin/io/kotest/extensions/wirespec
git mv emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter
rmdir emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec
rmdir emitter/src/test/kotlin/io/kotest/extensions/spring
```

(`emitter/src/test/resources/golden/` files are text content; their `import io.kotest.extensions.spring.wirespec...` lines get the sed update in Task 2.4.)

- [ ] **Step 4: Repeat for gradle-plugin**

Run:
```bash
mkdir -p gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec
git mv gradle-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec/gradle gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle
rmdir gradle-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec
rmdir gradle-plugin/src/main/kotlin/io/kotest/extensions/spring
```

- [ ] **Step 5: Repeat for maven-plugin**

Run:
```bash
mkdir -p maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec
git mv maven-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec/maven maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven
rmdir maven-plugin/src/main/kotlin/io/kotest/extensions/spring/wirespec
rmdir maven-plugin/src/main/kotlin/io/kotest/extensions/spring

mkdir -p maven-plugin/src/test/kotlin/io/kotest/extensions/wirespec
git mv maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec/maven maven-plugin/src/test/kotlin/io/kotest/extensions/wirespec/maven
rmdir maven-plugin/src/test/kotlin/io/kotest/extensions/spring/wirespec
rmdir maven-plugin/src/test/kotlin/io/kotest/extensions/spring
```

- [ ] **Step 6: Repeat for example (main + test)**

Run:
```bash
mkdir -p example/src/main/kotlin/io/kotest/extensions/wirespec
git mv example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example example/src/main/kotlin/io/kotest/extensions/wirespec/example
rmdir example/src/main/kotlin/io/kotest/extensions/spring/wirespec
rmdir example/src/main/kotlin/io/kotest/extensions/spring

mkdir -p example/src/test/kotlin/io/kotest/extensions/wirespec
git mv example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example example/src/test/kotlin/io/kotest/extensions/wirespec/example
rmdir example/src/test/kotlin/io/kotest/extensions/spring/wirespec
rmdir example/src/test/kotlin/io/kotest/extensions/spring
```

- [ ] **Step 7: Verify directories all moved cleanly**

Run: `find . -path '*/io/kotest/extensions/spring' -type d -not -path '*/build/*' -not -path '*/.git/*'`

Expected: no output (every occurrence relocated).

- [ ] **Step 8: Commit the move (build is broken until Task 2.4 fixes package declarations)**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: move source directories from io/kotest/extensions/spring/wirespec to io/kotest/extensions/wirespec

Pure directory rename via git mv; package declarations and imports are updated
in the follow-up commit. Build is intentionally broken between this commit and
the next one.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.4: Update package declarations, imports, and string references

**Files:**
- Modify: every `.kt`, `.kts`, `.xml`, `.md`, `.properties` file that currently contains `io.kotest.extensions.spring.wirespec` (43 files at start of this plan; verify with the grep below).
- Modify: every file containing the artifactId strings `kotest-extensions-spring-wirespec*`.

- [ ] **Step 1: Survey the files that need editing**

Run:
```bash
grep -rln 'io\.kotest\.extensions\.spring\.wirespec' --include='*.kt' --include='*.kts' --include='*.xml' --include='*.md' --include='*.properties' \
  --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.idea --exclude-dir=.kotlin
```

Expected: a list of ~43 files spanning core/, emitter/, gradle-plugin/, maven-plugin/, example/, the README, and the maven plugin's fixture POM + plugin descriptor template. Use this list as your batch target.

- [ ] **Step 2: Replace package + import paths**

Run (BSD sed on macOS — `-i ''`; switch to `-i` on Linux):

```bash
LC_ALL=C find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.md' -o -name '*.properties' \) \
  -not -path '*/build/*' -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/.idea/*' -not -path '*/.kotlin/*' \
  -exec sed -i '' 's|io\.kotest\.extensions\.spring\.wirespec|io.kotest.extensions.wirespec|g' {} +
```

- [ ] **Step 3: Replace artifactId strings**

Run:

```bash
LC_ALL=C find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.md' -o -name '*.properties' \) \
  -not -path '*/build/*' -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/.idea/*' -not -path '*/.kotlin/*' \
  -exec sed -i '' 's|kotest-extensions-spring-wirespec-emitter|kotest-wirespec-emitter|g' {} +

LC_ALL=C find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.md' -o -name '*.properties' \) \
  -not -path '*/build/*' -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/.idea/*' -not -path '*/.kotlin/*' \
  -exec sed -i '' 's|kotest-extensions-spring-wirespec-maven-plugin|kotest-wirespec-maven-plugin|g' {} +

LC_ALL=C find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.md' -o -name '*.properties' \) \
  -not -path '*/build/*' -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/.idea/*' -not -path '*/.kotlin/*' \
  -exec sed -i '' 's|kotest-extensions-spring-wirespec-gradle|kotest-wirespec-gradle|g' {} +

# The plain runtime artifact — has to come AFTER the `-emitter`, `-maven-plugin`,
# `-gradle` substitutions above, otherwise the shorter prefix swallows them.
LC_ALL=C find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.md' -o -name '*.properties' \) \
  -not -path '*/build/*' -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/.idea/*' -not -path '*/.kotlin/*' \
  -exec sed -i '' 's|kotest-extensions-spring-wirespec|kotest-wirespec|g' {} +
```

- [ ] **Step 4: Update the Maven plugin's emitter coordinates inside `KotestWirespecSpringMojo.kt`**

The Mojo class still has its old name; that's renamed in Task 2.8. For this commit, the `EMITTER_*` constants need a *manual* check because the sed above already mutated them. Inspect:

```bash
grep -n 'EMITTER_' maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/*.kt
```

Expected: `EMITTER_GROUP = "io.kotest.extensions"` should now read `io.kotest.extensions.wirespec` (matches `gradle.properties#group`); `EMITTER_ARTIFACT = "kotest-wirespec-emitter"` (already updated by sed); `EMITTER_FQCN` ends in `io.kotest.extensions.wirespec.emitter.TypesafeDslEmitter` (already updated).

If `EMITTER_GROUP` is still `"io.kotest.extensions"`, edit the constant to `"io.kotest.extensions.wirespec"`.

- [ ] **Step 5: Update the gradle-plugin's emitter dependency coordinate**

Edit `gradle-plugin/build.gradle.kts`:

```kotlin
implementation("io.kotest.extensions:kotest-extensions-spring-wirespec-emitter:0.0.0-SNAPSHOT")
```

Should now read (the sed above may have already done it):

```kotlin
implementation("io.kotest.extensions.wirespec:kotest-wirespec-emitter:0.0.0-SNAPSHOT")
```

If the group portion is still `io.kotest.extensions:` (sed only touched the artifactId), edit it manually.

- [ ] **Step 6: Sanity-check there are no stragglers**

Run:

```bash
grep -rln 'io\.kotest\.extensions\.spring\.wirespec\|kotest-extensions-spring-wirespec' \
  --include='*.kt' --include='*.kts' --include='*.xml' --include='*.md' --include='*.properties' \
  --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.idea --exclude-dir=.kotlin || echo OK
```

Expected: `OK` (no matches). If anything remains, edit it by hand — typically these are in the maven plugin descriptor template (`plugin.xml`) or in golden test fixtures.

- [ ] **Step 7: Build core (compile-only, before running tests)**

Run: `./gradlew :core:compileKotlin :core:compileTestKotlin --no-daemon`

Expected: BUILD SUCCESSFUL. Common failures:
- `Package directive doesn't match…` → a `.kt` file's `package io.kotest.extensions.wirespec.X` line wasn't updated. Re-run the sed in Step 2 (it's idempotent).
- `Unresolved reference` in a generated DSL file under `example/` → ignore for now; those are produced by the Gradle plugin and will regenerate when the plugin runs.

- [ ] **Step 8: Run the full Gradle test build**

Run: `./gradlew test --no-daemon`

Expected: BUILD SUCCESSFUL. If `:example:test` fails with import errors, run `./gradlew :example:clean :example:wirespecKotlin :example:test --no-daemon` — the generated DSL needs to regenerate against the new package.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: update package declarations, imports, and artifactId strings

Replaces all io.kotest.extensions.spring.wirespec → io.kotest.extensions.wirespec
and kotest-extensions-spring-wirespec* → kotest-wirespec* references across
Kotlin sources, Gradle scripts, the Maven plugin descriptor, the fixture POM,
the README, and the emitter's golden test fixtures. Behaviour unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.5: Rename Gradle plugin id and DSL extension (drop "Spring" from public names)

**Files:**
- Modify: `gradle-plugin/build.gradle.kts`
- Rename: `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecSpringPlugin.kt` → `KotestWirespecPlugin.kt`
- Rename: `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecSpringExtension.kt` → `KotestWirespecExtension.kt`
- Modify: `example/build.gradle.kts`

- [ ] **Step 1: Rename the plugin source files with git**

Run:
```bash
git mv gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecSpringPlugin.kt \
       gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPlugin.kt
git mv gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecSpringExtension.kt \
       gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecExtension.kt
```

- [ ] **Step 2: Rename the classes and DSL extension inside the renamed plugin file**

Edit `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPlugin.kt`:

Replace:

```kotlin
class KotestWirespecSpringPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "kotestWirespecSpring",
            KotestWirespecSpringExtension::class.java,
        )
```

with:

```kotlin
class KotestWirespecPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "kotestWirespec",
            KotestWirespecExtension::class.java,
        )
```

- [ ] **Step 3: Rename the class inside the renamed extension file**

Edit `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecExtension.kt`:

Replace `abstract class KotestWirespecSpringExtension` with `abstract class KotestWirespecExtension`.

- [ ] **Step 4: Update the Gradle plugin declaration**

Edit `gradle-plugin/build.gradle.kts` `gradlePlugin { plugins { … } }` block:

```kotlin
gradlePlugin {
    website.set("https://github.com/kotest/kotest-wirespec")
    vcsUrl.set("https://github.com/kotest/kotest-wirespec.git")
    plugins {
        create("kotestWirespec") {
            id = "io.kotest.extensions.wirespec"
            displayName = "Kotest Wirespec"
            description = "Extracts Wirespec contracts from Spring controllers and exposes a property-based scenario DSL for Kotest."
            tags.set(listOf("kotest", "spring", "wirespec", "property-based", "contract-testing"))
            implementationClass = "io.kotest.extensions.wirespec.gradle.KotestWirespecPlugin"
        }
    }
}
```

- [ ] **Step 5: Update example consumer**

Edit `example/build.gradle.kts`:

- Plugins block: `id("io.kotest.extensions.spring.wirespec")` → `id("io.kotest.extensions.wirespec")`
- DSL block: `kotestWirespecSpring { … }` → `kotestWirespec { … }`

- [ ] **Step 6: Run example tests**

Run: `./gradlew :example:clean :example:test --no-daemon`

Expected: BUILD SUCCESSFUL. The plugin id, DSL extension, and class names all need to line up; if Gradle reports `Plugin with id 'io.kotest.extensions.spring.wirespec' not found`, you missed the example update in Step 5.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(gradle-plugin): rename plugin id, class, and DSL extension (drop Spring)

Plugin id io.kotest.extensions.spring.wirespec → io.kotest.extensions.wirespec.
DSL kotestWirespecSpring { … } → kotestWirespec { … }. Class
KotestWirespecSpringPlugin → KotestWirespecPlugin (likewise the extension
class). Spring is still the only extractor wired today; the rename leaves
headroom for adding non-Spring extractors without another rename pass.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.6: Rename Maven plugin Mojo class and parameter prefix

**Files:**
- Rename: `maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecSpringMojo.kt` → `KotestWirespecMojo.kt`
- Modify: `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml` (already partially updated by Task 2.4 sed)
- Modify: `maven-plugin/src/test/resources/fixture/pom.xml`

- [ ] **Step 1: Rename the Mojo file with git**

Run:
```bash
git mv maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecSpringMojo.kt \
       maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt
```

- [ ] **Step 2: Rename the class and parameter property prefixes**

Edit `maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt`:

- `class KotestWirespecSpringMojo` → `class KotestWirespecMojo`
- `@Parameter(property = "kotestWirespecSpring.basePackage", required = true)` → `@Parameter(property = "kotestWirespec.basePackage", required = true)`
- `@Parameter(property = "kotestWirespecSpring.generatedPackage")` → `@Parameter(property = "kotestWirespec.generatedPackage")`

- [ ] **Step 3: Update the plugin descriptor template**

Edit `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`:

- `<name>Kotest Spring Wirespec Maven Plugin</name>` → `<name>Kotest Wirespec Maven Plugin</name>`
- `<implementation>io.kotest.extensions.wirespec.maven.KotestWirespecSpringMojo</implementation>` → `<implementation>io.kotest.extensions.wirespec.maven.KotestWirespecMojo</implementation>` (the package was already updated; only the class name needs adjustment)
- In the `<configuration>` block: `${kotestWirespecSpring.basePackage}` → `${kotestWirespec.basePackage}`, same for `generatedPackage`.

- [ ] **Step 4: Update the fixture POM**

Edit `maven-plugin/src/test/resources/fixture/pom.xml`:

- `<groupId>io.kotest.extensions.spring.wirespec.fixture</groupId>` → `<groupId>io.kotest.extensions.wirespec.fixture</groupId>`
- The plugin `<groupId>io.kotest.extensions</groupId>` referencing the maven plugin → `<groupId>io.kotest.extensions.wirespec</groupId>`
- The runtime dep `<groupId>io.kotest.extensions</groupId><artifactId>kotest-wirespec</artifactId>` (group already updated to `io.kotest.extensions.wirespec` by Task 2.4 if it caught both groupId strings; verify manually).

Run after edits to confirm:

```bash
grep -n '<groupId>\|<artifactId>' maven-plugin/src/test/resources/fixture/pom.xml
```

Every Wirespec-our-project line should show `io.kotest.extensions.wirespec` group and `kotest-wirespec*` artifactIds.

- [ ] **Step 5: Update the existing PluginDescriptorTest if it asserts on the class name**

Run: `grep -n 'KotestWirespecSpringMojo\|kotestWirespecSpring' maven-plugin/src/test/kotlin/io/kotest/extensions/wirespec/maven/PluginDescriptorTest.kt || echo OK`

If any matches, update them in line with Steps 2–3.

- [ ] **Step 6: Run the maven plugin integration test**

Run: `(cd maven-plugin && ./../gradlew test --no-daemon)`

Expected: BUILD SUCCESSFUL. This is the slow path — it does `publishToMavenLocal` for emitter and core then shells out `mvn verify` against the fixture. If the fixture compile fails with `package io.kotest.extensions.spring.wirespec does not exist`, the smoke spec inside the fixture (`maven-plugin/src/test/resources/fixture/src/test/kotlin/example/PetSmokeSpec.kt`) needs a manual import update — the Task 2.4 sed should have handled it; verify and fix.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(maven-plugin): rename Mojo class and parameter prefix (drop Spring)

KotestWirespecSpringMojo → KotestWirespecMojo. Configuration property prefix
kotestWirespecSpring.* → kotestWirespec.*. Plugin descriptor template and
fixture POM updated accordingly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.7: Final Phase-2 verification across the whole composite build

- [ ] **Step 1: Clean everything**

Run: `./gradlew clean --no-daemon && (cd emitter && ./../gradlew clean --no-daemon) && (cd gradle-plugin && ./../gradlew clean --no-daemon) && (cd maven-plugin && ./../gradlew clean --no-daemon)`

- [ ] **Step 2: Full test pass**

Run: `./gradlew test --no-daemon && (cd emitter && ./../gradlew test --no-daemon) && (cd maven-plugin && ./../gradlew test --no-daemon)`

Expected: every sub-build BUILD SUCCESSFUL. End-of-phase milestone.

---

## Phase 3: Split `core` from new `spring` module and introduce the `ContextProvider` SPI

### Task 3.1: Scaffold the empty `spring` module

**Files:**
- Create: `spring/build.gradle.kts`
- Create: `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/.gitkeep`
- Create: `spring/src/test/kotlin/io/kotest/extensions/wirespec/spring/.gitkeep`
- Create: `spring/src/main/resources/META-INF/services/.gitkeep`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Make the directories**

Run:
```bash
mkdir -p spring/src/main/kotlin/io/kotest/extensions/wirespec/spring
mkdir -p spring/src/test/kotlin/io/kotest/extensions/wirespec/spring
mkdir -p spring/src/main/resources/META-INF/services
touch spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/.gitkeep \
      spring/src/test/kotlin/io/kotest/extensions/wirespec/spring/.gitkeep \
      spring/src/main/resources/META-INF/services/.gitkeep
```

- [ ] **Step 2: Add the module include to root settings**

Edit `settings.gradle.kts`, add `include(":spring")` next to `include(":core")` (order doesn't matter to Gradle but keep alphabetic):

```kotlin
include(":core")
include(":example")
include(":spring")
```

- [ ] **Step 3: Write the spring module's build script**

Create `spring/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotest-wirespec-spring"
            pom {
                name.set("Kotest Wirespec Spring Integration")
                description.set("Spring transports (MockMvc, WebClient, EmbeddedKafka) and auto-registered ContextProvider for kotest-wirespec.")
            }
        }
    }
}
```

- [ ] **Step 4: Verify the empty module is recognised**

Run: `./gradlew :spring:tasks --no-daemon | head -10`

Expected: a tasks listing for `:spring`. No content yet, but Gradle should resolve the project.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts spring/
git commit -m "$(cat <<'EOF'
build: scaffold empty :spring module that will hold Spring transports + SPI

No code moved yet; that's the next commit. This isolates the build-graph wiring
from the source moves so an error in either is easier to bisect.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.2: Add the `ContextProvider` SPI in core

**Files:**
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/context/ContextProvider.kt`
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/context/ContextRegistry.kt`
- Create: `core/src/test/kotlin/io/kotest/extensions/wirespec/context/ContextRegistryTest.kt`

- [ ] **Step 1: Write the failing registry test**

Create `core/src/test/kotlin/io/kotest/extensions/wirespec/context/ContextRegistryTest.kt`:

```kotlin
package io.kotest.extensions.wirespec.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContextRegistryTest : FunSpec({

    test("ContextRegistry returns the providers registered via ServiceLoader") {
        // No spring module on the test classpath, so there should be exactly zero
        // providers discovered. Confirm the loader does not throw.
        val providers = ContextRegistry.providers
        providers.size shouldBe 0
    }
})
```

- [ ] **Step 2: Run the test to confirm it fails (no SPI types yet)**

Run: `./gradlew :core:test --tests 'io.kotest.extensions.wirespec.context.ContextRegistryTest' --no-daemon`

Expected: FAIL with `Unresolved reference: ContextRegistry`.

- [ ] **Step 3: Write the SPI interface**

Create `core/src/main/kotlin/io/kotest/extensions/wirespec/context/ContextProvider.kt`:

```kotlin
package io.kotest.extensions.wirespec.context

import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext

/**
 * Service-loader-discovered hook that supplies framework-specific transports
 * and lifecycle to [io.kotest.extensions.wirespec.WirespecSpec].
 *
 * The spring module ships exactly one provider that registers itself via
 * `META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider`.
 * Core code never calls into a provider directly — every provider method is
 * optional and defaults to `null`, so adding a new context (e.g. for Ktor)
 * doesn't force existing providers to change.
 */
interface ContextProvider {
    /**
     * A Kotest [SpecExtension] that should be mounted on every [WirespecSpec].
     * Used by the spring provider to install the upstream `SpringExtension`.
     */
    fun specExtension(): SpecExtension? = null

    /**
     * Resolve a default [WirespecTestContext] from the running spec instance.
     * Return `null` if this provider can't supply one (e.g. no MockMvc bean
     * available); the spec then tries the next provider or surfaces a clear
     * error.
     */
    fun endpointContext(spec: Spec): WirespecTestContext? = null

    /**
     * Resolve a default [WirespecChannelContext] from the running spec
     * instance. `null` is the common case — channel tests opt in by, e.g.,
     * annotating the spec with `@EmbeddedKafka`.
     */
    fun channelContext(spec: Spec): WirespecChannelContext? = null
}
```

- [ ] **Step 4: Write the registry singleton**

Create `core/src/main/kotlin/io/kotest/extensions/wirespec/context/ContextRegistry.kt`:

```kotlin
package io.kotest.extensions.wirespec.context

import java.util.ServiceLoader

/**
 * Lazy snapshot of [ContextProvider]s discovered via `ServiceLoader` at the
 * first call. Lookup order follows classpath order; the first non-null
 * `endpointContext` / `channelContext` result wins. With one provider on the
 * classpath today (spring), ordering is moot — revisit if a second provider
 * ever ships in the same artifact set.
 *
 * Internal to keep the surface tight; the public entry point is
 * [io.kotest.extensions.wirespec.WirespecSpec].
 */
internal object ContextRegistry {
    val providers: List<ContextProvider> by lazy {
        ServiceLoader.load(
            ContextProvider::class.java,
            ContextProvider::class.java.classLoader,
        ).toList()
    }
}
```

Note: `ContextRegistry` is `internal`. Make `ContextRegistry.providers` accessible from `WirespecSpec` in the same module — both live in core, so `internal` works.

- [ ] **Step 5: Run the test to confirm it passes**

Run: `./gradlew :core:test --tests 'io.kotest.extensions.wirespec.context.ContextRegistryTest' --no-daemon`

Expected: PASS. (No spring module yet — empty providers list.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/io/kotest/extensions/wirespec/context/ContextProvider.kt \
        core/src/main/kotlin/io/kotest/extensions/wirespec/context/ContextRegistry.kt \
        core/src/test/kotlin/io/kotest/extensions/wirespec/context/ContextRegistryTest.kt
git commit -m "$(cat <<'EOF'
feat(core): add ContextProvider SPI + lazy ServiceLoader registry

Internal SPI that the spring module (next commit) will register against to
auto-supply WirespecTestContext, WirespecChannelContext, and the Kotest
SpringExtension when its module is on the test classpath. Core stays
framework-neutral; providers return null when they can't satisfy a request.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.3: Move Spring transports from core to spring

**Files:**
- Move: `core/src/main/kotlin/io/kotest/extensions/wirespec/spring/MockMvcTransportation.kt` → `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/MockMvcTransportation.kt`
- Move: `core/src/main/kotlin/io/kotest/extensions/wirespec/spring/WebClientTransportation.kt` → `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/WebClientTransportation.kt`
- Move: `core/src/main/kotlin/io/kotest/extensions/wirespec/channel/EmbeddedKafkaMessageTransport.kt` → `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/EmbeddedKafkaMessageTransport.kt` (also rename the package directive — see Step 3)

- [ ] **Step 1: Move the HTTP transports**

Run:
```bash
git mv core/src/main/kotlin/io/kotest/extensions/wirespec/spring/MockMvcTransportation.kt \
       spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/MockMvcTransportation.kt
git mv core/src/main/kotlin/io/kotest/extensions/wirespec/spring/WebClientTransportation.kt \
       spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/WebClientTransportation.kt
rmdir core/src/main/kotlin/io/kotest/extensions/wirespec/spring
```

- [ ] **Step 2: Move the embedded-kafka transport**

Run:
```bash
git mv core/src/main/kotlin/io/kotest/extensions/wirespec/channel/EmbeddedKafkaMessageTransport.kt \
       spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/EmbeddedKafkaMessageTransport.kt
```

- [ ] **Step 3: Update the embedded-kafka transport's package declaration**

Edit `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/EmbeddedKafkaMessageTransport.kt`:

- `package io.kotest.extensions.wirespec.channel` → `package io.kotest.extensions.wirespec.spring`
- Add the import for the channel SPI it uses: `import io.kotest.extensions.wirespec.channel.MessageTransport` (and the data classes `OutgoingRecord`, `IncomingRecord` if not already wildcarded).

Verify with:

```bash
grep -n 'package\|^import' spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/EmbeddedKafkaMessageTransport.kt
```

Expected first line: `package io.kotest.extensions.wirespec.spring`. Channel and Kafka imports follow.

- [ ] **Step 4: Strip Spring deps from `core/build.gradle.kts`**

Edit `core/build.gradle.kts`. Remove (the publish artifactId becomes `kotest-wirespec` later in Task 3.5):

```kotlin
    api("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    api("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")

    compileOnly("org.springframework.kafka:spring-kafka:3.3.0")
    compileOnly("org.springframework.kafka:spring-kafka-test:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka-test:3.3.0")

    api("jakarta.servlet:jakarta.servlet-api:6.0.0")

    api("io.kotest:kotest-extensions-spring-jvm:6.1.11")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
```

Also delete the now-unused `val springBootVersion = "3.4.1"`.

(Channel runtime classes that stay in core — `MessageTransport`, `InMemoryMessageTransport`, `MessageRecord` data classes — do NOT depend on Spring. They stay put.)

- [ ] **Step 5: Update example test classpath to depend on spring**

Edit `example/build.gradle.kts`:

```kotlin
    testImplementation(project(":core"))
    testImplementation(project(":spring"))
    testImplementation("org.springframework.kafka:spring-kafka-test")
```

(was `testImplementation(project(":runtime"))` after Phase 2; both `:core` and `:spring` are needed because the `example` specs currently use Spring transports.)

- [ ] **Step 6: Compile both modules**

Run: `./gradlew :core:compileKotlin :spring:compileKotlin --no-daemon`

Expected: BUILD SUCCESSFUL. Failure modes:
- `core` references `org.springframework.*` → a transport file you missed; grep `grep -rn 'org\.springframework' core/src/main` and move the offending file to `spring/`.
- `spring` references `io.kotest.extensions.wirespec.X` symbols that aren't `internal` — make those `public` in core if the spring module needs them.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: move Spring transports from core to new :spring module

WebClientTransportation, MockMvcTransportation, and EmbeddedKafkaMessageTransport
move into the spring module under io.kotest.extensions.wirespec.spring. Core
build script drops the Spring Boot, Servlet, Kafka, and kotlinx-coroutines-reactor
dependencies. example/build.gradle.kts now declares both :core and :spring on
its test classpath.

WirespecSpec still lives in core and still hard-codes Spring extension mounting;
that goes away in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.4: Add `SpringContextProvider` and the ServiceLoader registration

**Files:**
- Create: `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/SpringContextProvider.kt`
- Create: `spring/src/main/resources/META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider`
- Create: `spring/src/test/kotlin/io/kotest/extensions/wirespec/spring/SpringContextProviderServiceLoaderTest.kt`

- [ ] **Step 1: Write the failing service-loader test**

Create `spring/src/test/kotlin/io/kotest/extensions/wirespec/spring/SpringContextProviderServiceLoaderTest.kt`:

```kotlin
package io.kotest.extensions.wirespec.spring

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.extensions.wirespec.context.ContextProvider
import java.util.ServiceLoader

class SpringContextProviderServiceLoaderTest : FunSpec({

    test("META-INF/services registers SpringContextProvider exactly once") {
        val classNames = ServiceLoader.load(ContextProvider::class.java)
            .map { it::class.java.name }
        classNames shouldContainExactlyInAnyOrder listOf(
            "io.kotest.extensions.wirespec.spring.SpringContextProvider",
        )
    }
})
```

- [ ] **Step 2: Run it to verify failure (no provider class yet)**

Run: `./gradlew :spring:test --tests 'io.kotest.extensions.wirespec.spring.SpringContextProviderServiceLoaderTest' --no-daemon`

Expected: FAIL — `Unresolved reference: SpringContextProvider` (or, after the class exists but no services file, an empty list assertion failure).

- [ ] **Step 3: Write `SpringContextProvider`**

Create `spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/SpringContextProvider.kt`:

```kotlin
package io.kotest.extensions.wirespec.spring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.context.ContextProvider
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Default [ContextProvider] for Spring-based scenarios. Activated when this
 * module is on the test classpath via
 * `META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider`.
 *
 * Resolution strategy:
 *   1. Mount the upstream [SpringExtension] so `@SpringBootTest` boots and
 *      `@Autowired` fields populate.
 *   2. For [endpointContext], reflect the spec for a property of type
 *      [ApplicationContext], look up a `MockMvc` bean, wrap it in
 *      [MockMvcTransportation]. Returns `null` if either step fails — the
 *      user can then override `endpointCtx` manually (e.g. to use a
 *      `LocalServerPort`-driven [WebClientTransportation]).
 *   3. For [channelContext], try to resolve an `EmbeddedKafkaBroker` bean
 *      via [EmbeddedKafkaMessageTransport]. Returns `null` if the
 *      `@EmbeddedKafka` setup isn't present.
 */
class SpringContextProvider : ContextProvider {

    override fun specExtension(): SpecExtension = SpringExtension

    override fun endpointContext(spec: Spec): WirespecTestContext? {
        val app = applicationContextOf(spec) ?: return null
        val mvc = app.getBeanProvider(MockMvc::class.java).getIfAvailable() ?: return null
        return WirespecTestContext(
            transportation = MockMvcTransportation(mvc),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    override fun channelContext(spec: Spec): WirespecChannelContext? {
        val app = applicationContextOf(spec) ?: return null
        // spring-kafka is compileOnly; absence is silent at runtime.
        return runCatching {
            WirespecChannelContext(
                messaging = EmbeddedKafkaMessageTransport(app),
                serialization = WirespecSerialization(jacksonObjectMapper()),
            )
        }.getOrNull()
    }

    private fun applicationContextOf(spec: Spec): ApplicationContext? {
        val match = spec::class.memberProperties
            .firstOrNull { property ->
                val classifier = property.returnType.classifier as? KClass<*> ?: return@firstOrNull false
                ApplicationContext::class.java.isAssignableFrom(classifier.java)
            } ?: return null
        match.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (match as kotlin.reflect.KProperty1<Any, *>).get(spec) as? ApplicationContext
    }
}
```

- [ ] **Step 4: Register the provider**

Create `spring/src/main/resources/META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider` (no extension, no leading directory tricks) with this exact content:

```
io.kotest.extensions.wirespec.spring.SpringContextProvider
```

(Single line, no blank lines.)

- [ ] **Step 5: Run the test to confirm it passes**

Run: `./gradlew :spring:test --tests 'io.kotest.extensions.wirespec.spring.SpringContextProviderServiceLoaderTest' --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add spring/src/main/kotlin/io/kotest/extensions/wirespec/spring/SpringContextProvider.kt \
        spring/src/main/resources/META-INF/services/io.kotest.extensions.wirespec.context.ContextProvider \
        spring/src/test/kotlin/io/kotest/extensions/wirespec/spring/SpringContextProviderServiceLoaderTest.kt
git commit -m "$(cat <<'EOF'
feat(spring): add SpringContextProvider + ServiceLoader registration

Provider auto-mounts the upstream SpringExtension on every WirespecSpec,
reflects the spec for an @Autowired ApplicationContext field, and resolves
MockMvc / EmbeddedKafkaBroker beans into the corresponding wirespec contexts.
Registered via META-INF/services so simply adding kotest-wirespec-spring to
the test classpath activates it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.5: Add the new `WirespecSpec` base class and delete `SpringWirespecSpec`

**Files:**
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/WirespecSpec.kt`
- Delete: `core/src/main/kotlin/io/kotest/extensions/wirespec/SpringWirespecSpec.kt`
- Modify: `core/src/main/kotlin/io/kotest/extensions/wirespec/Scenario.kt` (the existing `runScenarioOnce` helper stays in place; `wirespec()` override moves with it)

- [ ] **Step 1: Create `WirespecSpec`**

Create `core/src/main/kotlin/io/kotest/extensions/wirespec/WirespecSpec.kt`:

```kotlin
package io.kotest.extensions.wirespec

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.context.ContextRegistry
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.property.RandomSource
import io.kotest.property.checkAll

/**
 * Base spec for Wirespec scenarios. Framework-neutral — context providers (e.g.
 * the spring module's [io.kotest.extensions.wirespec.spring.SpringContextProvider])
 * register via `ServiceLoader` and auto-supply the [endpointCtx] / [channelCtx]
 * and any Kotest [io.kotest.core.extensions.SpecExtension] they need mounted
 * (the spring provider mounts the upstream `SpringExtension` so
 * `@SpringBootTest`-annotated subclasses just work).
 *
 * Override [endpointCtx] / [channelCtx] to supply transports yourself
 * (e.g. against Testcontainers, or a plain `WebClient` against
 * `@LocalServerPort`).
 *
 * ```
 * @SpringBootTest(classes = [App::class])
 * @AutoConfigureMockMvc
 * class PetSpec : WirespecSpec({
 *     test("pet CRUD", iterations = 10) {
 *         val petId = createPet.returning<CreatePet.Response201, String> { it.body.id }
 *         getPet.path(petId).expecting<GetPet.Response200>()
 *     }
 * }) {
 *     @Autowired
 *     protected lateinit var applicationContext: ApplicationContext
 * }
 * ```
 */
abstract class WirespecSpec(body: WirespecSpec.() -> Unit = {}) : FunSpec() {

    init {
        ContextRegistry.providers
            .mapNotNull { it.specExtension() }
            .forEach { extension(it) }
        body()
    }

    open val endpointCtx: WirespecTestContext by lazy {
        ContextRegistry.providers.firstNotNullOfOrNull { it.endpointContext(this) }
            ?: error(
                "No WirespecTestContext available for ${this::class.simpleName}. " +
                    "Either override `endpointCtx` on the spec, or add " +
                    "`io.kotest.extensions.wirespec:kotest-wirespec-spring` to the test " +
                    "classpath so the Spring context provider can resolve a MockMvc bean " +
                    "(requires @AutoConfigureMockMvc).",
            )
    }

    open val channelCtx: WirespecChannelContext? by lazy {
        ContextRegistry.providers.firstNotNullOfOrNull { it.channelContext(this) }
    }

    fun test(name: String, iterations: Int = 1, body: ScenarioBuilder.() -> Unit) {
        super.test(name) {
            if (iterations <= 1) {
                runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(System.nanoTime()), body)
            } else {
                checkAll<Int>(iterations = iterations) {
                    runScenarioOnce(endpointCtx, channelCtx, randomSource(), body)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Migrate the `eventually` step helper if it lived on `SpringWirespecSpec`**

Run: `grep -n 'eventually' core/src/main/kotlin/io/kotest/extensions/wirespec/SpringWirespecSpec.kt`

If `eventually(...)` is a member of `SpringWirespecSpec`, copy its body into `WirespecSpec.kt` (preserving the same signature). If it's already inside `ScenarioBuilder` (more likely, given `runtime/dsl/ScenarioBuilder.kt`), leave it alone.

- [ ] **Step 3: Delete `SpringWirespecSpec.kt`**

Run: `git rm core/src/main/kotlin/io/kotest/extensions/wirespec/SpringWirespecSpec.kt`

- [ ] **Step 4: Compile the core module**

Run: `./gradlew :core:compileKotlin :core:compileTestKotlin --no-daemon`

Expected: BUILD SUCCESSFUL. If something inside `core/src/test/` still references `SpringWirespecSpec`, update or delete that test — it likely became redundant once SpringWirespecSpec was inlined.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(core): replace SpringWirespecSpec with framework-neutral WirespecSpec

WirespecSpec consults ContextRegistry on first access to endpointCtx /
channelCtx and mounts any SpecExtensions registered by providers. With
:spring on the test classpath the user experience matches the old
SpringWirespecSpec; without it, core works against any user-supplied context.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.6: Migrate the example specs to `WirespecSpec`

**Files:**
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt`
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetChannelScenariosSpec.kt`
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosJUnitTest.kt` (no `WirespecSpec` change — uses `scenario(ctx) { … }` directly — but the import remains `io.kotest.extensions.wirespec.scenario`, which is already correct after Phase 2)
- Modify: `maven-plugin/src/test/resources/fixture/src/test/kotlin/example/PetSmokeSpec.kt`

- [ ] **Step 1: Migrate `PetScenariosSpec.kt`**

Edit `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt`:

Replace:

```kotlin
import io.kotest.extensions.wirespec.SpringWirespecSpec
```

with:

```kotlin
import io.kotest.extensions.wirespec.WirespecSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
```

Change the class declaration:

```kotlin
class PetScenariosSpec : SpringWirespecSpec({
```

to:

```kotlin
class PetScenariosSpec : WirespecSpec({
```

Just before the closing brace of the file, inside the class body but outside the `init` lambda, add:

```kotlin
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

(The existing close paren `})` is replaced by the block above. Total result: the existing `init { … }` lambda is unchanged, but the class now has a body containing the `@Autowired` field.)

- [ ] **Step 2: Migrate `PetChannelScenariosSpec.kt`**

Edit `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetChannelScenariosSpec.kt`:

Replace:

```kotlin
import io.kotest.extensions.wirespec.SpringWirespecSpec
```

with:

```kotlin
import io.kotest.extensions.wirespec.WirespecSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
```

Change the class declaration:

```kotlin
class PetChannelScenariosSpec : SpringWirespecSpec({
```

to:

```kotlin
class PetChannelScenariosSpec : WirespecSpec({
```

Replace the existing closing `})` at the end of the file with:

```kotlin
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

- [ ] **Step 3: Migrate the maven fixture spec**

Edit `maven-plugin/src/test/resources/fixture/src/test/kotlin/example/PetSmokeSpec.kt`:

```kotlin
import io.kotest.extensions.wirespec.WirespecSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
class PetSmokeSpec : WirespecSpec({

    test("getPet round-trips", iterations = 3) {
        getPet
            .path("existing")
            .expecting<GetPet.Response200>()
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

- [ ] **Step 4: Run example tests**

Run: `./gradlew :example:test --no-daemon`

Expected: BUILD SUCCESSFUL — both `PetScenariosSpec` and `PetChannelScenariosSpec` pass. Common failures:
- `No WirespecTestContext available …` → the spec lacks `@AutoConfigureMockMvc`, or the `applicationContext` field reflection didn't find it. Check the spec's annotations and that the `@Autowired` field is declared with type `ApplicationContext` (not a more specific subtype).
- `lateinit property applicationContext has not been initialized` → the upstream `SpringExtension` didn't get mounted. Verify the `META-INF/services` file from Task 3.4 is on the test classpath (`unzip -l ~/.gradle/.../kotest-wirespec-spring-*.jar | grep META-INF`).

- [ ] **Step 5: Run the maven plugin integration test**

Run: `(cd maven-plugin && ./../gradlew test --no-daemon)`

Expected: BUILD SUCCESSFUL. This also revalidates that `publishCoreToMavenLocal` and `publishToMavenLocal` for the spring module produce coordinates the fixture POM can resolve. If the fixture POM's runtime dep is still pointing at `kotest-wirespec` (core only), add a second dep on `kotest-wirespec-spring`:

Edit `maven-plugin/src/test/resources/fixture/pom.xml`, add right below the existing runtime dep:

```xml
<dependency>
    <groupId>io.kotest.extensions.wirespec</groupId>
    <artifactId>kotest-wirespec-spring</artifactId>
    <version>${wirespec.runtime.version}</version>
    <scope>test</scope>
</dependency>
```

Also extend the maven-plugin's pre-test publish chain so the spring module reaches `~/.m2`:

Edit `maven-plugin/build.gradle.kts`, add next to the existing `publishCoreToMavenLocal` task:

```kotlin
val publishSpringToMavenLocal by tasks.registering(Exec::class) {
    workingDir = outerRoot
    commandLine(outerGradlew, "--no-daemon", ":spring:publishToMavenLocal")
}
```

And update the `tasks.test { dependsOn(...) }` block to include `publishSpringToMavenLocal`.

Re-run `(cd maven-plugin && ./../gradlew test --no-daemon)`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(example, maven-plugin/fixture): migrate from SpringWirespecSpec to WirespecSpec

User-visible specs now extend WirespecSpec and declare the @Autowired
ApplicationContext field themselves. SpringContextProvider reflects that field
to resolve transports. Maven fixture POM gains a kotest-wirespec-spring dep and
the maven plugin build publishes the spring artifact alongside core/emitter
before running the integration test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.7: Full Phase-3 verification

- [ ] **Step 1: Clean and full test pass**

Run: `./gradlew clean test --no-daemon && (cd emitter && ./../gradlew clean test --no-daemon) && (cd maven-plugin && ./../gradlew clean test --no-daemon)`

Expected: every sub-build BUILD SUCCESSFUL.

---

## Phase 4: Gradle plugin — `spring` flag with auto-detection

### Task 4.1: Add `spring` property to `KotestWirespecExtension`

**Files:**
- Modify: `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecExtension.kt`

- [ ] **Step 1: Add the property**

Replace the body of `KotestWirespecExtension` with:

```kotlin
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
     * `false`. When `false`, the plugin still wires the `wirespecKotlin`
     * compile/emit task — supply `.ws` files via the standard
     * `community.flock.wirespec.plugin.gradle` extension's input configuration.
     */
    abstract val spring: Property<Boolean>
}
```

(Imports unchanged.)

### Task 4.2: Make `KotestWirespecPlugin.apply` conditional on `spring`

**Files:**
- Modify: `gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPlugin.kt`

- [ ] **Step 1: Replace `apply` with a conditional version**

Replace `apply` body with:

```kotlin
override fun apply(project: Project) {
    val extension = project.extensions.create(
        "kotestWirespec",
        KotestWirespecExtension::class.java,
    )

    extension.spring.convention(
        project.provider { project.plugins.hasPlugin("org.springframework.boot") }
    )

    project.pluginManager.apply("community.flock.wirespec.plugin.gradle")

    val extractedDir = project.layout.buildDirectory.dir("wirespec/extracted")
    val generatedDir = project.layout.buildDirectory.dir("generated/wirespec")
    val defaultInputDir = project.layout.projectDirectory.dir("src/test/wirespec")
    val resolvedGeneratedPackage = extension.generatedPackage
        .orElse(extension.basePackage.map { "$it.generated" })

    val compileTask = project.tasks.register(
        "wirespecKotlin",
        CompileWirespecTask::class.java,
        object : Action<CompileWirespecTask> {
            override fun execute(task: CompileWirespecTask) {
                task.description = "Generate Kotlin sources + typesafe DSL from the extracted Wirespec contracts."
                task.group = "wirespec"
                // Default input dir is used when spring=false; afterEvaluate
                // below overrides it to the extracted dir when spring=true.
                task.input.set(defaultInputDir)
                task.output.set(generatedDir)
                task.packageName.set(resolvedGeneratedPackage)
                task.emitterClass.set(TypesafeDslEmitter::class.java)
            }
        },
    )

    // Wire the Spring extractor only when requested. We do the lookup at task-graph
    // configuration time via afterEvaluate so the user can override `spring = false`
    // in their build script regardless of plugin order.
    project.afterEvaluate {
        if (extension.spring.get()) {
            project.pluginManager.apply("community.flock.wirespec.spring.extractor")
            val extractorExt = project.extensions.getByType(WirespecExtractorExtension::class.java)
            extractorExt.outputDir.set(extractedDir)
            extractorExt.basePackage.set(extension.basePackage)

            val extractTask = project.tasks.named("extractWirespec", ExtractWirespecTask::class.java)
            compileTask.configure { task ->
                task.input.set(extractedDir)
                task.dependsOn(extractTask)
            }
        }
    }

    project.plugins.withId("org.jetbrains.kotlin.jvm") {
        val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
        sourceSets.getByName("test").java.srcDir(compileTask.flatMap { it.output })
        project.tasks.named("compileTestKotlin").configure(
            object : Action<Task> {
                override fun execute(task: Task) {
                    task.dependsOn(compileTask)
                }
            },
        )
    }
}
```

(Imports: keep `CompileWirespecTask`, `TypesafeDslEmitter`, `WirespecExtractorExtension`, `ExtractWirespecTask`, the Gradle Action/Plugin/Project/Task/JavaPluginExtension ones.)

- [ ] **Step 2: Run example tests (auto-detect path: `spring` true because the example applies `org.springframework.boot`)**

Run: `./gradlew :example:clean :example:test --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecExtension.kt \
        gradle-plugin/src/main/kotlin/io/kotest/extensions/wirespec/gradle/KotestWirespecPlugin.kt
git commit -m "$(cat <<'EOF'
feat(gradle-plugin): add `spring` boolean to enable/disable extractor wiring

`spring` defaults to `true` when org.springframework.boot is applied (matches
prior behaviour for the example). Set explicitly to `false` to skip the
wirespec-spring-extractor; in that case users wire input themselves via the
standard community.flock.wirespec.plugin.gradle extension.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5: Maven plugin — `spring` parameter with auto-detection

### Task 5.1: Add the `spring` parameter and skip extractor when false

**Files:**
- Modify: `maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt`
- Modify: `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`

- [ ] **Step 1: Add the parameter to the Mojo**

Edit `maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt`. Add inside the class, after `generatedDir`:

```kotlin
@Parameter(property = "kotestWirespec.spring")
var spring: Boolean? = null
```

- [ ] **Step 2: Detect the default in `execute`**

Replace the start of `execute()`:

```kotlin
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
```

with:

```kotlin
override fun execute() {
    val env = executionEnvironment(project, session, pluginManager)
    val effectivePackage = generatedPackage?.takeIf { it.isNotBlank() }
        ?: "$basePackage.generated"

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
        log.info("Skipping wirespec-spring-extractor (kotestWirespec.spring = false). " +
            "Using pre-existing .ws files at ${extractedDir.absolutePath}.")
    }
```

(The rest of `execute()` — the `wirespecKotlin` compile invocation and `project.addTestCompileSourceRoot` — stays unchanged.)

Add the detection helper at the bottom of the class:

```kotlin
private fun hasSpringBootOnClasspath(): Boolean = project.dependencies.any { dep ->
    dep.groupId == "org.springframework.boot"
}
```

- [ ] **Step 3: Document the new parameter in the plugin descriptor**

Edit `maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml`, add inside `<parameters>` near the others:

```xml
<parameter>
    <name>spring</name>
    <type>java.lang.Boolean</type>
    <required>false</required>
    <editable>true</editable>
    <description>Enable the Wirespec Spring extractor. Defaults to auto-detect (true when an org.springframework.boot:* dependency is declared).</description>
</parameter>
```

And inside `<configuration>`, add:

```xml
<spring implementation="java.lang.Boolean">${kotestWirespec.spring}</spring>
```

- [ ] **Step 4: Run maven integration test**

Run: `(cd maven-plugin && ./../gradlew test --no-daemon)`

Expected: BUILD SUCCESSFUL. (Fixture has spring-boot-starter-web, so auto-detect picks `true` — same path as before.)

- [ ] **Step 5: Commit**

```bash
git add maven-plugin/src/main/kotlin/io/kotest/extensions/wirespec/maven/KotestWirespecMojo.kt \
        maven-plugin/src/main/resources-template/META-INF/maven/plugin.xml
git commit -m "$(cat <<'EOF'
feat(maven-plugin): add `spring` parameter to toggle extractor

`spring` defaults to auto-detect (any org.springframework.boot:* dependency on
the project flips it to true). When false, the extractor mojo is skipped and
the configured extractedDir is used as-is by the wirespec-maven-plugin compile
step.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6: README and memory note

### Task 6.1: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Edit the title, headline, and the Gradle/Maven samples**

Edit `README.md`:

- Line 1: `# kotest-extensions-spring-wirespec` → `# kotest-wirespec`
- The Gradle sample block `id("io.kotest.extensions.spring.wirespec") version "0.1.0"` → `id("io.kotest.extensions.wirespec") version "0.1.0"`
- `kotestWirespecSpring { basePackage.set("com.example.api") }` → `kotestWirespec { basePackage.set("com.example.api") }`
- The Maven snippet's `<groupId>io.kotest.extensions</groupId>` → `<groupId>io.kotest.extensions.wirespec</groupId>` and `<artifactId>kotest-extensions-spring-wirespec-maven-plugin</artifactId>` → `<artifactId>kotest-wirespec-maven-plugin</artifactId>`
- All imports in the code samples: `io.kotest.extensions.spring.wirespec.*` → `io.kotest.extensions.wirespec.*`
- The "SpringSpecExtension is a thin SpecExtension wrapper..." paragraph (lines 134–140) is now obsolete; replace with:

```markdown
The spec inherits Spring lifecycle and a default `MockMvc`-backed
`endpointCtx` from the `kotest-wirespec-spring` module: drop that artifact on
the test classpath and the upstream `SpringExtension` is auto-mounted,
`@SpringBootTest` boots, and the spec resolves transports by reflecting on the
spec's `@Autowired ApplicationContext` field. Override `endpointCtx` to swap
the default (e.g. to point at `@LocalServerPort`).
```

- The "Channels (Kafka)" sample's `SpringWirespecSpec` reference → `WirespecSpec`, and add the `@Autowired applicationContext` field block at the bottom of the class.
- Add a one-line note about the `spring = true|false` knob right after the Gradle DSL sample:

```markdown
By default the plugin auto-detects whether `org.springframework.boot` is
applied and wires the Spring extractor accordingly. Set
`kotestWirespec { spring = false }` to skip extraction and supply
hand-authored `.ws` files via the upstream wirespec plugin.
```

- [ ] **Step 2: Spot-check that no `spring.wirespec` strings remain in the README**

Run: `grep -n 'spring\.wirespec\|kotest-extensions-spring-wirespec\|SpringWirespecSpec\|SpringSpecExtension\|kotestWirespecSpring' README.md || echo OK`

Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs(readme): refresh for kotest-wirespec rename and Spring auto-loading

Updates plugin id, DSL extension, Maven coords, and code samples. Documents
the new `spring = true|false` flag and the auto-mounted SpringExtension
behaviour via the kotest-wirespec-spring module. Drops the now-obsolete
SpringSpecExtension wrapper paragraph.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 6.2: Refresh the memory note

**Files:**
- Modify: `/Users/wilmveel/.claude/projects/-Users-wilmveel-Projects-kotest-spring/memory/feedback_spring_testing.md`

- [ ] **Step 1: Rewrite the memory's Why and How-to-apply lines**

Replace the body of `feedback_spring_testing.md` with:

```markdown
When writing or reviewing tests for Spring Boot apps in this project, use the upstream `io.kotest:kotest-extensions-spring-jvm` extension. Do not bring back a project-local `SpringWirespecExtension` / `SpringSpecExtension` wrapper.

**Why:** The original wrapper existed because `io.kotest.extensions:kotest-extensions-spring:1.3.0` was Kotest 5-era and crashed on Kotest 6's per-test path. As of the kotest-wirespec rename (2026-05-25) the project depends on `io.kotest:kotest-extensions-spring-jvm:6.1.11`, which is Kotest 6 native — the wrapper is gone and the auto-mounted `SpringExtension` is registered by `kotest-wirespec-spring`'s `SpringContextProvider` via `META-INF/services`.

**How to apply:** Spec subclasses of `WirespecSpec` should not call `extension(SpringExtension)` themselves — it is mounted by the SPI when `kotest-wirespec-spring` is on the test classpath. They must declare `@Autowired protected lateinit var applicationContext: ApplicationContext` so the provider can resolve `MockMvc` / `EmbeddedKafkaBroker` beans from it. If a non-default transport is needed (e.g. WebClient against `@LocalServerPort`), override `endpointCtx` directly.
```

- [ ] **Step 2: No commit needed (memory files are outside the repo).**

---

## Final verification

- [ ] **Step 1: Cold-cache full pass across all sub-builds**

Run:
```bash
./gradlew clean --no-daemon
(cd emitter && ./../gradlew clean --no-daemon)
(cd gradle-plugin && ./../gradlew clean --no-daemon)
(cd maven-plugin && ./../gradlew clean --no-daemon)

./gradlew test --no-daemon
(cd emitter && ./../gradlew test --no-daemon)
(cd maven-plugin && ./../gradlew test --no-daemon)
```

Expected: every sub-build BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm published coordinates from a dry run**

Run: `./gradlew :core:generatePomFileForMavenPublication :spring:generatePomFileForMavenPublication --no-daemon && cat core/build/publications/maven/pom-default.xml | head -10 && echo --- && cat spring/build/publications/maven/pom-default.xml | head -10`

Expected: both POMs declare `<groupId>io.kotest.extensions.wirespec</groupId>` and `<artifactId>kotest-wirespec</artifactId>` / `<artifactId>kotest-wirespec-spring</artifactId>` respectively.

- [ ] **Step 3: Done — no further commits required from this plan.**
