# Kafka Channel Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `kotest-extensions-spring-wirespec` so Wirespec `channel` definitions (extracted from `@KafkaListener` / `kafkaTemplate.send(...)` by `wirespec-spring-extractor` 0.0.7+) get a per-channel Kotest DSL that interleaves with the existing per-endpoint DSL in one unified `scenario { … }` block. EmbeddedKafka v1.

**Architecture:** Add a sibling at each existing layer. Runtime gets `WirespecChannelContext` + `MessageTransport` (next to `WirespecTestContext` + `Wirespec.Transportation`). DSL gets `ChannelCallBuilder` + a sealed `Step` type in `ScenarioBuilder` (next to `EndpointCallBuilder`). Emitter gets `ChannelShape` + `ChannelDslFileEmitter` (next to `EndpointShape` + `DslFileEmitter`). `SpringWirespecSpec.defaultCtx` is renamed to `endpointCtx` for symmetry with a new `channelCtx`. Transport is a self-contained `KafkaProducer`/`KafkaConsumer<String, ByteArray>` built from `EmbeddedKafkaBroker.brokersAsString` — no bean lookup, no fight with the app's own producer-serializer config.

**Tech Stack:** Kotlin 2.3.0, Kotest 6.1.11, Wirespec 0.19.0-RC.4, wirespec-spring-extractor 0.0.8, Spring Boot 3.4.1, spring-kafka + spring-kafka-test, EmbeddedKafkaBroker.

**Reference spec:** `docs/superpowers/specs/2026-05-24-kafka-channel-tests-design.md`.

---

## File structure

**Created**

- `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShape.kt` — shape extraction for `compiler.core.parse.ast.Channel`.
- `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitter.kt` — emits `<Name>ChannelDsl.kt`.
- `emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShapeTest.kt`
- `emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitterTest.kt`
- `emitter/src/test/resources/golden/SimplePayloadChannelDsl.kt`
- `emitter/src/test/resources/golden/CustomPayloadChannelDsl.kt`
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/MessageTransport.kt` — interface + `OutgoingRecord`/`IncomingRecord`.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransport.kt` — deterministic fake.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/EmbeddedKafkaMessageTransport.kt` — real KafkaProducer/KafkaConsumer.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecChannelContext.kt`
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt` — sealed type.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilder.kt`
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelReflection.kt`
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidator.kt`
- `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransportTest.kt`
- `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilderTest.kt`
- `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidatorTest.kt`
- `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunnerChannelTest.kt`
- `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetEventPublisher.kt`
- `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetCommandListener.kt`
- `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetChannelScenariosSpec.kt`

**Modified**

- `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/TypesafeDslEmitter.kt` — also dispatches `Channel` AST nodes.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt` — `calls: MutableList<EndpointCallBuilder<*,*,*>>` becomes `steps: MutableList<Step>`; adds `.channel(channelClass)`.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt` — two-arg `scenario(endpointCtx, channelCtx) { … }` overload + threading channelCtx through `runScenarioOnce`.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt` — `endpointCtx`/`channelCtx` constructor params; dispatch on `Step.Endpoint` vs `Step.Channel`.
- `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt` — rename `defaultCtx` → `endpointCtx`; add `open val channelCtx: WirespecChannelContext?`.
- `runtime/build.gradle.kts` — `compileOnly("org.springframework.kafka:spring-kafka")`, `testImplementation("org.springframework.kafka:spring-kafka-test")`.
- `example/build.gradle.kts` — `implementation("org.springframework.boot:spring-boot-starter")`, `implementation("org.springframework.kafka:spring-kafka")`, `testImplementation("org.springframework.kafka:spring-kafka-test")`.
- `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/controller/PetController.kt` — call `PetEventPublisher.publishPetCreated(...)` after creating a pet.
- `gradle.properties` — `wirespecExtractorVersion=0.0.8`.
- `README.md` — new "Channels" section under "What your tests look like".

---

## Phase A: Emitter (lowest layer; no runtime deps)

### Task A1: ChannelShape

**Files:**
- Create: `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShape.kt`
- Create: `emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShapeTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShapeTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelShapeTest : FunSpec({

    fun stringRef() = Reference.Primitive(Reference.Primitive.Type.String(null), false)

    test("simple-payload channel — primitive String body") {
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("HeartbeatChannel"),
            reference = stringRef(),
        )

        val shape = ChannelShape.from(channel)

        shape.name shouldBe "HeartbeatChannel"
        shape.dslName shouldBe "heartbeatChannel"
        shape.payloadType shouldBe "String"
        shape.payloadFields shouldBe emptyList()
        shape.modelImports shouldBe emptyList()
    }

    test("custom-payload channel — references a Type, populates payloadFields") {
        val petCreated = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreated"),
            shape = Type.Shape(
                value = listOf(
                    Field(emptyList(), FieldIdentifier("id"), stringRef()),
                    Field(emptyList(), FieldIdentifier("name"), stringRef()),
                ),
            ),
            extends = emptyList(),
        )
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreatedChannel"),
            reference = Reference.Custom("PetCreated", false),
        )

        val shape = ChannelShape.from(channel, types = mapOf("PetCreated" to petCreated))

        shape.payloadType shouldBe "PetCreated"
        shape.payloadFields.map { it.name } shouldBe listOf("id", "name")
        shape.payloadFields.map { it.kotlinType } shouldBe listOf("String", "String")
        shape.modelImports shouldBe listOf("PetCreated")
    }

    test("list-payload channel — Reference.Iterable") {
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("BatchChannel"),
            reference = Reference.Iterable(Reference.Custom("Item", false), false),
        )

        val shape = ChannelShape.from(channel)

        shape.payloadType shouldBe "List<Item>"
        shape.payloadFields shouldBe emptyList()
        shape.modelImports shouldBe listOf("Item")
    }
})
```

- [ ] **Step 2: Run the test, verify FAIL**

Run: `./gradlew :emitter:test --tests "io.kotest.extensions.spring.wirespec.emitter.ChannelShapeTest"`
Expected: FAIL with "unresolved reference ChannelShape".

- [ ] **Step 3: Implement ChannelShape**

Create `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShape.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type

data class ChannelShape(
    val name: String,
    val payloadType: String,
    val payloadFields: List<EndpointShape.NamedTypedField>,
    val modelImports: List<String>,
) {
    val dslName: String get() = name.replaceFirstChar(Char::lowercaseChar)

    companion object {
        fun from(channel: Channel, types: Map<String, Type> = emptyMap()): ChannelShape {
            val payloadRef = channel.reference
            val payloadType = KotlinTypeMapper.map(payloadRef)
            val payloadFields = (payloadRef as? Reference.Custom)
                ?.let { types[it.value] }
                ?.shape?.value
                ?.map { EndpointShape.NamedTypedField(it.identifier.value, KotlinTypeMapper.map(it.reference)) }
                ?: emptyList()

            val modelImports = collectCustomNames(payloadRef).distinct()

            return ChannelShape(
                name = channel.identifier.value,
                payloadType = payloadType,
                payloadFields = payloadFields,
                modelImports = modelImports,
            )
        }

        private fun collectCustomNames(reference: Reference): List<String> = when (reference) {
            is Reference.Custom -> listOf(reference.value)
            is Reference.Iterable -> collectCustomNames(reference.reference)
            is Reference.Dict -> collectCustomNames(reference.reference)
            else -> emptyList()
        }
    }
}
```

- [ ] **Step 4: Run the test, verify PASS**

Run: `./gradlew :emitter:test --tests "io.kotest.extensions.spring.wirespec.emitter.ChannelShapeTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShape.kt \
        emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelShapeTest.kt
git commit -m "$(cat <<'EOF'
feat(emitter): add ChannelShape for Wirespec Channel AST nodes

Mirrors EndpointShape — extracts payload type + per-field shape
(for the future send(block) body builder) + model imports from a
compiler.core.parse.ast.Channel.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A2: ChannelDslFileEmitter + golden tests

**Files:**
- Create: `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitter.kt`
- Create: `emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitterTest.kt`
- Create: `emitter/src/test/resources/golden/SimplePayloadChannelDsl.kt`
- Create: `emitter/src/test/resources/golden/CustomPayloadChannelDsl.kt`

- [ ] **Step 1: Write the failing test**

Create `emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitterTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelDslFileEmitterTest : FunSpec({

    val pkg = PackageName("com.example.api")
    fun stringRef() = Reference.Primitive(Reference.Primitive.Type.String(null), false)

    test("simple-payload channel — only topic/send/expecting on the Call class") {
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("SimplePayloadChannel"),
            reference = stringRef(),
        )

        val emitted = ChannelDslFileEmitter.emit(channel, pkg)

        emitted.file shouldBe "com/example/api/kotest/SimplePayloadChannelDsl.kt"
        emitted.result shouldBe readGolden("SimplePayloadChannelDsl.kt")
    }

    test("custom-payload channel — payload body builder for send(block) overrides") {
        val petCreated = Type(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreated"),
            shape = Type.Shape(
                value = listOf(
                    Field(emptyList(), FieldIdentifier("id"), stringRef()),
                    Field(emptyList(), FieldIdentifier("name"), stringRef()),
                ),
            ),
            extends = emptyList(),
        )
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreatedChannel"),
            reference = Reference.Custom("PetCreated", false),
        )

        val emitted = ChannelDslFileEmitter.emit(channel, pkg, types = mapOf("PetCreated" to petCreated))

        emitted.file shouldBe "com/example/api/kotest/PetCreatedChannelDsl.kt"
        emitted.result shouldBe readGolden("CustomPayloadChannelDsl.kt")
    }
})

private fun readGolden(name: String): String =
    ChannelDslFileEmitterTest::class.java.classLoader.getResource("golden/$name")!!.readText()
```

- [ ] **Step 2: Run test, verify FAIL**

Run: `./gradlew :emitter:test --tests "io.kotest.extensions.spring.wirespec.emitter.ChannelDslFileEmitterTest"`
Expected: FAIL with "unresolved reference ChannelDslFileEmitter".

- [ ] **Step 3: Implement ChannelDslFileEmitter**

