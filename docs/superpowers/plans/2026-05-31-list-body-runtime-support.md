# List-Body Runtime Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the typed `body { … }` builder work for endpoints whose body is `List<Custom>`. Users gain `body(count = 1..3) { field = Arb… }` instead of falling back to `body(arb = Arb.constant(listOf(Model(...))))`.

**Architecture:** Three localized changes in `kotest-spring`. `EndpointReflection` captures the body parameter's `parameterizedType` so we can recover the erased element class. `EndpointCallBuilder` gains a `bodyListSize: Arb<Int>?` slot set by the emitter-generated `body(count, block)` overload. `ScenarioRunner.resolveSlots` adds a new branch — when `bodyElementClass != null`, draw a size, then call `arbReceiver.generatorFor(elementClass).generate(generator, rootPath + "$i")` per index and assemble a `List<Any>`. The emitter adds a `count: IntRange = 1..3` parameter to the existing `body(block)` overload but only for `BodyKind.List` endpoints.

**Tech Stack:** Kotlin / kotest-property / `community.flock.wirespec.integration.kotest`. Spec at `/Users/wilmveel/Projects/kotest-spring/docs/superpowers/specs/2026-05-31-list-body-runtime-support-design.md`.

---

## File Structure

**Modified:**
- `core/src/main/kotlin/io/kotest/extensions/wirespec/validation/EndpointReflection.kt` — capture `bodyElementClass: Class<*>?`.
- `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/EndpointCallBuilder.kt` — add `bodyListSize: Arb<Int>?` slot + internal setter.
- `core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunner.kt` — list-body branch in `resolveSlots`.
- `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt` — `count: IntRange = 1..3` parameter on `body(block)` for `BodyKind.List`.
- `emitter/src/test/resources/golden/PetCreateBulkDsl.kt` — golden updated to match new signature.
- `example/src/main/kotlin/io/kotest/extensions/wirespec/example/controller/PetController.kt` — new `POST /api/pets/bulk` endpoint accepting `List<CreatePetRequest>`.
- `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt` — new scenario(s) driving the bulk-create endpoint through the typed `body { … }` builder.
- `/Users/wilmveel/Projects/gambit/gambit-sp-campaign-service/src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt` — replace four `body(arb = Arb.constant(listOf(...)))` calls with `body(count = 1..3) { … }`.

**New (none).**

---

## Task 1: EndpointReflection — capture body element class

**Files:**
- Modify: `core/src/main/kotlin/io/kotest/extensions/wirespec/validation/EndpointReflection.kt`

`requestConstructor.parameters[i].type` is erased (`java.util.List` for a `List<X>` body). We need the element type — available via `parameters[i].parameterizedType` (returns `java.lang.reflect.ParameterizedType`).

- [ ] **Step 1.1: Add a unit test that exercises the new field via a hand-built endpoint**

The existing test fixtures in `core/src/test/kotlin/` don't drive `EndpointReflection.of` directly with a list-bodied endpoint. Build the test inline against a minimal `Wirespec.Endpoint` whose request has `body: List<String>` (we use `String` here because it's accessible without any Wirespec-generated model class — the test only verifies `bodyElementClass` extraction, not generator lookup):

Create `core/src/test/kotlin/io/kotest/extensions/wirespec/validation/EndpointReflectionTest.kt`:

