# Nested + List Body Builders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit body builders for list-bodied endpoints and nested object/list-of-object fields, so every body value at any nesting depth can be field-overridden with Arbs — eliminating the need for `Arb.constant(listOf(Model(...)))` hardcoded fixtures in consumer tests.

**Architecture:** Pure emitter change. The runtime's `KotestWirespecGeneratorBuilder` already supports nested-path overrides via `registerPath(vararg segments)` with `"*"` wildcards matching list indices. The current emitter only renders a typed `body { … }` builder when the body is a single `Reference.Custom` Type and only with single-segment paths (`registerPath("field")`). This plan extends `DslFileEmitter` + `EndpointShape` to (a) treat `Reference.Iterable(Reference.Custom)` bodies as builder-eligible (using `"*"` prefix for element fields), and (b) emit nested-type builders that compose into the root via `<field> { … }` overloads, prefixing the registered path.

**Tech Stack:** Kotlin (`KotlinIrEmitter` / `community.flock.wirespec.ir`); Wirespec compiler AST (`Endpoint`, `Type`, `Refined`, `Reference`); Kotest 6 (`Arb`); kotest-wirespec runtime (`KotestWirespecGeneratorBuilder.registerPath`). No runtime/integration changes.

**Scope:** kotest-spring `emitter` module only, plus a consumer refactor of `gambit-sp-campaign-service`'s `CampaignContractTest.kt` to validate the end-to-end ergonomics. List-size control (overriding the default `Arb.int(1..10)` element count of array fields) is **out of scope** — the framework's default size remains; that's a follow-up.

---

## File Structure

**Modified:**
- `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShape.kt` — extend to classify body kind (Object / Iterable-of-Custom / other), and to extract a recursive field tree for nested types.
- `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt` — emit the `body { … }` for Iterable-of-Custom; emit nested-type builders + `<field> { … }` overloads; pass a path-prefix through.

**New tests:**
- `emitter/src/test/resources/golden/PetCreateBulkDsl.kt` — golden for `Reference.Iterable(Reference.Custom)` body.
- `emitter/src/test/resources/golden/PetCreateNestedDsl.kt` — golden for nested object + nested list fields.
- `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShapeTest.kt` — *(modify if exists, else add)* unit-test the recursive shape extraction.

**Consumer refactor (separate repo):**
- `/Users/wilmveel/Projects/gambit/gambit-sp-campaign-service/src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt` — drop `Arb.constant(...)` fixtures, switch to nested `body { … }` form.

---

## Task 1: EndpointShape — classify body kind

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShape.kt`
- Test: `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShapeTest.kt`

The current `EndpointShape` only extracts `bodyFields` when the root `bodyRef` is `Reference.Custom`. We need a richer view: the body root may be a **single Custom Type** *or* an **Iterable of Custom Type**, and each field may itself be **nested** (Custom) or **list-of-nested** (Iterable<Custom>). Introduce a sealed `BodyShape` to carry that.

- [ ] **Step 1.1: Write the failing test**

Add to `EndpointShapeTest.kt` (create file if missing):

```kotlin
package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EndpointShapeTest : FunSpec({
    val stringRef = Reference.Primitive(Reference.Primitive.Type.String(null), false)

    test("Iterable<Custom> body — bodyKind is List, bodyFields come from element type") {
        val pet = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("Pet"),
            shape = Type.Shape(listOf(Field(emptyList(), FieldIdentifier("name"), stringRef))),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreateBulk"),
            method = Endpoint.Method.POST,
            path = emptyList(),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        "application/json",
                        Reference.Iterable(Reference.Custom("Pet", false), isNullable = false),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint, types = mapOf("Pet" to pet))

        shape.bodyKind shouldBe EndpointShape.BodyKind.List
        shape.bodyType shouldBe "List<Pet>"
        shape.bodyElementType shouldBe "Pet"
        shape.bodyFields.map { it.name } shouldBe listOf("name")
    }

    test("Custom body — bodyKind is Object, bodyFields come from the type itself") {
        val createReq = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("CreatePetRequest"),
            shape = Type.Shape(listOf(Field(emptyList(), FieldIdentifier("name"), stringRef))),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreate"),
            method = Endpoint.Method.POST,
            path = emptyList(),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        "application/json",
                        Reference.Custom("CreatePetRequest", false),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val shape = EndpointShape.from(endpoint, types = mapOf("CreatePetRequest" to createReq))

        shape.bodyKind shouldBe EndpointShape.BodyKind.Object
        shape.bodyType shouldBe "CreatePetRequest"
        shape.bodyElementType shouldBe "CreatePetRequest"
        shape.bodyFields.map { it.name } shouldBe listOf("name")
    }
})
```

- [ ] **Step 1.2: Run the test to verify it fails**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.EndpointShapeTest" -i`

Expected: COMPILE FAIL — `bodyKind`, `bodyElementType` don't exist on `EndpointShape`.