Create `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitter.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

object ChannelDslFileEmitter {

    fun emit(
        channel: Channel,
        packageName: PackageName,
        types: Map<String, Type> = emptyMap(),
    ): Emitted {
        val shape = ChannelShape.from(channel, types)
        val kotestPkg = "${packageName.value}.kotest"
        val channelPkg = "${packageName.value}.channel"
        val modelPkg = "${packageName.value}.model"
        val filePath = kotestPkg.replace('.', '/') + "/${shape.name}Dsl.kt"

        val irFile = file("${shape.name}Dsl") {
            `package`(kotestPkg)

            import("io.kotest.extensions.spring.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.spring.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.spring.wirespec.dsl", "WirespecScenarioDsl")
            import("io.kotest.property", "Arb")
            import("kotlin.time", "Duration")
            import(channelPkg, shape.name)
            shape.modelImports.forEach { import(modelPkg, it) }

            raw(renderExtensionFunction(shape))
            raw(renderCallClass(shape))
            if (shape.payloadFields.isNotEmpty()) {
                raw(renderPayloadBuilder(shape.payloadType, shape.payloadFields))
            }
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

    private fun renderExtensionFunction(shape: ChannelShape): String =
        "public val ScenarioBuilder.${shape.dslName}: ${shape.name}Call\n" +
            "    get() = ${shape.name}Call(this)"

    private fun renderCallClass(shape: ChannelShape): String = buildString {
        val call = "${shape.name}Call"
        val payload = shape.payloadType
        appendLine("@WirespecScenarioDsl")
        appendLine("public class $call internal constructor(scenario: ScenarioBuilder) {")
        appendLine("    @PublishedApi internal val inner = scenario.channel(${shape.name}::class)")
        appendLine()
        appendLine("    public fun topic(value: String): $call =")
        appendLine("        apply { inner.topic(value) }")
        appendLine("    public fun topic(ref: ResultRef<String>): $call =")
        appendLine("        apply { inner.topic { ref.require() } }")
        appendLine()
        appendLine("    public fun key(value: String): $call =")
        appendLine("        apply { inner.key(value) }")
        appendLine()
        appendLine("    public fun send(value: $payload): $call =")
        appendLine("        apply { inner.send(value) }")
        appendLine("    public fun send(arb: Arb<$payload>): $call =")
        appendLine("        apply { inner.send(arb) }")
        if (shape.payloadFields.isNotEmpty()) {
            appendLine("    public fun send(block: ${payload}PayloadBuilder.() -> Unit): $call = apply {")
            appendLine("        val builder = ${payload}PayloadBuilder().apply(block)")
            appendLine("        inner.send {")
            shape.payloadFields.forEach { f ->
                appendLine("            builder.${f.name}?.let { registerPath(\"${f.name}\") { it } }")
            }
            appendLine("        }")
            appendLine("    }")
        }
        appendLine()
        appendLine("    public fun expecting(): $call =")
        appendLine("        apply { inner.expecting() }")
        appendLine("    public fun expecting(block: ($payload) -> Unit): $call =")
        appendLine("        apply { inner.expecting(block) }")
        appendLine("    public fun collecting(count: Int, block: (List<$payload>) -> Unit): $call =")
        appendLine("        apply { inner.collecting(count, block) }")
        appendLine("    public fun collecting(duration: Duration, block: (List<$payload>) -> Unit): $call =")
        appendLine("        apply { inner.collecting(duration, block) }")
        appendLine()
        appendLine("    public fun <T> returning(projection: ($payload) -> T): ResultRef<T> =")
        append("        inner.returning(projection)\n}")
    }

    private fun renderPayloadBuilder(payloadType: String, fields: List<EndpointShape.NamedTypedField>): String = buildString {
        appendLine("@WirespecScenarioDsl")
        appendLine("public class ${payloadType}PayloadBuilder {")
        fields.forEach { f ->
            appendLine("    public var ${f.name}: Arb<${f.kotlinType}>? = null")
        }
        append("}")
    }
}
```

- [ ] **Step 4: Run test, see actual output, capture as golden**

Run: `./gradlew :emitter:test --tests "io.kotest.extensions.spring.wirespec.emitter.ChannelDslFileEmitterTest" --info`

Test fails because the goldens don't exist yet. We'll generate them from the emitter output. Add a temporary "println the result" branch to the test — but DON'T do that. Instead, generate the goldens by hand from the renderer specification above. Create them now:

Create `emitter/src/test/resources/golden/SimplePayloadChannelDsl.kt`:

```kotlin
package com.example.api.kotest

import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.property.Arb
import kotlin.time.Duration
import com.example.api.channel.SimplePayloadChannel

public val ScenarioBuilder.simplePayloadChannel: SimplePayloadChannelCall
    get() = SimplePayloadChannelCall(this)

@WirespecScenarioDsl
public class SimplePayloadChannelCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.channel(SimplePayloadChannel::class)

    public fun topic(value: String): SimplePayloadChannelCall =
        apply { inner.topic(value) }
    public fun topic(ref: ResultRef<String>): SimplePayloadChannelCall =
        apply { inner.topic { ref.require() } }

    public fun key(value: String): SimplePayloadChannelCall =
        apply { inner.key(value) }

    public fun send(value: String): SimplePayloadChannelCall =
        apply { inner.send(value) }
    public fun send(arb: Arb<String>): SimplePayloadChannelCall =
        apply { inner.send(arb) }

    public fun expecting(): SimplePayloadChannelCall =
        apply { inner.expecting() }
    public fun expecting(block: (String) -> Unit): SimplePayloadChannelCall =
        apply { inner.expecting(block) }
    public fun collecting(count: Int, block: (List<String>) -> Unit): SimplePayloadChannelCall =
        apply { inner.collecting(count, block) }
    public fun collecting(duration: Duration, block: (List<String>) -> Unit): SimplePayloadChannelCall =
        apply { inner.collecting(duration, block) }

    public fun <T> returning(projection: (String) -> T): ResultRef<T> =
        inner.returning(projection)
}
```

Create `emitter/src/test/resources/golden/CustomPayloadChannelDsl.kt`:

```kotlin
package com.example.api.kotest

import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.property.Arb
import kotlin.time.Duration
import com.example.api.channel.PetCreatedChannel
import com.example.api.model.PetCreated

public val ScenarioBuilder.petCreatedChannel: PetCreatedChannelCall
    get() = PetCreatedChannelCall(this)

@WirespecScenarioDsl
public class PetCreatedChannelCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.channel(PetCreatedChannel::class)

    public fun topic(value: String): PetCreatedChannelCall =
        apply { inner.topic(value) }
    public fun topic(ref: ResultRef<String>): PetCreatedChannelCall =
        apply { inner.topic { ref.require() } }

    public fun key(value: String): PetCreatedChannelCall =
        apply { inner.key(value) }

    public fun send(value: PetCreated): PetCreatedChannelCall =
        apply { inner.send(value) }
    public fun send(arb: Arb<PetCreated>): PetCreatedChannelCall =
        apply { inner.send(arb) }
    public fun send(block: PetCreatedPayloadBuilder.() -> Unit): PetCreatedChannelCall = apply {
        val builder = PetCreatedPayloadBuilder().apply(block)
        inner.send {
            builder.id?.let { registerPath("id") { it } }
            builder.name?.let { registerPath("name") { it } }
        }
    }

    public fun expecting(): PetCreatedChannelCall =
        apply { inner.expecting() }
    public fun expecting(block: (PetCreated) -> Unit): PetCreatedChannelCall =
        apply { inner.expecting(block) }
    public fun collecting(count: Int, block: (List<PetCreated>) -> Unit): PetCreatedChannelCall =
        apply { inner.collecting(count, block) }
    public fun collecting(duration: Duration, block: (List<PetCreated>) -> Unit): PetCreatedChannelCall =
        apply { inner.collecting(duration, block) }

    public fun <T> returning(projection: (PetCreated) -> T): ResultRef<T> =
        inner.returning(projection)
}
@WirespecScenarioDsl
public class PetCreatedPayloadBuilder {
    public var id: Arb<String>? = null
    public var name: Arb<String>? = null
}
```

- [ ] **Step 5: Run test, verify PASS**

Run: `./gradlew :emitter:test --tests "io.kotest.extensions.spring.wirespec.emitter.ChannelDslFileEmitterTest"`
Expected: PASS (2 tests). If golden mismatch surfaces a whitespace/newline diff, update the golden to match the emitter's output byte-for-byte — the goldens are the spec for the emitter, but a small tweak to match the generator's formatting is fine.

- [ ] **Step 6: Commit**

```bash
git add emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitter.kt \
        emitter/src/test/kotlin/io/kotest/extensions/spring/wirespec/emitter/ChannelDslFileEmitterTest.kt \
        emitter/src/test/resources/golden/SimplePayloadChannelDsl.kt \
        emitter/src/test/resources/golden/CustomPayloadChannelDsl.kt
git commit -m "$(cat <<'EOF'
feat(emitter): emit per-channel Kotest DSL alongside per-endpoint DSL

ChannelDslFileEmitter emits <Name>ChannelDsl.kt with topic/key slots,
send/expecting/collecting/returning, and a single-payload BodyBuilder
for send(block) overrides. Mirrors DslFileEmitter for endpoints.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A3: Wire TypesafeDslEmitter to dispatch channels

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/TypesafeDslEmitter.kt`

- [ ] **Step 1: Update TypesafeDslEmitter to filter and emit Channels**

Replace the file's contents with:

```kotlin
package io.kotest.extensions.spring.wirespec.emitter

import arrow.core.NonEmptyList
import community.flock.wirespec.compiler.core.emit.EmitShared
import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.compiler.core.parse.ast.AST
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.compiler.utils.Logger
import community.flock.wirespec.emitters.kotlin.KotlinIrEmitter

open class TypesafeDslEmitter(
    packageName: PackageName,
    emitShared: EmitShared,
) : KotlinIrEmitter(packageName, emitShared) {

    override fun emit(ast: AST, logger: Logger): NonEmptyList<Emitted> {
        val base = super.emit(ast, logger)
        val statements = ast.modules.toList().flatMap { it.statements.toList() }
        val types = statements.filterIsInstance<Type>().associateBy { it.identifier.value }

        val endpointDsl: List<Emitted> = statements
            .filterIsInstance<Endpoint>()
            .map { DslFileEmitter.emit(it, packageName, types) }
        val channelDsl: List<Emitted> = statements
            .filterIsInstance<Channel>()
            .map { ChannelDslFileEmitter.emit(it, packageName, types) }

        val extra = endpointDsl + channelDsl
        return if (extra.isEmpty()) base else NonEmptyList(base.head, base.tail + extra)
    }
}
```

