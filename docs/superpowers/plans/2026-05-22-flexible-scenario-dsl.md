# Flexible scenario DSL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `SpringScenarioSpec` (FunSpec inheritance + hand-rolled iteration loop) with a composable triple — `WirespecTestContext`, a Kotest `SpringWirespecExtension`, and a top-level `scenario(...)` function — so the DSL works from any Kotest spec style and from JUnit Jupiter alike.

**Architecture:** The runtime keeps `ScenarioBuilder` / `EndpointCallBuilder` / `ArbReceiver` / `ScenarioRunner` unchanged. A new public `scenario(ctx) { … }` extension on kotest-property's `PropertyContext` drives one iteration; users wrap it in `checkAll(iterations = N) { scenario(ctx) { … } }` to get property-style iteration with seed reporting for free. Kotest users register `SpringWirespecExtension` via `install(...)`; JUnit users use Spring's `@SpringBootTest(webEnvironment = RANDOM_PORT)` plus a small `WirespecTestContext.http(...)` factory. `SpringScenarioSpec.kt` is deleted.

**Tech Stack:** Kotlin 2.3, Spring Boot 3.4 (WebFlux), Kotest 6.1.11 (runner-junit5 + property), Wirespec 0.19.0-RC.3, JUnit Jupiter (transitive via `spring-boot-starter-test`).

**Spec:** `docs/superpowers/specs/2026-05-22-flexible-scenario-dsl-design.md`.

---

## File map

**New (runtime):**
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecTestContext.kt` — value type + `http(baseUrl, serialization)` factory.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt` — top-level `scenario(...)` overloads.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringWirespecExtension.kt` — Kotest BeforeSpec/AfterSpec listener.

**New (runtime tests):**
- `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/ScenarioTest.kt` — wiring test using a fake transportation, no Spring.

**New (example):**
- `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosJUnitTest.kt` — JUnit Jupiter twin of the Kotest example.

**Modified:**
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/SpringTestContext.kt` — drop `internal class …` → `class …`.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/WebClientTransportation.kt` — drop `internal class WebClientTransportation` → `class WebClientTransportation` (needed by `WirespecTestContext.http`).
- `runtime/build.gradle.kts` — remove the obsolete comment block referring to `SpringScenarioSpec`.
- `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt` — rewrite as `FunSpec` + `install` + `checkAll`.

**Deleted:**
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt`.

---

## Task 1: Promote `SpringTestContext` and `WebClientTransportation` to public

**Files:**
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/SpringTestContext.kt:28`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/WebClientTransportation.kt:26` (and `:88` for `HeadOnlyTransportation` if we want to keep symmetry — leave `HeadOnlyTransportation` `internal` for now; it's not on the user path)

- [ ] **Step 1: Read both files to confirm exact `internal class` lines**

Run: `grep -n "^internal class" runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/SpringTestContext.kt runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/WebClientTransportation.kt`

Expected: two matches — `SpringTestContext.kt:28:internal class SpringTestContext` and `WebClientTransportation.kt:26:internal class WebClientTransportation`.

- [ ] **Step 2: Remove `internal` from `SpringTestContext`**

In `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/SpringTestContext.kt`:

Replace:
```kotlin
internal class SpringTestContext private constructor(
```
with:
```kotlin
class SpringTestContext private constructor(
```

- [ ] **Step 3: Remove `internal` from `WebClientTransportation`**

In `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/WebClientTransportation.kt`:

Replace:
```kotlin
internal class WebClientTransportation(
    private val client: WebClient,
) : Wirespec.Transportation {
```
with:
```kotlin
class WebClientTransportation(
    private val client: WebClient,
) : Wirespec.Transportation {
```

(Leave `HeadOnlyTransportation` at line 88 untouched — still `internal`.)

- [ ] **Step 4: Compile to confirm nothing broke**

Run: `./gradlew :runtime:compileKotlin :runtime:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/SpringTestContext.kt runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/WebClientTransportation.kt
git commit -m "refactor(runtime): expose SpringTestContext + WebClientTransportation as public"
```

---

## Task 2: Add `WirespecTestContext`

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecTestContext.kt`
- Test: covered indirectly by Task 3's wiring test and Task 7's JUnit example. No standalone test — class is a trivial value holder + one factory.

- [ ] **Step 1: Create `WirespecTestContext.kt`**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecTestContext.kt` with:

```kotlin
package io.kotest.extensions.spring.wirespec

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.spring.WebClientTransportation
import org.springframework.web.reactive.function.client.WebClient

/**
 * Framework-neutral handle the scenario DSL consumes: a [Wirespec.Transportation]
 * for sending requests and a [Wirespec.Serialization] for typed (de)serialization.
 *
 * Build it directly when you already have both halves (e.g. wired via Spring
 * beans), or use [http] to spin up a [WebClient]-backed transport against a
 * running app's base URL.
 */
class WirespecTestContext(
    val transportation: Wirespec.Transportation,
    val serialization: Wirespec.Serialization,
) {
    companion object {
        /**
         * Build a context backed by Spring's `WebClient` for HTTP-based integration
         * tests (e.g. against an app booted by `@SpringBootTest(webEnvironment = RANDOM_PORT)`).
         */
        fun http(baseUrl: String, serialization: Wirespec.Serialization): WirespecTestContext =
            WirespecTestContext(
                transportation = WebClientTransportation(WebClient.create(baseUrl)),
                serialization = serialization,
            )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :runtime:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecTestContext.kt
git commit -m "feat(runtime): add WirespecTestContext with http(baseUrl, serialization) factory"
```

---

## Task 3: Add the top-level `scenario(...)` function (with TDD wiring test)

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt`
- Create: `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/ScenarioTest.kt`

- [ ] **Step 1: Write the failing wiring test first**

Create `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/ScenarioTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec

import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import java.util.concurrent.atomic.AtomicInteger

class ScenarioTest : FunSpec({

    val noopTransport = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no scenario step should call transport in this test")
    }
    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val ctx = WirespecTestContext(noopTransport, serialization)

    test("scenario with no calls runs cleanly inside checkAll") {
        val iterations = 5
        val counter = AtomicInteger(0)

        checkAll<Int>(iterations = iterations) {
            scenario(ctx) {
                // intentionally empty: no endpoint calls registered
                counter.incrementAndGet()
            }
        }

        counter.get() shouldBe iterations
    }

    test("scenario with no calls runs cleanly with the seed overload") {
        var ran = false
        scenario(ctx, seed = 1234L) {
            ran = true
        }
        ran shouldBe true
    }
})
```

- [ ] **Step 2: Run test to verify it fails (missing `scenario` symbol)**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.ScenarioTest"`
Expected: FAIL — compilation error, "unresolved reference: scenario".

