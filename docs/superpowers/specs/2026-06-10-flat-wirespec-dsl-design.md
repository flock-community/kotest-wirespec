# Flat (wrapper-free) wirespec DSL

Date: 2026-06-10
Status: Approved — ready for implementation planning

## Problem

The current test DSL requires wrapping every test body in a `scenario { … }` block:

```kotlin
test("pet CRUD") {
    scenario(iterations = 10) {
        val petId = wirespec.createPet.returning<CreatePet.Response201, String> { it.body.id }
        wirespec.getPet1.path(petId).expecting<GetPet1.Response200>()
        // …
    }
}
```

Two things are unsatisfying:

1. **The `scenario { }` wrapper is ceremony.** It exists only so that `wirespec` can be an
   extension property on a `ScenarioBuilder` receiver and so the builder can collect steps and
   loop over them. Tests would read better as plain, eager calls directly inside `test { }`.
2. **Property-based iteration is bespoke** (`scenario(iterations = N)`) instead of using kotest's
   own `checkAll`.

The desired shape:

```kotlin
test("pet CRUD") {
    val petId = PetControllerV1.createPet
        .returning<CreatePet.Response201, String> { it.body.id }

    PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()

    PetControllerV1.updatePet
        .path(petId)
        .body { name = Arb.constant("new name") }
        .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

    PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200> { it.body.name shouldBe "new name" }
    PetControllerV1.deletePet.path(id = petId).expecting<DeletePet.Response204>()
    PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response404>()
}
```

Plus optional property-based runs using kotest's native `checkAll`.

## Goals

- Drop the `scenario { }` wrapper entirely. Tests call the catalog directly inside `test { }`.
- Each terminal call executes eagerly (suspends, fires the request, asserts, returns its result).
- Catalogs are grouped **per source controller** (`PetControllerV1`, `PetControllerV2`,
  `PetEventPublisher`, …), reached as bare top-level objects.
- Property-based runs use kotest's native `checkAll`, with an opt-in bridge for seed-accurate
  reproduction of wirespec-generated data.
- Replace `scenario` completely — no back-compat shim. Migrate all example specs.

## Non-goals

- Changing the wirespec spec/IR pipeline or the external `wirespec-spring-extractor` plugin.
- Changing how the underlying typed `Wirespec.Client` / transport / serialization works.
- Cross-language (multiplatform) concerns beyond the existing JVM target.

## Decisions (from brainstorming)

| Question | Decision |
| --- | --- |
| Scope vs. `scenario { }` | **Replace entirely.** Remove `scenario`, migrate all examples. |
| Catalog grouping | **Per-controller catalogs**, one top-level object per `.ws` module. |
| Catalog naming | The `.ws` filename verbatim (= controller simple name), e.g. `PetControllerV1`. |
| Channels | Grouped the same way (e.g. `PetEventPublisher.ws` → `object PetEventPublisher`). |
| Property runs | **Both:** ambient RNG by default, plus opt-in `useWirespecSeed()` bridge. |
| Ambient context wiring | **Explicit** `@ApplyExtension(WirespecExtension::class)` per spec (+ `withWirespec` for JUnit). |

## Execution model: lazy-collect → eager

### Current (lazy)

`scenario { }` constructs a `ScenarioBuilder`, runs the user block once to **accumulate** `Step`s
(each endpoint/channel call registers itself), then `ScenarioRunner` resolves slots and executes
the steps — looping over `iterations` via `checkAll<Int>`. `returning` hands back a `ResultRef<T>`
(a lazy placeholder) because nothing has executed yet; `.path(ref)` reads it later.

### New (eager)

There is no builder receiver and no step list. The catalog object's endpoint property returns a
fresh call builder. Non-terminal methods (`.path`, `.body`, `.query`, `.header`, `.topic`, `.key`,
`.send`) just stash slot inputs and return the builder. The **terminals** execute immediately:

- `suspend fun expecting<R>()` / `expecting<R> { … }` — resolves slots against the ambient
  `RandomSource`, builds the request, calls the typed client through the ambient context, asserts
  status (and runs the optional block), and **returns the response variant `R`**.
- `suspend fun returning<R, T> { projection }` — same, but returns the projected `T` (the actual
  value, not a `ResultRef`).
- Channel terminals (`expecting`, `collecting`, `returning`) behave analogously.

Because execution is eager and synchronous-by-return:

- `val petId = …returning { it.body.id }` yields a real `String`.
- `.path(petId)` / `.path(id = petId)` take **raw values** — the generated typed path params.
- **`ResultRef` is removed.** Capture-by-return replaces lazy refs.

A call that is built but never terminated does nothing (it is just a discarded builder). This is the
intended trade-off for an eager model; the previous auto-register-on-create behaviour is gone.

### Removed conveniences