- [ ] **Step 2: Run the full emitter test suite, verify PASS**

Run: `./gradlew :emitter:test`
Expected: PASS — existing endpoint goldens unchanged, new channel goldens cover Channel dispatch.

- [ ] **Step 3: Commit**

```bash
git add emitter/src/main/kotlin/io/kotest/extensions/spring/wirespec/emitter/TypesafeDslEmitter.kt
git commit -m "$(cat <<'EOF'
feat(emitter): dispatch Channel AST nodes through ChannelDslFileEmitter

TypesafeDslEmitter now filters both Endpoint and Channel statements
from the AST and feeds each to its respective DSL emitter. Endpoint
behaviour is unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase B: Runtime — MessageTransport + WirespecChannelContext

### Task B1: MessageTransport interface + records

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/MessageTransport.kt`

- [ ] **Step 1: Write the interface**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/MessageTransport.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.channel

import kotlin.time.Duration

/**
 * Raw-bytes messaging transport: parallel to [community.flock.wirespec.kotlin.Wirespec.Transportation]
 * but message-shaped instead of request/response-shaped. The runtime's
 * [community.flock.wirespec.kotlin.Wirespec.Serialization] handles typed
 * (de)serialization on top.
 *
 * `receive` returns as soon as `atLeast` records have been collected on
 * `topic`, OR `within` has elapsed — whichever happens first. Implementations
 * are responsible for honoring the timeout.
 */
interface MessageTransport {
    suspend fun publish(record: OutgoingRecord)
    suspend fun receive(
        topic: String,
        atLeast: Int,
        within: Duration,
    ): List<IncomingRecord>
}

data class OutgoingRecord(
    val topic: String,
    val key: String?,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is OutgoingRecord &&
        topic == other.topic && key == other.key && body.contentEquals(other.body)
    override fun hashCode(): Int = (topic.hashCode() * 31 + (key?.hashCode() ?: 0)) * 31 + body.contentHashCode()
}

data class IncomingRecord(
    val topic: String,
    val key: String?,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is IncomingRecord &&
        topic == other.topic && key == other.key && body.contentEquals(other.body)
    override fun hashCode(): Int = (topic.hashCode() * 31 + (key?.hashCode() ?: 0)) * 31 + body.contentHashCode()
}
```

- [ ] **Step 2: Confirm it compiles**

Run: `./gradlew :runtime:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/MessageTransport.kt
git commit -m "$(cat <<'EOF'
feat(runtime): add MessageTransport interface for channel tests

Raw-bytes counterpart to Wirespec.Transportation, plus OutgoingRecord
and IncomingRecord value types. receive(topic, atLeast, within) is the
single primitive both expecting() and collecting() will sit on top of.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B2: InMemoryMessageTransport + tests

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransport.kt`
- Create: `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransportTest.kt`

- [ ] **Step 1: Write failing tests**

Create `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransportTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.channel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class InMemoryMessageTransportTest : FunSpec({

    test("publish + receive — receive sees previously published records on the same topic") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("events", key = null, body = "one".toByteArray()))
            transport.publish(OutgoingRecord("events", key = null, body = "two".toByteArray()))

            val received = transport.receive("events", atLeast = 2, within = 1.seconds)

            received shouldHaveSize 2
            received.map { String(it.body) } shouldBe listOf("one", "two")
        }
    }

    test("receive — filters by topic") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("a", null, "hit".toByteArray()))
            transport.publish(OutgoingRecord("b", null, "miss".toByteArray()))

            val received = transport.receive("a", atLeast = 1, within = 1.seconds)

            received.map { String(it.body) } shouldBe listOf("hit")
        }
    }

    test("receive — returns early once atLeast records are seen") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("events", null, "one".toByteArray()))
            transport.publish(OutgoingRecord("events", null, "two".toByteArray()))

            val elapsed = measureTime {
                transport.receive("events", atLeast = 2, within = 10.seconds)
            }

            // Strict upper bound: short — records are already there.
            (elapsed < 1.seconds) shouldBe true
        }
    }

    test("receive — times out when fewer than atLeast records arrive") {
        val transport = InMemoryMessageTransport()
        runBlocking {
            transport.publish(OutgoingRecord("events", null, "only-one".toByteArray()))

            val elapsed = measureTime {
                val records = transport.receive("events", atLeast = 5, within = 200.milliseconds)
                records shouldHaveSize 1
            }
            (elapsed >= 200.milliseconds) shouldBe true
        }
    }
})
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.channel.InMemoryMessageTransportTest"`
Expected: FAIL with "unresolved reference InMemoryMessageTransport".

- [ ] **Step 3: Implement InMemoryMessageTransport**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransport.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.channel

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Deterministic fake [MessageTransport] for runtime unit tests.
 *
 *  - `publish` appends to a thread-safe in-memory log.
 *  - `receive` filters by topic, returning as soon as `atLeast` records have
 *    accumulated *or* `within` has elapsed. Polls the log at 10 ms intervals.
 *  - Per-call `consumedOffset` tracks where each receiver started so a single
 *    transport can be reused across many calls without re-seeing old records.
 *    (The runner uses a fresh transport per scenario, but this also keeps the
 *    fake honest for any test that reuses one.)
 */
class InMemoryMessageTransport : MessageTransport {

    private val log: MutableList<IncomingRecord> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile private var receiverCursor: Int = 0

    override suspend fun publish(record: OutgoingRecord) {
        log += IncomingRecord(record.topic, record.key, record.body)
    }

    override suspend fun receive(topic: String, atLeast: Int, within: Duration): List<IncomingRecord> {
        val mark = TimeSource.Monotonic.markNow()
        val collected = mutableListOf<IncomingRecord>()
        val start = receiverCursor
        var cursor = start
        while (collected.size < atLeast && mark.elapsedNow() < within) {
            synchronized(log) {
                while (cursor < log.size) {
                    val r = log[cursor]
                    cursor++
                    if (r.topic == topic) collected += r
                }
            }
            if (collected.size >= atLeast) break
            delay(10.milliseconds)
        }
        receiverCursor = cursor
        return collected.toList()
    }