```kotlin
package io.kotest.extensions.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private object TestEndpoint : Wirespec.Endpoint {
    object Handler

    data class Path(val unused: Unit = Unit) : Wirespec.Path
    data class Queries(val unused: Unit = Unit) : Wirespec.Queries
    data class RequestHeaders(val unused: Unit = Unit) : Wirespec.Request.Headers

    class Request(val body: List<String>) : Wirespec.Request<List<String>> {
        override val path: Wirespec.Path = Path()
        override val method: Wirespec.Method = Wirespec.Method.POST
        override val queries: Wirespec.Queries = Queries()
        override val headers: Wirespec.Request.Headers = RequestHeaders()
        override val content: Wirespec.Content<List<String>>? = null
    }

    abstract class Response<T>(override val status: Int) : Wirespec.Response<T> {
        override val headers: Wirespec.Response.Headers = object : Wirespec.Response.Headers {}
        override val content: Wirespec.Content<T>? = null
    }
    class Response201 : Response<Unit>(201) { override val body: Unit = Unit }

    @JvmStatic fun fromResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Response<*> = Response201()
}

private object NoBodyEndpoint : Wirespec.Endpoint {
    object Handler
    data class Path(val unused: Unit = Unit) : Wirespec.Path
    data class Queries(val unused: Unit = Unit) : Wirespec.Queries
    data class RequestHeaders(val unused: Unit = Unit) : Wirespec.Request.Headers
    class Request() : Wirespec.Request<Unit> {
        override val path: Wirespec.Path = Path()
        override val method: Wirespec.Method = Wirespec.Method.GET
        override val queries: Wirespec.Queries = Queries()
        override val headers: Wirespec.Request.Headers = RequestHeaders()
        override val content: Wirespec.Content<Unit>? = null
    }
    abstract class Response<T>(override val status: Int) : Wirespec.Response<T> {
        override val headers: Wirespec.Response.Headers = object : Wirespec.Response.Headers {}
        override val content: Wirespec.Content<T>? = null
    }
    class Response200 : Response<Unit>(200) { override val body: Unit = Unit }
    @JvmStatic fun fromResponse(serialization: Wirespec.Serialization, response: Wirespec.RawResponse): Response<*> = Response200()
}

class EndpointReflectionTest : FunSpec({

    test("List<String> body parameter — bodyElementClass is String") {
        val reflection = EndpointReflection.of(TestEndpoint)
        reflection.bodyElementClass shouldBe String::class.java
    }

    test("no body — bodyElementClass is null") {
        val reflection = EndpointReflection.of(NoBodyEndpoint)
        reflection.bodyElementClass.shouldBeNull()
    }
})
```

- [ ] **Step 1.2: Run the test, expect compile failure**

```
cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :core:test --tests "io.kotest.extensions.wirespec.validation.EndpointReflectionTest" -i
```

Expected: COMPILE FAIL with `Unresolved reference 'bodyElementClass'`.

- [ ] **Step 1.3: Add the field + extraction logic**

In `EndpointReflection.kt`:

(a) Add the field to the constructor:

```kotlin
@PublishedApi
internal class EndpointReflection private constructor(
    val endpointName: String,
    val pathClass: Class<*>,
    val queriesClass: Class<*>,
    val headersClass: Class<*>,
    val responseVariantsByStatus: Map<Int, Class<*>>,
    private val fromResponseMethod: Method,
    private val instance: Any,
    val requestConstructor: Constructor<*>,
    val requestConstructorParamNames: List<String>,
    val pathFieldNames: List<String>,
    val queriesFieldNames: List<String>,
    val headersFieldNames: List<String>,
    val hasBody: Boolean,
    val bodyElementClass: Class<*>?,
) {
```

(b) In the companion's `introspect()`, replace the existing `val hasBody = "body" in paramNames` line with:

```kotlin
val hasBody = "body" in paramNames

val bodyElementClass: Class<*>? = if (hasBody) {
    val bodyParam = requestConstructor.parameters.first { it.name == "body" }
    val erased = bodyParam.type
    if (java.util.List::class.java.isAssignableFrom(erased)) {
        val parameterized = bodyParam.parameterizedType as? java.lang.reflect.ParameterizedType
        parameterized?.actualTypeArguments?.firstOrNull() as? Class<*>
    } else null
} else null
```

(c) Pass it into the constructor:

```kotlin
return EndpointReflection(
    endpointName = cls.simpleName ?: cls.java.name,
    pathClass = pathClass,
    queriesClass = queriesClass,
    headersClass = headersClass,
    responseVariantsByStatus = variants,
    fromResponseMethod = fromResponseMethod,
    instance = instance,
    requestConstructor = requestConstructor,
    requestConstructorParamNames = paramNames.filterNotNull(),
    pathFieldNames = pathFieldNames,
    queriesFieldNames = queriesFieldNames,
    headersFieldNames = headersFieldNames,
    hasBody = hasBody,
    bodyElementClass = bodyElementClass,
)
```

- [ ] **Step 1.4: Run the test, expect PASS**

```
./gradlew :core:test --tests "io.kotest.extensions.wirespec.validation.EndpointReflectionTest" -i
```

Expected: 2/2 pass.

- [ ] **Step 1.5: Run the full core test suite**

```
./gradlew :core:test -i
```

Expected: all previously-passing tests still pass — the new field is additive.

- [ ] **Step 1.6: Commit**