- [ ] **Step 1.3: Extend EndpointShape**

Replace `EndpointShape.from` and the data class in `EndpointShape.kt` with this version (preserves all existing fields, adds `bodyKind` + `bodyElementType`):

```kotlin
data class EndpointShape(
    val name: String,
    val pathFields: List<NamedTypedField>,
    val queryFields: List<NamedTypedField>,
    val headerFields: List<NamedTypedField>,
    val bodyType: String?,
    val bodyKind: BodyKind,
    val bodyElementType: String?,
    val bodyFields: List<NamedTypedField>,
    val modelImports: List<String>,
) {
    val dslName: String get() = name.replaceFirstChar(Char::lowercaseChar)

    data class NamedTypedField(val name: String, val kotlinType: String)

    enum class BodyKind { None, Object, List }

    companion object {
        fun from(
            endpoint: Endpoint,
            types: Map<String, Type> = emptyMap(),
            refined: Map<String, Refined> = emptyMap(),
        ): EndpointShape {
            val pathFields = endpoint.path
                .filterIsInstance<Endpoint.Segment.Param>()
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val queryFields = endpoint.queries
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val headerFields = endpoint.headers
                .map { NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
            val bodyRef = endpoint.requests.firstOrNull()?.content?.reference
            val bodyType = bodyRef?.let { if (it is Reference.Unit) null else KotlinTypeMapper.map(it) }

            val (bodyKind, elementCustomName) = when (bodyRef) {
                null, is Reference.Unit -> BodyKind.None to null
                is Reference.Custom -> BodyKind.Object to bodyRef.value
                is Reference.Iterable -> {
                    val inner = bodyRef.reference
                    if (inner is Reference.Custom) BodyKind.List to inner.value else BodyKind.None to null
                }
                else -> BodyKind.None to null
            }
            val bodyElementType = elementCustomName

            val bodyFields = elementCustomName
                ?.let { types[it] }
                ?.shape?.value
                ?.map { NamedTypedField(it.identifier.value, mapWithRefinedUnwrap(it.reference, refined)) }
                ?: emptyList()

            val refs = buildList {
                endpoint.path.filterIsInstance<Endpoint.Segment.Param>().forEach { add(it.reference) }
                endpoint.queries.forEach { add(it.reference) }
                endpoint.headers.forEach { add(it.reference) }
                if (bodyRef != null) add(bodyRef)
            }
            val bodyFieldRefs = elementCustomName
                ?.let { types[it] }
                ?.shape?.value
                ?.map { it.reference }
                ?: emptyList()
            val modelImports = (refs + bodyFieldRefs).flatMap(::collectCustomNames).distinct()

            return EndpointShape(
                name = endpoint.identifier.value,
                pathFields = pathFields,
                queryFields = queryFields,
                headerFields = headerFields,
                bodyType = bodyType,
                bodyKind = bodyKind,
                bodyElementType = bodyElementType,
                bodyFields = bodyFields,
                modelImports = modelImports,
            )
        }

        // ... keep existing collectCustomNames and mapWithRefinedUnwrap unchanged ...
    }
}
```

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.EndpointShapeTest" -i`

Expected: PASS.

- [ ] **Step 1.5: Run the full emitter test suite to verify no regressions**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test -i`

Expected: PASS (all existing golden tests still match — the new fields don't affect rendering yet).

- [ ] **Step 1.6: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShape.kt \
        emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShapeTest.kt
git commit -m "feat(emitter): classify body kind as Object/List/None on EndpointShape"
```

---

## Task 2: Emit body builder for `Iterable<Custom>` bodies

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt`
- Test (modify): `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitterTest.kt`
- Create: `emitter/src/test/resources/golden/PetCreateBulkDsl.kt`

For a list-bodied endpoint, the runtime walks element paths as `["0", "fieldName"]`, `["1", "fieldName"]`, etc. We register at `("*", "fieldName")` to hit every element.

- [ ] **Step 2.1: Write the failing golden test**

Add at the bottom of the existing `FunSpec({ ... })` block in `DslFileEmitterTest.kt`:

```kotlin
    test("body-only endpoint with Iterable<Custom> body (PetCreateBulk) — body{} emits with wildcard prefix") {
        val stringRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.String(null), false
        )
        val petType = community.flock.wirespec.compiler.core.parse.ast.Type(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("Pet"),
            shape = community.flock.wirespec.compiler.core.parse.ast.Type.Shape(
                listOf(community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("name"), stringRef)),
            ),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("PetCreateBulk"),
            method = Endpoint.Method.POST,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content(
                        "application/json",
                        community.flock.wirespec.compiler.core.parse.ast.Reference.Iterable(
                            community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("Pet", false),
                            isNullable = false,
                        ),
                    ),
                ),
            ),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg, types = mapOf("Pet" to petType))
        emitted.file shouldBe "com/example/api/kotest/PetCreateBulkDsl.kt"
        emitted.result shouldBe readGolden("PetCreateBulkDsl.kt")
    }