    /** Snapshot of all published records (any topic), for debugging in tests. */
    fun published(): List<OutgoingRecord> = synchronized(log) {
        log.map { OutgoingRecord(it.topic, it.key, it.body) }
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.channel.InMemoryMessageTransportTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransport.kt \
        runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/channel/InMemoryMessageTransportTest.kt
git commit -m "$(cat <<'EOF'
feat(runtime): add InMemoryMessageTransport fake for unit tests

Deterministic implementation of MessageTransport: in-memory log,
per-receiver cursor, 10 ms poll interval, honors receive() timeout.
Used by ScenarioRunner channel tests so the unit suite doesn't need
a real broker.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B3: WirespecChannelContext

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecChannelContext.kt`

- [ ] **Step 1: Write the class**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecChannelContext.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.channel.MessageTransport

/**
 * Framework-neutral handle for the channel half of the scenario DSL: a
 * [MessageTransport] for publishing/receiving raw records and a
 * [Wirespec.Serialization] for typed (de)serialization of channel payloads.
 *
 * Build it directly when you already have both halves, or — for an
 * EmbeddedKafka-backed Spring test — use the companion factory in
 * `io.kotest.extensions.spring.wirespec.channel.embeddedKafkaChannelContext`.
 */
class WirespecChannelContext(
    val messaging: MessageTransport,
    val serialization: Wirespec.Serialization,
)
```

- [ ] **Step 2: Confirm it compiles**

Run: `./gradlew :runtime:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/WirespecChannelContext.kt
git commit -m "$(cat <<'EOF'
feat(runtime): add WirespecChannelContext for channel scenarios

Sibling to WirespecTestContext: pairs a MessageTransport with a
Wirespec.Serialization so channel calls in a scenario can publish and
receive typed payloads.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C: Runtime DSL — Step, ChannelReflection, ChannelCallBuilder, ScenarioBuilder

### Task C1: Step sealed type + ScenarioBuilder refactor

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt` (iteration over `steps`)
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt` (clearRefs target)

> **Why combined:** `calls → steps` touches the builder, the runner's iterator, and `clearRefs`. Splitting these into separate commits would leave the tree uncompilable between commits.

- [ ] **Step 1: Create Step.kt**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

/**
 * One declared step inside a scenario. Endpoint steps and channel steps
 * interleave in declaration order; the runner dispatches on this type.
 */
sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
    data class Channel(val call: ChannelCallBuilder<*>) : Step()
}
```

This references `ChannelCallBuilder` which doesn't exist yet — that's fine, we'll fill it in Task C3. For now, comment the Channel data class out and uncomment in C3. Use this stub:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

/**
 * One declared step inside a scenario. Endpoint steps and channel steps
 * interleave in declaration order; the runner dispatches on this type.
 *
 * Step.Channel is added by Task C3 when ChannelCallBuilder lands.
 */
sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
}
```

- [ ] **Step 2: Update ScenarioBuilder — rename `calls` → `steps`, register `Endpoint` steps**

Replace `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec

@WirespecScenarioDsl
class ScenarioBuilder internal constructor(
    val arb: ArbReceiver,
) {

    internal val steps: MutableList<Step> = mutableListOf()

    fun <BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> endpoint(
        client: Wirespec.Client<Req, Resp>,
        endpointObject: Wirespec.Endpoint,
    ): EndpointCallBuilder<BodyT, Req, Resp> =
        EndpointCallBuilder(this, client, endpointObject)

    internal fun register(call: EndpointCallBuilder<*, *, *>) {
        steps += Step.Endpoint(call)
    }

    internal fun clearRefs() {
        steps.forEach { step ->
            when (step) {
                is Step.Endpoint -> step.call.returnedRef?.clear()
            }
        }
    }
}
```

- [ ] **Step 3: Update ScenarioRunner.run() to iterate over `steps`**

In `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt`, replace the `run()` method:

```kotlin
fun run() {
    for ((index, step) in scenario.steps.withIndex()) {
        when (step) {
            is Step.Endpoint -> runOne(step.call, index)
        }
    }
}
```

Add the `Step` import at the top:

```kotlin
import io.kotest.extensions.spring.wirespec.dsl.Step
```

(No change to `runOne` — it still operates on `EndpointCallBuilder` directly.)

- [ ] **Step 4: Confirm `Scenario.kt`'s `clearRefs` block still compiles**

`Scenario.kt` calls `builder.clearRefs()` — the call site is unchanged; the method's internals now walk `steps`. No edit needed.

- [ ] **Step 5: Run existing tests, verify still PASS**

Run: `./gradlew :runtime:test`
Expected: PASS — all existing endpoint tests untouched semantically. The `calls` rename is internal.

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt
git commit -m "$(cat <<'EOF'
refactor(runtime): replace ScenarioBuilder.calls with sealed steps list

Steps are sealed Step.Endpoint (today) and Step.Channel (added in a
later commit). ScenarioRunner.run() now dispatches via when-on-Step;
behaviour is unchanged for existing endpoint scenarios.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task C2: ChannelReflection

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelReflection.kt`

- [ ] **Step 1: Write the class**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelReflection.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.starProjectedType

/**
 * Minimal reflection over a generated `Wirespec.Channel` class. The generated
 * channel is just `fun interface <Name>Channel { operator fun invoke(message: T) }`
 * (see KotlinChannelDefinitionEmitter), so the only thing we need to recover at
 * runtime is the payload type.
 */
@PublishedApi
internal class ChannelReflection private constructor(
    val channelName: String,
    val payloadType: KType,
) {
    companion object {
        private val cache = ConcurrentHashMap<KClass<out Wirespec.Channel>, ChannelReflection>()

        fun of(channelClass: KClass<out Wirespec.Channel>): ChannelReflection =
            cache.computeIfAbsent(channelClass) { introspect(it) }

        private fun introspect(cls: KClass<out Wirespec.Channel>): ChannelReflection {
            val invoke = cls.java.declaredMethods.firstOrNull { it.name == "invoke" }
                ?: error("${cls.simpleName}: no `invoke(message: …)` method found. " +
                    "Is this a Wirespec-generated channel?")
            val param = invoke.parameters.firstOrNull()
                ?: error("${cls.simpleName}.invoke has no parameters. Unexpected channel shape.")
            // KType is approximate (loses generic args from reflection) — but
            // Wirespec.Serialization handles erased List<X> by accepting the raw
            // List class. For non-generic payloads this is exact.
            val payloadKType = param.parameterizedType.let { t ->
                // Best-effort: pick the runtime class and treat it as star-projected.
                val rawClass = when (t) {
                    is Class<*> -> t.kotlin
                    is java.lang.reflect.ParameterizedType -> (t.rawType as Class<*>).kotlin
                    else -> error("${cls.simpleName}: unexpected parameter type $t")
                }
                rawClass.starProjectedType
            }
            return ChannelReflection(
                channelName = cls.simpleName ?: cls.java.name,
                payloadType = payloadKType,
            )
        }
    }
}
```

- [ ] **Step 2: Confirm it compiles**

Run: `./gradlew :runtime:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelReflection.kt
git commit -m "$(cat <<'EOF'
feat(runtime): add ChannelReflection — payload-type recovery for Channel

The generated Wirespec channel is just a fun interface with one
invoke(message: T) method, so reflection only needs to extract the
payload type. Cached per-class.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task C3: ChannelCallBuilder + tests

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilder.kt`
- Create: `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilderTest.kt`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt` — un-stub Step.Channel
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt` — add `.channel(...)`

- [ ] **Step 1: Update Step.kt — add Step.Channel**

Replace `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
    data class Channel(val call: ChannelCallBuilder<*>) : Step()
}
```

- [ ] **Step 2: Write failing tests**

Create `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilderTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.RandomSource

// Minimal generated-channel stand-in: a `fun interface` with one invoke(message: T).
fun interface PetCreatedChannelStub : Wirespec.Channel {
    operator fun invoke(message: String)
}

class ChannelCallBuilderTest : FunSpec({

    val rs = RandomSource.seeded(0L)

    test("topic(value) sets a literal topic input") {
        val scenario = ScenarioBuilder(ArbReceiver(rs))
        val call = scenario.channel(PetCreatedChannelStub::class)
        call.topic("pets.events")

        call.topicInput shouldBe Input.Literal("pets.events")
        scenario.steps.size shouldBe 1
    }

    test("topic(ref) sets a lazy topic input that reads from the ResultRef") {
        val ref = ResultRef<String>("upstream-topic").also { it.set("dynamic.topic") }
        val call = ScenarioBuilder(ArbReceiver(rs)).channel(PetCreatedChannelStub::class)
        call.topic { ref.require() }

        (call.topicInput as Input.Lazy).builder() shouldBe "dynamic.topic"
    }

    test("send(value) and expecting(block) on the same call — configuration error") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel(PetCreatedChannelStub::class)
        call.topic("t").send("payload")

        val ex = runCatching { call.expecting<String> { } }.exceptionOrNull()
            ?: error("expected configuration error")
        ex.message!! shouldContain "cannot set both `send` and `expecting`"
    }

    test("expecting(block) then send(value) — same configuration error in the opposite order") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel(PetCreatedChannelStub::class)
        call.topic("t").expecting<String> { }

        val ex = runCatching { call.send("payload") }.exceptionOrNull()
            ?: error("expected configuration error")
        ex.message!! shouldContain "cannot set both `send` and `expecting`"
    }

    test("collecting(count) sets the right receive policy") {
        val call = ScenarioBuilder(ArbReceiver(rs)).channel(PetCreatedChannelStub::class)
        call.topic("t").collecting<String>(count = 3) { }

        val (atLeast, _) = call.receivePolicy()
        atLeast shouldBe 3
    }
})
```

- [ ] **Step 3: Run, verify FAIL**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.dsl.ChannelCallBuilderTest"`
Expected: FAIL with "unresolved reference: channel" on ScenarioBuilder, "ChannelCallBuilder" missing.

- [ ] **Step 4: Implement ChannelCallBuilder**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilder.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.validation.ChannelReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@WirespecScenarioDsl
class ChannelCallBuilder<MessageT : Any> internal constructor(
    private val scenario: ScenarioBuilder,
    channelClass: KClass<out Wirespec.Channel>,
) {

    @PublishedApi
    internal val reflection: ChannelReflection = ChannelReflection.of(channelClass)

    internal var topicInput: Input<String>? = null
    internal var keyInput: Input<String>? = null

    internal var sendInput: Input<Any>? = null

    internal var direction: Direction? = null
    internal var expectedClass: KClass<*>? = null
    internal var customAssertion: ((Any) -> Unit)? = null

    internal var collectMode: CollectMode? = null

    internal var returningProjection: ((Any) -> Any?)? = null
    internal var returnedRef: ResultRef<Any?>? = null

    init {
        scenario.register(this)
    }

    fun topic(value: String): ChannelCallBuilder<MessageT> = apply {
        topicInput = Input.Literal(value)
    }

    fun topic(builder: () -> String): ChannelCallBuilder<MessageT> = apply {
        topicInput = Input.Lazy(builder)
    }

    fun key(value: String): ChannelCallBuilder<MessageT> = apply {
        keyInput = Input.Literal(value)
    }

    fun send(value: MessageT): ChannelCallBuilder<MessageT> = apply {
        requireNotExpecting()
        sendInput = Input.Literal(value as Any)
        direction = Direction.Send
    }

    fun send(arb: Arb<MessageT>): ChannelCallBuilder<MessageT> = apply {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.FromArb(arb as Arb<Any>)
        direction = Direction.Send
    }

    inline fun <reified R : MessageT> expecting(noinline block: (R) -> Unit): ChannelCallBuilder<MessageT> =
        expecting(R::class, block)

    fun <R : MessageT> expecting(messageClass: KClass<R>, block: (R) -> Unit): ChannelCallBuilder<MessageT> = apply {
        requireNotSending()
        direction = Direction.Expect
        expectedClass = messageClass
        @Suppress("UNCHECKED_CAST")
        customAssertion = { msg -> block(msg as R) }
    }

    /** Single-message expect without an assertion block. */
    fun expecting(): ChannelCallBuilder<MessageT> = apply {
        requireNotSending()
        direction = Direction.Expect
        customAssertion = null
    }

    inline fun <reified R : MessageT> collecting(count: Int, noinline block: (List<R>) -> Unit): ChannelCallBuilder<MessageT> =
        collecting(R::class, CollectMode.ByCount(count), block)

    inline fun <reified R : MessageT> collecting(duration: Duration, noinline block: (List<R>) -> Unit): ChannelCallBuilder<MessageT> =
        collecting(R::class, CollectMode.ByDuration(duration), block)

    fun <R : MessageT> collecting(messageClass: KClass<R>, mode: CollectMode, block: (List<R>) -> Unit): ChannelCallBuilder<MessageT> = apply {
        requireNotSending()
        direction = Direction.Collect
        expectedClass = messageClass
        collectMode = mode
        @Suppress("UNCHECKED_CAST")
        customAssertion = { list -> block(list as List<R>) }
    }

    inline fun <reified R : MessageT, T> returning(noinline projection: (R) -> T): ResultRef<T> =
        returning(R::class, projection)

    fun <R : MessageT, T> returning(messageClass: KClass<R>, projection: (R) -> T): ResultRef<T> {
        // Default direction to Expect if not yet set — `returning` on a send call
        // captures the sent payload (handled by the runner).
        if (direction == null) {
            direction = Direction.Expect
            expectedClass = messageClass
        }
        val ref = ResultRef<T>(label = "${reflection.channelName}.${messageClass.simpleName ?: "msg"}")
        @Suppress("UNCHECKED_CAST")
        returnedRef = ref as ResultRef<Any?>
        returningProjection = { msg ->
            @Suppress("UNCHECKED_CAST")
            projection(msg as R)
        }
        return ref
    }

    /** Compute (atLeast, within) — used by the runner. */
    internal fun receivePolicy(): Pair<Int, Duration> = when (val m = collectMode) {
        is CollectMode.ByCount -> m.count to (m.count.coerceAtLeast(1).seconds)
        is CollectMode.ByDuration -> 0 to m.duration
        null -> 1 to 2.seconds
    }

    private fun requireNotSending() = check(direction != Direction.Send) {
        "Channel ${reflection.channelName}: cannot set both `send` and `expecting`/`collecting` on the same call."
    }

    private fun requireNotExpecting() {
        val d = direction
        check(d != Direction.Expect && d != Direction.Collect) {
            "Channel ${reflection.channelName}: cannot set both `send` and `expecting`/`collecting` on the same call."
        }
    }

    enum class Direction { Send, Expect, Collect }

    sealed class CollectMode {
        data class ByCount(val count: Int) : CollectMode()
        data class ByDuration(val duration: Duration) : CollectMode()
    }
}
```

- [ ] **Step 5: Add `.channel(...)` to ScenarioBuilder**

Edit `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt`. Replace its body with:

```kotlin
package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import kotlin.reflect.KClass

