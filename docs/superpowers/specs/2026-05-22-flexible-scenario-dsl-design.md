# Flexible scenario DSL — design

**Status:** drafted 2026-05-22
**Owner:** Willem Veelenturf
**Related:** existing `SpringScenarioSpec` (`runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt`)

## Goal

Decouple the scenario DSL from the `SpringScenarioSpec` base class so that:

1. Any Kotest spec style (`FunSpec`, `BehaviorSpec`, `ShouldSpec`, …) can drive
   the DSL — no forced inheritance.
2. The iteration loop is delegated to kotest-property's `checkAll`, not a
   hand-rolled for-loop inside the runtime — users get every `checkAll` knob
   (seed override, edge-case bias, `PropTestConfig`) for free.
3. The same DSL is callable from a pure JUnit Jupiter test, so consumers who
   prefer `@SpringBootTest` over Kotest can adopt the runtime without switching
   test frameworks.

## Non-goals

- Replacing `kotest-property` with a homegrown iteration loop.
- Shipping a custom JUnit Jupiter `Extension`. Spring's
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` already covers the lifecycle
  for JUnit users; we provide only a tiny context-construction helper.
- Changing the `ScenarioBuilder` / `EndpointCallBuilder` / `ArbReceiver` /
  `ResultRef` DSL surface. The body of a `scenario { ... }` block stays
  byte-identical.

## Approach

Three public pieces replace `SpringScenarioSpec`:

### 1. `WirespecTestContext` — framework-neutral

A simple value type binding the two things the runner needs: a
`Wirespec.Transportation` and a `Wirespec.Serialization`. Built directly or via
factories.

```kotlin
package io.kotest.extensions.spring.wirespec

class WirespecTestContext(
    val transportation: Wirespec.Transportation,
    val serialization: Wirespec.Serialization,
) {
    companion object {
        fun http(baseUrl: String, serialization: Wirespec.Serialization): WirespecTestContext =
            WirespecTestContext(
                transportation = WebClientTransportation(WebClient.create(baseUrl)),
                serialization = serialization,
            )
    }
}
```

### 2. `SpringWirespecExtension` — Kotest spec listener

Owns the Spring Boot lifecycle for Kotest users. Installs into any spec style:

```kotlin
package io.kotest.extensions.spring.wirespec.kotest

class SpringWirespecExtension(
    private val application: KClass<*>,
) : BeforeSpecListener, AfterSpecListener {
    private lateinit var spring: SpringTestContext
    val context: WirespecTestContext get() =
        WirespecTestContext(spring.transportation, spring.serialization)

    override suspend fun beforeSpec(spec: Spec) { spring = SpringTestContext.boot(application) }
    override suspend fun afterSpec(spec: Spec)  { if (::spring.isInitialized) spring.close() }
}
```

`SpringTestContext` is unhidden (drop the `internal` modifier) so the extension
— and any user who wants to manage lifecycle themselves — can reuse it.

### 3. `scenario(...)` — the DSL entrypoint

A top-level suspending function. The primary form is an extension on
kotest-property's `PropertyContext`, so the iteration `RandomSource` flows in
from `checkAll`. A convenience overload accepts an explicit seed for one-shot
runs outside `checkAll`.

```kotlin
package io.kotest.extensions.spring.wirespec

suspend fun PropertyContext.scenario(
    ctx: WirespecTestContext,
    block: ScenarioBuilder.() -> Unit,
) {
    val rs = randomSource()
    val arb = ArbReceiver(rs)
    val builder = ScenarioBuilder(arb).apply(block)
    try {
        ScenarioRunner(builder, ctx.transportation, ctx.serialization, rs, arb).run()
    } finally {
        builder.clearRefs()
    }
}

suspend fun scenario(
    ctx: WirespecTestContext,
    seed: Long = System.nanoTime(),
    block: ScenarioBuilder.() -> Unit,
) { /* builds its own RandomSource, then calls the receiver overload */ }
```

`ScenarioRunner` stays `internal`. Seed reporting on failure is delegated to
`checkAll`, which already prints the failing seed.

## What gets deleted

- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt`
  is removed. Its per-iteration loop, seed catch-and-rethrow, and FunSpec
  inheritance all move into (or are subsumed by) the `scenario(...)` function
  + `checkAll`.

## Usage — Kotest

`install(...)` is the `Spec.install` extension function from
`io.kotest.core.extensions` — it's not auto-imported in Kotest 6, so the
import is explicit. `SpringWirespecExtension` implements
`MountableExtension<Unit, SpringWirespecExtension>` to participate in `install`.

```kotlin
import io.kotest.core.extensions.install

class PetScenariosSpec : FunSpec({

    val ws = install(SpringWirespecExtension(ExampleApplication::class))

    test("pet CRUD") {
        checkAll<Int>(iterations = 10) {
            scenario(ws.context) {
                val petId = createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                getPet.path(petId).expecting<GetPet.Response200>()

                updatePet
                    .path(petId)
                    .body { name = Arb.string() }
                    .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

                deletePet.path(id = petId).expecting<DeletePet.Response204>()
                getPet.path(petId).expecting<GetPet.Response404>()
            }
        }
    }
})
```

> The explicit `<Int>` is needed because Kotest 6's no-Arb `checkAll` overload
> always synthesizes a value — there is no zero-value form. `Int` is a
> conventional dummy; the synthesized value is unused inside `scenario { … }`.

A consumer who wants a single deterministic run skips `checkAll`:

```kotlin
test("smoke") {
    scenario(ws.context, seed = 42L) {
        getPet.path("123").expecting<GetPet.Response404>()
    }
}
```

## Usage — JUnit Jupiter

```kotlin
@SpringBootTest(
    classes = [ExampleApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PetScenariosJUnitTest {

    @LocalServerPort var port: Int = 0
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
                deletePet.path(id = petId).expecting<DeletePet.Response204>()
                getPet.path(petId).expecting<GetPet.Response404>()
            }
        }
    }
}
```

Same three layers as the Kotest example — `@Test … = runBlocking { checkAll {
scenario(ctx) { … } } }` — with an identical inner DSL body.

## Files touched

**New:**
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecTestContext.kt`
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt` (the
  top-level `scenario(...)` overloads)
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/kotest/SpringWirespecExtension.kt`
- `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosJUnitTest.kt`

**Modified:**
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/spring/SpringTestContext.kt`
  — drop `internal`, leave behavior untouched.
- `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt`
  — rewritten as `FunSpec` + `install` + `checkAll`.
- `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/dsl/InputTest.kt`
  — adjust only if it imported `SpringScenarioSpec`.

**Deleted:**
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringScenarioSpec.kt`

## Verification

- `./gradlew :runtime:test` — DSL-level tests still green.
- `./gradlew :example:test` — both the rewritten Kotest spec and the new
  JUnit test pass against the live `ExampleApplication`.
- Manual sanity: fail one assertion inside a `scenario { ... }` body and
  confirm `checkAll`'s failure message includes the seed so the run is
  reproducible.

## Migration notes for downstream consumers

Anyone extending `SpringScenarioSpec` today migrates with three mechanical
edits:

1. Replace `: SpringScenarioSpec(App::class, { … })` with
   `: FunSpec({ val ws = install(SpringWirespecExtension(App::class)); … })`.
2. Wrap each existing `scenario("name", iterations = N) { … }` body in
   `test("name") { checkAll<Int>(iterations = N) { scenario(ws.context) { … } } }`.
3. Delete the import of `io.kotest.extensions.spring.wirespec.SpringScenarioSpec`.

The block bodies themselves are unchanged.