- [ ] **Step 3: Create `Scenario.kt` with both overloads**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec

import io.kotest.extensions.spring.wirespec.dsl.ArbReceiver
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.runtime.ScenarioRunner
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource

/**
 * Run a single iteration of the scenario DSL against [ctx].
 *
 * Intended to be called inside kotest-property's `checkAll { … }` so the
 * per-iteration [RandomSource] flows in via [PropertyContext.randomSource].
 * On failure, `checkAll` reports the failing seed so the run is reproducible.
 *
 * ```
 * checkAll(iterations = 10) {
 *     scenario(ctx) {
 *         createPet.expecting<CreatePet.Response201>()
 *         …
 *     }
 * }
 * ```
 */
suspend fun PropertyContext.scenario(
    ctx: WirespecTestContext,
    block: ScenarioBuilder.() -> Unit,
) {
    val rs = randomSource()
    runScenarioOnce(ctx, rs, block)
}

/**
 * Single-run overload for one-shot scenarios outside `checkAll` (smoke tests,
 * deterministic regressions). Seed defaults to [System.nanoTime] — pass a fixed
 * seed to reproduce a previously-failing run.
 */
suspend fun scenario(
    ctx: WirespecTestContext,
    seed: Long = System.nanoTime(),
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioOnce(ctx, RandomSource.seeded(seed), block)
}