```

Create `emitter/src/test/resources/golden/PetCreateBulkDsl.kt`:

```kotlin
package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateBulk
import io.kotest.property.Arb
import com.example.api.model.Pet
@WirespecScenarioDsl
public class PetCreateBulkCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetCreateBulk.Handler, PetCreateBulk)
    public fun body(value: List<Pet>): PetCreateBulkCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<List<Pet>>): PetCreateBulkCall =
        apply { inner.body(arb) }
    public fun body(block: PetBodyBuilder.() -> Unit): PetCreateBulkCall = apply {
        val builder = PetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("*", "name") { it } }
        }
    }
    public inline fun <reified R : PetCreateBulk.Response<*>> expecting(): PetCreateBulkCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetCreateBulk.Response<*>> expecting(noinline block: (R) -> Unit): PetCreateBulkCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetCreateBulk.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetCreateBulk.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetCreateBulkCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetCreateBulk.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetCreateBulkCall =
        apply { inner.collecting<R>(duration, block) }
}
@WirespecScenarioDsl
public class PetBodyBuilder {
    public var name: Arb<String>? = null
}
```

- [ ] **Step 2.2: Run the test to verify it fails**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.DslFileEmitterTest" -i`

Expected: FAIL — emitted `PetCreateBulkDsl.kt` differs from golden (no `body { … }` block).

- [ ] **Step 2.3: Update DslFileEmitter to render body{} for List body**

In `DslFileEmitter.emit`, the `if (shape.bodyType != null && shape.bodyFields.isNotEmpty())` guard for `renderBodyBuilder` already keys off `bodyFields`. The change is in **two places**:

(a) In `renderBodySlot`, change the `inner.body { registerPath("field") }` block to use a wildcard prefix when the body is a list:

```kotlin
private fun renderBodySlot(shape: EndpointShape, bodyType: String): String = buildString {
    val call = "${shape.name}Call"
    appendLine("    public fun body(value: $bodyType): $call =")
    appendLine("        apply { inner.body(value) }")
    appendLine("    public fun body(arb: Arb<$bodyType>): $call =")
    appendLine("        apply { inner.body(arb) }")
    if (shape.bodyFields.isNotEmpty()) {
        val element = shape.bodyElementType ?: error("bodyFields present but no bodyElementType")
        val builderName = "${element}BodyBuilder"
        val pathPrefix = if (shape.bodyKind == EndpointShape.BodyKind.List) "\"*\", " else ""
        appendLine("    public fun body(block: $builderName.() -> Unit): $call = apply {")
        appendLine("        val builder = $builderName().apply(block)")
        appendLine("        inner.body {")
        shape.bodyFields.forEach { f ->
            appendLine("            builder.${f.name}?.let { registerPath($pathPrefix\"${f.name}\") { it } }")
        }
        appendLine("        }")
        appendLine("    }")
    }
}
```

(b) In `renderBodyBuilder`, name the builder by **element type**, not body type:

```kotlin
private fun renderBodyBuilder(elementType: String, fields: List<EndpointShape.NamedTypedField>): String = buildString {
    appendLine("@WirespecScenarioDsl")
    appendLine("public class ${elementType}BodyBuilder {")
    fields.forEach { f ->
        appendLine("    public var ${f.name}: Arb<${f.kotlinType}>? = null")
    }
    append("}")
}
```

(c) In `DslFileEmitter.emit`, update the call site:

```kotlin
if (shape.bodyType != null && shape.bodyFields.isNotEmpty()) {
    val element = shape.bodyElementType
        ?: error("bodyFields present but no bodyElementType for ${shape.name}")
    raw(renderBodyBuilder(element, shape.bodyFields))
}
```

- [ ] **Step 2.4: Run the test to verify it passes**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.DslFileEmitterTest" -i`

Expected: PASS for `PetCreateBulkDsl`. Other goldens may break if `PetUpdateDsl.kt` / `PetCreateDsl.kt` referenced `${bodyType}BodyBuilder` and golden expected `UpdatePetRequestBodyBuilder` — since we renamed to use `bodyElementType` which equals `bodyType` for `Object` kind, names should be unchanged.

- [ ] **Step 2.5: Run the full test suite**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test -i`

Expected: PASS — all golden tests.

- [ ] **Step 2.6: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt \
        emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitterTest.kt \
        emitter/src/test/resources/golden/PetCreateBulkDsl.kt
git commit -m "feat(emitter): emit body{} builder for Iterable<Custom> bodies"
```

---

## Task 3: Nested-object field overloads in the body builder

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShape.kt`
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt`
- Modify: `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitterTest.kt`
- Create: `emitter/src/test/resources/golden/PetCreateNestedDsl.kt`