@WirespecScenarioDsl
class ScenarioBuilder internal constructor(
    val arb: ArbReceiver,
) {

    internal val steps: MutableList<Step> = mutableListOf()

    fun <BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> endpoint(
        client: Wirespec.Client<Req, Resp>,
        endpointObject: Wirespec.Endpoint,
    ): EndpointCallBuilder<BodyT, Req, Resp> =
        EndpointCallBuilder(this, client, endpointObject)

    fun <MessageT : Any> channel(channelClass: KClass<out Wirespec.Channel>): ChannelCallBuilder<MessageT> =
        ChannelCallBuilder(this, channelClass)

    internal fun register(call: EndpointCallBuilder<*, *, *>) {
        steps += Step.Endpoint(call)
    }

    internal fun register(call: ChannelCallBuilder<*>) {
        steps += Step.Channel(call)
    }

    internal fun clearRefs() {
        steps.forEach { step ->
            when (step) {
                is Step.Endpoint -> step.call.returnedRef?.clear()
                is Step.Channel -> step.call.returnedRef?.clear()
            }
        }
    }
}
```

- [ ] **Step 6: Run, verify PASS**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.dsl.ChannelCallBuilderTest"`
Expected: PASS (5 tests).

Run full runtime suite: `./gradlew :runtime:test`
Expected: PASS — no regressions on existing endpoint tests.

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilder.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/Step.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/dsl/ScenarioBuilder.kt \
        runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/dsl/ChannelCallBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(runtime): ChannelCallBuilder + ScenarioBuilder.channel(...)

Per-channel DSL surface: topic / key / send / expecting / collecting /
returning, with mutual-exclusion guards between send and expecting,
and a receivePolicy() helper for the runner.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase D: Channel validation

### Task D1: ChannelValidator + tests

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidator.kt`
- Create: `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidatorTest.kt`

- [ ] **Step 1: Write failing tests**

Create `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidatorTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.validation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

fun interface PrimitiveChannelStub : Wirespec.Channel {
    operator fun invoke(message: String)
}

class ChannelValidatorTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val reflection = ChannelReflection.of(PrimitiveChannelStub::class)

    test("deserializes a well-formed payload") {
        val validator = ChannelValidator(reflection, serialization)
        val bytes = serialization.serializeBody("hello", reflection.payloadType)

        val typed = validator.deserialize(bytes)

        typed shouldBe "hello"
    }

    test("malformed payload — fails with channel-name + raw body in the message") {
        val validator = ChannelValidator(reflection, serialization)
        val malformed = "not a JSON string".toByteArray()

        val ex = runCatching { validator.deserialize(malformed) }.exceptionOrNull()
            ?: error("expected ChannelViolation")
        ex.message!!.let { msg ->
            msg shouldContain "PrimitiveChannelStub"
            msg shouldContain "not a JSON string"
        }
    }
})
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.validation.ChannelValidatorTest"`
Expected: FAIL with "unresolved reference: ChannelValidator".

- [ ] **Step 3: Implement ChannelValidator**

Create `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidator.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.validation

import community.flock.wirespec.kotlin.Wirespec

/**
 * Mirrors [ContractValidator] for channels — minus the status check.
 * Channels carry a single payload type, so validation is just:
 * "do the bytes deserialize into the declared payload?"
 */
internal class ChannelValidator(
    private val reflection: ChannelReflection,
    private val serialization: Wirespec.Serialization,
) {
    fun deserialize(body: ByteArray): Any {
        return try {
            serialization.deserializeBody(body, reflection.payloadType)
        } catch (t: Throwable) {
            throw ChannelViolation(
                channel = reflection.channelName,
                message = "payload did not match ${reflection.payloadType}: ${t.message ?: t::class.simpleName}",
                rawBody = body,
                cause = t,
            )
        }
    }
}

class ChannelViolation internal constructor(
    val channel: String,
    message: String,
    val rawBody: ByteArray?,
    cause: Throwable? = null,
) : AssertionError(
    buildString {
        append("[$channel] ")
        append(message)
        rawBody?.let { body ->
            append("\n  body=")
            append(String(body).take(2048))
        }
    },
    cause,
)
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.validation.ChannelValidatorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidator.kt \
        runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/validation/ChannelValidatorTest.kt
git commit -m "$(cat <<'EOF'
feat(runtime): ChannelValidator — payload-deserialize check for channels

Sibling to ContractValidator without the status half. Failures throw
ChannelViolation with channel name + raw body, same shape as
ContractViolation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase E: Runner — runChannel dispatch

### Task E1: ScenarioRunner takes channelCtx and handles Send direction

**Files:**
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt`

> **Why combined:** `Scenario.runScenarioOnce` constructs the `ScenarioRunner`. Adding a constructor parameter forces the call site to pass it.

- [ ] **Step 1: Update Scenario.kt — thread channelCtx through (no-arg default = null)**

Replace `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec

import io.kotest.extensions.spring.wirespec.dsl.ArbReceiver
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.runtime.ScenarioRunner
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource

/**
 * Run a single iteration of the scenario DSL against [endpointCtx] (and
 * optionally [channelCtx] for channel steps).
 */
suspend fun PropertyContext.scenario(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    block: ScenarioBuilder.() -> Unit,
) {
    val rs = randomSource()
    runScenarioOnce(endpointCtx, channelCtx, rs, block)
}

/**
 * Single-run overload for one-shot scenarios outside `checkAll`.
 */
suspend fun scenario(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    seed: Long = System.nanoTime(),
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(seed), block)
}

internal fun runScenarioOnce(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext?,
    rs: RandomSource,
    block: ScenarioBuilder.() -> Unit,
) {
    val arb = ArbReceiver(rs)
    val builder = ScenarioBuilder(arb).apply(block)
    try {
        ScenarioRunner(
            scenario = builder,
            endpointCtx = endpointCtx,
            channelCtx = channelCtx,
            randomSource = rs,
            arbReceiver = arb,
        ).run()
    } finally {
        builder.clearRefs()
    }
}
```

- [ ] **Step 2: Update ScenarioRunner — accept the two contexts; add runChannel for Send**

Edit `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt`:

Constructor: replace the current single-`transportation`/`serialization` params with:

```kotlin
internal class ScenarioRunner(
    private val scenario: ScenarioBuilder,
    private val endpointCtx: WirespecTestContext,
    private val channelCtx: WirespecChannelContext?,
    private val randomSource: RandomSource,
    private val arbReceiver: ArbReceiver,
) {
```

Replace every internal reference to `transportation` with `endpointCtx.transportation`, and to `serialization` with `endpointCtx.serialization`. Update `run()` and add `runChannel` (Send path only here; Expect/Collect arrives in Task E2):

```kotlin
fun run() {
    for ((index, step) in scenario.steps.withIndex()) {
        when (step) {
            is Step.Endpoint -> runOne(step.call, index)
            is Step.Channel -> runChannel(step.call, index)
        }
    }
}

private fun runChannel(call: ChannelCallBuilder<*>, index: Int) {
    val ctx = channelCtx ?: error(
        "Scenario step #${index + 1} (${call.reflection.channelName}) requires a channel context. " +
            "Pass channelCtx to scenario(...) (or annotate the spec with @EmbeddedKafka and override " +
            "SpringWirespecSpec.channelCtx)."
    )
    val topic = call.topicInput?.resolve(randomSource)
        ?: error("Scenario step #${index + 1} (${call.reflection.channelName}): .topic(...) is required.")
    val key = call.keyInput?.resolve(randomSource)

    when (call.direction) {
        ChannelCallBuilder.Direction.Send -> {
            val payload = call.sendInput?.resolve(randomSource)
                ?: error("Scenario step #${index + 1} (${call.reflection.channelName}): " +
                    ".send(...) value not set.")
            val bytes = ctx.serialization.serializeBody(payload, call.reflection.payloadType)
            kotlinx.coroutines.runBlocking {
                ctx.messaging.publish(io.kotest.extensions.spring.wirespec.channel.OutgoingRecord(topic, key, bytes))
            }
            call.returningProjection?.let { proj ->
                @Suppress("UNCHECKED_CAST")
                val ref = call.returnedRef as ResultRef<Any?>
                ref.set(proj.invoke(payload))
            }
        }
        ChannelCallBuilder.Direction.Expect,
        ChannelCallBuilder.Direction.Collect ->
            error("Scenario step #${index + 1} (${call.reflection.channelName}): " +
                "receive direction not yet supported in this commit.")
        null -> error("Scenario step #${index + 1} (${call.reflection.channelName}): " +
            "set .send(...) or .expecting()/.collecting(...) before running the scenario.")
    }
}
```

Add the missing imports at the top of the file:

```kotlin
import io.kotest.extensions.spring.wirespec.WirespecChannelContext
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.dsl.ChannelCallBuilder
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.Step
```