```
git -C /Users/wilmveel/Projects/kotest-spring add core/src/main/kotlin/io/kotest/extensions/wirespec/validation/EndpointReflection.kt core/src/test/kotlin/io/kotest/extensions/wirespec/validation/EndpointReflectionTest.kt
git -C /Users/wilmveel/Projects/kotest-spring commit -m "$(cat <<'EOF'
feat(core): capture body element class on EndpointReflection

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: EndpointCallBuilder — list-size slot

**Files:**
- Modify: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/EndpointCallBuilder.kt`

A new slot to capture the per-call list-size Arb. Used by Task 3's runtime branch, set by Task 4's emitter overload.

- [ ] **Step 2.1: Add the slot + setter (no test yet — the slot is exercised by Tasks 3-5)**

In `EndpointCallBuilder.kt`, after the existing `internal var bodyOverrides: …` line (around line 25), add:

```kotlin
internal var bodyListSize: Arb<Int>? = null
```

In the same class, add a public method (the emitter calls it). Place it next to the `body(...)` overloads near line 50:

```kotlin
fun bodyListSize(size: Arb<Int>): EndpointCallBuilder<BodyT, Req, Resp> = apply {
    bodyListSize = size
}
```

(No `body(...)`-style clearing: this is a separate slot that the typed builder overload sets *before* `inner.body { … }`.)

- [ ] **Step 2.2: Verify compile**

```
./gradlew :core:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.3: Commit**

```
git -C /Users/wilmveel/Projects/kotest-spring add core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/EndpointCallBuilder.kt
git -C /Users/wilmveel/Projects/kotest-spring commit -m "$(cat <<'EOF'
feat(core): add bodyListSize slot to EndpointCallBuilder

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: ScenarioRunner — list-body resolution branch

**Files:**
- Modify: `core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunner.kt`

Adds the branch that builds a `List<Any>` body by drawing a size and calling the per-element generator. The element generator lookup is the existing `arbReceiver.generatorFor(elementClass)` — works because `elementClass` is now a proper Wirespec-generated model class, not `java.util.List`.

- [ ] **Step 3.1: Add the new branch in `resolveSlots`**

Replace the existing `when { … }` block (lines 214-235) with:

```kotlin
when {
    call.bodyInput != null -> {
        args["body"] = resolve(call.bodyInput!!)
    }
    reflection.hasBody && reflection.bodyElementClass != null -> {
        val (generator, rootPath) = call.bodyOverrides?.let { overrides ->
            kotestWirespecKotlinGenerator(seed = randomSource.random.nextLong()) {
                overrides()
            } to emptyList<String>()
        } ?: (arbReceiver.generator to listOf("#$index"))
        val sizeArb = call.bodyListSize ?: io.kotest.property.arbitrary.int(1..3)
        val size = sizeArb.next(io.kotest.property.RandomSource.seeded(
            randomSource.random.nextLong() xor ("#$index/size".hashCode().toLong())
        ))
        val elementGen = arbReceiver.generatorFor(reflection.bodyElementClass!!)
        args["body"] = (0 until size).map { i ->
            elementGen.generate(generator, rootPath + "$i")
        }
    }
    reflection.hasBody -> {
        val bodyType = reflection.requestConstructor.parameters
            .firstOrNull { it.name == "body" }
            ?.type
            ?: error("${reflection.endpointName}: hasBody=true but no `body` constructor param.")
        // Default Arb generation reuses the iteration-scoped shared generator, whose path-keyed seeding
        // would collapse repeated same-endpoint calls onto identical bodies. Prefix the call index so each
        // call gets a distinct root path and therefore a distinct seed per field. The override branch
        // already varies (fresh per-call generator) and prepending here would silently break
        // user-registered single-segment path overrides (matching is exact-length).
        val (generator, rootPath) = call.bodyOverrides?.let { overrides ->
            kotestWirespecKotlinGenerator(seed = randomSource.random.nextLong()) {
                overrides()
            } to emptyList<String>()
        } ?: (arbReceiver.generator to listOf("#$index"))
        args["body"] = arbReceiver.generatorFor(bodyType).generate(generator, rootPath)
    }
}
```

Add imports at the top of the file:

```kotlin
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
```

(The `next(RandomSource)` extension comes from `io.kotest.property.arbitrary.next`.)

- [ ] **Step 3.2: Compile**

```
./gradlew :core:compileKotlin
```

Expected: BUILD SUCCESSFUL. Existing tests (next step) verify no regression.