A body field whose reference is `Reference.Custom("Owner")` (and `Owner` is a known `Type`) gets a `<field> { … }` overload that drills into the nested type's fields. Same for `Reference.Iterable(Reference.Custom("Owner"))` — except the registered path inserts `"*"`. We solve both in this task: classify each field, then emit nested builders.

**Design choice:** Each field's nested builder is a separate generated class scoped to the parent (e.g., `PetOwnerBuilder` for `Pet.owner`). We don't reuse builders across endpoints because the path-prefix in their `apply()` differs by call site. The builder class itself only stores `Arb<T>?` fields; the **path-registration** happens in a lambda generated in the parent that consumes the nested builder's slots.

- [ ] **Step 3.1: Extend EndpointShape with recursive field classification**

In `EndpointShape.kt`, add a sealed `BodyFieldShape` and replace `NamedTypedField` usage for body fields:

```kotlin
sealed interface BodyFieldShape {
    val name: String

    data class Primitive(override val name: String, val kotlinType: String) : BodyFieldShape
    data class NestedObject(
        override val name: String,
        val typeName: String,
        val fields: List<BodyFieldShape>,
    ) : BodyFieldShape
    data class NestedList(
        override val name: String,
        val elementTypeName: String,
        val fields: List<BodyFieldShape>,
    ) : BodyFieldShape
}
```

Add a recursive extractor (alongside `mapWithRefinedUnwrap`):

```kotlin
private fun extractBodyFields(
    typeName: String,
    types: Map<String, Type>,
    refined: Map<String, Refined>,
    visited: Set<String>,
): List<BodyFieldShape> {
    if (typeName in visited) return emptyList()
    val type = types[typeName] ?: return emptyList()
    val nextVisited = visited + typeName
    return type.shape.value.map { field ->
        val name = field.identifier.value
        when (val ref = field.reference) {
            is Reference.Custom -> if (ref.value in types) {
                BodyFieldShape.NestedObject(
                    name = name,
                    typeName = ref.value,
                    fields = extractBodyFields(ref.value, types, refined, nextVisited),
                )
            } else {
                BodyFieldShape.Primitive(name, mapWithRefinedUnwrap(ref, refined))
            }
            is Reference.Iterable -> {
                val inner = ref.reference
                if (inner is Reference.Custom && inner.value in types) {
                    BodyFieldShape.NestedList(
                        name = name,
                        elementTypeName = inner.value,
                        fields = extractBodyFields(inner.value, types, refined, nextVisited),
                    )
                } else {
                    BodyFieldShape.Primitive(name, mapWithRefinedUnwrap(ref, refined))
                }
            }
            else -> BodyFieldShape.Primitive(name, mapWithRefinedUnwrap(ref, refined))
        }
    }
}
```

Add `bodyFieldShapes: List<BodyFieldShape>` to the `EndpointShape` data class, populate in `from()`:

```kotlin
val bodyFieldShapes: List<BodyFieldShape> = elementCustomName
    ?.let { extractBodyFields(it, types, refined, visited = emptySet()) }
    ?: emptyList()
```

`bodyFields: List<NamedTypedField>` remains as the **flat top-level view** for backwards-compat with existing callers (kept as a derived view from `bodyFieldShapes`):

```kotlin
val bodyFields: List<NamedTypedField> = bodyFieldShapes.map { f ->
    NamedTypedField(
        f.name,
        when (f) {
            is BodyFieldShape.Primitive -> f.kotlinType
            is BodyFieldShape.NestedObject -> f.typeName
            is BodyFieldShape.NestedList -> "List<${f.elementTypeName}>"
        },
    )
}
```

(The cycle-guard via `visited` keeps recursive types from infinite-looping.)

- [ ] **Step 3.2: Write the failing golden test**

Add to `DslFileEmitterTest.kt`:

```kotlin
    test("nested object and nested list body fields — emit per-field nested builders") {
        val stringRef = community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive(
            community.flock.wirespec.compiler.core.parse.ast.Reference.Primitive.Type.String(null), false
        )
        val ownerType = community.flock.wirespec.compiler.core.parse.ast.Type(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("Owner"),
            shape = community.flock.wirespec.compiler.core.parse.ast.Type.Shape(
                listOf(community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("email"), stringRef)),
            ),
            extends = emptyList(),
        )
        val tagType = community.flock.wirespec.compiler.core.parse.ast.Type(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("Tag"),
            shape = community.flock.wirespec.compiler.core.parse.ast.Type.Shape(
                listOf(community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("label"), stringRef)),
            ),
            extends = emptyList(),
        )
        val petType = community.flock.wirespec.compiler.core.parse.ast.Type(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("Pet"),
            shape = community.flock.wirespec.compiler.core.parse.ast.Type.Shape(
                listOf(
                    community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("name"), stringRef),
                    community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("owner"), community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("Owner", false)),
                    community.flock.wirespec.compiler.core.parse.ast.Field(emptyList(), community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier("tags"), community.flock.wirespec.compiler.core.parse.ast.Reference.Iterable(community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("Tag", false), isNullable = false)),
                ),
            ),
            extends = emptyList(),
        )
        val endpoint = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier("PetCreateNested"),
            method = Endpoint.Method.POST,
            path = listOf(Endpoint.Segment.Literal("api"), Endpoint.Segment.Literal("pets")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(
                Endpoint.Request(
                    content = Endpoint.Content("application/json", community.flock.wirespec.compiler.core.parse.ast.Reference.Custom("Pet", false)),
                ),
            ),
            responses = emptyList(),
        )

        val emitted = DslFileEmitter.emit(endpoint, pkg, types = mapOf("Pet" to petType, "Owner" to ownerType, "Tag" to tagType))
        emitted.file shouldBe "com/example/api/kotest/PetCreateNestedDsl.kt"
        emitted.result shouldBe readGolden("PetCreateNestedDsl.kt")
    }
```