Update `SpringWirespecSpec` to compile against the new `runScenarioOnce`. In `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt`, both call sites of `runScenarioOnce` now need a `channelCtx` arg. For now, pass `null`:

```kotlin
if (iterations <= 1) {
    runScenarioOnce(defaultCtx, null, RandomSource.seeded(System.nanoTime()), body)
} else {
    checkAll<Int>(iterations = iterations) {
        runScenarioOnce(defaultCtx, null, randomSource(), body)
    }
}
```

(The rename to `endpointCtx` arrives in Task F1.)

- [ ] **Step 3: Run full runtime suite, verify still PASS**

Run: `./gradlew :runtime:test`
Expected: PASS — no behavioural change to existing endpoint tests; channel Send works.

- [ ] **Step 4: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/Scenario.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt
git commit -m "$(cat <<'EOF'
feat(runtime): ScenarioRunner accepts channelCtx and runs Send steps

Scenario.scenario(endpointCtx, channelCtx, ...) is the new public
shape. Send-direction channel steps serialize the payload via
ctx.serialization and publish via ctx.messaging. Expect/Collect
support follows in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task E2: Expect + Collect direction; integration test with InMemoryMessageTransport

**Files:**
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt`
- Create: `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunnerChannelTest.kt`

- [ ] **Step 1: Write failing tests**

Create `runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunnerChannelTest.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.wirespec.WirespecChannelContext
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.channel.InMemoryMessageTransport
import io.kotest.extensions.spring.wirespec.scenario
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

fun interface GreetingChannelStub : Wirespec.Channel {
    operator fun invoke(message: String)
}

class ScenarioRunnerChannelTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val noopHttp = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no endpoint step in this test")
    }
    val httpCtx = WirespecTestContext(noopHttp, serialization)

    test("send then expecting on the same topic — round-trip via InMemory transport") {
        val transport = InMemoryMessageTransport()
        val channelCtx = WirespecChannelContext(transport, serialization)

        var received: String? = null
        scenario(httpCtx, channelCtx, seed = 1L) {
            channel<String>(GreetingChannelStub::class)
                .topic("greetings").send("hi")
            channel<String>(GreetingChannelStub::class)
                .topic("greetings").expecting<String> { received = it }
        }

        received shouldBe "hi"
    }

    test("expecting times out and reports surplus/zero records") {
        val transport = InMemoryMessageTransport()
        val channelCtx = WirespecChannelContext(transport, serialization)

        val ex = runCatching {
            scenario(httpCtx, channelCtx, seed = 1L) {
                channel<String>(GreetingChannelStub::class)
                    .topic("never-published").expecting<String> { }
            }
        }.exceptionOrNull() ?: error("expected timeout assertion error")

        ex.message!! shouldContain "expected exactly 1 message"
    }

    test("collecting(count) gathers exactly that many records") {
        val transport = InMemoryMessageTransport()
        val channelCtx = WirespecChannelContext(transport, serialization)

        var collected: List<String>? = null
        scenario(httpCtx, channelCtx, seed = 1L) {
            channel<String>(GreetingChannelStub::class).topic("t").send("a")
            channel<String>(GreetingChannelStub::class).topic("t").send("b")
            channel<String>(GreetingChannelStub::class).topic("t").send("c")
            channel<String>(GreetingChannelStub::class).topic("t").collecting<String>(count = 3) {
                collected = it
            }
        }

        collected shouldBe listOf("a", "b", "c")
    }
})
```

Note: This uses `channel<String>(GreetingChannelStub::class)` — the ScenarioBuilder helper returns `ChannelCallBuilder<MessageT>`. The reified version is added in the generated DSL, but for tests we use the explicit form.

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.runtime.ScenarioRunnerChannelTest"`
Expected: FAIL — receive direction not yet supported.

- [ ] **Step 3: Implement Expect + Collect in ScenarioRunner.runChannel**

Replace the `ChannelCallBuilder.Direction.Expect, ChannelCallBuilder.Direction.Collect ->` branch in `runChannel`:

```kotlin
ChannelCallBuilder.Direction.Expect,
ChannelCallBuilder.Direction.Collect -> {
    val (atLeast, within) = call.receivePolicy()
    val records = kotlinx.coroutines.runBlocking {
        ctx.messaging.receive(topic, atLeast, within)
    }
    val validator = io.kotest.extensions.spring.wirespec.validation.ChannelValidator(call.reflection, ctx.serialization)
    val typed = records.map { rec ->
        try {
            validator.deserialize(rec.body)
        } catch (t: Throwable) {
            throw AssertionError(
                "Scenario step #${index + 1} (${call.reflection.channelName}) failed to decode " +
                    "record on topic '$topic': ${t.message}",
                t,
            )
        }
    }
    if (call.direction == ChannelCallBuilder.Direction.Expect) {
        val one = typed.singleOrNull()
            ?: throw AssertionError(
                "Scenario step #${index + 1} (${call.reflection.channelName}): " +
                    "expected exactly 1 message on '$topic' within $within, got ${typed.size}."
            )
        call.customAssertion?.invoke(one)
        call.returningProjection?.let { proj ->
            @Suppress("UNCHECKED_CAST")
            val ref = call.returnedRef as ResultRef<Any?>
            ref.set(proj.invoke(one))
        }
    } else {
        call.customAssertion?.invoke(typed)
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :runtime:test --tests "io.kotest.extensions.spring.wirespec.runtime.ScenarioRunnerChannelTest"`
Expected: PASS (3 tests).

Full suite: `./gradlew :runtime:test`
Expected: PASS overall.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunner.kt \
        runtime/src/test/kotlin/io/kotest/extensions/spring/wirespec/runtime/ScenarioRunnerChannelTest.kt
git commit -m "$(cat <<'EOF'
feat(runtime): ScenarioRunner handles Expect/Collect channel directions

Pulls from MessageTransport.receive(), deserializes each record via
ChannelValidator, and runs the user assertion. Expect requires exactly
one; collecting respects the count/duration mode set on the call.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase F: SpringWirespecSpec rename + channelCtx

### Task F1: Rename defaultCtx → endpointCtx; add channelCtx

**Files:**
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt`
- Modify: `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetScenariosSpec.kt` — only if it explicitly overrides `defaultCtx`. Read first.

- [ ] **Step 1: Read PetScenariosSpec to confirm no override**

Run: `grep -n "defaultCtx" example/src/test/kotlin example/src/main maven-plugin/src runtime/src`

If any non-doc usage of `defaultCtx` is found, update it to `endpointCtx` in the same commit.

- [ ] **Step 2: Update SpringWirespecSpec**

Replace the file:

```kotlin
package io.kotest.extensions.spring.wirespec

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.wirespec.channel.EmbeddedKafkaMessageTransport
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.kotest.SpringSpecExtension
import io.kotest.extensions.spring.wirespec.spring.MockMvcTransportation
import io.kotest.property.RandomSource
import io.kotest.property.checkAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc

abstract class SpringWirespecSpec(body: SpringWirespecSpec.() -> Unit = {}) : FunSpec() {

    @Autowired
    protected lateinit var applicationContext: ApplicationContext

    init {
        extension(SpringSpecExtension)
        body()
    }

