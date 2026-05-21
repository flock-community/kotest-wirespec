# kotest-extensions-spring-wirespec

> Property-based scenario testing for Spring HTTP endpoints, driven by Wirespec contracts.

One Gradle plugin. Extracts a Wirespec contract from your `@RestController`s, compiles it to typed Kotlin (endpoint objects, sealed response families, `Arb<T>` generators), and gives you a scenario-first Kotest DSL that auto-validates status codes and response bodies against the contract on every call.

## Quick start

```kotlin
// build.gradle.kts
plugins {
    id("org.springframework.boot") version "3.4.1"
    kotlin("jvm") version "2.3.0"
    id("io.kotest.extensions.spring.wirespec") version "0.1.0"
}

kotestWirespecSpring {
    basePackage.set("com.example.api.controller")
}
```

```kotlin
// PetScenariosSpec.kt
class PetScenariosSpec : SpringScenarioSpec(MyApplication::class, {

    scenario("pet CRUD", iterations = 50) {
        val petId = endpoint(PetCreate)
            .returning<PetCreate.Response201> { it.body.id }

        endpoint(PetGet).path(petId).expecting<PetGet.Response200>()
        endpoint(PetUpdate).path(petId).expecting<PetUpdate.Response200>()
        endpoint(PetDelete).path(petId).expecting<PetDelete.Response204>()
        endpoint(PetGet).path(petId).expecting<PetGet.Response404>()
    }
})
```

## What the DSL gives you

- **Sensible Arb defaults**: every slot (`body`, `path`, `query`, `header`) defaults to an `Arb<T>` from the Wirespec ↔ Kotest adapter. Only override when you don't want random.
- **Status-narrowed responses**: `.expecting<Response201>()` / `.returning<Response200> { … }` are reified over the matched Wirespec response variant — the lambda receiver is exactly that variant, no casts, no `when` branches.
- **Auto-validation on every call**: status code must match a declared variant; response body must deserialize against that variant's schema. Failures carry scenario name · iteration · seed · endpoint · raw response · concrete mismatch.
- **Streaming**: `.collecting<Response200>(count = 10) { events -> … }` for SSE / NDJSON.

## Status

Pre-release. See [`docs/design.md`](docs/design.md) for the architecture, layout, and roadmap.

## License

Apache 2.0 — see [LICENSE](LICENSE).