- The builder's custom `delay(…)` and `eventually(…)` are removed. Users use kotlinx
  `delay(…)` and kotest `eventually { … }` (which now work because flat calls are eager).

## Ambient context

A `CoroutineContext.Element` carries everything a terminal call needs:

```
WirespecAmbient(
    spec: Any?,                       // for lazy provider resolution (kotest path)
    endpointCtxOverride: WirespecTestContext?,   // for explicit/JUnit path
    channelCtxOverride: WirespecChannelContext?,
    rng: RandomSourceHolder,          // mutable holder so the seed bridge can rebind it
)
```

Resolution is **lazy and cached on first use**: a terminal call reads
`coroutineContext[WirespecAmbient]`, and if the endpoint/channel context is not already an explicit
override it resolves via the existing `ContextRegistry.providers` using `spec`. Lazy resolution
avoids any extension-ordering dependency with `SpringRootTestExtension`. If no ambient element is
present, the terminal throws a clear error pointing the user at `@ApplyExtension(WirespecExtension::class)`
or `withWirespec(ctx) { … }`.

### Two entry points install the element

1. **kotest** — `WirespecExtension : TestCaseExtension`, mounted via
   `@ApplyExtension(WirespecExtension::class)`. Its `intercept` wraps execution:

   ```kotlin
   override suspend fun intercept(testCase, execute): TestResult {
       val ambient = WirespecAmbient(spec = testCase.spec, rng = RandomSourceHolder(seed = System.nanoTime()))
       return withContext(ambient) { execute(testCase) }
           // on failure, surface ambient.rng.seed so the run is reproducible
   }
   ```

2. **JUnit / explicit context** — `withWirespec(endpointCtx, channelCtx? = null) { … }`, a suspend
   block that installs a `WirespecAmbient` with explicit overrides for its body. This is the
   replacement for `scenario(ctx) { }` in the JUnit twin.

The catalog objects themselves are **stateless namespaces** — they hold no context. Each endpoint
property returns a `*Call` builder; the builder reads the ambient element only when a terminal runs.

## Property-based testing

### Default — ambient RNG

The per-test `RandomSource` (seeded once per test via the holder) advances on every generated value.
Wrapping flat calls in kotest's `checkAll(n) { … }` simply re-runs the block `n` times, each draw
producing fresh data automatically. On failure the wirespec layer surfaces its own seed for repro.

```kotlin
checkAll<Int>(iterations = 10) {
    repeat(25) { PetControllerV1.createPet.expecting<CreatePet.Response201>() }
    PetControllerV1.listPets.query(limit = 10, offset = 0)
        .expecting<ListPets.Response200> { it.body.content.size shouldBe it.body.total.coerceAtMost(10) }
}
```

### Opt-in — seed bridge

`useWirespecSeed()` is a `PropertyContext` extension. Called at the top of a `checkAll` block, it
rebinds the ambient `RandomSourceHolder` to that iteration's `randomSource()`, so kotest's reported
seed reproduces wirespec-generated bodies as well:

```kotlin
checkAll<Int>(iterations = 100) {
    useWirespecSeed()                                  // opt-in; omit for ambient-only
    PetControllerV1.createPet.body { name = Arb.string() }.expecting<CreatePet.Response201>()
}
```

## Emitter & generated code

### Per-controller catalogs (grouping by module)

The Spring extractor already writes **one `.ws` file per controller**, and that boundary survives in
the AST as a `Module` with a `FileUri`. `TypesafeDslEmitter.emit(ast, …)` currently flattens
`ast.modules` into a single statement list (discarding the grouping). The new behaviour:

1. Group statements by their source `Module` instead of flattening:
   `module.fileUri → (endpoints, channels)`.
2. Derive the catalog name from the filename: `PetControllerV1.ws` → `PetControllerV1`.
3. Emit **one catalog object per module that contains at least one endpoint or channel.**
   `types.ws` (types only) produces no catalog.

`*Dsl.kt` (per-endpoint) and `*ChannelDsl.kt` (per-channel) files are still emitted per
endpoint/channel, unchanged in count — only their construction changes (no `ScenarioBuilder`).

### Catalog shape (generated)

```kotlin
// PetControllerV1.ws →
public object PetControllerV1 {
    public val createPet: CreatePetCall get() = CreatePetCall()
    public val getPet1: GetPet1Call get() = GetPet1Call()
    public val updatePet: UpdatePetCall get() = UpdatePetCall()
    public val deletePet: DeletePetCall get() = DeletePetCall()
    public val listPets: ListPetsCall get() = ListPetsCall()
    public val createPetsBulk: CreatePetsBulkCall get() = CreatePetsBulkCall()
}

// PetControllerV2.ws →
public object PetControllerV2 {
    public val getPet2: GetPet2Call get() = GetPet2Call()
}

// PetEventPublisher.ws → object PetEventPublisher { … channel calls … }
```