    /** Default endpoint context — MockMvc-backed, resolved from the Spring container. */
    open val endpointCtx: WirespecTestContext by lazy {
        val mvc = applicationContext.getBeanProvider(MockMvc::class.java).getIfAvailable()
            ?: error(
                "No MockMvc bean is available on the Spring ApplicationContext. " +
                    "Annotate the spec with @AutoConfigureMockMvc (or use @WebMvcTest), " +
                    "or override `endpointCtx` to supply a custom WirespecTestContext.",
            )
        WirespecTestContext(
            transportation = MockMvcTransportation(mvc),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    /**
     * Default channel context — EmbeddedKafka-backed when the spec carries
     * `@EmbeddedKafka` and `spring-kafka` is on the classpath. `null`
     * otherwise; channel steps against a null context fail fast in the runner.
     *
     * Override to wire a different transport (e.g. Testcontainers).
     */
    open val channelCtx: WirespecChannelContext? by lazy {
        runCatching {
            WirespecChannelContext(
                messaging = EmbeddedKafkaMessageTransport(applicationContext),
                serialization = WirespecSerialization(jacksonObjectMapper()),
            )
        }.getOrNull()
    }

    fun test(name: String, iterations: Int = 1, body: ScenarioBuilder.() -> Unit) {
        super.test(name) {
            if (iterations <= 1) {
                runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(System.nanoTime()), body)
            } else {
                checkAll<Int>(iterations = iterations) {
                    runScenarioOnce(endpointCtx, channelCtx, randomSource(), body)
                }
            }
        }
    }
}

fun ScenarioBuilder.wirespec(
    ctx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    block: ScenarioBuilder.() -> Unit,
) {
    runScenarioOnce(ctx, channelCtx, RandomSource.seeded(System.nanoTime()), block)
}
```

Note this **forward-references `EmbeddedKafkaMessageTransport`**, which arrives in Phase G. Until then, replace its instantiation with `error("EmbeddedKafkaMessageTransport not yet implemented")` and rely on `runCatching` to swallow that into `null`. That keeps the file compiling.

Use this stub for the channelCtx block until Phase G:

```kotlin
open val channelCtx: WirespecChannelContext? by lazy {
    // EmbeddedKafkaMessageTransport is wired in Phase G.
    null
}
```

- [ ] **Step 3: Run runtime tests, verify still PASS**

Run: `./gradlew :runtime:test`
Expected: PASS — internal rename, callers in this module updated.

- [ ] **Step 4: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt
# plus any consumer-side files updated in Step 1
git commit -m "$(cat <<'EOF'
refactor(runtime)!: rename SpringWirespecSpec.defaultCtx to endpointCtx

Symmetrical with the new channelCtx slot. Breaking change for any
subclass that overrides defaultCtx — public API.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase G: EmbeddedKafkaMessageTransport

### Task G1: Add spring-kafka deps to runtime

**Files:**
- Modify: `runtime/build.gradle.kts`

- [ ] **Step 1: Add the two deps**

Edit `runtime/build.gradle.kts`. After the existing `api("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")` line, before the `jakarta.servlet-api` block, add:

```kotlin
    // Spring Kafka — only the transport types (KafkaProducer / KafkaConsumer
    // through spring-kafka's transitive kafka-clients dep, EmbeddedKafkaBroker
    // via spring-kafka-test). Kept compileOnly so HTTP-only consumers of this
    // library don't drag spring-kafka onto their test classpath. Consumers who
    // write channel tests add spring-kafka(-test) themselves.
    compileOnly("org.springframework.kafka:spring-kafka:3.3.0")
    compileOnly("org.springframework.kafka:spring-kafka-test:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka:3.3.0")
    testImplementation("org.springframework.kafka:spring-kafka-test:3.3.0")
```

(Version 3.3.0 is the spring-kafka release that aligns with Spring Boot 3.4. If `./gradlew :runtime:dependencies` shows a different managed version, take that one.)

- [ ] **Step 2: Run runtime tests, verify still PASS**

Run: `./gradlew :runtime:test`
Expected: PASS — no compile changes yet, just classpath additions.

- [ ] **Step 3: Commit**

```bash
git add runtime/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(runtime): add spring-kafka(-test) for the EmbeddedKafka channel transport

compileOnly so HTTP-only consumers don't transitively pick up
spring-kafka. Consumers writing channel tests add the deps to their
own build.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task G2: EmbeddedKafkaMessageTransport implementation

**Files:**
- Create: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/EmbeddedKafkaMessageTransport.kt`
- Modify: `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt` — uncomment the channelCtx wiring stubbed in Phase F.

- [ ] **Step 1: Implement EmbeddedKafkaMessageTransport**

Create the file:

```kotlin
package io.kotest.extensions.spring.wirespec.channel

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.context.ApplicationContext
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [MessageTransport] backed by Spring's [EmbeddedKafkaBroker]. Self-contained:
 *  - owns its own [KafkaProducer]<String, ByteArray>
 *  - opens a short-lived [KafkaConsumer]<String, ByteArray> per receive() call
 *    (random group.id, auto.offset.reset=earliest)
 *
 * Doesn't depend on the application's own `KafkaTemplate` / `ProducerFactory`
 * bean wiring — the only thing needed in the Spring context is the
 * `EmbeddedKafkaBroker` that `@EmbeddedKafka` registers.
 */
class EmbeddedKafkaMessageTransport(
    applicationContext: ApplicationContext,
) : MessageTransport {

    private val brokers: String =
        applicationContext.getBean(EmbeddedKafkaBroker::class.java).brokersAsString

    private val producer: KafkaProducer<String, ByteArray> by lazy {
        KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
                ProducerConfig.CLIENT_ID_CONFIG to "wirespec-test-${UUID.randomUUID()}",
            )
        )
    }

    override suspend fun publish(record: OutgoingRecord) {
        producer.send(ProducerRecord(record.topic, record.key, record.body))
            .get(5, TimeUnit.SECONDS)
        producer.flush()
    }

    override suspend fun receive(topic: String, atLeast: Int, within: Duration): List<IncomingRecord> {
        val consumer = KafkaConsumer<String, ByteArray>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers,
                ConsumerConfig.GROUP_ID_CONFIG to "wirespec-test-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            )
        )
        consumer.use { c ->
            c.subscribe(listOf(topic))
            val mark = TimeSource.Monotonic.markNow()
            val collected = mutableListOf<IncomingRecord>()
            while (collected.size < atLeast && mark.elapsedNow() < within) {
                val remaining = (within - mark.elapsedNow()).coerceAtLeast(Duration.ZERO)
                val pollMillis = remaining.inWholeMilliseconds.coerceAtMost(200L)
                val polled = c.poll(java.time.Duration.ofMillis(pollMillis))
                for (r in polled) {
                    if (r.topic() == topic) collected += IncomingRecord(r.topic(), r.key(), r.value())
                }
            }
            return collected.toList()
        }
    }
}
```

- [ ] **Step 2: Update SpringWirespecSpec to use it**

In `runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt`, replace the stub:

```kotlin
open val channelCtx: WirespecChannelContext? by lazy {
    null
}
```

with the real wiring:

```kotlin
open val channelCtx: WirespecChannelContext? by lazy {
    runCatching {
        WirespecChannelContext(
            messaging = EmbeddedKafkaMessageTransport(applicationContext),
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }.getOrNull()
}
```

Add the import:

```kotlin
import io.kotest.extensions.spring.wirespec.channel.EmbeddedKafkaMessageTransport
```

- [ ] **Step 3: Run runtime tests**

Run: `./gradlew :runtime:test`
Expected: PASS — the new transport isn't unit-tested directly (no broker in the runtime test JVM); the example app's spec in Phase I covers it end-to-end.

- [ ] **Step 4: Commit**

```bash
git add runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/channel/EmbeddedKafkaMessageTransport.kt \
        runtime/src/main/kotlin/io/kotest/extensions/spring/wirespec/SpringWirespecSpec.kt
git commit -m "$(cat <<'EOF'
feat(runtime): EmbeddedKafkaMessageTransport for @EmbeddedKafka tests

Self-contained KafkaProducer<String, ByteArray> + per-receive
short-lived KafkaConsumer<String, ByteArray>. Reads broker addresses
from EmbeddedKafkaBroker, sidestepping the app's own producer
serializer config. SpringWirespecSpec.channelCtx now wires this when
the test classpath has spring-kafka-test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase H: Bump extractor + example deps

### Task H1: gradle.properties bump

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

Open `gradle.properties` and change:

```
wirespecExtractorVersion=0.0.7
```

to:

```
wirespecExtractorVersion=0.0.8
```

- [ ] **Step 2: Refresh dependency lockfiles if any**

Run: `./gradlew :gradle-plugin:dependencies | grep wirespec-spring-extractor`
Expected: shows `0.0.8` resolved.

- [ ] **Step 3: Commit**

```bash
git add gradle.properties
git commit -m "$(cat <<'EOF'
build: bump wirespecExtractorVersion to 0.0.8

Picks up the dedup fix on top of 0.0.7's Kafka channel extraction,
which is the version the new channel DSL is built against.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task H2: example deps

**Files:**
- Modify: `example/build.gradle.kts`

- [ ] **Step 1: Add spring-kafka deps to the example**

Edit `example/build.gradle.kts` — under `dependencies { ... }`, after the existing `implementation` lines and before `testImplementation(project(":runtime"))`, add:

```kotlin
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
```

(Spring Boot's BOM manages the version, no need to pin.)

- [ ] **Step 2: Verify the example builds**

Run: `./gradlew :example:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add example/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(example): add spring-kafka + spring-kafka-test

For the new PetEventPublisher / PetCommandListener and the
PetChannelScenariosSpec that exercises the EmbeddedKafka transport
end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase I: Example app — publisher, listener, scenario spec

### Task I1: PetEventPublisher + wire into PetController

**Files:**
- Create: `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetEventPublisher.kt`
- Modify: `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/controller/PetController.kt`
- Create: `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/domain/PetCreatedEvent.kt`

> **Detection note:** the extractor walks the controller's bytecode (`KafkaProducerBytecodeWalker`) to find `kafkaTemplate.send(...)` sites. Calling the template from a `@Service` is fine — the walker recursively follows method calls; the channel ends up named after the *enclosing controller method* that initiated the chain. To keep names tidy, the controller calls `publisher.publishPetCreated(...)` and the publisher does the actual send. The extracted channel name will be `PublishPetCreated` (after the publisher method) since the walker uses the most-local enclosing method name; if not, the channel name is `Create` (controller's create method). Read what's emitted in build/wirespec/extracted after Step 4 and use that name in I3.

- [ ] **Step 1: Create the event DTO**

Create `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/domain/PetCreatedEvent.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.example.domain

data class PetCreatedEvent(
    val id: String,
    val name: String,
    val species: String,
)
```

- [ ] **Step 2: Create PetEventPublisher**

Create `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetEventPublisher.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.example.service

import io.kotest.extensions.spring.wirespec.example.domain.PetCreatedEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class PetEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, PetCreatedEvent>,
) {
    fun publishPetCreated(event: PetCreatedEvent) {
        kafkaTemplate.send("pets.events", event.id, event)
    }
}
```

- [ ] **Step 3: Wire it into the controller**

Read `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/controller/PetController.kt` to find the `create` method. Add `PetEventPublisher` as a constructor dependency and call `publisher.publishPetCreated(...)` after persisting the new pet. Example diff:

```kotlin
@RestController
class PetController(
    private val repository: PetRepository,
    private val publisher: PetEventPublisher,   // NEW
) {
    @PostMapping("/api/pets")
    @ApiResponses(...)
    suspend fun create(@RequestBody @Valid body: CreatePetRequest): ResponseEntity<PetResponse> {
        val pet = repository.save(/* … */)
        publisher.publishPetCreated(
            PetCreatedEvent(id = pet.id, name = pet.name, species = pet.species),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(/* … */)
    }
}
```

Also register a `KafkaTemplate<String, PetCreatedEvent>` config — for an `@EmbeddedKafka` test app, the default `KafkaAutoConfiguration` will produce a `KafkaTemplate<*, *>` whose producer factory uses JSON. Add a small `KafkaConfig` (or rely on Spring Boot defaults via `spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer`).

Create `example/src/main/resources/application-test.properties` if it doesn't exist with:

```
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=io.kotest.extensions.spring.wirespec.example.domain
```

- [ ] **Step 4: Verify the extractor picks the producer up as a channel**

Run: `./gradlew :example:extractWirespec`
Expected: BUILD SUCCESSFUL.

Then inspect: `cat example/build/wirespec/extracted/PetController.ws` (or whichever owner file lists the producer site). Confirm a `channel <Name> -> PetCreatedEvent` line appears.

- [ ] **Step 5: Verify the generated DSL compiles**

Run: `./gradlew :example:compileTestKotlin`
Expected: BUILD SUCCESSFUL. A new file `example/build/generated/wirespec/io/kotest/extensions/spring/wirespec/example/generated/kotest/<ChannelName>Dsl.kt` should exist.

- [ ] **Step 6: Commit**

