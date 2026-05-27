# Scenario DSL ergonomics: `wirespec` catalog + no base class

**Date:** 2026-05-27
**Status:** Approved, implementing

## Problem

Two ergonomic pain points in the generated scenario DSL:

1. **Noisy autocomplete.** Endpoints/channels are generated as top-level extension
   properties on `ScenarioBuilder` (`public val ScenarioBuilder.createPet: CreatePetCall`).
   Inside `scenario { }` (receiver `ScenarioBuilder`), typing a bare prefix like
   `crea` lists the generated `createPet` *plus* every accessible top-level
   declaration — `createTempFile`, `createAssertionError`, etc. A library cannot
   suppress stdlib symbols from bare-prefix completion.

2. **Required base class.** Writing a scenario spec means extending the custom
   `WirespecSpec` base class. Users want to use plain Kotest specs (`FunSpec`,
   `WordSpec`, …) with no wrapper.

## Decisions

- **Single catalog**, named `wirespec`. Endpoints and channels are members of one
  generated holder reached via `ScenarioBuilder.wirespec`. Qualified access
  (`wirespec.`) is the only reliable way to get a completion list containing *only*
  the contract's operations.
- **Remove** the old bare extension properties entirely (generated code — consumers
  regenerate).
- **Remove** the `WirespecSpec` base class entirely. Plain specs become the only path.
- Spring lifecycle is mounted per spec via `@ApplyExtension(SpringRootTestExtension::class)`
  (the documented Kotest mechanism), replacing the `ContextProvider.specExtension()` SPI.
- The scenario context **auto-resolves** from the running spec, with an explicit
  `scenario(ctx) { }` escape hatch for custom transports.

## Target end-state (consumer)

```kotlin
@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class)
class PetScenariosSpec : FunSpec({
    test("pet CRUD") {
        scenario(iterations = 10) {
            val petId = wirespec.createPet.returning<CreatePet.Response201, String> { it.body.id }
            wirespec.getPet.path(petId).expecting<GetPet.Response200>()
        }
    }
}) {
    @Autowired lateinit var applicationContext: ApplicationContext
}
```

Inside `scenario { }`, bare completion offers only `wirespec`, `delay`, `eventually`;
`wirespec.` lists only the endpoints and channels.

## Part 1 — `wirespec` catalog (emitter module)

- `DslFileEmitter` / `ChannelDslFileEmitter`: drop `renderExtensionFunction`. Each
  per-endpoint/channel file now emits only the `*Call` class (+ body/payload builders).
- New `CatalogFileEmitter`: `emit(endpointNames, channelNames, packageName) -> Emitted`
  producing `<pkg>/kotest/WirespecCatalog.kt`:
  ```kotlin
  public val ScenarioBuilder.wirespec: WirespecCatalog
      get() = WirespecCatalog(this)
  @WirespecScenarioDsl
  public class WirespecCatalog internal constructor(private val scenario: ScenarioBuilder) {
      public val createPet: CreatePetCall
          get() = CreatePetCall(scenario)
      // … one `val <dslName>: <Name>Call` per endpoint, then per channel
  }
  ```
  `*Call` classes share the `kotest` package, so no imports for them; their `internal`
  constructors stay reachable within the consumer's generated module. `dslName` =
  first char lowercased (matching the shapes).
- `TypesafeDslEmitter.emit()`: after collecting endpoints/channels across all modules,
  append the one catalog file to `extra` (skip when both lists are empty). `emit()` is
  called once per compilation with the full `Root`, so exactly one catalog is produced —
  no `wirespec` name clash.

### Tests (TDD, golden-based)
- Update the 8 existing per-file goldens: remove the two `val ScenarioBuilder.x` lines.
- New `CatalogFileEmitterTest` + `golden/WirespecCatalog.kt`.
- Extend `TypesafeDslEmitterTest`: assert the emitted files include
  `…/kotest/WirespecCatalog.kt` and that one catalog aggregates multiple endpoints + a channel.

## Part 2 — no base class (core + spring modules)

- **Delete** `core/.../WirespecSpec.kt`.
- Add to `core/.../Scenario.kt` (package `io.kotest.extensions.wirespec`):
  - `suspend fun TestScope.scenario(iterations: Int = 1, block: ScenarioBuilder.() -> Unit)`
    — resolves ctx from `testCase.spec` via `ContextRegistry` (logic lifted from
    `WirespecSpec`); folds `checkAll<Int>(iterations)` in when `iterations > 1`, single
    run otherwise.
  - `suspend fun TestScope.scenario(endpointCtx, channelCtx = null, iterations = 1, block)`
    — explicit-context escape hatch.
  - Keep the existing `PropertyContext.scenario(ctx, …)` and top-level `scenario(ctx, seed, …)`.
- `ContextProvider`: remove `specExtension()`. `SpringContextProvider`: remove its
  `specExtension()` override and the now-unused `SpringRootTestExtension` import.
  `ContextRegistry` stays `internal` (core-only); `endpointContext`/`channelContext` unchanged.
- Auto-resolution still reflects the spec for an `@Autowired ApplicationContext` field
  (unchanged from today; noted as a possible future simplification, out of scope).

### Tests
- Migrate `example` specs (`PetScenariosSpec`, `PetChannelScenariosSpec`) and the maven
  fixture `PetSmokeSpec` to plain `FunSpec` + `@ApplyExtension(SpringRootTestExtension::class)`
  + `scenario(iterations = N) { wirespec.* }`. These are the acceptance tests.
- Migrate `PetScenariosJUnitTest` to `wirespec.*` (it already uses the explicit-ctx path).
- `./gradlew :emitter:test` then `:core:test :spring:test`, finally `:example:test`
  (regenerates the DSL via the composite build and runs the migrated specs end-to-end).

## Docs
- README: replace the `WirespecSpec` sections with the plain-spec + `@ApplyExtension` pattern.

## Out of scope
- `@WirespecSpringTest` meta-annotation bundling `@ApplyExtension` — deferred pending a
  check that Kotest resolves `@ApplyExtension` transitively through meta-annotations.
- Resolving the Spring `ApplicationContext` without an `@Autowired` field on the spec.