### Call wrapper shape (generated)

`*Call` wrappers are constructed with **no `ScenarioBuilder`**; their terminals are `suspend` and
delegate to the core single-call executor (which reads the ambient context):

```kotlin
@WirespecScenarioDsl
public class CreatePetCall internal constructor() {
    @PublishedApi internal val inner = EndpointCall(CreatePet.Handler, CreatePet)

    public fun body(value: CreatePetRequest): CreatePetCall = apply { inner.body(value) }
    public fun body(arb: Arb<CreatePetRequest>): CreatePetCall = apply { inner.body(arb) }
    public fun body(block: CreatePetCreatePetRequestBodyBuilder.() -> Unit): CreatePetCall = apply { /* … */ }

    public suspend inline fun <reified R : CreatePet.Response<*>> expecting(): R = inner.expecting<R>()
    public suspend inline fun <reified R : CreatePet.Response<*>> expecting(noinline block: (R) -> Unit): R = inner.expecting(block)
    public suspend inline fun <reified R : CreatePet.Response<*>, T> returning(noinline projection: (R) -> T): T = inner.returning(projection)
}
```

Path/query/header setters take raw typed values (e.g. `path(id: String)`), since `ResultRef` is gone.

Emitters touched: `TypesafeDslEmitter` (grouping + per-module catalog calls), `CatalogFileEmitter`
(gains a `catalogName` param, emits a top-level `object`, dropped the `ScenarioBuilder` extension
property), `DslFileEmitter` and `ChannelDslFileEmitter` (drop `ScenarioBuilder` param, suspend
terminals, raw-value path/query/header setters, return response/projection).

## Core library changes

**Remove:**
- `Scenario.kt` — all `scenario(…)` overloads.
- `ScenarioBuilder` and `@WirespecScenarioDsl`-scoped step collection on it.
- `ResultRef` and its `.path(ref)` integration.
- `Step` / `register` / the run-loop in `ScenarioRunner`.
- Custom `delay` / `eventually` on the builder.

**Add:**
- `WirespecAmbient` (coroutine-context element) + `RandomSourceHolder`.
- `WirespecExtension : TestCaseExtension`.
- `withWirespec(endpointCtx, channelCtx?) { … }` suspend entry point.
- `PropertyContext.useWirespecSeed()` bridge.
- A single-call executor (core types `EndpointCall` / `ChannelCall`, or a renamed
  `EndpointCallBuilder` / `ChannelCallBuilder`) that reuses today's slot-resolution, request
  construction, transport, and validation logic from `ScenarioRunner.runOne`, but for one call and
  reading the ambient context instead of an injected one.

**Change:**
- `EndpointCallBuilder` / `ChannelCallBuilder` terminals become `suspend`, execute immediately
  against the ambient context, and return the response/projection. Non-terminal setters stay sync.

## Migration

- `PetScenariosSpec` (kotest) — drop `scenario`, add `@ApplyExtension(WirespecExtension::class)`,
  use `checkAll` where iterations were used, calls via `PetControllerV1` / `PetControllerV2`.
- `PetChannelScenariosSpec` (kotest) — same, channels via `PetEventPublisher`.
- `PetScenariosJUnitTest` (JUnit) — replace `scenario(ctx) { … }` with `withWirespec(ctx) { … }`
  inside `runBlocking { checkAll { … } }`.

## Testing

- Core unit tests: single-call executor (slot resolution, status assertion, projection);
  `WirespecAmbient` resolution (lazy provider vs. explicit override; missing-element error);
  `WirespecExtension` install + seed-on-failure; `useWirespecSeed()` rebinds the holder.
- Emitter tests: per-module grouping → one `object` per controller with the right name and the
  right endpoint/channel members; `types.ws` yields no catalog; generated terminals are `suspend`
  and take raw path/query/header values.
- Integration: the example module's migrated specs (kotest + JUnit) are the end-to-end check.

## Risks / open questions

- **`withContext` element propagation in kotest.** `WirespecExtension.intercept` must install the
  element so the test body and any nested `checkAll` inherit it. Verified mechanism: wrap
  `execute(testCase)` in `withContext(ambient)`. If a spec hops threads/dispatchers mid-test the
  coroutine-context element still travels (unlike a `ThreadLocal`), which is why the element approach
  is used.
- **Lazy context resolution timing.** Resolving on first terminal call (not in `intercept`) sidesteps
  ordering against `SpringRootTestExtension`; the spec's `@Autowired ApplicationContext` is ready by
  the time the first call runs.
- **Name collisions across controllers.** Endpoint operation names are already globally unique in the
  current flat catalog (e.g. `GetPet1` vs `GetPet2`), so per-controller grouping does not introduce
  new collisions. Catalog object names equal controller simple names, assumed unique per spec.