```bash
git add example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/domain/PetCreatedEvent.kt \
        example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetEventPublisher.kt \
        example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/controller/PetController.kt \
        example/src/main/resources/application-test.properties
git commit -m "$(cat <<'EOF'
feat(example): publish a PetCreatedEvent on Kafka after creating a pet

PetController.create now also calls PetEventPublisher, which fires a
'pets.events' record. wirespec-spring-extractor 0.0.8 picks this up
as a channel; the Kotest DSL emitter generates the test-side surface.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task I2: PetCommandListener

**Files:**
- Create: `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetCommandListener.kt`
- Create: `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/domain/CreatePetCommand.kt`

- [ ] **Step 1: Create the command DTO**

Create `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/domain/CreatePetCommand.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.example.domain

data class CreatePetCommand(
    val correlationId: String,
    val name: String,
    val species: String,
)
```

- [ ] **Step 2: Create the listener**

Create `example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetCommandListener.kt`:

```kotlin
package io.kotest.extensions.spring.wirespec.example.service

import io.kotest.extensions.spring.wirespec.example.domain.CreatePetCommand
import io.kotest.extensions.spring.wirespec.example.domain.Pet
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PetCommandListener(
    private val repository: PetRepository,
) {
    @KafkaListener(topics = ["pets.commands"], groupId = "pet-command-listener")
    fun onCreatePetCommand(command: CreatePetCommand) {
        repository.save(
            Pet(
                id = command.correlationId,
                name = command.name,
                species = command.species,
                bornAt = Instant.now(),
            ),
        )
    }
}
```

(`Pet` and `PetRepository` already exist — this assumes their existing signatures. If `Pet`'s constructor differs, adapt to what's already there.)

- [ ] **Step 3: Verify the extractor picks it up as a channel**

Run: `./gradlew :example:extractWirespec`
Expected: BUILD SUCCESSFUL.

Inspect: `cat example/build/wirespec/extracted/PetCommandListener.ws` (or whichever file lists listener channels) — expect `channel OnCreatePetCommand -> CreatePetCommand`.

- [ ] **Step 4: Verify the generated DSL compiles**

Run: `./gradlew :example:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/domain/CreatePetCommand.kt \
        example/src/main/kotlin/io/kotest/extensions/spring/wirespec/example/service/PetCommandListener.kt
git commit -m "$(cat <<'EOF'
feat(example): @KafkaListener for CreatePetCommand on pets.commands

Demonstrates the consumer side. Extractor emits a channel for the
listener; the channel DSL will publish stimulus messages and assert
the resulting Pet via the existing HTTP DSL.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task I3: PetChannelScenariosSpec — end-to-end EmbeddedKafka test

**Files:**
- Create: `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetChannelScenariosSpec.kt`

- [ ] **Step 1: Look up the actual channel identifiers**

The channel names depend on what the extractor emitted in Phases I1/I2. Run:

```bash
grep "^channel " example/build/wirespec/extracted/*.ws
```

Note the two identifiers — they'll have generated `*Dsl.kt` files and DSL receivers in `example.generated.kotest.*`.

- [ ] **Step 2: Write the spec**

Create `example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetChannelScenariosSpec.kt`. Substitute `<ProducerChannel>` and `<ConsumerChannel>` with the identifiers from Step 1:

```kotlin
package io.kotest.extensions.spring.wirespec.example

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.extensions.spring.wirespec.SpringWirespecSpec
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.spring.wirespec.example.generated.endpoint.GetPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.createPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.getPet
import io.kotest.extensions.spring.wirespec.example.generated.kotest.<producerChannelDslName>
import io.kotest.extensions.spring.wirespec.example.generated.kotest.<consumerChannelDslName>
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events", "pets.commands"])
@ActiveProfiles("test")
class PetChannelScenariosSpec : SpringWirespecSpec({

    test("HTTP create publishes a PetCreatedEvent", iterations = 5) {
        val petId = createPet
            .body { name = Arb.string(minSize = 1, maxSize = 16); species = Arb.string(minSize = 1, maxSize = 8) }
            .returning<CreatePet.Response201, String> { it.body.id }

        <producerChannelDslName>
            .topic("pets.events")
            .expecting { it.id shouldBe petId.require() }
    }

    test("Kafka command creates a pet", iterations = 3) {
        val command = <consumerChannelDslName>
            .topic("pets.commands")
            .send { name = Arb.string(minSize = 1, maxSize = 16); species = Arb.string(minSize = 1, maxSize = 8) }
            .returning { it }

        eventually(5.seconds) {
            getPet
                .path(command.require().correlationId)
                .expecting<GetPet.Response200>()
        }
    }
})
```

If the consumer-channel DSL's `send(block)` payload builder doesn't expose a `correlationId` field (because the extractor names builder fields after DTO fields and includes `correlationId`), set it explicitly: `correlationId = Arb.of(UUID.randomUUID().toString()).constant()` style. Use whatever the generated `<ConsumerChannel>PayloadBuilder` exposes.

- [ ] **Step 3: Run the spec**

Run: `./gradlew :example:test --tests "*PetChannelScenariosSpec"`
Expected: PASS (2 tests, multiple iterations each).

- [ ] **Step 4: Commit**

```bash
git add example/src/test/kotlin/io/kotest/extensions/spring/wirespec/example/PetChannelScenariosSpec.kt
git commit -m "$(cat <<'EOF'
test(example): PetChannelScenariosSpec — both directions end-to-end

Producer side: HTTP create -> assert PetCreatedEvent on pets.events.
Consumer side: publish CreatePetCommand -> assert pet appears via
GET /api/pets/{correlationId}. Exercises the real
EmbeddedKafkaMessageTransport.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase J: README

### Task J1: Document channels

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a "Channels" subsection under "What your tests look like"**

Append after the existing endpoint-DSL example. New section:

````markdown
### Channels (Kafka)

`wirespec-spring-extractor` 0.0.7+ extracts `@KafkaListener` methods and
`kafkaTemplate.send(...)` call sites as Wirespec channels. The Kotest DSL
emitter generates a per-channel receiver alongside the per-endpoint one —
both interleave in the same `scenario { ... }`:

```kotlin
@SpringBootTest(classes = [MyApp::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events"])
class PetChannelSpec : SpringWirespecSpec({

    test("HTTP create publishes a PetCreatedEvent", iterations = 5) {
        val petId = createPet.returning<CreatePet.Response201, String> { it.body.id }

        petCreatedChannel
            .topic("pets.events")
            .expecting { it.id shouldBe petId.require() }
    }
})
```

`SpringWirespecSpec.channelCtx` is auto-resolved from `@EmbeddedKafka` — no
listener wiring needed. Override `channelCtx` for a custom transport
(e.g. Testcontainers).

**Slots on a channel call:**

- `.topic(value)` / `.topic(ref: ResultRef<String>)` — required. The
  extracted contract does not carry topic names.
- `.key(value)` — optional partition key for producer steps.
- `.send(value | Arb | block { ... })` — test publishes a message; drives an
  app `@KafkaListener`.
- `.expecting()` / `.expecting { assertion }` — test consumes; asserts the
  app published exactly one record on `topic` within 2 s.
- `.collecting(count = N)` / `.collecting(duration = d)` — batched consume.
- `.returning { projection }` — same `ResultRef` pattern as endpoints.

Setting both `.send` and `.expecting` on one channel call is a configuration
error caught before any transport call runs.
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs(readme): document the channel DSL for Kafka tests

Mirrors the endpoint section with the channel-specific slots and a
worked EmbeddedKafka example.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

- [ ] **Run the full multi-module build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL across `:emitter`, `:runtime`, `:gradle-plugin`, `:maven-plugin`, `:example`.

- [ ] **If anything fails, do NOT retry blindly.** Read the failure, diagnose the root cause, and fix in a new commit. The most likely failure surfaces are:
  - Spring-kafka version mismatch with Spring Boot 3.4 — let the BOM manage it (drop the pinned version in `runtime/build.gradle.kts`).
  - The extractor channel name in I3 doesn't match what's in the generated DSL — inspect `example/build/wirespec/extracted/*.ws` and `example/build/generated/wirespec/.../kotest/*Dsl.kt` and adjust the import in the spec.
  - Two `Wirespec.Serialization` instances in the spec (one for HTTP, one for channel) — fine; they share `jacksonObjectMapper()`. No action needed unless tests complain.

---

## Self-review

Checking each spec requirement against the plan:

| Spec section | Covered by |
|---|---|
| ChannelShape | A1 |
| ChannelDslFileEmitter, goldens | A2 |
| TypesafeDslEmitter dispatches Channel | A3 |
| MessageTransport interface + records | B1 |
| InMemoryMessageTransport fake | B2 |
| WirespecChannelContext | B3 |
| Step sealed type, ScenarioBuilder refactor | C1 |
| ChannelReflection | C2 |
| ChannelCallBuilder, ScenarioBuilder.channel(...) | C3 |
| ChannelValidator (no status check) | D1 |
| ScenarioRunner runChannel (Send) | E1 |
| ScenarioRunner runChannel (Expect/Collect) | E2 |
| Scenario.scenario(endpointCtx, channelCtx, ...) overloads | E1 |
| SpringWirespecSpec rename defaultCtx → endpointCtx | F1 |
| SpringWirespecSpec.channelCtx | F1 + G2 |
| EmbeddedKafkaMessageTransport | G2 |
| spring-kafka deps in runtime + example | G1, H2 |
| wirespecExtractorVersion bump | H1 |
| Example PetEventPublisher + controller wiring | I1 |
| Example PetCommandListener | I2 |
| Example PetChannelScenariosSpec | I3 |
| README channels section | J1 |

No spec requirements unmapped.

Placeholder scan — none of the listed forbidden patterns appear in the plan.

Type/signature consistency — `WirespecChannelContext(messaging, serialization)` consistent across B3/F1/G2/I3. `MessageTransport.publish(record: OutgoingRecord)` / `receive(topic, atLeast, within)` consistent across B1/B2/E1/E2/G2. `ChannelCallBuilder.Direction` enum used consistently in C3/E1/E2. `ScenarioBuilder.channel(channelClass: KClass<out Wirespec.Channel>)` consistent across C3 and the emitter's render output in A2 goldens.