private suspend fun runScenarioOnce(
    ctx: WirespecTestContext,
    rs: RandomSource,
    block: ScenarioBuilder.() -> Unit,
) {
    val arb = ArbReceiver(rs)
    val builder = ScenarioBuilder(arb).apply(block)
    try {
        ScenarioRunner(
            scenario = builder,
            transportation = ctx.transportation,
            serialization = ctx.serialization,
            randomSource = rs,
            arbReceiver = arb,
        ).run()
    } finally {
        builder.clearRefs()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.ScenarioTest"`
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/ScenarioTest.kt
git commit -m "feat(runtime): add scenario(ctx) DSL entry-point + seed overload"
```

---

## Task 4: Add `SpringWirespecExtension`

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringWirespecExtension.kt`
- Test: covered by Task 6's rewritten example spec (booting Spring in a runtime-level unit test is heavier than it's worth).

- [ ] **Step 1: Create the extension**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringWirespecExtension.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.kotest

import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.spring.SpringTestContext
import kotlin.reflect.KClass

/**
 * Kotest spec-scoped listener that boots a Spring Boot application on a random
 * port before the spec runs and tears it down afterwards.
 *
 * Install once per spec (any spec style works — FunSpec, BehaviorSpec, …):
 * ```
 * class MySpec : FunSpec({
 *     val ws = install(SpringWirespecExtension(MyApp::class))
 *
 *     test("…") {
 *         checkAll<Int>(iterations = 10) {
 *             scenario(ws.context) { … }
 *         }
 *     }
 * })
 * ```
 *
 * Access the booted [WirespecTestContext] via [context] inside tests.
 */
class SpringWirespecExtension(
    private val application: KClass<*>,
) : BeforeSpecListener, AfterSpecListener {

    private lateinit var spring: SpringTestContext

    /** Booted context: transportation + serialization. Access only inside tests. */
    val context: WirespecTestContext
        get() = WirespecTestContext(spring.transportation, spring.serialization)

    override suspend fun beforeSpec(spec: Spec) {
        spring = SpringTestContext.boot(application)
    }

    override suspend fun afterSpec(spec: Spec) {
        if (::spring.isInitialized) spring.close()
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :runtime:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringWirespecExtension.kt
git commit -m "feat(runtime): add SpringWirespecExtension Kotest listener for Spring lifecycle"
```

---

## Task 5: Delete `SpringScenarioSpec` and update the build-file comment

**Files:**
- Delete: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt`
- Modify: `runtime/build.gradle.kts` (the comment about `SpringScenarioSpec` becomes stale)

- [ ] **Step 1: Confirm no remaining references in the runtime module**

Run: `grep -rn "SpringScenarioSpec" runtime/`
Expected: only `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt:11` (the class itself) and any internal doc/comment that mentions it. **The example module still references it at this point** — that's fine; Task 6 fixes the example.

- [ ] **Step 2: Delete the file**

Run: `git rm runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt`

- [ ] **Step 3: Update the stale comment in `runtime/build.gradle.kts`**

Open `runtime/build.gradle.kts`. Find the block that begins:

```kotlin
    // Note: kotest-extensions-spring 1.3.x is built against Kotest 5.x and
    // conflicts at runtime with kotest-runner-junit5:6.1.x (SpecRef.Reference
    // arity mismatch). Until a 6.x-compatible release is available we wire the
    // Spring boot lifecycle manually in SpringScenarioSpec.
```

Replace it with:

```kotlin
    // Note: kotest-extensions-spring 1.3.x is built against Kotest 5.x and
    // conflicts at runtime with kotest-runner-junit5:6.1.x (SpecRef.Reference
    // arity mismatch). Until a 6.x-compatible release is available we wire the
    // Spring boot lifecycle manually via SpringTestContext + SpringWirespecExtension.
```

- [ ] **Step 4: Verify runtime still compiles (example will break — that's expected)**

Run: `./gradlew :runtime:compileKotlin :runtime:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add runtime/build.gradle.kts
git commit -m "refactor(runtime): drop SpringScenarioSpec; lifecycle now via SpringWirespecExtension"
```

(The `git rm` from Step 2 stages the deletion automatically; both staged changes go into this commit.)

---

## Task 6: Rewrite `PetScenariosSpec` for the new style

**Files:**
- Modify: `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt`

- [ ] **Step 1: Replace the file contents wholesale**

Replace the entire body of `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt` with:

```kotlin
package io.kotest.extensions.spring.wirespec.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.ListPets
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.deletePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.listPets
import io.kotest.extensions.spring.wirespec.example.generated.kotest.updatePet
import io.kotest.extensions.spring.wirespec.kotest.SpringWirespecExtension
import io.kotest.extensions.spring.wirespec.scenario
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class PetScenariosSpec : FunSpec({

    val ws = install(SpringWirespecExtension(ExampleApplication::class))

    test("pet CRUD") {
        checkAll<Int>(iterations = 10) {
            scenario(ws.context) {
                val petId = createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                getPet
                    .path(petId)
                    .expecting<GetPet.Response200>()

                val newName = Arb.string()
                updatePet
                    .path(petId)
                    .body { name = newName }
                    .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

                getPet
                    .path(petId)
                    .expecting<GetPet.Response200>()

                deletePet
                    .path(id = petId)
                    .expecting<DeletePet.Response204>()

                getPet
                    .path(petId)
                    .expecting<GetPet.Response404>()
            }
        }
    }

    test("typesafe queries") {
        checkAll<Int>(iterations = 8) {
            scenario(ws.context) {
                (1..25).forEach { _ ->
                    createPet.expecting<CreatePet.Response201>()
                }
                listPets
                    .query(limit = 10, offset = 0)
                    .expecting<ListPets.Response200> { resp ->
                        resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                    }
            }
        }
    }
})
```

- [ ] **Step 2: Run the rewritten spec end-to-end**

Run: `./gradlew :example:test --tests "io.kotest.extensions.spring.wirespec.example.PetScenariosSpec"`
Expected: PASS — both `pet CRUD` and `typesafe queries` tests green.

- [ ] **Step 3: Commit**

```bash
git add example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt
git commit -m "test(example): migrate PetScenariosSpec to FunSpec + checkAll + scenario(ctx)"
```

---

## Task 7: Add `PetScenariosJUnitTest`

**Files:**
- Create: `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosJUnitTest.kt`

- [ ] **Step 1: Create the JUnit twin**

Create `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosJUnitTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.ListPets
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.deletePet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.listPets
import io.kotest.extensions.spring.wirespec.example.generated.kotest.updatePet
import io.kotest.extensions.spring.wirespec.scenario
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

/**
 * JUnit Jupiter twin of [PetScenariosSpec]. Demonstrates that the `scenario(...)` DSL
 * is framework-neutral: the inner block is identical to the Kotest example;
 * only the outer wiring (`@SpringBootTest` + `@Test fun = runBlocking { checkAll { … } }`)
 * is JUnit-flavored.
 */
@SpringBootTest(
    classes = [ExampleApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PetScenariosJUnitTest {

    @LocalServerPort
    var port: Int = 0

    private lateinit var ctx: WirespecTestContext

    @BeforeEach
    fun setUp() {
        ctx = WirespecTestContext.http(
            baseUrl = "http://localhost:$port",
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    @Test
    fun `pet CRUD`() = runBlocking {
        checkAll<Int>(iterations = 10) {
            scenario(ctx) {
                val petId = createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                getPet.path(petId).expecting<GetPet.Response200>()

                val newName = Arb.string()
                updatePet
                    .path(petId)
                    .body { name = newName }
                    .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

                getPet.path(petId).expecting<GetPet.Response200>()
                deletePet.path(id = petId).expecting<DeletePet.Response204>()
                getPet.path(petId).expecting<GetPet.Response404>()
            }
        }
    }

    @Test
    fun `typesafe queries`() = runBlocking {
        checkAll<Int>(iterations = 8) {
            scenario(ctx) {
                repeat(25) { createPet.expecting<CreatePet.Response201>() }
                listPets
                    .query(limit = 10, offset = 0)
                    .expecting<ListPets.Response200> { resp ->
                        resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                    }
            }
        }
    }
}
```

- [ ] **Step 2: Run the JUnit test**

Run: `./gradlew :example:test --tests "io.kotest.extensions.spring.wirespec.example.PetScenariosJUnitTest"`
Expected: PASS — both `pet CRUD` and `typesafe queries` test methods green.

- [ ] **Step 3: Commit**

```bash
git add example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosJUnitTest.kt
git commit -m "test(example): add PetScenariosJUnitTest showing JUnit Jupiter usage of the DSL"
```

---

## Task 8: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full runtime + example test suites**

Run: `./gradlew :runtime:test :example:test`
Expected: BUILD SUCCESSFUL — `ScenarioTest`, `InputTest`, `PetScenariosSpec`, and `PetScenariosJUnitTest` all green.

- [ ] **Step 2: Confirm `SpringScenarioSpec` is fully gone**

Run: `grep -rn "SpringScenarioSpec" --include="*.kt" --include="*.kts" --include="*.md"`
Expected: matches only in `docs/superpowers/specs/2026-05-22-flexible-scenario-dsl-design.md` (referenced as the *old* class) and `docs/superpowers/plans/2026-05-22-flexible-scenario-dsl.md` (this plan). No source references.

- [ ] **Step 3: Sanity-check failure seed reporting**

In `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt`, temporarily change the inner `expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }` to `it.body.name shouldBe "definitely-not-this-string"` and re-run that one test:

Run: `./gradlew :example:test --tests "io.kotest.extensions.spring.wirespec.example.PetScenariosSpec.pet CRUD" --info`

Expected: FAIL — failure message includes a `seed=<long>` line (kotest-property's built-in seed reporting). Revert the change after observing the seed:

Run: `git checkout -- example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt`

(Do not commit any of this step — it's a manual verification.)

- [ ] **Step 4: Final pass**

Run: `./gradlew :runtime:test :example:test`
Expected: BUILD SUCCESSFUL.

No commit for Task 8 — this is verification only.

---

## Self-review notes

- **Spec coverage:** `WirespecTestContext` (Task 2), `SpringWirespecExtension` (Task 4), `scenario(...)` overloads (Task 3), `SpringScenarioSpec` deletion + build comment update (Task 5), Kotest example (Task 6), JUnit example (Task 7), verification (Task 8). All spec sections mapped.
- **Type consistency:** `WirespecTestContext` constructor has parameter order `(transportation, serialization)` everywhere it's used. `SpringWirespecExtension.context` returns a `WirespecTestContext`. `scenario(ctx, …)` matches the type used by both examples.
- **No placeholders:** every "Write …" step contains the full code; every "Run …" step has the exact command and expected outcome.