Create `emitter/src/test/resources/golden/PetCreateNestedDsl.kt`:

```kotlin
package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder.StreamingMode
import kotlin.time.Duration
import com.example.api.endpoint.PetCreateNested
import io.kotest.property.Arb
import com.example.api.model.Pet
import com.example.api.model.Owner
import com.example.api.model.Tag
@WirespecScenarioDsl
public class PetCreateNestedCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.endpoint(PetCreateNested.Handler, PetCreateNested)
    public fun body(value: Pet): PetCreateNestedCall =
        apply { inner.body(value) }
    public fun body(arb: Arb<Pet>): PetCreateNestedCall =
        apply { inner.body(arb) }
    public fun body(block: PetBodyBuilder.() -> Unit): PetCreateNestedCall = apply {
        val builder = PetBodyBuilder().apply(block)
        inner.body {
            builder.name?.let { registerPath("name") { it } }
            builder._ownerBlock?.let { block ->
                val nested = OwnerBodyBuilder().apply(block)
                nested.email?.let { registerPath("owner", "email") { it } }
            }
            builder._tagsBlock?.let { block ->
                val nested = TagBodyBuilder().apply(block)
                nested.label?.let { registerPath("tags", "*", "label") { it } }
            }
        }
    }
    public inline fun <reified R : PetCreateNested.Response<*>> expecting(): PetCreateNestedCall =
        apply { inner.expecting<R>() }
    public inline fun <reified R : PetCreateNested.Response<*>> expecting(noinline block: (R) -> Unit): PetCreateNestedCall =
        apply { inner.expecting<R>(block) }
    public inline fun <reified R : PetCreateNested.Response<*>, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        inner.returning<R, T>(projection)
    public inline fun <reified R : PetCreateNested.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit): PetCreateNestedCall =
        apply { inner.collecting<R>(count, block) }
    public inline fun <reified R : PetCreateNested.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit): PetCreateNestedCall =
        apply { inner.collecting<R>(duration, block) }
}
@WirespecScenarioDsl
public class PetBodyBuilder {
    public var name: Arb<String>? = null
    @PublishedApi internal var _ownerBlock: (OwnerBodyBuilder.() -> Unit)? = null
    public fun owner(block: OwnerBodyBuilder.() -> Unit) { _ownerBlock = block }
    @PublishedApi internal var _tagsBlock: (TagBodyBuilder.() -> Unit)? = null
    public fun tags(block: TagBodyBuilder.() -> Unit) { _tagsBlock = block }
}
@WirespecScenarioDsl
public class OwnerBodyBuilder {
    public var email: Arb<String>? = null
}
@WirespecScenarioDsl
public class TagBodyBuilder {
    public var label: Arb<String>? = null
}
```

- [ ] **Step 3.3: Run the test to verify it fails**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.DslFileEmitterTest" -i`

Expected: FAIL on the new test — emitted differs from golden.

- [ ] **Step 3.4: Implement nested rendering in DslFileEmitter**

Replace `renderBodySlot` and `renderBodyBuilder` with the recursive versions, plus add `modelImports` extraction over the full nested tree.

First, in `DslFileEmitter.emit`, replace the `shape.modelImports` line with: that's already covered by `EndpointShape.from`'s `modelImports` collection — but we need it to also include nested-type names. Update `EndpointShape.from`'s `modelImports` to walk `bodyFieldShapes` recursively. Add:

```kotlin
private fun collectNestedTypeNames(fields: List<BodyFieldShape>): List<String> = fields.flatMap { f ->
    when (f) {
        is BodyFieldShape.Primitive -> emptyList()
        is BodyFieldShape.NestedObject -> listOf(f.typeName) + collectNestedTypeNames(f.fields)
        is BodyFieldShape.NestedList -> listOf(f.elementTypeName) + collectNestedTypeNames(f.fields)
    }
}
```

And extend the modelImports line:

```kotlin
val modelImports = ((refs + bodyFieldRefs).flatMap(::collectCustomNames) +
    collectNestedTypeNames(bodyFieldShapes)).distinct()
```

Now in `DslFileEmitter.kt`, rewrite the body-builder rendering. Replace `renderBodySlot` and `renderBodyBuilder` with these, and add helpers:

```kotlin
private fun renderBodySlot(shape: EndpointShape, bodyType: String): String = buildString {
    val call = "${shape.name}Call"
    appendLine("    public fun body(value: $bodyType): $call =")
    appendLine("        apply { inner.body(value) }")
    appendLine("    public fun body(arb: Arb<$bodyType>): $call =")
    appendLine("        apply { inner.body(arb) }")
    if (shape.bodyFieldShapes.isNotEmpty()) {
        val element = shape.bodyElementType ?: error("bodyFieldShapes present but no bodyElementType")
        val builderName = "${element}BodyBuilder"
        val rootPrefix = if (shape.bodyKind == EndpointShape.BodyKind.List) listOf("\"*\"") else emptyList()
        appendLine("    public fun body(block: $builderName.() -> Unit): $call = apply {")
        appendLine("        val builder = $builderName().apply(block)")
        appendLine("        inner.body {")
        renderFieldRegistrations(this, "builder", shape.bodyFieldShapes, rootPrefix, indent = "            ")
        appendLine("        }")
        appendLine("    }")
    }
}

private fun renderFieldRegistrations(
    out: StringBuilder,
    receiver: String,
    fields: List<EndpointShape.BodyFieldShape>,
    pathPrefix: List<String>,
    indent: String,
) {
    fields.forEach { f ->
        val nameSegment = "\"${f.name}\""
        val pathArgs = (pathPrefix + nameSegment).joinToString(", ")
        when (f) {
            is EndpointShape.BodyFieldShape.Primitive -> {
                out.appendLine("$indent$receiver.${f.name}?.let { registerPath($pathArgs) { it } }")
            }
            is EndpointShape.BodyFieldShape.NestedObject -> {
                val nestedBuilder = "${f.typeName}BodyBuilder"
                val nestedVar = "nested_${f.name}"
                out.appendLine("$indent$receiver._${f.name}Block?.let { block ->")
                out.appendLine("$indent    val $nestedVar = $nestedBuilder().apply(block)")
                renderFieldRegistrations(out, nestedVar, f.fields, pathPrefix + nameSegment, "$indent    ")
                out.appendLine("$indent}")
            }
            is EndpointShape.BodyFieldShape.NestedList -> {
                val nestedBuilder = "${f.elementTypeName}BodyBuilder"
                val nestedVar = "nested_${f.name}"
                out.appendLine("$indent$receiver._${f.name}Block?.let { block ->")
                out.appendLine("$indent    val $nestedVar = $nestedBuilder().apply(block)")
                renderFieldRegistrations(out, nestedVar, f.fields, pathPrefix + nameSegment + "\"*\"", "$indent    ")
                out.appendLine("$indent}")
            }
        }
    }
}

private fun renderBodyBuilder(elementType: String, fields: List<EndpointShape.BodyFieldShape>): String = buildString {
    appendLine("@WirespecScenarioDsl")
    appendLine("public class ${elementType}BodyBuilder {")
    fields.forEach { f ->
        when (f) {
            is EndpointShape.BodyFieldShape.Primitive -> {
                appendLine("    public var ${f.name}: Arb<${f.kotlinType}>? = null")
            }
            is EndpointShape.BodyFieldShape.NestedObject -> {
                appendLine("    @PublishedApi internal var _${f.name}Block: (${f.typeName}BodyBuilder.() -> Unit)? = null")
                appendLine("    public fun ${f.name}(block: ${f.typeName}BodyBuilder.() -> Unit) { _${f.name}Block = block }")
            }
            is EndpointShape.BodyFieldShape.NestedList -> {
                appendLine("    @PublishedApi internal var _${f.name}Block: (${f.elementTypeName}BodyBuilder.() -> Unit)? = null")
                appendLine("    public fun ${f.name}(block: ${f.elementTypeName}BodyBuilder.() -> Unit) { _${f.name}Block = block }")
            }
        }
    }
    append("}")
}
```

And update `DslFileEmitter.emit` to emit a builder for **every distinct nested type** as well:

```kotlin
if (shape.bodyFieldShapes.isNotEmpty()) {
    val element = shape.bodyElementType ?: error("…")
    raw(renderBodyBuilder(element, shape.bodyFieldShapes))

    // Emit nested-type builders, deduped by type name. Each appears once per file.
    val nestedDefs = collectNestedBuilders(shape.bodyFieldShapes, alreadyEmitted = setOf(element))
    nestedDefs.forEach { (typeName, fields) ->
        raw(renderBodyBuilder(typeName, fields))
    }
}
```

with helper:

```kotlin
private fun collectNestedBuilders(
    fields: List<EndpointShape.BodyFieldShape>,
    alreadyEmitted: Set<String>,
): List<Pair<String, List<EndpointShape.BodyFieldShape>>> {
    val result = mutableListOf<Pair<String, List<EndpointShape.BodyFieldShape>>>()
    val emitted = alreadyEmitted.toMutableSet()
    fun walk(fs: List<EndpointShape.BodyFieldShape>) {
        fs.forEach { f ->
            when (f) {
                is EndpointShape.BodyFieldShape.Primitive -> Unit
                is EndpointShape.BodyFieldShape.NestedObject -> {
                    if (emitted.add(f.typeName)) { result += f.typeName to f.fields; walk(f.fields) }
                }
                is EndpointShape.BodyFieldShape.NestedList -> {
                    if (emitted.add(f.elementTypeName)) { result += f.elementTypeName to f.fields; walk(f.fields) }
                }
            }
        }
    }
    walk(fields)
    return result
}
```

Update `renderBodyBuilder`'s old call site (Task 2) to use `shape.bodyFieldShapes` instead of `shape.bodyFields`.

- [ ] **Step 3.5: Run the test to verify it passes**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.DslFileEmitterTest" -i`

Expected: PASS — `PetCreateNestedDsl` matches golden, plus `PetCreateBulkDsl` still matches (its `bodyFieldShapes` is a list of one `Primitive`, equivalent rendering).

- [ ] **Step 3.6: Run the full test suite**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test -i`

Expected: PASS.

- [ ] **Step 3.7: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/EndpointShape.kt \
        emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt \
        emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitterTest.kt \
        emitter/src/test/resources/golden/PetCreateNestedDsl.kt
git commit -m "feat(emitter): emit nested-object and nested-list body-builder overloads"
```

---

## Task 4: Integration smoke test on the catalog emitter

**Files:**
- Modify: `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitterTest.kt`
- Create: `emitter/src/test/resources/golden/<TBD>.kt` if a new fixture file is needed.

Confirm the full pipeline (`TypesafeDslEmitter` → `DslFileEmitter`) wires nested types through unchanged.

- [ ] **Step 4.1: Inspect the existing TypesafeDslEmitterTest**

Run: `cat /Users/wilmveel/Projects/kotest-spring/emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitterTest.kt`

Identify whether a nested-type fixture already exists. If yes, modify it to assert the nested builder appears in the emitted catalog/dsl set. If not, add a new `test("contract with nested type bodies — emits per-field builders end-to-end")` that compiles a tiny `.ws` source through the emitter and asserts the resulting `DslFile`'s text contains both `OwnerBodyBuilder` and `TagBodyBuilder`.

- [ ] **Step 4.2: Run the test**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew :emitter:test --tests "io.kotest.extensions.wirespec.emitter.TypesafeDslEmitterTest" -i`

Expected: PASS.

- [ ] **Step 4.3: Commit**

```bash
cd /Users/wilmveel/Projects/kotest-spring
git add emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitterTest.kt
git commit -m "test(emitter): integration coverage for nested body-builder pipeline"
```

---

## Task 5: Publish the snapshot

**Files:** (none — Gradle publish)

- [ ] **Step 5.1: Publish to local Maven**

Run: `cd /Users/wilmveel/Projects/kotest-spring && ./gradlew publishToMavenLocal`

Expected: BUILD SUCCESSFUL. New `0.0.0-SNAPSHOT` jars in `~/.m2/repository/io/kotest/extensions/`.

---

## Task 6: Consumer refactor — gambit-sp-campaign-service

**Files:**
- Modify: `/Users/wilmveel/Projects/gambit/gambit-sp-campaign-service/src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt`

Drop all `Arb.constant(...)` fixtures, switch to nested `body { … }` form. The framework will random-generate every field; the test only constrains what the app's validation rejects.

- [ ] **Step 6.1: Regenerate the DSL**

Run: `cd /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service && ./mvnw -DskipTests generate-sources`

(Or the project's equivalent — `./mvnw wirespec:generate` if a separate goal.)

Expected: `target/generated-sources/.../wirespec/kotest/AddProductsDsl.kt` etc. now contain a `body { … }` with element-builders.

- [ ] **Step 6.2: Refactor `addProducts` — replace `Arb.constant(...)` with builder**

In `CampaignContractTest.kt`, the products scenario currently has:

```kotlin
wirespec.addProducts.path(campaignId = campaignId)
    .body(
        arb = Arb.constant(
            listOf(SponsoredProductInLegacy(null, "webshop_1", null, "brand", 12345, "active")),
        ),
    )
    .expecting<AddProducts.Response200> { }
```

Replace with:

```kotlin
wirespec.addProducts.path(campaignId = campaignId)
    .body {
        webShopId = Arb.constant("webshop_1")
        nasaNumber = Arb.int(0..99999)
        status = Arb.constant("active")
        // id, retailProductId, brand: framework-generated
    }
    .expecting<AddProducts.Response200> { }
