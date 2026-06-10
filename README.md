# kotest-wirespec

> **Your Spring controllers _are_ the contract. Your tests _know_ the contract. Drift is impossible.**

A Gradle plugin that extracts an API contract from your Spring `@RestController`s, generates a typesafe Kotest DSL from it, and runs property-based scenarios against the live app — refusing to compile the moment your code and your tests stop agreeing.

## What you get for one `plugins { … }` line

```kotlin
plugins {
    id("org.springframework.boot") version "3.4.1"
    kotlin("jvm") version "2.3.0"
    id("io.kotest.extensions.wirespec") version "0.1.0"
}

kotestWirespec {
    basePackage.set("com.example.api")
    // spring = false                                  // opt out of Spring extraction
    // wirespecPath.set(file("src/test/wirespec"))     // compile .ws files from this folder
}

dependencies {
    testImplementation("io.kotest.extensions.wirespec:kotest-wirespec:0.1.0")
    testImplementation("io.kotest.extensions.wirespec:kotest-wirespec-spring:0.1.0")
}
```

By default the plugin auto-detects whether `org.springframework.boot` is applied and wires the Spring extractor accordingly. Set `kotestWirespec { spring = false }` to skip extraction and compile hand-authored `.ws` files instead — from `src/test/wirespec/` by default, or from a folder you set via `wirespecPath`. `wirespecPath`, when set, is always the compile input; with `spring = true` the extractor writes its emitted `.ws` files there before compiling (point it at a dedicated directory).

…or, in Maven:

```xml
<plugin>
    <groupId>io.kotest.extensions.wirespec</groupId>
    <artifactId>kotest-wirespec-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <basePackage>com.example.api</basePackage>
                <!-- <spring>false</spring> -->
                <!-- <wirespecPath>${project.basedir}/src/test/wirespec</wirespecPath> -->
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

That's the whole setup. Each `gradle test` (or `mvn verify`) now does:

```
@RestController + @ApiResponses
        │
        ▼  extractWirespec   (scans your controllers; off when spring = false)
  build/wirespec/extracted/*.ws
        │
        ▼  wirespecKotlin     (generates typed models, endpoints, Arb<T> generators)
  build/generated/wirespec/.../endpoint/*.kt
        │
        ▼  TypesafeDslEmitter (per-endpoint chained DSL)
  build/generated/wirespec/.../kotest/*Dsl.kt
        │
        ▼
  FunSpec { test { PetControllerV1.createPet.expecting<…>() } } → live Spring Boot
```

## What your tests look like

```kotlin
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.WirespecExtension
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetScenariosSpec : FunSpec({

    test("pet CRUD") {
        val petId = PetControllerV1.createPet
            .returning<CreatePet.Response201, String> { it.body.id }

        PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()

        PetControllerV1.updatePet
            .path(petId)
            .body { name = Arb.constant("Rex") }
            .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

        PetControllerV1.deletePet.path(id = petId).expecting<DeletePet.Response204>()
        PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response404>()
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

Use a plain Kotest spec (`FunSpec`, `WordSpec`, …) — no base class. The generated catalog object (e.g. `PetControllerV1`, one per controller/`.ws` file) is the single accessor for all endpoints on that controller, imported from `...generated.kotest.<CatalogName>`. IDE completion shows only your contract's operations — not stdlib noise.

The spec resolves a default `MockMvc`-backed context from the `kotest-wirespec-spring` module: add that artifact to the test classpath and annotate the spec with `@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)` — `@SpringBootTest` boots, `@Autowired` fields populate, and `WirespecExtension` installs the ambient wirespec context so the bare catalog calls resolve it automatically.

Terminals are **suspend and eager**: `expecting<R>()` / `expecting<R> { … }` execute the call immediately and return the typed response. `returning<R, T> { it.body.id }` executes the call and returns the projected value directly — there is no `ResultRef` wrapper and no `.require()` call.

Every identifier you see — `PetControllerV1.createPet`, `PetControllerV1.getPet1`, `Response201`, `Response404`, `CreatePetRequest` — was generated from your controller this build. Rename a method, change a path parameter, drop a `@ApiResponse` annotation: the test won't compile.

## Property-based runs

Wrap the flat catalog calls in kotest-native `checkAll<Int>(iterations = N) { … }` to run a block N times with a seeded `RandomSource`. Unset slots (`body`, `path`, `query`, `header`) default to `Arb<T>` generators derived from the Wirespec types — random valid payloads for free. Pin only what your test actually cares about.

```kotlin
@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetPropertySpec : FunSpec({

    test("typesafe queries") {
        checkAll<Int>(iterations = 8) {
            repeat(25) { PetControllerV1.createPet.expecting<CreatePet.Response201>() }
            PetControllerV1.listPets
                .query(limit = 10, offset = 0)
                .expecting<ListPets.Response200> { resp ->
                    resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                }
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

Call `useWirespecSeed()` at the top of a `checkAll` block to align kotest's reported seed with wirespec-generated data so failures are fully reproducible.

## JUnit Jupiter

For JUnit tests, build a `WirespecTestContext` manually and wrap calls in `withWirespec(ctx) { … }`:

```kotlin
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.spring.http
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [MyApp::class],
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
    fun `pet CRUD`(): Unit = runBlocking {
        checkAll<Int>(iterations = 10) {
            withWirespec(ctx) {
                val petId = PetControllerV1.createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()

                PetControllerV1.deletePet.path(id = petId).expecting<DeletePet.Response204>()
                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response404>()
            }
        }
    }
}
```

`withWirespec(ctx) { … }` installs the context for the duration of the block, making all catalog calls inside it resolve to that transport. Each `checkAll` iteration can wrap its own `withWirespec` block for full isolation.

## Channels (Kafka)

`wirespec-spring-extractor` 0.0.7+ extracts `@KafkaListener` methods and `kafkaTemplate.send(...)` call sites as Wirespec channels. The Kotest DSL emitter generates a per-channel catalog object alongside the per-endpoint one — both interleave in the same `test { … }`:

```kotlin
@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events", "pets.commands"])
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetChannelSpec : FunSpec({

    test("HTTP create publishes a PetCreatedEvent") {
        val petId = PetControllerV1.createPet
            .body {
                name = Arb.string(minSize = 1, maxSize = 16)
                species = Arb.string(minSize = 1, maxSize = 8)
            }
            .returning<CreatePet.Response201, String> { it.body.id }

        PetEventPublisher.publishPetCreated
            .topic("pets.events")
            .expecting { it.id shouldBe petId }
    }

    test("Kafka command creates a pet") {
        val correlationId = PetCommandListener.onCreatePetCommand
            .topic("pets.commands")
            .send()
            .correlationId

        eventually(5.seconds) {
            PetControllerV1.getPet1.path(correlationId).expecting<GetPet1.Response200>()
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

The channel context is auto-resolved from `@EmbeddedKafka` by `SpringContextProvider` — no listener wiring needed. For a custom transport (e.g. Testcontainers), pass it explicitly to `withWirespec`.

**Slots on a channel call:**

- `.topic(value)` — required. The extracted contract does not carry topic names.
- `.key(value)` — optional partition key for producer steps.
- `.send()` / `.send(value | Arb | block { … })` — test publishes a message; drives an app `@KafkaListener`. Returns the sent payload directly — e.g. `.send().correlationId`. The `block { … }` form takes a per-field `KotestWirespecGeneratorBuilder` receiver — `name = Arb.string(…)` pins the `name` field while other fields are Arb-generated from the schema.
- `.expecting()` / `.expecting { assertion }` — test consumes; asserts the app published exactly one record on `topic` within 2 s.
- `.collecting(count = N)` / `.collecting(duration = d)` — batched consume.

Setting both `.send` and `.expecting` on one channel call is a configuration error caught before any transport call runs.

`spring-kafka(-test)` is `compileOnly` in `kotest-wirespec-spring` — consumers writing channel tests add the runtime artifacts to their own test build.

## The value proposition

### Contract drift becomes a compile error

Rename `fun create()` to `fun createPet()` in your controller. The extractor regenerates the contract, the emitter regenerates the DSL, and `PetControllerV1.createPet` is suddenly unresolved. CI catches it before the PR opens.

### One source of truth — your code

No hand-maintained OpenAPI yaml, no separate `.ws` files to keep in sync. The controller's `@RestController`, `@RequestMapping`, `@PathVariable`, `@RequestParam`, `@RequestBody`, and `@ApiResponses` annotations are the contract.

### Status-narrowed responses, no casts

`expecting<GetPet1.Response404>()` returns a value statically typed to that variant. The body is `it.body.code`, not `(it.body as? ErrorResponse)?.code ?: error(...)`. `Response201` and `Response404` are different types, so a typo can't compile.

### Auto-validation on every call

Every call validates that:
- The response status matches a status declared by the contract.
- The response body deserializes against that status's declared schema.

Failures carry `iteration · seed · endpoint · raw response · concrete mismatch` so you can replay them.

### Property-based by default

Wrap calls in `checkAll<Int>(iterations = N) { … }` and they run N times with a seeded `RandomSource` threaded through kotest-property — failures print the seed for free. Unset slots (`body`, `path`, `query`, `header`) default to `Arb<T>` generators derived from the Wirespec types — you get random valid payloads for free. Pin only what your test actually cares about.

### Slot ergonomics: only what exists

The emitter renders `path(id: String)` only when the endpoint declares a path parameter, `query(limit: Int, offset: Int)` only when it declares those queries, `body(T)` only when there's a request body. Calling `.path(...)` on an endpoint where `Path` is empty is a compile error.

## Modules

| Artifact | What's in it |
|---|---|
| `io.kotest.extensions.wirespec:kotest-wirespec` | Framework-neutral runtime: catalog DSL, `ContextProvider` SPI. No Spring deps. |
| `io.kotest.extensions.wirespec:kotest-wirespec-spring` | Spring transports (`MockMvc`, `WebClient`, `EmbeddedKafka`), `WirespecTestContext.http(...)` factory, auto-registered `SpringContextProvider`, upstream `io.kotest:kotest-extensions-spring` lifecycle. |
| `io.kotest.extensions.wirespec:kotest-wirespec-emitter` | The Wirespec `Emitter` that produces the typesafe Kotest DSL (used by the build plugins). |
| `io.kotest.extensions.wirespec:kotest-wirespec-maven-plugin` | Maven Mojo wrapping the extractor + emitter pipeline. |
| Gradle plugin id `io.kotest.extensions.wirespec` | Gradle plugin wrapping the extractor + emitter pipeline. |

## What this replaces

| Without the plugin | With the plugin |
|---|---|
| `MockMvc.perform(post("/pets")…)` with magic strings | `PetControllerV1.createPet.body { … }` — fully typed |
| `.andExpect(status().isCreated)` | `.expecting<CreatePet.Response201>()` — and the body type is checked |
| `objectMapper.readValue(json, Pet::class.java)` | The matched variant's `body` field is already typed |
| Manually authored OpenAPI spec to keep in sync | Extracted from `@RestController` annotations |
| Property tests that go stale when the controller drifts | Tests that fail to compile when they drift |
| Streaming endpoints tested with ad-hoc loops | `.collecting<Response200>(count = 10) { events -> … }` |

## Status

Pre-release. Iteration on the DSL and emitter is ongoing — the public API may shift before 1.0.

## License

Apache 2.0 — see [LICENSE](LICENSE).
