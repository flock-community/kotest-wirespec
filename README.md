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
    // spring = false  // opt out of Spring extraction; supply .ws files under src/test/wirespec/
}

dependencies {
    testImplementation("io.kotest.extensions.wirespec:kotest-wirespec:0.1.0")
    testImplementation("io.kotest.extensions.wirespec:kotest-wirespec-spring:0.1.0")
}
```

By default the plugin auto-detects whether `org.springframework.boot` is applied and wires the Spring extractor accordingly. Set `kotestWirespec { spring = false }` to skip extraction and supply hand-authored `.ws` files via `src/test/wirespec/` (or configure the upstream `community.flock.wirespec.plugin.gradle` extension).

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
  FunSpec { test { scenario { … } } } → live Spring Boot
```

## What your tests look like

```kotlin
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.scenario
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.wirespec.example.generated.kotest.wirespec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class)
class PetScenariosSpec : FunSpec({

    test("pet CRUD") {
        scenario(iterations = 50) {
            val petId = wirespec.createPet
                .returning<CreatePet.Response201, String> { it.body.id }

            wirespec.getPet
                .path(petId)
                .expecting<GetPet.Response200>()

            wirespec.updatePet
                .path(petId)
                .body(UpdatePetRequest(name = "Rex", species = null))
                .expecting<UpdatePet.Response200> { it.body.name shouldBe "Rex" }

            wirespec.deletePet.path(petId).expecting<DeletePet.Response204>()
            wirespec.getPet.path(petId).expecting<GetPet.Response404>()
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

Use a plain Kotest spec (`FunSpec`, `WordSpec`, …) — no base class. `wirespec.` is the single accessor for every generated endpoint and channel, so IDE completion shows only your contract's operations (not stdlib noise).

The spec resolves a default `MockMvc`-backed context from the `kotest-wirespec-spring` module: add that artifact to the test classpath and annotate the spec with `@ApplyExtension(SpringRootTestExtension::class)` (from `io.kotest:kotest-extensions-spring`), so `@SpringBootTest` boots and `@Autowired` fields populate; `scenario { … }` then resolves transports by reflecting on the `@Autowired ApplicationContext` field. For a custom transport, pass one explicitly — `scenario(ctx, iterations = N) { … }` — e.g. a `@LocalServerPort`-driven `WirespecTestContext.http(...)` from `kotest-wirespec-spring`.

The DSL is spec-style-agnostic: `scenario { … }` works in any Kotest spec, and the explicit `scenario(ctx) { … }` form drops into a JUnit Jupiter `@Test` via `@SpringBootTest(webEnvironment = RANDOM_PORT)`. See `example/src/test/kotlin/.../PetScenariosJUnitTest.kt` for the JUnit variant.

Every identifier you see — `wirespec.createPet`, `wirespec.getPet`, `Response201`, `Response404`, `CreatePetRequest` — was generated from your controller this build. Rename a method, change a path parameter, drop a `@ApiResponse` annotation: the test won't compile.

## Channels (Kafka)

`wirespec-spring-extractor` 0.0.7+ extracts `@KafkaListener` methods and `kafkaTemplate.send(...)` call sites as Wirespec channels. The Kotest DSL emitter generates a per-channel receiver alongside the per-endpoint one — both interleave in the same `scenario { … }`:

```kotlin
@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events"])
@ApplyExtension(SpringRootTestExtension::class)
class PetChannelSpec : FunSpec({

    test("HTTP create publishes a PetCreatedEvent") {
        scenario(iterations = 5) {
            val petId = wirespec.createPet.returning<CreatePet.Response201, String> { it.body.id }

            wirespec.publishPetCreated
                .topic("pets.events")
                .expecting { it.id shouldBe petId.require() }
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

The channel context is auto-resolved from `@EmbeddedKafka` by `SpringContextProvider` — no listener wiring needed. For a custom transport (e.g. Testcontainers), pass it explicitly: `scenario(ctx, channelCtx = …) { … }`.

**Slots on a channel call:**

- `.topic(value)` / `.topic(ref: ResultRef<String>)` — required. The extracted contract does not carry topic names.
- `.key(value)` — optional partition key for producer steps.
- `.send(value | Arb | block { … })` — test publishes a message; drives an app `@KafkaListener`. The `block { … }` form takes a per-field `KotestWirespecGeneratorBuilder` receiver — `name = Arb.string(…)` pins the `name` field while other fields are Arb-generated from the schema.
- `.expecting()` / `.expecting { assertion }` — test consumes; asserts the app published exactly one record on `topic` within 2 s.
- `.collecting(count = N)` / `.collecting(duration = d)` — batched consume.
- `.returning { projection }` — same `ResultRef` pattern as endpoints.

Setting both `.send` and `.expecting` on one channel call is a configuration error caught before any transport call runs.

`spring-kafka(-test)` is `compileOnly` in `kotest-wirespec-spring` — consumers writing channel tests add the runtime artifacts to their own test build.

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

## Modules

| Artifact | What's in it |
|---|---|
| `io.kotest.extensions.wirespec:kotest-wirespec` | Framework-neutral runtime: scenario DSL, `ContextProvider` SPI. No Spring deps. |
| `io.kotest.extensions.wirespec:kotest-wirespec-spring` | Spring transports (`MockMvc`, `WebClient`, `EmbeddedKafka`), `WirespecTestContext.http(...)` factory, auto-registered `SpringContextProvider`, upstream `io.kotest:kotest-extensions-spring` lifecycle. |
| `io.kotest.extensions.wirespec:kotest-wirespec-emitter` | The Wirespec `Emitter` that produces the typesafe Kotest DSL (used by the build plugins). |
| `io.kotest.extensions.wirespec:kotest-wirespec-maven-plugin` | Maven Mojo wrapping the extractor + emitter pipeline. |
| Gradle plugin id `io.kotest.extensions.wirespec` | Gradle plugin wrapping the extractor + emitter pipeline. |

## What this replaces

| Without the plugin | With the plugin |
|---|---|
| `MockMvc.perform(post("/pets")…)` with magic strings | `wirespec.createPet.body(CreatePetRequest(...))` — fully typed |
| `.andExpect(status().isCreated)` | `.expecting<CreatePet.Response201>()` — and the body type is checked |
| `objectMapper.readValue(json, Pet::class.java)` | The matched variant's `body` field is already typed |
| Manually authored OpenAPI spec to keep in sync | Extracted from `@RestController` annotations |
| Property tests that go stale when the controller drifts | Tests that fail to compile when they drift |
| Streaming endpoints tested with ad-hoc loops | `.collecting<Response200>(count = 10) { events -> … }` |

## Status

Pre-release. Iteration on the DSL and emitter is ongoing — the public API may shift before 1.0.

## License

Apache 2.0 — see [LICENSE](LICENSE).