- [ ] **Step 3.3: Run all core tests**

```
./gradlew :core:test -i
```

Expected: all previously-passing tests still pass. The new branch only fires when `bodyElementClass != null`, which is only true for `List<Custom>` body endpoints — none currently exist in `:core` tests.

- [ ] **Step 3.4: Commit**

```
git -C /Users/wilmveel/Projects/kotest-spring add core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunner.kt
git -C /Users/wilmveel/Projects/kotest-spring commit -m "$(cat <<'EOF'
feat(core): list-body resolution branch in ScenarioRunner

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Emitter — `body(count, block)` overload for List bodies

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt`
- Modify: `emitter/src/test/resources/golden/PetCreateBulkDsl.kt`
- Modify: `emitter/src/test/resources/golden/PetCreateNestedDsl.kt` (object-bodied — must remain unchanged; this step is a guard)
- Modify: `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitterTest.kt` (the existing `shouldContain "registerPath(\"*\", \"name\")"` assertion remains; add one more assertion for the new `count: IntRange = 1..3` parameter)

The emitter only changes the body-builder overload, and only for `BodyKind.List`. Object-bodied endpoints emit the existing signature unchanged.

- [ ] **Step 4.1: Update the failing-test guard in `DslFileEmitterTest.kt`**

In the existing `"body-only endpoint with Iterable<Custom> body (PetCreateBulk) — body{} emits with wildcard prefix"` test, add one new assertion after the existing `emitted.result shouldContain "registerPath(\"*\", \"name\")"`:

```kotlin
        emitted.result shouldContain "body(count: IntRange = 1..3"
```

- [ ] **Step 4.2: Update the golden `PetCreateBulkDsl.kt`**

Open `emitter/src/test/resources/golden/PetCreateBulkDsl.kt`. The current relevant block is:

```kotlin
    public fun body(block: PetCreateBulkPetBodyBuilder.() -> Unit): PetCreateBulkCall = apply {
        val builder = PetCreateBulkPetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("*", "name") { it } }
        }
    }
```

Replace with:

```kotlin
    public fun body(count: IntRange = 1..3, block: PetCreateBulkPetBodyBuilder.() -> Unit): PetCreateBulkCall = apply {
        val builder = PetCreateBulkPetBodyBuilder().apply(block)
        inner.bodyListSize(io.kotest.property.arbitrary.int(count))
        inner.body {
            builder.name?.let { registerPath("*", "name") { it } }
        }
    }
```

The `import io.kotest.property.arbitrary.int` import is referenced fully-qualified so the existing import list in the file doesn't need to grow (matches the existing `kotlin.time.Duration` style — keep imports minimal).

- [ ] **Step 4.3: Run the test, expect FAIL on golden mismatch**

```
./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.DslFileEmitterTest" -i
```

Expected: FAIL on the `PetCreateBulk` test (golden text differs from emitter output, and the new `shouldContain "body(count: IntRange = 1..3"` assertion fails).

- [ ] **Step 4.4: Modify the emitter to produce the new signature**

In `DslFileEmitter.kt`, find `renderBodySlot`. The existing block that emits the typed body builder is roughly:

```kotlin
if (shape.bodyFieldShapes.isNotEmpty()) {
    val element = shape.bodyElementType ?: error("bodyFieldShapes present but no bodyElementType")
    val builderName = "${shape.name}${element}BodyBuilder"
    val rootPrefix = if (shape.bodyKind == EndpointShape.BodyKind.List) listOf("\"*\"") else emptyList()
    appendLine("    public fun body(block: $builderName.() -> Unit): $call = apply {")
    appendLine("        val builder = $builderName().apply(block)")
    appendLine("        inner.body {")
    renderFieldRegistrations(this, "builder", shape.bodyFieldShapes, rootPrefix, indent = "            ", builderPrefix = shape.name)
    appendLine("        }")
    appendLine("    }")
}
```

Change to conditionally emit a `count: IntRange = 1..3` parameter and a `bodyListSize(...)` call when `bodyKind == List`:

```kotlin
if (shape.bodyFieldShapes.isNotEmpty()) {
    val element = shape.bodyElementType ?: error("bodyFieldShapes present but no bodyElementType")
    val builderName = "${shape.name}${element}BodyBuilder"
    val rootPrefix = if (shape.bodyKind == EndpointShape.BodyKind.List) listOf("\"*\"") else emptyList()
    val isList = shape.bodyKind == EndpointShape.BodyKind.List
    val signature = if (isList) {
        "body(count: IntRange = 1..3, block: $builderName.() -> Unit)"
    } else {
        "body(block: $builderName.() -> Unit)"
    }
    appendLine("    public fun $signature: $call = apply {")
    appendLine("        val builder = $builderName().apply(block)")
    if (isList) {
        appendLine("        inner.bodyListSize(io.kotest.property.arbitrary.int(count))")
    }
    appendLine("        inner.body {")
    renderFieldRegistrations(this, "builder", shape.bodyFieldShapes, rootPrefix, indent = "            ", builderPrefix = shape.name)
    appendLine("        }")
    appendLine("    }")
}
```

Also: ensure `IntRange` is reachable in the generated file. `IntRange` is in `kotlin` (auto-imported), so no new import needed in the emitted file.

- [ ] **Step 4.5: Run all emitter tests**

```
./gradlew :emitter:test -i
```

Expected: PASS — the new `PetCreateBulkDsl` golden matches, the `shouldContain "body(count: IntRange = 1..3"` assertion passes, and existing object-bodied goldens (`PetCreateDsl`, `PetUpdateDsl`, `PetCreateNestedDsl`) are unchanged.

- [ ] **Step 4.6: Commit**

```
git -C /Users/wilmveel/Projects/kotest-spring add emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt emitter/src/test/resources/golden/PetCreateBulkDsl.kt emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitterTest.kt
git -C /Users/wilmveel/Projects/kotest-spring commit -m "$(cat <<'EOF'
feat(emitter): body(count, block) overload for list-bodied endpoints

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Example — bulk-create endpoint + scenario test

**Files:**
- Modify: `example/src/main/kotlin/io/kotest/extensions/wirespec/example/controller/PetController.kt`
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt`

Add a real list-bodied endpoint to the example so we get end-to-end coverage (controller → Wirespec extractor → emitter → ScenarioRunner → real HTTP via MockMvc).

- [ ] **Step 5.1: Add `POST /api/pets/bulk` to `PetController`**

Append the new endpoint after `listPets`:

```kotlin
@PostMapping("/bulk")
@ApiResponses(
    ApiResponse(responseCode = "201", content = [Content(schema = Schema(implementation = PetPage::class))]),
    ApiResponse(responseCode = "400", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
)
suspend fun createPetsBulk(@RequestBody requests: List<CreatePetRequest>): ResponseEntity<Any> {
    if (requests.isEmpty()) {
        return ResponseEntity.badRequest().body(
            ErrorResponse("validation", "at least one pet required"),
        )
    }
    val created = requests.map { req ->
        repository.create(req.name.ifBlank { "anon" }, req.species.ifBlank { "unknown" })
            .also {
                publisher.publishPetCreated(
                    PetCreatedEvent(id = it.id, name = it.name, species = it.species),
                )
            }
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(
        PetPage(content = created.map { it.toResponse() }, total = created.size),
    )
}
```

(Defensive defaults for blank fields keep the test happy when the framework randomizes — we're not exercising validation here, we're proving the runtime path.)

- [ ] **Step 5.2: Add the scenario test**

Open `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt`. Read the file first to understand the existing test scaffold (existing scenarios for `createPet`, `getPet`, `updatePet`, `deletePet`, `listPets`). Add a new test after the existing scenarios — adapt to whatever scaffolding the file already uses for `scenario(...) { wirespec.<endpoint>… }`:

```kotlin
test("createPetsBulk — body(count = 2..2) { … } sends a 2-element list and the response shows total=2") {
    scenario(endpointCtx, iterations = 1) {
        wirespec.createPetsBulk
            .body(count = 2..2) {
                name = Arb.constant("rex")
                species = Arb.constant("dog")
            }
            .expecting<CreatePetsBulk.Response201> { response ->
                // PetPage in the response decodes to a List<PetResponse> with total=2
                response.body.total shouldBe 2
            }
    }
}

test("createPetsBulk — default count (1..3) generates between 1 and 3 elements") {
    scenario(endpointCtx, iterations = 5) {
        wirespec.createPetsBulk
            .body {
                name = Arb.constant("polly")
                species = Arb.constant("parrot")
            }
            .expecting<CreatePetsBulk.Response201> { response ->
                response.body.total shouldBeIn (1..3)
            }
    }
}
```

