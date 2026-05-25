# kotest-extensions-spring-wirespec

> **Your Spring controllers _are_ the contract. Your tests _know_ the contract. Drift is impossible.**

A Gradle plugin that extracts an API contract from your Spring `@RestController`s, generates a typesafe Kotest DSL from it, and runs property-based scenarios against the live app — refusing to compile the moment your code and your tests stop agreeing.

## What you get for one `plugins { … }` line

```kotlin
plugins {
    id("org.springframework.boot") version "3.4.1"
    kotlin("jvm") version "2.3.0"
    id("io.kotest.extensions.wirespec") version "0.1.0"
}

kotestWirespecSpring {
    basePackage.set("com.example.api")
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

That's the whole setup. Each `gradle test` (or `mvn verify`) now does:

```
@RestController + @ApiResponses
        │
        ▼  extractWirespec   (scans your controllers)
  build/wirespec/extracted/*.ws
        │
        ▼  wirespecKotlin     (generates typed models, endpoints, Arb<T> generators)
  build/generated/wirespec/.../endpoint/*.kt
        │
        ▼  TypesafeDslEmitter (per-endpoint chained DSL)
  build/generated/wirespec/.../kotest/*Dsl.kt
        │
        ▼
  checkAll { scenario(ctx) { … } } → live Spring Boot on a random port
```

## What your tests look like

```kotlin
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.kotest.SpringSpecExtension
import io.kotest.extensions.wirespec.scenario
import io.kotest.property.checkAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [MyApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PetScenariosSpec : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    private val ctx: WirespecTestContext by lazy {
        WirespecTestContext.http(
            baseUrl = "http://localhost:$port",
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    init {
        extension(SpringSpecExtension)

        test("pet CRUD") {
            checkAll<Int>(iterations = 50) {
                scenario(ctx) {
                    val petId = createPet
                        .returning<CreatePet.Response201, String> { it.body.id }

                    getPet
                        .path(petId)
                        .expecting<GetPet.Response200>()

                    updatePet
                        .path(petId)
                        .body(UpdatePetRequest(name = "Rex", species = null))
                        .expecting<UpdatePet.Response200> { it.body.name shouldBe "Rex" }

                    deletePet.path(petId).expecting<DeletePet.Response204>()
                    getPet.path(petId).expecting<GetPet.Response404>()
                }
            }
        }
    }
}
```

`SpringSpecExtension` is a thin `SpecExtension` wrapper around the official
`io.kotest.extensions:kotest-extensions-spring` `SpringTestExtension`. It
delegates the actual Spring lifecycle (booting `@SpringBootTest`, populating
`@LocalServerPort` / `@Autowired`) to the maintained extension while bypassing
a `TestCaseExtension` code path that the 1.3.0 binary inherits from Kotest
5.x — the upstream artifact has not yet shipped a Kotest 6 release. Drop the
wrapper once it does.

The DSL itself is spec-style-agnostic — drop `scenario(ctx) { … }` into any
Kotest spec (FunSpec, BehaviorSpec, ShouldSpec, …) or into a JUnit Jupiter
`@Test` method via `@SpringBootTest(webEnvironment = RANDOM_PORT)`. See
`example/src/test/kotlin/.../PetScenariosJUnitTest.kt` for the JUnit variant.

Every identifier you see — `createPet`, `getPet`, `Response201`, `Response404`, `CreatePetRequest` — was generated from your controller this build. Rename a method, change a path parameter, drop a `@ApiResponse` annotation: the test won't compile.

## Channels (Kafka)

`wirespec-spring-extractor` 0.0.7+ extracts `@KafkaListener` methods and
`kafkaTemplate.send(...)` call sites as Wirespec channels. The Kotest DSL
emitter generates a per-channel receiver alongside the per-endpoint one —
both interleave in the same `scenario { … }`:

```kotlin
@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events"])
class PetChannelSpec : SpringWirespecSpec({

    test("HTTP create publishes a PetCreatedEvent", iterations = 5) {
        val petId = createPet.returning<CreatePet.Response201, String> { it.body.id }

        publishPetCreated
            .topic("pets.events")
            .expecting { it.id shouldBe petId.require() }
    }
})
```

`SpringWirespecSpec.channelCtx` is auto-resolved from `@EmbeddedKafka` — no
listener wiring needed. Override `channelCtx` for a custom transport
(e.g. Testcontainers).

**Slots on a channel call:**

- `.topic(value)` / `.topic(ref: ResultRef<String>)` — required. The extracted
  contract does not carry topic names.
- `.key(value)` — optional partition key for producer steps.
- `.send(value | Arb | block { … })` — test publishes a message; drives an
  app `@KafkaListener`. The `block { … }` form takes a per-field
  `KotestWirespecGeneratorBuilder` receiver — `name = Arb.string(…)` pins
  the `name` field while other fields are Arb-generated from the schema.
- `.expecting()` / `.expecting { assertion }` — test consumes; asserts the
  app published exactly one record on `topic` within 2 s.
- `.collecting(count = N)` / `.collecting(duration = d)` — batched consume.
- `.returning { projection }` — same `ResultRef` pattern as endpoints.

Setting both `.send` and `.expecting` on one channel call is a configuration
error caught before any transport call runs.

Channel runtime deps are `compileOnly` in the library — consumers writing
channel tests add `spring-kafka` + `spring-kafka-test` to their own build.

## The value proposition

### Contract drift becomes a compile error

Rename `fun create()` to `fun createPet()` in your controller. The extractor regenerates the contract, the emitter regenerates the DSL, and `createPet { … }` is suddenly unresolved. CI catches it before the PR opens.

### One source of truth — your code

No hand-maintained OpenAPI yaml, no separate `.ws` files to keep in sync. The controller's `@RestController`, `@RequestMapping`, `@PathVariable`, `@RequestParam`, `@RequestBody`, and `@ApiResponses` annotations are the contract.

### Status-narrowed responses, no casts

`expecting<GetPet.Response404>()` returns a value statically typed to that variant. The body is `it.body.code`, not `(it.body as? ErrorResponse)?.code ?: error(...)`. `Response201` and `Response404` are different types, so a typo can't compile.

### Auto-validation on every call

Every iteration validates that:
- The response status matches a status declared by the contract.
- The response body deserializes against that status's declared schema.

Failures carry `scenario · iteration · seed · endpoint · raw response · concrete mismatch` so you can replay them.

### Property-based by default

Wrap a `scenario(ctx) { … }` block in `checkAll<Int>(iterations = N) { … }` and it runs N times with a seeded `RandomSource` threaded through kotest-property — failures print the seed for free. Unset slots (`body`, `path`, `query`, `header`) default to `Arb<T>` generators derived from the Wirespec types — you get random valid payloads for free. Pin only what your scenario actually cares about.

### Slot ergonomics: only what exists

The emitter renders `path(id: String)` only when the endpoint declares a path parameter, `query(limit: Int, offset: Int)` only when it declares those queries, `body(T)` only when there's a request body. Calling `petCreate.path(...)` (where `PetCreate.Path` is empty) is a compile error.

## What this replaces

| Without the plugin | With the plugin |
|---|---|
| `MockMvc.perform(post("/pets")…)` with magic strings | `createPet.body(CreatePetRequest(...))` — fully typed |
| `.andExpect(status().isCreated)` | `.expecting<CreatePet.Response201>()` — and the body type is checked |
| `objectMapper.readValue(json, Pet::class.java)` | The matched variant's `body` field is already typed |
| Manually authored OpenAPI spec to keep in sync | Extracted from `@RestController` annotations |
| Property tests that go stale when the controller drifts | Tests that fail to compile when they drift |
| Streaming endpoints tested with ad-hoc loops | `.collecting<Response200>(count = 10) { events -> … }` |

## Status

Pre-release. Iteration on the DSL and emitter is ongoing — the public API may shift before 1.0.

## License

Apache 2.0 — see [LICENSE](LICENSE).