```

- [ ] **Step 6.3: Refactor `addKeywords` similarly**

```kotlin
wirespec.addKeywords.path(campaignId = campaignId)
    .body {
        bidValue = Arb.double(0.01..1.0)
        matchType = Arb.constant("exact")
        status = Arb.constant("active")
        keyword = Arb.constant(RefinedACB3389C("kw"))  // refined-type; primitive Arb<String> if framework supports
    }
    .expecting<AddKeywords.Response200> { }
```

- [ ] **Step 6.4: Refactor `addOrUpdateCategories` similarly**

```kotlin
wirespec.addOrUpdateCategories.path(campaignId = campaignId)
    .body {
        id = Arb.constant("1")
        bidValue = Arb.double(0.01..1.0)
        status = Arb.constant(Status.ACTIVE)
    }
    .expecting<AddOrUpdateCategories.Response200> { }
```

- [ ] **Step 6.5: Refactor `upsertPlacements` similarly**

```kotlin
wirespec.upsertPlacements.path(campaignId = campaignId)
    .body {
        placementCode = Arb.constant("HOMEPAGE")
        bidValue = Arb.double(0.01..1.0)
        status = Arb.constant(Status.ACTIVE)
        // id: framework-generated (null-allowed)
    }
    .expecting<UpsertPlacements.Response200> { }
```

- [ ] **Step 6.6: Refactor `applyValidCampaign` — use nested builders for `products`/`keywords`**

Replace the `products = Arb.constant(...)` and `keywords = Arb.constant(...)` blocks in `applyValidCampaign()` with nested DSL calls:

```kotlin
products {
    webShopId = Arb.constant("webshop_1")
    nasaNumber = Arb.int(0..99999)
    status = Arb.constant("active")
}
keywords {
    bidValue = Arb.double(0.01..1.0)
    matchType = Arb.constant("exact")
    status = Arb.constant("active")
}
```

(`applyValidCampaign()`'s receiver type `NewCampaignBodyBuilder` now exposes `products(block)` / `keywords(block)` overloads from Task 3.)

- [ ] **Step 6.7: Refactor `addNewPoNumber` — also a Custom body without nesting**

```kotlin
wirespec.addNewPoNumber.path(advertiserId = advertiserId)
    .body {
        poId = Arb.constant("PO-${java.util.UUID.randomUUID().toString().take(8)}")
    }
    .expecting<AddNewPoNumber.Response201> { }
```

- [ ] **Step 6.8: Refactor `createCampaigns` / `deleteCampaigns`**

These are `TestDataCreateParameters` / `TestDataDeleteParameters` bodies — single Custom Types. Same pattern as `addNewPoNumber`.

- [ ] **Step 6.9: Run the contract tests**

Run: `cd /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service && ./mvnw test -Dtest=CampaignContractTest -DfailIfNoTests=false`

Expected: All 8 scenarios PASS. If any fail, the failure points to an app-validation rule that framework-random data violates — pin only the offending field with `Arb.constant(...)` and re-run.

- [ ] **Step 6.10: Verify no `Arb.constant(listOf(` or `Arb.constant(SponsoredProductInLegacy` remain**

Run: `cd /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service && grep -E "Arb\.constant\((listOf|SponsoredProductInLegacy|NewKeyword|CategoryRequestData|PlacementRequest|PoNumber|TestData)" src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt`

Expected: no matches. (Plain `Arb.constant("string")` for primitive overrides is fine.)

- [ ] **Step 6.11: Commit consumer refactor**

```bash
cd /Users/wilmveel/Projects/gambit/gambit-sp-campaign-service
git add src/test/kotlin/com/ahold/api/campaign/wirespec/CampaignContractTest.kt
git commit -m "test(contract): drop Arb.constant fixtures in favor of nested body{} builders"
```

---

## Self-review checklist (run before handoff)

1. **Spec coverage** — does each goal task have an implementing step?
   - List-bodied builder → Task 2 ✓
   - Nested object overload → Task 3 ✓
   - Nested list-of-object overload → Task 3 ✓
   - End-to-end consumer validation → Task 6 ✓
2. **Placeholder scan** — Task 4 step 4.1 says "if a fixture exists, modify it; else create one" — this is borderline. Reader needs to inspect first. *Acceptable: instruction is exact about what to check.*
3. **Type consistency** — `bodyElementType`, `bodyFieldShapes`, `BodyFieldShape.{Primitive,NestedObject,NestedList}` used consistently in Tasks 1, 3. `_<field>Block` naming consistent across builder + parent's `inner.body { }` block.
4. **Out-of-scope** — list-size override and refined-type field defaulting are explicitly excluded; consumer task notes refined-type as one-liner using `Arb.constant(RefinedACB3389C("kw"))`.