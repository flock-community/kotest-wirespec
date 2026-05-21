# kotest-extensions-spring-wirespec

> **Your Spring controllers _are_ the contract. Your tests _know_ the contract. Drift is impossible.**

A Gradle plugin that extracts an API contract from your Spring `@RestController`s, generates a typesafe Kotest DSL from it, and runs property-based scenarios against the live app — refusing to compile the moment your code and your tests stop agreeing.

## What you get for one `plugins { … }` line

```kotlin
plugins {
    id("org.springframework.boot") version "3.4.1"
    kotlin("jvm") version "2.3.0"
    id("io.kotest.extensions.spring.wirespec") version "0.1.0"
}

kotestWirespecSpring {
    basePackage.set("com.example.api")
}
```

That's the whole setup. Each `gradle test` now does:

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
  PetScenariosSpec → live Spring Boot on a random port
```

## What your tests look like

```kotlin
class PetScenariosSpec : SpringScenarioSpec(MyApp::class, {

    scenario("pet CRUD", iterations = 50) {
        val petId = createPet
            .body(CreatePetRequest(name = "Fido", species = "dog"))
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
})
```

Every identifier you see — `createPet`, `getPet`, `Response201`, `Response404`, `CreatePetRequest` — was generated from your controller this build. Rename a method, change a path parameter, drop a `@ApiResponse` annotation: the test won't compile.

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

Each `scenario(iterations = N)` runs N times with a seeded `RandomSource`. Unset slots (`body`, `path`, `query`, `header`) default to `Arb<T>` generators derived from the Wirespec types — you get random valid payloads for free. Pin only what your scenario actually cares about.

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