(`shouldBeIn` comes from `io.kotest.matchers.ints.shouldBeIn` or similar — pick the right matcher for the project's kotest version. `endpointCtx` is whatever the file already uses for the existing scenarios.)

- [ ] **Step 5.3: Regenerate the example's Wirespec sources**

```
cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :example:wirespecKotlin
```

Expected: BUILD SUCCESSFUL. The generated `…/wirespec/kotest/CreatePetsBulkDsl.kt` should contain a `body(count: IntRange = 1..3, block: CreatePetsBulkCreatePetRequestBodyBuilder.() -> Unit)` overload.

Verify:

```
grep -n "body(count: IntRange" /Users/wilmveel/Projects/kotest-spring/example/build/generated-sources/wirespec/io/kotest/extensions/wirespec/example/kotest/CreatePetsBulkDsl.kt
```

Expected: at least one match.

- [ ] **Step 5.4: Run the example tests**

```
./gradlew :example:test --tests "*PetScenariosSpec*" -i
```

Expected: BUILD SUCCESSFUL, both new scenarios pass plus all pre-existing scenarios continue to pass.

- [ ] **Step 5.5: Commit**

```
git -C /Users/wilmveel/Projects/kotest-spring add example/src/main/kotlin/io/kotest/extensions/wirespec/example/controller/PetController.kt example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt
git -C /Users/wilmveel/Projects/kotest-spring commit -m "$(cat <<'EOF'
test(example): list-body scenarios via createPetsBulk

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Publish snapshot

- [ ] **Step 6.1: Clean rebuild + publish**

```
cd /Users/wilmveel/Projects/kotest-spring
./gradlew clean :emitter:build :core:build :spring:build
./gradlew :emitter:publishMavenPublicationToMavenLocal :core:publishMavenPublicationToMavenLocal :spring:publishMavenPublicationToMavenLocal :maven-plugin:publishMavenPublicationToMavenLocal --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.2: Verify the new code is in the published jars**

```
jar tf ~/.m2/repository/io/kotest/extensions/wirespec/kotest-wirespec/0.0.0-SNAPSHOT/kotest-wirespec-0.0.0-SNAPSHOT.jar | grep -E "EndpointReflection|EndpointCallBuilder"
javap -p -cp ~/.m2/repository/io/kotest/extensions/wirespec/kotest-wirespec/0.0.0-SNAPSHOT/kotest-wirespec-0.0.0-SNAPSHOT.jar io.kotest.extensions.wirespec.validation.EndpointReflection | grep bodyElementClass
javap -p -cp ~/.m2/repository/io/kotest/extensions/wirespec/kotest-wirespec/0.0.0-SNAPSHOT/kotest-wirespec-0.0.0-SNAPSHOT.jar io.kotest.extensions.wirespec.dsl.EndpointCallBuilder | grep bodyListSize
```

Expected: `bodyElementClass` field appears in `EndpointReflection` and `bodyListSize` method/field appears in `EndpointCallBuilder`.

---

## Task 7: Consumer — refactor `CampaignContractTest`

**Files:**
- Modify: `/Users/wilmveel/Projects/gambit/gambit-sp-campaign-service/src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt`

- [ ] **Step 7.1: Regenerate the consumer's Wirespec DSL**

```
cd /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service && mvn -DskipTests generate-test-sources
```

Verify the new overload exists on the four list-bodied endpoints:

```
grep -n "body(count: IntRange" target/generated-test-sources/wirespec/com/ahold/api/campaign/wirespec/kotest/AddProductsDsl.kt target/generated-test-sources/wirespec/com/ahold/api/campaign/wirespec/kotest/AddKeywordsDsl.kt target/generated-test-sources/wirespec/com/ahold/api/campaign/wirespec/kotest/AddOrUpdateCategoriesDsl.kt target/generated-test-sources/wirespec/com/ahold/api/campaign/wirespec/kotest/UpsertPlacementsDsl.kt
```

Expected: one match per file.

- [ ] **Step 7.2: Refactor `addProducts`**

Find the `addProducts` test scenario (search for `wirespec.addProducts`). Replace the existing block:

```kotlin
// BLOCKED on framework (see ScenarioRunner.kt:233): … long comment …
wirespec.addProducts.path(campaignId = campaignId)
    .body(
        arb = Arb.constant(
            listOf(SponsoredProductInLegacy(...)),
        ),
    )
    .expecting<AddProducts.Response200> { }
```

with:

```kotlin
wirespec.addProducts.path(campaignId = campaignId)
    .body(count = 1..3) {
        webShopId = Arb.constant("webshop_1")
        nasaNumber = Arb.long(0L..99999L)
        status = Arb.constant("active")
    }
    .expecting<AddProducts.Response200> { }
```

Remove the BLOCKED comment.

- [ ] **Step 7.3: Refactor `addKeywords`**

Replace the existing block with:

```kotlin
wirespec.addKeywords.path(campaignId = campaignId)
    .body(count = 1..3) {
        bidValue = Arb.double(0.01..1.0)
        matchType = Arb.constant("exact")
        status = Arb.constant("active")
        name = Arb.constant(RefinedACB3389C("kw"))  // or whatever the refined-wrapper field is named in the regenerated DSL
    }
    .expecting<AddKeywords.Response200> { }
```

(If the refined wrapper field can't be overridden because of the `JvmRefinedWrapper` enum-constructor bug, leave that field unset and let the framework randomize.)

Remove the BLOCKED comment.

- [ ] **Step 7.4: Refactor `addOrUpdateCategories`**

Replace with:

```kotlin
wirespec.addOrUpdateCategories.path(campaignId = campaignId)
    .body(count = 1..3) {
        id = Arb.constant("1")
        bidValue = Arb.double(0.01..1.0)
        // status omitted — JvmRefinedWrapper enum-ctor bug; framework randomizes + serializer maps to app form
    }
    .expecting<AddOrUpdateCategories.Response200> { }
```

Remove the BLOCKED comment.

- [ ] **Step 7.5: Refactor `upsertPlacements`**

Replace with:

```kotlin
wirespec.upsertPlacements.path(campaignId = campaignId)
    .body(count = 1..3) {
        placementCode = Arb.constant("HOMEPAGE")
        bidValue = Arb.double(0.01..1.0)
        // status omitted — same reason as addOrUpdateCategories
    }
    .expecting<UpsertPlacements.Response200> { }
```

Remove the BLOCKED comment.

- [ ] **Step 7.6: Run the contract tests**

```
cd /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service && mvn test -Dtest=CampaignContractTest -DfailIfNoTests=false
```

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`. If any scenario fails, the most likely cause is an app-side validation rejecting a framework-randomized field — pin only the offending field with `Arb.constant(...)` and re-run.

- [ ] **Step 7.7: Verify no list-level `Arb.constant(listOf(...))` remains**

```
grep -Pzn "(?s)Arb\.constant\(\s*listOf" src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt
```

Expected: no matches.

```
grep -En "BLOCKED on framework" src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt
```

Expected: no matches (all four BLOCKED comments removed).

- [ ] **Step 7.8: Commit**

```
git -C /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service add src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt
git -C /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service commit -m "$(cat <<'EOF'
test(contract): use typed body(count, block) for list-bodied endpoints

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-review checklist

**Spec coverage:**
- Runtime: `EndpointReflection.bodyElementClass` → Task 1 ✓
- Runtime: `EndpointCallBuilder.bodyListSize` → Task 2 ✓
- Runtime: `ScenarioRunner` list-body branch → Task 3 ✓
- Emitter: `body(count, block)` overload for `BodyKind.List` → Task 4 ✓
- Default size 1..3 → Tasks 3 + 4 ✓
- End-to-end test → Task 5 ✓
- Consumer cleanup → Task 7 ✓
- Out-of-scope items (`JvmRefinedWrapper`, `Map<String,X>`, `List<Primitive>`) → spec explicitly excludes; Task 7 acknowledges with field-omission workarounds.

**Placeholder scan:** Task 5's "or whatever scaffolding the file already uses" depends on reading the existing spec file first — acceptable instruction-to-explore, not a placeholder for the engineer to invent. Task 7.3 says "or whatever the refined-wrapper field is named" — same: regeneration first, then the actual name will be obvious. No `TBD`/`TODO` markers.

**Type consistency:** `bodyElementClass: Class<*>?` (Task 1), `bodyListSize: Arb<Int>?` (Task 2), used identically in Task 3. The emitter (Task 4) generates `inner.bodyListSize(io.kotest.property.arbitrary.int(count))` — `bodyListSize` setter exists in Task 2. The `count: IntRange` parameter type is consistent across emitter + consumer-facing surface.
