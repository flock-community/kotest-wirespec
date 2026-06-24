# kotest-wirespec

> **Your Spring controllers _are_ the contract. Your tests _know_ the contract. Drift is impossible.**

A Gradle (and Maven) plugin that extracts an API contract from your Spring `@RestController`s, generates a typesafe Kotest scenario DSL from it, and runs property-based scenarios against the live app — refusing to compile the moment your code and your tests stop agreeing.

The runtime and DSL emitter now ship with Wirespec itself: the plugin drives Wirespec's
`KotlinIrEmitter` + `KotestDslExtension`, and your tests depend on
`community.flock.wirespec.integration:kotest-jvm`. This repo provides the **extraction +
codegen plugins** (Gradle and Maven) that wire that pipeline into your build.

## What you get for one `plugins { … }` line

```kotlin
plugins {
    id("org.springframework.boot") version "3.4.1"
    kotlin("jvm") version "2.3.0"
    id("community.flock.wirespec.kotest") version "<version>"
}

kotestWirespec {
    basePackage.set("com.example.api")              // @RestControllers the extractor scans
    // spring = false                               // opt out of Spring extraction
    // wirespecPath.set(file("src/test/wirespec"))  // compile .ws files from this folder
}

dependencies {
    // Wirespec's Kotest scenario-DSL runtime — the generated `<Endpoint>.call { }` DSL
    // compiles against it (brings the `Wirespec` runtime + kotest-property transitively).
    testImplementation("community.flock.wirespec.integration:kotest-jvm:0.20.0-RC.2")
    testImplementation("community.flock.wirespec.integration:wirespec-jvm:0.20.0-RC.2")
    testImplementation("community.flock.wirespec.integration:jackson-jvm:0.20.0-RC.2")
    testImplementation("io.kotest:kotest-property:6.1.4")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.4")
    testImplementation("io.kotest:kotest-assertions-core:6.1.4")
}
```

By default the plugin auto-detects whether `org.springframework.boot` is applied and wires the Spring extractor accordingly. Set `kotestWirespec { spring = false }` to skip extraction and compile hand-authored `.ws` files instead — from `src/test/wirespec/` by default, or from a folder you set via `wirespecPath`. `wirespecPath`, when set, is always the compile input; with `spring = true` the extractor writes its emitted `.ws` files there before compiling (point it at a dedicated directory).

…or, in Maven:

```xml
<plugin>
    <groupId>community.flock.wirespec.kotest</groupId>
    <artifactId>kotest-wirespec-maven-plugin</artifactId>
    <version>&lt;version&gt;</version>
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

That's the whole codegen setup. Each `gradle test` (or `mvn verify`) now does:

```
@RestController + @ApiResponses
        │
        ▼  extractWirespec      (scans your controllers; off when spring = false)
  build/wirespec/*.ws
        │
        ▼  KotlinIrEmitter      (generates typed models, endpoints, channels, Arb<T> generators)
  build/generated/wirespec/.../endpoint/*.kt
        │
        ▼  KotestDslExtension   (per-endpoint / per-channel `*.call { }` DSL)
  build/generated/wirespec/.../kotest/*Dsl.kt
        │
        ▼
  FunSpec { test { CreatePet.call { expecting<…>() } } } → live Spring Boot
```

> **Generated package (wirespec 0.20.0-RC.2 limitation).** This release's `KotlinIrEmitter`
> emits the generated **models** into the fixed package `community.flock.wirespec.generated`,
> ignoring `basePackage`/`generatedPackage` (only the Kotest DSL honours them). To keep the
> generated DSL and models compilable together, the plugins pin the generated code to
> `community.flock.wirespec.generated.*`. `basePackage` still drives the Spring extractor.
> When a Wirespec release restores `packageName` handling in the IR emitter, the generated
> code will move back under your own namespace.

## What your tests look like

Mount `WirespecExtension` and call the generated `<Endpoint>.call { }` DSL. The transport is
supplied by a `ContextProvider` you register (see [Wiring the transport](#wiring-the-transport)).

```kotlin
import community.flock.wirespec.generated.endpoint.CreatePet
import community.flock.wirespec.generated.endpoint.DeletePet
import community.flock.wirespec.generated.endpoint.GetPet1
import community.flock.wirespec.generated.endpoint.UpdatePet
import community.flock.wirespec.generated.kotest.call
import community.flock.wirespec.integration.kotest.WirespecExtension
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant

@ApplyExtension(WirespecExtension::class)
class PetScenariosSpec : FunSpec({

    test("pet CRUD") {
        val petId = CreatePet.call {
            body = { name = Arb.constant("rex"); species = Arb.constant("dog") }
            expecting<CreatePet.Response201>()
        }.body.id

        GetPet1.call { path = { id = Arb.constant(petId) }; expecting<GetPet1.Response200>() }

        UpdatePet.call {
            path = { id = Arb.constant(petId) }
            body = { name = Arb.constant("new name") }
            expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }
        }

        DeletePet.call { path = { id = Arb.constant(petId) }; expecting<DeletePet.Response204>() }
        GetPet1.call { path = { id = Arb.constant(petId) }; expecting<GetPet1.Response404>() }
    }
})
```

Use a plain Kotest spec (`FunSpec`, `WordSpec`, …) — no base class. `CreatePet`, `GetPet1`, … are the generated endpoint objects; `<Endpoint>.call { … }` opens a scope where you pin request slots (`path`, `query`, `body`) with kotest `Arb`s and end on a terminal. IDE completion shows only your contract's operations and response variants — not stdlib noise.

Inside a `call { }` scope:

- `path = { … }` / `query = { … }` / `body = { … }` — set request slots; each field is a `Gen<T>`. Unset slots (and unset fields) are generated from the contract.
- `bodyCount = 1..3` — for list-bodied endpoints, how many elements to generate.
- `expecting<R>()` / `expecting<R> { assertion }` — **suspend and eager**: execute the call and return the response statically typed to variant `R`.
- `collecting<R>(count = N) { … }` / `collecting<R>(duration = d) { … }` — batched/streamed consume.

Every identifier you see — `CreatePet`, `GetPet1`, `Response201`, `Response404` — was generated from your controller this build. Rename a method, change a path parameter, drop an `@ApiResponse` annotation: the test won't compile.

## Property-based runs

Wrap calls in kotest-native `checkAll<Int>(iterations = N) { … }` to run a block N times with a seeded `RandomSource`. Unset slots and fields default to `Arb<T>` generators derived from the Wirespec types — random valid payloads for free. Pin only what your test actually cares about.

```kotlin
test("typesafe queries") {
    checkAll<Int>(iterations = 8) {
        repeat(25) {
            CreatePet.call {
                body = { name = Arb.constant("rex"); species = Arb.constant("dog") }
                expecting<CreatePet.Response201>()
            }
        }
        ListPets.call {
            query = { limit = Arb.constant(10); offset = Arb.constant(0) }
            expecting<ListPets.Response200> { resp ->
                resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
            }
        }
    }
}
```

## Channels (Kafka)

The Spring extractor also extracts `@KafkaListener` methods and `kafkaTemplate.send(...)` call sites as Wirespec **channels**, and `KotestDslExtension` generates a `<Channel>.call { }` DSL alongside the endpoint one — both interleave in the same `test { … }`:

```kotlin
test("an HTTP create publishes a matching PetCreatedEvent") {
    val petId = CreatePet.call {
        body = { name = Arb.constant("rex"); species = Arb.constant("dog") }
        expecting<CreatePet.Response201>()
    }.body.id

    PublishPetCreated.call {
        topic("pets.events")
        expecting { it.id shouldBe petId }
    }
}

test("a command sent through the DSL is consumed by the application") {
    val command = OnCreatePetCommand.call {
        topic("pets.commands")
        send { name = Arb.constant("rex"); species = Arb.constant("dog") }
    }

    eventually(5.seconds) {
        GetPet1.call { path = { id = Arb.constant(command.correlationId) }; expecting<GetPet1.Response200>() }
    }
}
```

Channel-call slots:

- `.topic(value)` — the topic to publish to / receive from. The extracted contract does not carry topic names.
- `.key(value)` — optional partition key for producer steps.
- `send()` / `send(Gen)` / `send { field = Arb… }` — test publishes a message (drives an app `@KafkaListener`); returns the sent payload.
- `expecting()` / `expecting { assertion }` — test consumes one message published by the app.
- `collecting(count = N) { … }` / `collecting(duration = d) { … }` — batched consume.

Channel messages move over a `community.flock.wirespec.integration.kotest.ChannelTransport`
(`publish` / `receive` of raw bytes) that you supply — see below. The app's own
`@KafkaListener` / `KafkaTemplate` operate on the same topics independently.

## Wiring the transport

The runtime no longer ships Spring transports. You supply them by implementing
`community.flock.wirespec.integration.kotest.context.ContextProvider` and registering it via
`META-INF/services`. It hands the DSL a `WirespecTestContext(transportation, serialization)`
for endpoints and (optionally) a `WirespecChannelContext(transport, serialization, defaultTopic)`
for channels:

```kotlin
class ScenarioContextProvider : ContextProvider {
    override fun endpointContext(spec: Spec): WirespecTestContext = MyEnvironment.endpointContext
    override fun channelContext(spec: Spec): WirespecChannelContext = MyEnvironment.channelContext
}
```

```
src/test/resources/META-INF/services/community.flock.wirespec.integration.kotest.context.ContextProvider
  → com.example.ScenarioContextProvider
```

The [`example/`](example) module is the canonical, runnable reference — it boots the Spring app
on a random port, supplies an `HttpClientTransportation` (a `Wirespec.Transportation` over real
HTTP) and a `KafkaChannelTransport` (a `ChannelTransport` over an embedded broker), and wires
both into a process-wide `ContextProvider`.

## The value proposition

### Contract drift becomes a compile error

Rename `fun create()` to `fun createPet()` in your controller. The extractor regenerates the contract, the emitter regenerates the DSL, and `CreatePet.call { … }` is suddenly unresolved. CI catches it before the PR opens.

### One source of truth — your code

No hand-maintained OpenAPI yaml, no separate `.ws` files to keep in sync. The controller's `@RestController`, `@RequestMapping`, `@PathVariable`, `@RequestParam`, `@RequestBody`, and `@ApiResponses` annotations are the contract.

### Status-narrowed responses, no casts

`expecting<GetPet1.Response404>()` returns a value statically typed to that variant. The body is `it.body.code`, not `(it.body as? ErrorResponse)?.code ?: error(...)`. `Response201` and `Response404` are different types, so a typo can't compile.

### Auto-validation on every call

Every call validates that the response status matches a status declared by the contract, and that the body deserializes against that status's declared schema. Failures carry `iteration · seed · endpoint · raw response · concrete mismatch` so you can replay them.

### Property-based by default

Wrap calls in `checkAll<Int>(iterations = N) { … }` and they run N times with a seeded `RandomSource` — failures print the seed for free. Unset request slots and fields default to `Arb<T>` generators derived from the Wirespec types. Pin only what your test actually cares about.

## Modules

| Artifact | What's in it |
|---|---|
| Gradle plugin id `community.flock.wirespec.kotest` (`kotest-wirespec-gradle-plugin`) | Wraps the Spring extractor + Wirespec `KotlinIrEmitter` + `KotestDslExtension` codegen pipeline. |
| `community.flock.wirespec.kotest:kotest-wirespec-maven-plugin` | Maven Mojo wrapping the same extractor + codegen pipeline. |

The runtime and DSL emitter live upstream in Wirespec:
`community.flock.wirespec.integration:kotest-jvm` (scenario-DSL runtime + `KotestDslExtension`)
and `community.flock.wirespec.compiler.emitters:kotlin-jvm` (`KotlinIrEmitter`).

## What this replaces

| Without the plugin | With the plugin |
|---|---|
| `MockMvc.perform(post("/pets")…)` with magic strings | `CreatePet.call { body = { … } }` — fully typed |
| `.andExpect(status().isCreated)` | `.expecting<CreatePet.Response201>()` — and the body type is checked |
| `objectMapper.readValue(json, Pet::class.java)` | The matched variant's `body` field is already typed |
| Manually authored OpenAPI spec to keep in sync | Extracted from `@RestController` annotations |
| Property tests that go stale when the controller drifts | Tests that fail to compile when they drift |
| Streaming endpoints tested with ad-hoc loops | `.collecting<Response200>(count = 10) { events -> … }` |

## Status

Pre-release. Tracks Wirespec `0.20.0-RC.2`. Iteration on the DSL and emitter is ongoing — the public API may shift before 1.0.

## License

Apache 2.0 — see [LICENSE](LICENSE).
