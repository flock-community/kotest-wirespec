# List-Body Runtime Support — Design

## Goal

Make the typed `body { … }` builder work end-to-end for endpoints whose request body is `Iterable<Custom>` (e.g. `addProducts` taking `List<SponsoredProductInLegacy>`). Today the emitter generates the builder, but the runtime crashes when it tries to resolve a generator for the erased `java.util.List` class. Consumers fall back to `body(arb = Arb.constant(listOf(Model(...))))` — a documented workaround that defeats the original "everything to be generators" goal.

## Why this is a runtime gap, not an emitter gap

The emitter side is correct (Tasks 1–3 of `2026-05-29-nested-and-list-body-builders.md`). For a List-bodied endpoint, `DslFileEmitter` emits a typed `body(block: <Element>BodyBuilder.() -> Unit)` overload that registers field overrides at paths like `("*", "field")`. The builder writes its overrides into `bodyOverrides: KotestWirespecGeneratorBuilder.() -> Unit` on `EndpointCallBuilder` and clears `bodyInput`.

At runtime, `ScenarioRunner.resolveSlots` (`core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunner.kt:213-235`) takes the "no `bodyInput`, but `hasBody`" branch:

```kotlin
val bodyType = reflection.requestConstructor.parameters
    .firstOrNull { it.name == "body" }
    ?.type
    ?: error("…: hasBody=true but no `body` constructor param.")
// …
args["body"] = arbReceiver.generatorFor(bodyType).generate(generator, rootPath)
```

`parameter.type` is the erased `java.lang.Class<?>`. For `body: List<Pet>`, this is `java.util.List`. `ArbReceiver.generatorFor` (`core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ArbReceiver.kt:61-81`) builds a generator lookup path `<package>.generator.<SimpleName>Generator` — for `java.util.List` that is `java.util.generator.ListGenerator`, which doesn't exist. The lookup throws.

The fix is to give the runtime enough type information to recover the element class, dispatch list-construction itself, and call `generatorFor(elementClass)` once per element.

## Scope

In scope:
- Runtime support for `List<Custom>` bodies in `ScenarioRunner.resolveSlots`.
- Element type recovery via `Parameter.parameterizedType.actualTypeArguments[0]`, cached on `EndpointReflection`.
- A configurable list size, defaulting to `Arb.int(1..3)`, threaded through `EndpointCallBuilder` and exposed as a `count: IntRange` parameter on the emitter-generated `body(block)` overload for `BodyKind.List` endpoints.
- Tests: a new `ScenarioRunner` test for list bodies; updates to existing emitter golden(s) so the `body(count, block)` overload signature is asserted.
- Consumer cleanup in `gambit-sp-campaign-service`: drop the four `Arb.constant(listOf(...))` workarounds, replace with `body(count = 1..3) { … }`.

Out of scope (called out so reviewers know we considered them):
- `Map<String, Custom>` (`Reference.Dict`) bodies — same pattern would apply, no consumer needs it today.
- The `JvmRefinedWrapper` enum-constructor bug (lives in `community.flock.wirespec.integration.kotest`, not kotest-spring).
- The `WirespecStatusAppValueSerializer` workaround in the consumer — Jackson serialization for the consumer's app-side enums, not a framework concern.
- `List<Primitive>` and `Iterable<Custom>` (non-`List`) — the runtime branch will key off "is the parameter assignable from `java.util.List`?" but the element generator lookup assumes a Wirespec-generated custom type, so primitive list elements continue to need `body(arb)`.

## Components

### 1. `EndpointReflection`

Add a derived field captured at introspection time:

```kotlin
val bodyElementClass: Class<*>?  // non-null when body parameter is List<Custom>
```

`introspect()` reads `requestConstructor.parameters[bodyIdx].parameterizedType`. If it is a `ParameterizedType` with raw type `java.util.List` (use `Class.isAssignableFrom` so subtypes like `java.util.ArrayList` would also work, though the constructor type is invariant `List`) and `actualTypeArguments[0]` is a `Class<*>`, store it. Otherwise null.

### 2. `EndpointCallBuilder`

Add a slot:

```kotlin
internal var bodyListSize: Arb<Int>? = null
```

Set by the emitter-generated `body(count: IntRange = 1..3, block: …)` overload alongside `bodyOverrides`. When null (i.e. user used `body(value)` or `body(arb)` — not the typed builder), the runtime won't reach the list branch anyway because `bodyInput != null`.

### 3. `ScenarioRunner.resolveSlots`

Add a new branch between the existing `call.bodyInput != null` and `reflection.hasBody` branches:

```kotlin
when {
    call.bodyInput != null -> { args["body"] = resolve(call.bodyInput!!) }
    reflection.hasBody && reflection.bodyElementClass != null -> {
        val sizeArb = call.bodyListSize ?: Arb.int(1..3)
        val size = sizeArb.next(rsFor(listOf("#$index", "size")))
        val (generator, rootPath) = call.bodyOverrides?.let { overrides ->
            kotestWirespecKotlinGenerator(seed = randomSource.random.nextLong()) {
                overrides()
            } to emptyList<String>()
        } ?: (arbReceiver.generator to listOf("#$index"))
        val elementGen = arbReceiver.generatorFor(reflection.bodyElementClass!!)
        args["body"] = (0 until size).map { i ->
            elementGen.generate(generator, rootPath + "$i")
        }
    }
    reflection.hasBody -> { /* existing single-Custom-body path */ }
}
```

The `rsFor(listOf("#$index", "size"))` keying matches the existing per-call seed prefixing pattern in the file. The `rootPath + "$i"` matches `KotestWirespecGenerator.generateLeaf`'s array element path convention (`field.generate(path + "$i")` at line 267) so the `("*", "fieldName")` overrides registered by the typed builder will match each element.

### 4. Emitter — `DslFileEmitter`

For `BodyKind.List` endpoints, the existing `body(block: <Element>BodyBuilder.() -> Unit)` overload becomes:

```kotlin
public fun body(count: IntRange = 1..3, block: <Endpoint><Element>BodyBuilder.() -> Unit): <Endpoint>Call = apply {
    val builder = <Endpoint><Element>BodyBuilder().apply(block)
    bodyListSize(io.kotest.property.arbitrary.int(count))  // NEW
    inner.body {
        builder.field?.let { registerPath("*", "field") { it } }
        // …
    }
}
```

…where `bodyListSize` is a new method on the inner `EndpointCallBuilder` that the emitter calls before `inner.body { … }`. Object-bodied endpoints emit the existing `body(block: …)` overload unchanged.

### 5. Consumer cleanup

In `gambit-sp-campaign-service/src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt`, the four `body(arb = Arb.constant(listOf(…)))` calls (at `addProducts`, `addKeywords`, `addOrUpdateCategories`, `upsertPlacements`) become:

```kotlin
wirespec.addProducts.path(campaignId = campaignId)
    .body(count = 1..3) {
        webShopId = Arb.constant("webshop_1")
        nasaNumber = Arb.long(0L..99999L)
        status = Arb.constant("active")
    }
    .expecting<AddProducts.Response200> { }
```

Remove the inline "BLOCKED on framework" comments.

## Default size

`Arb.int(1..3)`. Three considerations:
- **At least 1**: contract validation typically rejects empty lists (the consumer's app has `@NotEmpty` on products/keywords); a default that includes 0 would generate flaky test failures.
- **At most 3**: contract tests are integration-style and slow; large lists multiply the number of validation calls per test without adding semantic coverage.
- **Random within the range**: with kotest's seeded RandomSource, sizes are deterministic per (seed, call index), so failures are reproducible.

A test that needs a specific size writes `body(count = 5..5) { … }`.

## Testing

**Runtime — new test in `core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunnerListBodyTest.kt`:**

- Build a tiny hand-rolled `Wirespec.Endpoint` with a `List<Pet>` body and a stub transportation.
- Run `scenario(ctx, iterations = 1) { wirespec.<endpoint>.body { name = Arb.constant("fixed") } }`.
- Assert: the transport saw a request body that's a JSON array of 1..3 elements, each with `name = "fixed"` and other fields framework-randomized.
- Second test case: pass `body(count = 5..5) { … }` and assert exactly 5 elements.

**Emitter — update golden `emitter/src/test/resources/golden/PetCreateBulkDsl.kt`:**

The `public fun body(block: PetCreateBulkPetBodyBuilder.() -> Unit)` line gets a `count: IntRange = 1..3` parameter; the body adds a `bodyListSize(Arb.int(count))` call. Existing `EndpointShape` and behavior tests stay green.

**Consumer — `CampaignContractTest`:** all 8 scenarios continue to pass after the four call sites are switched.

## Risk + rollout

The change is additive in three places (`EndpointReflection.bodyElementClass`, `EndpointCallBuilder.bodyListSize`, `ScenarioRunner` new branch) plus an emitter signature tweak. Object-bodied endpoints are untouched in the runtime; the new branch only fires when `bodyElementClass != null`, which only happens for `List<Custom>` bodies. Existing `body(arb = Arb.constant(listOf(…)))` calls still work because `bodyInput != null` short-circuits the new branch.

Rollout:
1. Land runtime + emitter changes in kotest-spring.
2. Republish `0.0.0-SNAPSHOT`.
3. Regenerate DSL in gambit-sp-campaign-service.
4. Refactor the four call sites + remove the workaround comments.
5. Run `mvn test -Dtest=CampaignContractTest` — expect 8/8 green.
