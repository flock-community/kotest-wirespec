# Flat (wrapper-free) wirespec DSL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `scenario { }` wrapper with an eager, wrapper-free DSL where tests call per-controller catalog objects (`PetControllerV1.createPet…`) directly inside `test { }`, each terminal call executing immediately against an ambient context, with property runs via kotest's native `checkAll`.

**Architecture:** A coroutine-context element (`WirespecAmbient`) carries the test's HTTP/channel context (lazily resolved) and a per-test `RandomSource`. A kotest `TestCaseExtension` (`@ApplyExtension(WirespecExtension::class)`) installs it; `withWirespec(ctx) { }` does the same for JUnit. Call-builder terminals (`expecting`/`returning`/`collecting`, and channel `send`) become `suspend` and run a single-call executor (`CallExecutor`) that reads the ambient element. The emitter groups statements by their source `.ws` `Module` and emits one top-level catalog `object` per controller; generated `*Call` wrappers drop the old `ScenarioBuilder` receiver and gain suspend terminals.

**Tech Stack:** Kotlin, Kotest 6.1.11 (`kotest-runner-junit5`, `kotest-property`, `kotest-assertions-core`), kotlinx-coroutines, the Wirespec compiler/IR + `community.flock.wirespec.integration:kotest-jvm` adapter, Spring Boot test (example module), Gradle composite build.

---

## File Structure

**Core library — new files** (`core/src/main/kotlin/io/kotest/extensions/wirespec/`):
- `runtime/WirespecAmbient.kt` — coroutine-context element + `RandomSourceHolder` + `currentAmbient()`.
- `runtime/CallExecutor.kt` — single-call executor (endpoint + channel), reusing the old `ScenarioRunner` slot-resolution/transport/validation logic.
- `WirespecExtension.kt` — `TestCaseExtension` that installs the ambient element per test.
- `Wirespec.kt` — public `withWirespec { }` entry point + `PropertyContext.useWirespecSeed()` bridge.
- `dsl/CallFactories.kt` — public `endpointCall(...)` / `channelCall(...)` factories (replace `ScenarioBuilder.endpoint/channel`).

**Core library — modified:**
- `dsl/EndpointCallBuilder.kt` — drop `scenario` ctor param + `init { register }`; terminals become `suspend` and return values.
- `dsl/ChannelCallBuilder.kt` — same; `send*` become suspend terminals returning the sent payload; receive terminals return the message/list/projection.

**Core library — deleted:**
- `Scenario.kt`, `dsl/ScenarioBuilder.kt`, `dsl/ResultRef.kt`, `dsl/Step.kt`, `runtime/ScenarioRunner.kt`.

**Core tests — modified/replaced:**
- delete `ScenarioTest.kt`; add `runtime/WirespecAmbientTest.kt`.
- rewrite `runtime/ScenarioRunnerChannelTest.kt` → `runtime/CallExecutorChannelTest.kt`.
- rewrite `dsl/ChannelCallBuilderTest.kt` (state-only assertions).

**Emitter — modified:**
- `TypesafeDslEmitter.kt` — group by `Module`, one catalog per controller.
- `CatalogFileEmitter.kt` — emit a top-level `object <Controller>`.
- `DslFileEmitter.kt` — suspend terminals, no `ScenarioBuilder`/`ResultRef`, `endpointCall(...)` factory.
- `ChannelDslFileEmitter.kt` — same; suspend `send`/receive terminals.

**Emitter tests/goldens — updated:** `TypesafeDslEmitterTest.kt`, `CatalogFileEmitterTest.kt`, `DslFileEmitterTest.kt`, `ChannelDslFileEmitterTest.kt`, and all `emitter/src/test/resources/golden/*.kt` (regenerated).

**Example module — migrated:** `PetScenariosSpec.kt`, `PetChannelScenariosSpec.kt`, `PetScenariosJUnitTest.kt`.

---

## Phase 1 — Core: ambient context + entry points

### Task 1: `WirespecAmbient` coroutine-context element

**Files:**
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbient.kt`
- Test: `core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbientTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbientTest.kt`:

```kotlin
package io.kotest.extensions.wirespec.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.RandomSource

class WirespecAmbientTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val noopHttp = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no transport in this test")
    }

    test("currentAmbient outside any ambient throws a helpful error") {
        val ex = runCatching { currentAmbient() }.exceptionOrNull()
            ?: error("expected an error")
        ex.message!! shouldContain "No wirespec ambient context"
    }

    test("withWirespec installs an ambient whose endpointContext is the explicit override") {
        val ctx = WirespecTestContext(noopHttp, serialization)
        withWirespec(ctx, seed = 7L) {
            val ambient = currentAmbient()
            ambient.endpointContext() shouldBe ctx
            ambient.rng.seed shouldBe 7L
        }
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests '*WirespecAmbientTest'`
Expected: COMPILE FAILURE — `currentAmbient`, `withWirespec`, `WirespecAmbient` unresolved.

- [ ] **Step 3: Create `WirespecAmbient.kt`**

```kotlin
package io.kotest.extensions.wirespec.runtime

import io.kotest.core.spec.Spec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.context.ContextRegistry
import io.kotest.property.RandomSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Mutable holder for the per-test [RandomSource] so the property-run bridge
 * ([io.kotest.extensions.wirespec.useWirespecSeed]) can rebind it to a
 * `checkAll` iteration's source mid-test.
 */
class RandomSourceHolder(@Volatile var randomSource: RandomSource) {
    val seed: Long get() = randomSource.seed
}

/**
 * Coroutine-context element carrying everything an eager wirespec call needs.
 * The endpoint/channel context is resolved lazily on first use — either an
 * explicit override (JUnit / [withWirespec]) or via the [ContextRegistry] SPI
 * from the running [spec] (kotest / [WirespecExtension]). Lazy resolution avoids
 * any extension-ordering dependency with Spring's lifecycle extension.
 */
class WirespecAmbient internal constructor(
    private val spec: Spec?,
    endpointOverride: WirespecTestContext?,
    channelOverride: WirespecChannelContext?,
    val rng: RandomSourceHolder,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<WirespecAmbient>

    private var endpointResolved: Boolean = endpointOverride != null
    private var endpoint: WirespecTestContext? = endpointOverride
    private var channelResolved: Boolean = channelOverride != null
    private var channel: WirespecChannelContext? = channelOverride

    fun endpointContext(): WirespecTestContext {
        if (!endpointResolved) {
            endpoint = spec?.let { s -> ContextRegistry.providers.firstNotNullOfOrNull { it.endpointContext(s) } }
            endpointResolved = true
        }
        return endpoint ?: error(
            "No WirespecTestContext available" + (spec?.let { " for ${it::class.simpleName}" } ?: "") + ". " +
                "Add `io.kotest.extensions.wirespec:kotest-wirespec-spring` to the test classpath and " +
                "`@ApplyExtension(SpringRootTestExtension::class)` to the spec, or wrap calls in " +
                "withWirespec(ctx) { … }.",
        )
    }

    fun channelContext(): WirespecChannelContext? {
        if (!channelResolved) {
            channel = spec?.let { s -> ContextRegistry.providers.firstNotNullOfOrNull { it.channelContext(s) } }
            channelResolved = true
        }
        return channel
    }
}

/** Read the ambient element installed by [WirespecExtension] or [withWirespec]. */
internal suspend fun currentAmbient(): WirespecAmbient =
    coroutineContext[WirespecAmbient] ?: error(
        "No wirespec ambient context in scope. Mount @ApplyExtension(WirespecExtension::class) on the spec, " +
            "or wrap the calls in withWirespec(ctx) { … }.",
    )
```

- [ ] **Step 4: Create the `withWirespec` entry point (needed for the test to compile)**

Create `core/src/main/kotlin/io/kotest/extensions/wirespec/Wirespec.kt`:

```kotlin
package io.kotest.extensions.wirespec

import io.kotest.extensions.wirespec.runtime.RandomSourceHolder
import io.kotest.extensions.wirespec.runtime.WirespecAmbient
import io.kotest.property.PropertyContext
import io.kotest.property.RandomSource
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Run [block] with an ambient wirespec context bound to an explicit [endpointCtx]
 * (and optional [channelCtx]). The replacement for `scenario(ctx) { }` on
 * non-kotest runners (e.g. JUnit) and anywhere you want to supply the context
 * yourself. [seed] seeds the per-test [RandomSource]; pass a fixed value to
 * reproduce a previously failing run.
 */
suspend fun <T> withWirespec(
    endpointCtx: WirespecTestContext,
    channelCtx: WirespecChannelContext? = null,
    seed: Long = System.nanoTime(),
    block: suspend () -> T,
): T {
    val ambient = WirespecAmbient(
        spec = null,
        endpointOverride = endpointCtx,
        channelOverride = channelCtx,
        rng = RandomSourceHolder(RandomSource.seeded(seed)),
    )
    return withContext(ambient) { block() }
}

/**
 * Opt-in property-run bridge. Call at the top of a `checkAll { }` block so the
 * wirespec generators draw from that iteration's [RandomSource] — making
 * kotest's reported seed reproduce wirespec-generated bodies too. Omit it to use
 * the per-test ambient source (still re-randomised each iteration as the source
 * advances).
 */
suspend fun PropertyContext.useWirespecSeed() {
    val ambient = coroutineContext[WirespecAmbient]
        ?: error("useWirespecSeed() requires an ambient wirespec context (WirespecExtension or withWirespec).")
    ambient.rng.randomSource = randomSource()
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:test --tests '*WirespecAmbientTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbient.kt \
        core/src/main/kotlin/io/kotest/extensions/wirespec/Wirespec.kt \
        core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbientTest.kt
git commit -m "feat(core): ambient wirespec context + withWirespec/useWirespecSeed entry points"
```

---

### Task 2: `WirespecExtension` (kotest install) + `useWirespecSeed` test

**Files:**
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/WirespecExtension.kt`
- Test: `core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbientTest.kt` (append)

- [ ] **Step 1: Add a failing test for the seed bridge**

Append this test inside the `WirespecAmbientTest` `FunSpec({ … })` body (before the closing `})`):

```kotlin
    test("useWirespecSeed rebinds the ambient RandomSource to the checkAll iteration source") {
        val ctx = WirespecTestContext(noopHttp, serialization)
        var seenSeeds = mutableListOf<Long>()
        withWirespec(ctx, seed = 1L) {
            io.kotest.property.checkAll<Int>(iterations = 3) {
                useWirespecSeed()
                seenSeeds += currentAmbient().rng.seed
            }
        }
        // After binding, the ambient seed is no longer the fixed 1L for every iteration.
        seenSeeds.size shouldBe 3
        seenSeeds.any { it != 1L } shouldBe true
    }
```

Add the import at the top of the test file:

```kotlin
import io.kotest.extensions.wirespec.useWirespecSeed
```

- [ ] **Step 2: Run to verify it passes already (useWirespecSeed exists from Task 1)**

Run: `./gradlew :core:test --tests '*WirespecAmbientTest'`
Expected: PASS (3 tests). `useWirespecSeed` was created in Task 1; this test locks its behavior in.

- [ ] **Step 3: Create `WirespecExtension.kt`**

```kotlin
package io.kotest.extensions.wirespec

import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.extensions.wirespec.runtime.RandomSourceHolder
import io.kotest.extensions.wirespec.runtime.WirespecAmbient
import io.kotest.property.RandomSource
import kotlinx.coroutines.withContext

/**
 * Installs an ambient wirespec context around every test so wrapper-free
 * `PetControllerV1.createPet…` calls resolve their HTTP/channel context (via the
 * [io.kotest.extensions.wirespec.context.ContextProvider] SPI) and a per-test
 * [RandomSource]. Mount with `@ApplyExtension(WirespecExtension::class)`.
 */
class WirespecExtension : TestCaseExtension {
    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        val ambient = WirespecAmbient(
            spec = testCase.spec,
            endpointOverride = null,
            channelOverride = null,
            rng = RandomSourceHolder(RandomSource.seeded(System.nanoTime())),
        )
        return withContext(ambient) { execute(testCase) }
    }
}
```

- [ ] **Step 4: Verify the whole core module still compiles**

Run: `./gradlew :core:compileKotlin :core:compileTestKotlin`
Expected: BUILD SUCCESSFUL (Scenario.kt still present and compiling at this point).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/io/kotest/extensions/wirespec/WirespecExtension.kt \
        core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/WirespecAmbientTest.kt
git commit -m "feat(core): WirespecExtension installs ambient context per kotest test"
```

---

## Phase 2 — Core: single-call executor + eager terminals

### Task 3: `CallExecutor` + call factories

**Files:**
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/CallExecutor.kt`
- Create: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/CallFactories.kt`

This task creates the executor and factories. The builders are still wired to `ScenarioBuilder` at this point; Task 4 rewires them. We build the executor first because the builders will call it.

- [ ] **Step 1: Create `CallFactories.kt`**

```kotlin
package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import kotlin.reflect.KClass

/**
 * Build an [EndpointCallBuilder] for an endpoint. Generated `*Call` wrappers
 * call this instead of the removed `ScenarioBuilder.endpoint(...)`.
 */
fun <BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> endpointCall(
    client: Wirespec.Client<Req, Resp>,
    endpointObject: Wirespec.Endpoint,
): EndpointCallBuilder<BodyT, Req, Resp> = EndpointCallBuilder(client, endpointObject)

/** Build a [ChannelCallBuilder] for a channel. */
fun <MessageT : Any> channelCall(
    channelClass: KClass<out Wirespec.Channel>,
): ChannelCallBuilder<MessageT> = ChannelCallBuilder(channelClass)
```

(This will not compile until Task 4 changes the builder constructors. That's expected — do not run a build yet; continue to Step 2.)

- [ ] **Step 2: Create `CallExecutor.kt`**

```kotlin
package io.kotest.extensions.wirespec.runtime

import community.flock.wirespec.integration.kotest.kotestWirespecKotlinGenerator
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.channel.OutgoingRecord
import io.kotest.extensions.wirespec.dsl.ArbReceiver
import io.kotest.extensions.wirespec.dsl.ChannelCallBuilder
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder
import io.kotest.extensions.wirespec.dsl.Input
import io.kotest.extensions.wirespec.validation.ChannelValidator
import io.kotest.extensions.wirespec.validation.ContractValidator
import io.kotest.extensions.wirespec.validation.EndpointReflection
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlin.reflect.KClass

/**
 * Executes a single endpoint or channel call eagerly against the ambient
 * context. Mirrors the per-call logic of the old `ScenarioRunner` (slot
 * resolution → typed transport → contract validation → user assertion), but
 * suspends instead of `runBlocking` and reads the context from [currentAmbient].
 * Each call constructs a fresh [ArbReceiver] from the ambient [RandomSource];
 * because the source advances on every call, repeated same-endpoint calls draw
 * distinct bodies without the per-step index keying the old runner used.
 */
internal object CallExecutor {

    /** Run the endpoint call; returns the validated typed response. */
    suspend fun executeEndpoint(call: EndpointCallBuilder<*, *, *>): Any {
        val ambient = currentAmbient()
        val ctx = ambient.endpointContext()
        val rs = ambient.rng.randomSource
        val arb = ArbReceiver(rs)
        val reflection = call.reflection
        val request = reflection.buildRequest(resolveSlots(call, reflection, rs, arb))

        @Suppress("UNCHECKED_CAST")
        val starClient = call.client as Wirespec.Client<Wirespec.Request<Any>, Wirespec.Response<*>>
        val clientEdge = starClient.client(ctx.serialization)
        @Suppress("UNCHECKED_CAST")
        val rawRequest = clientEdge.to(request as Wirespec.Request<Any>)
        val rawResponse = ctx.transportation.transport(rawRequest)

        val validator = ContractValidator(reflection, ctx.serialization)
        val typedResponse = try {
            validator.validate(rawResponse, expectedStatuses = call.expectedStatuses)
        } catch (t: Throwable) {
            throw AssertionError(
                "${reflection.endpointName} failed (wirespec seed=${ambient.rng.seed}): ${t.message}",
                t,
            )
        }
        call.customAssertion?.invoke(typedResponse)
        return typedResponse
    }

    /**
     * Run the channel call; returns the "subject":
     *  - Send  → the sent payload,
     *  - Expect → the single received message,
     *  - Collect → the received `List<message>`.
     */
    suspend fun executeChannel(call: ChannelCallBuilder<*>): Any {
        val ambient = currentAmbient()
        val ctx = ambient.channelContext() ?: error(
            "${call.reflection.channelName} requires a channel context. Annotate the spec with @EmbeddedKafka " +
                "(spring) so the provider resolves one, or pass channelCtx to withWirespec(ctx, channelCtx).",
        )
        val rs = ambient.rng.randomSource
        val arb = ArbReceiver(rs)
        val topic = call.topicInput?.resolve(rs)
            ?: error("${call.reflection.channelName}: .topic(...) is required.")
        val key = call.keyInput?.resolve(rs)

        return when (call.direction) {
            ChannelCallBuilder.Direction.Send -> {
                val payload: Any = when {
                    call.sendInput != null -> call.sendInput!!.resolve(rs)
                    call.sendOverrides != null -> {
                        val generator = kotestWirespecKotlinGenerator(seed = rs.random.nextLong()) {
                            call.sendOverrides!!()
                        }
                        val payloadClass = (call.reflection.payloadType.classifier as? KClass<*>)?.java
                            ?: error(
                                "${call.reflection.channelName}: cannot resolve payload Java class from " +
                                    "${call.reflection.payloadType}.",
                            )
                        arb.generatorFor(payloadClass).generate(generator, emptyList())
                    }
                    else -> error("${call.reflection.channelName}: .send(...) value not set.")
                }
                val bytes = ctx.serialization.serializeBody(payload, call.reflection.payloadType)
                ctx.messaging.publish(OutgoingRecord(topic, key, bytes))
                payload
            }
            ChannelCallBuilder.Direction.Expect, ChannelCallBuilder.Direction.Collect -> {
                val (atLeast, within) = call.receivePolicy()
                val records = ctx.messaging.receive(topic, atLeast, within)
                val validator = ChannelValidator(call.reflection, ctx.serialization)
                val typed = records.map { rec ->
                    try {
                        validator.deserialize(rec.body)
                    } catch (t: Throwable) {
                        throw AssertionError(
                            "${call.reflection.channelName} failed to decode record on topic '$topic' " +
                                "(wirespec seed=${ambient.rng.seed}): ${t.message}",
                            t,
                        )
                    }
                }
                if (call.direction == ChannelCallBuilder.Direction.Expect) {
                    val one = typed.singleOrNull() ?: throw AssertionError(
                        "${call.reflection.channelName}: expected exactly 1 message on '$topic' within " +
                            "$within, got ${typed.size}.",
                    )
                    call.customAssertion?.invoke(one)
                    one
                } else {
                    call.customAssertion?.invoke(typed)
                    typed
                }
            }
            null -> error(
                "${call.reflection.channelName}: set .send(...) or .expecting()/.collecting(...) before running.",
            )
        }
    }

    private fun resolveSlots(
        call: EndpointCallBuilder<*, *, *>,
        reflection: EndpointReflection,
        rs: RandomSource,
        arb: ArbReceiver,
    ): Map<String, Any?> {
        val args = mutableMapOf<String, Any?>()

        when {
            call.bodyInput != null -> {
                args["body"] = call.bodyInput!!.resolve(rs)
            }
            reflection.hasBody && reflection.bodyElementClass != null -> {
                val generator = call.bodyOverrides?.let { overrides ->
                    kotestWirespecKotlinGenerator(seed = rs.random.nextLong()) { overrides() }
                } ?: arb.generator
                val sizeArb = call.bodyListSize ?: Arb.int(1..3)
                val size = sizeArb.next(rs)
                val elementGen = arb.generatorFor(reflection.bodyElementClass)
                args["body"] = (0 until size).map { i -> elementGen.generate(generator, listOf("$i")) }
            }
            reflection.hasBody -> {
                val bodyType = reflection.requestConstructor.parameters
                    .firstOrNull { it.name == "body" }
                    ?.type
                    ?: error("${reflection.endpointName}: hasBody=true but no `body` constructor param.")
                val generator = call.bodyOverrides?.let { overrides ->
                    kotestWirespecKotlinGenerator(seed = rs.random.nextLong()) { overrides() }
                } ?: arb.generator
                args["body"] = arb.generatorFor(bodyType).generate(generator, emptyList())
            }
        }

        call.pathInput?.let { distribute(it.resolve(rs), reflection.pathFieldNames, "path", reflection.pathClass, reflection.endpointName, args) }
        call.queryInput?.let { distribute(it.resolve(rs), reflection.queriesFieldNames, "query", reflection.queriesClass, reflection.endpointName, args) }
        call.headerInput?.let { distribute(it.resolve(rs), reflection.headersFieldNames, "header", reflection.headersClass, reflection.endpointName, args) }
        return args
    }

    private fun distribute(
        resolved: Any,
        fieldNames: List<String>,
        slotName: String,
        slotClass: Class<*>,
        endpointName: String,
        args: MutableMap<String, Any?>,
    ) {
        when {
            fieldNames.size == 1 && !slotClass.isInstance(resolved) -> args[fieldNames[0]] = resolved
            slotClass.isInstance(resolved) -> for (name in fieldNames) {
                val field = slotClass.getDeclaredField(name)
                field.isAccessible = true
                args[name] = field.get(resolved)
            }
            fieldNames.isEmpty() -> Unit
            else -> error(
                "Endpoint $endpointName: slot `$slotName` has ${fieldNames.size} fields ($fieldNames) but received " +
                    "a value of type ${resolved::class.simpleName} that is neither a single field value nor an " +
                    "instance of ${slotClass.simpleName}.",
            )
        }
    }
}
```

NOTE on `Input` import: `Input` is imported but only used transitively (resolve is called on `call.bodyInput` etc.). Keep the import; if the compiler flags it unused, delete the `import io.kotest.extensions.wirespec.dsl.Input` line.

- [ ] **Step 2b: Do not build yet** — `EndpointCallBuilder`/`ChannelCallBuilder` still reference `scenario`. Proceed to Task 4, which rewires them; build at the end of Task 4.

- [ ] **Step 3: Commit (WIP — compiles after Task 4)**

```bash
git add core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/CallExecutor.kt \
        core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/CallFactories.kt
git commit -m "feat(core): single-call executor + endpointCall/channelCall factories (WIP)"
```

---

### Task 4: Rewire the call builders to eager suspend terminals; delete scenario plumbing

**Files:**
- Modify: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/EndpointCallBuilder.kt`
- Modify: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ChannelCallBuilder.kt`
- Delete: `core/src/main/kotlin/io/kotest/extensions/wirespec/Scenario.kt`
- Delete: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ScenarioBuilder.kt`
- Delete: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ResultRef.kt`
- Delete: `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/Step.kt`
- Delete: `core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunner.kt`

- [ ] **Step 1: Delete the scenario plumbing files**

```bash
git rm core/src/main/kotlin/io/kotest/extensions/wirespec/Scenario.kt \
       core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ScenarioBuilder.kt \
       core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ResultRef.kt \
       core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/Step.kt \
       core/src/main/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunner.kt
```

- [ ] **Step 2: Replace `EndpointCallBuilder.kt` entirely**

Overwrite `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/EndpointCallBuilder.kt` with:

```kotlin
package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.runtime.CallExecutor
import io.kotest.extensions.wirespec.validation.EndpointReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration

@WirespecScenarioDsl
class EndpointCallBuilder<BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> internal constructor(
    @PublishedApi internal val client: Wirespec.Client<Req, Resp>,
    endpointObject: Wirespec.Endpoint,
) {

    @PublishedApi
    internal val reflection: EndpointReflection = EndpointReflection.of(endpointObject)

    @PublishedApi internal var pathInput: Input<Any>? = null
    internal var bodyInput: Input<Any>? = null
    @PublishedApi internal var queryInput: Input<Any>? = null
    @PublishedApi internal var headerInput: Input<Any>? = null

    internal var bodyOverrides: (KotestWirespecGeneratorBuilder.() -> Unit)? = null
    internal var bodyListSize: Arb<Int>? = null
    internal var expectedStatuses: Set<Int>? = null
    internal var customAssertion: ((Any) -> Unit)? = null
    internal var streamingMode: StreamingMode? = null

    fun body(value: BodyT): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = Input.Literal(value); bodyOverrides = null
    }

    fun body(arb: Arb<BodyT>): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = Input.FromArb(arb); bodyOverrides = null
    }

    fun body(overrides: KotestWirespecGeneratorBuilder.() -> Unit): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        bodyInput = null; bodyOverrides = overrides
    }

    fun bodyListSize(size: Arb<Int>): EndpointCallBuilder<BodyT, Req, Resp> = apply { bodyListSize = size }

    inline fun <reified P : Wirespec.Path> path(value: P): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.pathClass.isInstance(value)) {
            "${reflection.endpointName}.path: expected ${reflection.pathClass.simpleName}, got ${P::class.simpleName}"
        }
        pathInput = Input.Literal(value)
    }

    fun path(builder: () -> Wirespec.Path): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        pathInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified Q : Wirespec.Queries> query(value: Q): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.queriesClass.isInstance(value)) {
            "${reflection.endpointName}.query: expected ${reflection.queriesClass.simpleName}, got ${Q::class.simpleName}"
        }
        queryInput = Input.Literal(value)
    }

    fun query(builder: () -> Wirespec.Queries): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        queryInput = Input.Lazy { builder() as Any }
    }

    inline fun <reified H : Wirespec.Request.Headers> header(value: H): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        require(reflection.headersClass.isInstance(value)) {
            "${reflection.endpointName}.header: expected ${reflection.headersClass.simpleName}, got ${H::class.simpleName}"
        }
        headerInput = Input.Literal(value)
    }

    fun header(builder: () -> Wirespec.Request.Headers): EndpointCallBuilder<BodyT, Req, Resp> = apply {
        headerInput = Input.Lazy { builder() as Any }
    }

    // ---- terminals (eager, suspend) ----

    suspend inline fun <reified R : Resp> expecting(): R = expecting(R::class)

    suspend fun <R : Resp> expecting(variantClass: KClass<R>): R {
        expectedStatuses = setOf(statusOf(variantClass))
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeEndpoint(this) as R
    }

    suspend inline fun <reified R : Resp> expecting(noinline block: (R) -> Unit): R = expecting(R::class, block)

    suspend fun <R : Resp> expecting(variantClass: KClass<R>, block: (R) -> Unit): R {
        expectedStatuses = setOf(statusOf(variantClass))
        @Suppress("UNCHECKED_CAST")
        customAssertion = { response -> block(response as R) }
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeEndpoint(this) as R
    }

    suspend inline fun <reified R : Resp, T> returning(noinline projection: (R) -> T): T = returning(R::class, projection)

    suspend fun <R : Resp, T> returning(variantClass: KClass<R>, projection: (R) -> T): T {
        expectedStatuses = setOf(statusOf(variantClass))
        val resp = CallExecutor.executeEndpoint(this)
        @Suppress("UNCHECKED_CAST")
        return projection(resp as R)
    }

    suspend inline fun <reified R : Resp> collecting(count: Int, noinline block: (List<R>) -> Unit) =
        collecting(R::class, StreamingMode.ByCount(count), block)

    suspend inline fun <reified R : Resp> collecting(duration: Duration, noinline block: (List<R>) -> Unit) =
        collecting(R::class, StreamingMode.ByDuration(duration), block)

    suspend fun <R : Resp> collecting(variantClass: KClass<R>, mode: StreamingMode, block: (List<R>) -> Unit) {
        expectedStatuses = setOf(statusOf(variantClass))
        streamingMode = mode
        val resp = CallExecutor.executeEndpoint(this)
        @Suppress("UNCHECKED_CAST")
        block(listOf(resp as R))
    }

    @PublishedApi
    internal fun statusOf(variantClass: KClass<*>): Int {
        val name = variantClass.simpleName
            ?: error("Anonymous response variant class — pass a named ResponseNNN class.")
        val match = STATUS_REGEX.matchEntire(name)
            ?: error("Response variant class name '$name' doesn't match ResponseNNN. Use a Wirespec-generated response variant.")
        return match.groupValues[1].toInt()
    }

    sealed class StreamingMode {
        data class ByCount(val count: Int) : StreamingMode()
        data class ByDuration(val duration: Duration) : StreamingMode()
    }

    companion object {
        @PublishedApi
        internal val STATUS_REGEX = Regex("Response(\\d{3})")
    }
}

@DslMarker
annotation class WirespecScenarioDsl
```

Note: `statusOf` and `STATUS_REGEX` are now `@PublishedApi internal` because the `suspend inline` reified terminals delegate to non-inline overloads that reference them — but the reified terminals themselves only call the non-inline `expecting(R::class)` / `returning(R::class, …)`, so `statusOf` stays out of inline bodies. They are marked `@PublishedApi` defensively so the public-inline boundary is clean; if the compiler does not require it you may revert them to `private`.

- [ ] **Step 3: Replace `ChannelCallBuilder.kt` entirely**

Overwrite `core/src/main/kotlin/io/kotest/extensions/wirespec/dsl/ChannelCallBuilder.kt` with:

```kotlin
package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.integration.kotest.KotestWirespecGeneratorBuilder
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.runtime.CallExecutor
import io.kotest.extensions.wirespec.validation.ChannelReflection
import io.kotest.property.Arb
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@WirespecScenarioDsl
class ChannelCallBuilder<MessageT : Any> internal constructor(
    channelClass: KClass<out Wirespec.Channel>,
) {

    @PublishedApi
    internal val reflection: ChannelReflection = ChannelReflection.of(channelClass)

    internal var topicInput: Input<String>? = null
    internal var keyInput: Input<String>? = null
    internal var sendInput: Input<Any>? = null
    internal var sendOverrides: (KotestWirespecGeneratorBuilder.() -> Unit)? = null
    internal var direction: Direction? = null
    internal var expectedClass: KClass<*>? = null
    internal var customAssertion: ((Any) -> Unit)? = null
    internal var collectMode: CollectMode? = null

    fun topic(value: String): ChannelCallBuilder<MessageT> = apply { topicInput = Input.Literal(value) }
    fun topic(builder: () -> String): ChannelCallBuilder<MessageT> = apply { topicInput = Input.Lazy(builder) }
    fun key(value: String): ChannelCallBuilder<MessageT> = apply { keyInput = Input.Literal(value) }

    // ---- send terminals (eager, suspend) — return the sent payload ----

    suspend fun send(value: MessageT): MessageT {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.Literal(value as Any)
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend fun send(arb: Arb<MessageT>): MessageT {
        requireNotExpecting()
        @Suppress("UNCHECKED_CAST")
        sendInput = Input.FromArb(arb as Arb<Any>)
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend fun send(overrides: KotestWirespecGeneratorBuilder.() -> Unit): MessageT {
        requireNotExpecting()
        sendInput = null
        sendOverrides = overrides
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend fun send(): MessageT {
        requireNotExpecting()
        sendInput = null
        sendOverrides = {}
        direction = Direction.Send
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    // ---- receive terminals (eager, suspend) ----

    suspend fun expecting(): MessageT {
        requireNotSending()
        direction = Direction.Expect
        customAssertion = null
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as MessageT
    }

    suspend inline fun <reified R : MessageT> expecting(noinline block: (R) -> Unit): R = expecting(R::class, block)

    suspend fun <R : MessageT> expecting(messageClass: KClass<R>, block: (R) -> Unit): R {
        requireNotSending()
        direction = Direction.Expect
        expectedClass = messageClass
        @Suppress("UNCHECKED_CAST")
        customAssertion = { msg -> block(msg as R) }
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as R
    }

    suspend inline fun <reified R : MessageT> collecting(count: Int, noinline block: (List<R>) -> Unit): List<R> =
        collecting(R::class, CollectMode.ByCount(count), block)

    suspend inline fun <reified R : MessageT> collecting(duration: Duration, noinline block: (List<R>) -> Unit): List<R> =
        collecting(R::class, CollectMode.ByDuration(duration), block)

    suspend fun <R : MessageT> collecting(messageClass: KClass<R>, mode: CollectMode, block: (List<R>) -> Unit): List<R> {
        requireNotSending()
        direction = Direction.Collect
        expectedClass = messageClass
        collectMode = mode
        @Suppress("UNCHECKED_CAST")
        customAssertion = { list -> block(list as List<R>) }
        @Suppress("UNCHECKED_CAST")
        return CallExecutor.executeChannel(this) as List<R>
    }

    suspend inline fun <reified R : MessageT, T> returning(noinline projection: (R) -> T): T = returning(R::class, projection)

    suspend fun <R : MessageT, T> returning(messageClass: KClass<R>, projection: (R) -> T): T {
        if (direction == null) {
            direction = Direction.Expect
            expectedClass = messageClass
        }
        val subject = CallExecutor.executeChannel(this)
        @Suppress("UNCHECKED_CAST")
        return projection(subject as R)
    }

    /** Compute (atLeast, within) — used by the executor. */
    internal fun receivePolicy(): Pair<Int, Duration> = when (val m = collectMode) {
        is CollectMode.ByCount -> m.count to (m.count.coerceAtLeast(1).seconds)
        is CollectMode.ByDuration -> 0 to m.duration
        null -> 1 to 2.seconds
    }

    @PublishedApi
    internal fun requireNotSending() = check(direction != Direction.Send) {
        "Channel ${reflection.channelName}: cannot set both `send` and `expecting`/`collecting` on the same call."
    }

    @PublishedApi
    internal fun requireNotExpecting() {
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

- [ ] **Step 4: Compile main sources**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL. If `requireNotSending`/`requireNotExpecting`/`statusOf` raise "public-API inline function cannot access non-public-API" — they are already `@PublishedApi internal`; if the compiler instead complains they're unused as `@PublishedApi`, that's only a warning.

- [ ] **Step 5: Commit**

```bash
git add -A core/src/main/kotlin/io/kotest/extensions/wirespec
git commit -m "feat(core): eager suspend terminals; remove scenario/ScenarioBuilder/ResultRef/Step/ScenarioRunner"
```

---

### Task 5: Update core tests for the eager model

**Files:**
- Delete: `core/src/test/kotlin/io/kotest/extensions/wirespec/ScenarioTest.kt`
- Rename+rewrite: `core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunnerChannelTest.kt` → `CallExecutorChannelTest.kt`
- Rewrite: `core/src/test/kotlin/io/kotest/extensions/wirespec/dsl/ChannelCallBuilderTest.kt`

- [ ] **Step 1: Delete the scenario test**

```bash
git rm core/src/test/kotlin/io/kotest/extensions/wirespec/ScenarioTest.kt
```

- [ ] **Step 2: Replace the channel runner test with an executor test**

```bash
git mv core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/ScenarioRunnerChannelTest.kt \
       core/src/test/kotlin/io/kotest/extensions/wirespec/runtime/CallExecutorChannelTest.kt
```

Overwrite `CallExecutorChannelTest.kt` with:

```kotlin
package io.kotest.extensions.wirespec.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.wirespec.WirespecChannelContext
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.channel.InMemoryMessageTransport
import io.kotest.extensions.wirespec.dsl.channelCall
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

data class GreetingPayload(val text: String)

fun interface GreetingChannelStub : Wirespec.Channel {
    operator fun invoke(message: GreetingPayload)
}

class CallExecutorChannelTest : FunSpec({

    val serialization: Wirespec.Serialization = WirespecSerialization(jacksonObjectMapper())
    val noopHttp = object : Wirespec.Transportation {
        override suspend fun transport(request: Wirespec.RawRequest): Wirespec.RawResponse =
            error("no endpoint step in this test")
    }
    val httpCtx = WirespecTestContext(noopHttp, serialization)

    test("send then expecting on the same topic — round-trip via InMemory transport") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        withWirespec(httpCtx, channelCtx, seed = 1L) {
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("greetings").send(GreetingPayload("hi"))
            val received = channelCall<GreetingPayload>(GreetingChannelStub::class).topic("greetings").expecting()
            received shouldBe GreetingPayload("hi")
        }
    }

    test("expecting times out and reports surplus/zero records") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        val ex = runCatching {
            withWirespec(httpCtx, channelCtx, seed = 1L) {
                channelCall<GreetingPayload>(GreetingChannelStub::class).topic("never-published").expecting()
            }
        }.exceptionOrNull() ?: error("expected timeout assertion error")
        ex.message!! shouldContain "expected exactly 1 message"
    }

    test("collecting(count) gathers exactly that many records") {
        val channelCtx = WirespecChannelContext(InMemoryMessageTransport(), serialization)
        withWirespec(httpCtx, channelCtx, seed = 1L) {
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("a"))
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("b"))
            channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t").send(GreetingPayload("c"))
            val collected = channelCall<GreetingPayload>(GreetingChannelStub::class).topic("t")
                .collecting<GreetingPayload>(count = 3) { }
            collected shouldBe listOf(GreetingPayload("a"), GreetingPayload("b"), GreetingPayload("c"))
        }
    }
})
```

- [ ] **Step 3: Rewrite `ChannelCallBuilderTest.kt` to state-only assertions**

Overwrite with:

```kotlin
package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

// Minimal generated-channel stand-in: a `fun interface` with one invoke(message: T).
fun interface PetCreatedChannelStub : Wirespec.Channel {
    operator fun invoke(message: String)
}

class ChannelCallBuilderTest : FunSpec({

    test("topic(value) sets a literal topic input") {
        val call = channelCall<String>(PetCreatedChannelStub::class)
        call.topic("pets.events")
        call.topicInput shouldBe Input.Literal("pets.events")
    }

    test("topic(builder) sets a lazy topic input") {
        val call = channelCall<String>(PetCreatedChannelStub::class)
        call.topic { "dynamic.topic" }
        (call.topicInput as Input.Lazy).builder() shouldBe "dynamic.topic"
    }

    test("collecting mode drives the receive policy") {
        val call = channelCall<String>(PetCreatedChannelStub::class)
        call.collectMode = ChannelCallBuilder.CollectMode.ByCount(3)
        val (atLeast, _) = call.receivePolicy()
        atLeast shouldBe 3
    }
})
```

- [ ] **Step 4: Run the full core test suite**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL — `WirespecAmbientTest`, `CallExecutorChannelTest`, `ChannelCallBuilderTest`, and the untouched `InputTest`, `ContextRegistryTest`, validation tests all pass.

- [ ] **Step 5: Commit**

```bash
git add -A core/src/test
git commit -m "test(core): replace scenario tests with ambient/executor tests for the eager DSL"
```

---

## Phase 3 — Emitter: per-controller catalogs + eager generated DSL

### Task 6: Per-controller catalog grouping in `TypesafeDslEmitter` + `CatalogFileEmitter`

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitter.kt:20-47`
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/CatalogFileEmitter.kt` (whole file)
- Modify: `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitterTest.kt`
- Modify: `emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/CatalogFileEmitterTest.kt`
- Delete: `emitter/src/test/resources/golden/WirespecCatalog.kt`

- [ ] **Step 1: Rewrite the catalog assertion test to expect per-controller objects**

In `TypesafeDslEmitterTest.kt`, replace the test `"emit appends one WirespecCatalog.kt aggregating every endpoint then channel"` with:

```kotlin
    test("emit groups endpoints/channels per module into one top-level catalog object each") {
        fun endpoint(name: String) = Endpoint(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier(name),
            method = Endpoint.Method.GET,
            path = listOf(Endpoint.Segment.Literal("api")),
            queries = emptyList(),
            headers = emptyList(),
            requests = listOf(Endpoint.Request(content = null)),
            responses = emptyList(),
        )
        val channel = Channel(
            comment = null,
            annotations = emptyList(),
            identifier = DefinitionIdentifier("PetCreatedChannel"),
            reference = Reference.Primitive(Reference.Primitive.Type.String(null), false),
        )
        val v1 = Module(FileUri("mem://PetControllerV1.ws"), nonEmptyListOf(endpoint("PetCreate"), endpoint("PetGet")))
        val publisher = Module(FileUri("mem://PetEventPublisher.ws"), nonEmptyListOf(channel))
        val ast = Root(nonEmptyListOf(v1, publisher))

        val emitter = TypesafeDslEmitter(PackageName("com.example.api"), EmitShared())
        val emitted = emitter.emit(ast, noLogger).toList()

        val v1Catalog = emitted.single { it.file == "com/example/api/kotest/PetControllerV1Catalog.kt" }.result
        v1Catalog.contains("public object PetControllerV1 {") shouldBe true
        v1Catalog.contains("public val petCreate: PetCreateCall") shouldBe true
        v1Catalog.contains("get() = PetCreateCall()") shouldBe true
        v1Catalog.contains("public val petGet: PetGetCall") shouldBe true

        val pubCatalog = emitted.single { it.file == "com/example/api/kotest/PetEventPublisherCatalog.kt" }.result
        pubCatalog.contains("public object PetEventPublisher {") shouldBe true
        pubCatalog.contains("public val petCreatedChannel: PetCreatedChannelCall") shouldBe true

        // No global catalog any more.
        emitted.none { it.file == "com/example/api/kotest/WirespecCatalog.kt" } shouldBe true
    }
```

(The `shouldContain` import may become unused — remove `import io.kotest.matchers.collections.shouldContain` only if the compiler flags it; the first test in the file still uses `shouldContain` on a list, so keep it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :emitter:test --tests '*TypesafeDslEmitterTest'`
Expected: FAIL — current emitter produces `WirespecCatalog.kt`, not per-controller files.

- [ ] **Step 3: Rewrite `CatalogFileEmitter.kt`**

```kotlin
package io.kotest.extensions.wirespec.emitter

import community.flock.wirespec.compiler.core.emit.Emitted
import community.flock.wirespec.compiler.core.emit.PackageName
import community.flock.wirespec.ir.core.file
import community.flock.wirespec.ir.generator.KotlinGenerator

/**
 * Emits one top-level catalog `object` per source controller (`.ws` module),
 * named after the controller. `PetControllerV1.createPet…` reads as a bare
 * object access — no `scenario { }` receiver — and IDE completion stays scoped
 * to that controller's operations.
 *
 * The generated `*Call` classes live in the same `<pkg>.kotest` package, so the
 * object references them without imports and reaches their `internal`
 * constructors within the consumer's generated module.
 */
object CatalogFileEmitter {

    fun emit(
        catalogName: String,
        endpointNames: List<String>,
        channelNames: List<String>,
        packageName: PackageName,
    ): Emitted {
        val kotestPkg = "${packageName.value}.kotest"
        val filePath = kotestPkg.replace('.', '/') + "/${catalogName}Catalog.kt"

        val irFile = file("${catalogName}Catalog") {
            `package`(kotestPkg)
            raw(renderCatalogObject(catalogName, endpointNames + channelNames))
        }

        return Emitted(file = filePath, result = KotlinGenerator.generate(irFile))
    }

    private fun renderCatalogObject(catalogName: String, names: List<String>): String = buildString {
        appendLine("public object $catalogName {")
        names.forEach { name ->
            val dslName = name.replaceFirstChar(Char::lowercaseChar)
            appendLine("    public val $dslName: ${name}Call")
            appendLine("        get() = ${name}Call()")
        }
        append("}")
    }
}
```

- [ ] **Step 4: Rewrite the catalog branch of `TypesafeDslEmitter.emit`**

Replace lines 22-47 (from `val statements = …` through the `catalog` assignment) with:

```kotlin
        val modules = ast.modules.toList()
        val allStatements = modules.flatMap { it.statements.toList() }
        val types = allStatements.filterIsInstance<Type>().associateBy { it.identifier.value }
        val refined = allStatements.filterIsInstance<Refined>().associateBy { it.identifier.value }

        val endpoints = allStatements.filterIsInstance<Endpoint>()
        val channels = allStatements.filterIsInstance<Channel>()

        val endpointDsl: List<Emitted> = endpoints.map { DslFileEmitter.emit(it, packageName, types, refined) }
        val channelDsl: List<Emitted> = channels.map { ChannelDslFileEmitter.emit(it, packageName, types, refined) }

        // One catalog object per source `.ws` module (controller), named after the
        // file. Modules with neither endpoints nor channels (e.g. types-only) emit
        // nothing.
        val catalog: List<Emitted> = modules.mapNotNull { module ->
            val moduleEndpoints = module.statements.toList().filterIsInstance<Endpoint>().map { it.identifier.value }
            val moduleChannels = module.statements.toList().filterIsInstance<Channel>().map { it.identifier.value }
            if (moduleEndpoints.isEmpty() && moduleChannels.isEmpty()) {
                null
            } else {
                CatalogFileEmitter.emit(
                    catalogName = catalogNameOf(module.fileUri.value),
                    endpointNames = moduleEndpoints,
                    channelNames = moduleChannels,
                    packageName = packageName,
                )
            }
        }
```

Then add this private helper to the `TypesafeDslEmitter` class (e.g. after `fixWirespecImport`):

```kotlin
    /** Derive the catalog object name from a module's `.ws` file URI basename. */
    private fun catalogNameOf(fileUri: String): String =
        fileUri.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".ws")
```

(The rest of `emit` — `fixWirespecImport`, `extra`, the return — is unchanged.)

- [ ] **Step 5: Delete the obsolete golden + rewrite `CatalogFileEmitterTest.kt`**

```bash
git rm emitter/src/test/resources/golden/WirespecCatalog.kt
```

Open `CatalogFileEmitterTest.kt` and update every `CatalogFileEmitter.emit(...)` call to the new signature and assertions. Replace the file body's tests with calls of the shape:

```kotlin
        val emitted = CatalogFileEmitter.emit(
            catalogName = "PetControllerV1",
            endpointNames = listOf("PetCreate", "PetGet"),
            channelNames = emptyList(),
            packageName = PackageName("com.example.api"),
        )
        emitted.file shouldBe "com/example/api/kotest/PetControllerV1Catalog.kt"
        emitted.result.contains("public object PetControllerV1 {") shouldBe true
        emitted.result.contains("public val petCreate: PetCreateCall") shouldBe true
        emitted.result.contains("get() = PetCreateCall()") shouldBe true
        emitted.result.contains("ScenarioBuilder") shouldBe false
```

(Keep the file's existing imports for `PackageName`, `FunSpec`, `shouldBe`; remove any now-unused ones the compiler flags.)

- [ ] **Step 6: Run the catalog tests**

Run: `./gradlew :emitter:test --tests '*TypesafeDslEmitterTest' --tests '*CatalogFileEmitterTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/CatalogFileEmitter.kt \
        emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitter.kt \
        emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/TypesafeDslEmitterTest.kt \
        emitter/src/test/kotlin/io/kotest/extensions/wirespec/emitter/CatalogFileEmitterTest.kt
git rm --cached emitter/src/test/resources/golden/WirespecCatalog.kt 2>/dev/null || true
git commit -m "feat(emitter): per-controller catalog objects grouped by source module"
```

---

### Task 7: Eager generated endpoint DSL (`DslFileEmitter`)

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt`
- Update goldens: `emitter/src/test/resources/golden/{NoSlots,PetCreate,PetGet,PetList,PetUpdate,PetCreateBulk,PetCreateNested,HeaderEndpoint}Dsl.kt`

- [ ] **Step 1: Update imports in `DslFileEmitter.emit` (lines 28-31)**

Replace:

```kotlin
            import("io.kotest.extensions.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")
            import("io.kotest.extensions.wirespec.dsl", "EndpointCallBuilder.StreamingMode")
```

with (the generated code no longer names `StreamingMode`, so its import is dropped to avoid an unused-import warning):

```kotlin
            import("io.kotest.extensions.wirespec.dsl", "endpointCall")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")
```

The `import("kotlin.time", "Duration")` line immediately below it stays (the `collecting(duration: Duration, …)` overload still uses it).

- [ ] **Step 2: Update `renderCallClass` (lines 64-74)**

Replace the two header lines:

```kotlin
        appendLine("public class ${shape.name}Call internal constructor(scenario: ScenarioBuilder) {")
        appendLine("    @PublishedApi internal val inner = scenario.endpoint(${shape.name}.Handler, ${shape.name})")
```

with:

```kotlin
        appendLine("public class ${shape.name}Call internal constructor() {")
        appendLine("    @PublishedApi internal val inner = endpointCall(${shape.name}.Handler, ${shape.name})")
```

- [ ] **Step 3: Drop the `ResultRef` path overload in `renderPathSlot` (lines 106-110)**

Delete this block:

```kotlin
        if (shape.pathFields.size == 1) {
            val f = shape.pathFields.single()
            appendLine("    public fun path(${f.name}: ResultRef<${f.kotlinType}>): $call =")
            appendLine("        apply { inner.path { ${shape.name}.Path(${f.name} = ${f.name}.require()) } }")
        }
```

- [ ] **Step 4: Rewrite `renderResponseDsl` (lines 236-249)**

Replace the whole function body with:

```kotlin
    private fun renderResponseDsl(shape: EndpointShape): String = buildString {
        val resp = "${shape.name}.Response<*>"
        appendLine("    public suspend inline fun <reified R : $resp> expecting(): R =")
        appendLine("        inner.expecting<R>()")
        appendLine("    public suspend inline fun <reified R : $resp> expecting(noinline block: (R) -> Unit): R =")
        appendLine("        inner.expecting<R>(block)")
        appendLine("    public suspend inline fun <reified R : $resp, T> returning(noinline projection: (R) -> T): T =")
        appendLine("        inner.returning<R, T>(projection)")
        appendLine("    public suspend inline fun <reified R : $resp> collecting(count: Int, noinline block: (List<R>) -> Unit) {")
        appendLine("        inner.collecting<R>(count, block)")
        appendLine("    }")
        appendLine("    public suspend inline fun <reified R : $resp> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {")
        append("        inner.collecting<R>(duration, block)\n    }")
    }
```

The body/path/query/header slot setters stay non-suspend and keep returning `${shape.name}Call` for chaining — no changes there.

- [ ] **Step 5: Regenerate the endpoint goldens**

Run: `./gradlew :emitter:test --tests '*DslFileEmitterTest'`
Expected: FAIL — each `emitted.result shouldBe readGolden(...)` mismatches.

For each failing golden, capture the new generated text and overwrite the golden file, then visually confirm it matches the new render rules (header `internal constructor()`, `@PublishedApi internal val inner = endpointCall(...)`, suspend terminals returning `R`/`T`, no `ResultRef` import or overload). Use this helper to print actual output for one fixture at a time, e.g. for PetGet:

```bash
cd /Users/wilmveel/Projects/kotest-wirespec
./gradlew :emitter:test --tests '*DslFileEmitterTest' --info 2>/dev/null | sed -n '/expected:/,/but was:/p' | head -80
```

Simpler and reliable: temporarily add a `println(emitted.result)` to the failing test, run it, copy the output into the golden, remove the `println`. Do this for `NoSlotsDsl.kt`, `PetGetDsl.kt`, `PetListDsl.kt`, `PetUpdateDsl.kt`, `PetCreateDsl.kt`, `PetCreateBulkDsl.kt`, `PetCreateNestedDsl.kt`, `HeaderEndpointDsl.kt`.

For reference, the regenerated `PetGetDsl.kt` golden must read exactly:

```kotlin
package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.endpointCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import kotlin.time.Duration
import com.example.api.endpoint.PetGet
@WirespecScenarioDsl
public class PetGetCall internal constructor() {
    @PublishedApi internal val inner = endpointCall(PetGet.Handler, PetGet)
    public fun path(id: String): PetGetCall =
        apply { inner.path(PetGet.Path(id = id)) }
    public fun path(builder: () -> PetGet.Path): PetGetCall =
        apply { inner.path(builder) }
    public suspend inline fun <reified R : PetGet.Response<*>> expecting(): R =
        inner.expecting<R>()
    public suspend inline fun <reified R : PetGet.Response<*>> expecting(noinline block: (R) -> Unit): R =
        inner.expecting<R>(block)
    public suspend inline fun <reified R : PetGet.Response<*>, T> returning(noinline projection: (R) -> T): T =
        inner.returning<R, T>(projection)
    public suspend inline fun <reified R : PetGet.Response<*>> collecting(count: Int, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(count, block)
    }
    public suspend inline fun <reified R : PetGet.Response<*>> collecting(duration: Duration, noinline block: (List<R>) -> Unit) {
        inner.collecting<R>(duration, block)
    }
}
```

- [ ] **Step 6: Re-run to green**

Run: `./gradlew :emitter:test --tests '*DslFileEmitterTest'`
Expected: PASS. Confirm no `println` debugging lines remain in the test file (`git diff` the test).

- [ ] **Step 7: Commit**

```bash
git add -A emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/DslFileEmitter.kt \
        emitter/src/test/resources/golden
git commit -m "feat(emitter): eager suspend endpoint DSL; drop ScenarioBuilder/ResultRef from generated calls"
```

---

### Task 8: Eager generated channel DSL (`ChannelDslFileEmitter`)

**Files:**
- Modify: `emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/ChannelDslFileEmitter.kt`
- Update goldens: `emitter/src/test/resources/golden/{SimplePayloadChannel,CustomPayloadChannel}Dsl.kt`

- [ ] **Step 1: Update imports in `ChannelDslFileEmitter.emit` (lines 28-30)**

Replace:

```kotlin
            import("io.kotest.extensions.wirespec.dsl", "ResultRef")
            import("io.kotest.extensions.wirespec.dsl", "ScenarioBuilder")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")
```

with:

```kotlin
            import("io.kotest.extensions.wirespec.dsl", "channelCall")
            import("io.kotest.extensions.wirespec.dsl", "WirespecScenarioDsl")
```

- [ ] **Step 2: Rewrite `renderCallClass` (lines 49-87)**

```kotlin
    private fun renderCallClass(shape: ChannelShape): String = buildString {
        val call = "${shape.name}Call"
        val payload = shape.payloadType
        appendLine("@WirespecScenarioDsl")
        appendLine("public class $call internal constructor() {")
        appendLine("    @PublishedApi internal val inner = channelCall<$payload>(${shape.name}::class)")
        appendLine("    public fun topic(value: String): $call =")
        appendLine("        apply { inner.topic(value) }")
        appendLine("    public fun key(value: String): $call =")
        appendLine("        apply { inner.key(value) }")
        appendLine("    public suspend fun send(): $payload =")
        appendLine("        inner.send()")
        appendLine("    public suspend fun send(value: $payload): $payload =")
        appendLine("        inner.send(value)")
        appendLine("    public suspend fun send(arb: Arb<$payload>): $payload =")
        appendLine("        inner.send(arb)")
        if (shape.payloadFields.isNotEmpty()) {
            appendLine("    public suspend fun send(block: ${payload}PayloadBuilder.() -> Unit): $payload {")
            appendLine("        val builder = ${payload}PayloadBuilder().apply(block)")
            appendLine("        return inner.send {")
            shape.payloadFields.forEach { f ->
                appendLine("            builder.${f.name}?.let { registerPath(\"${f.name}\") { it.asArb() } }")
            }
            appendLine("        }")
            appendLine("    }")
        }
        appendLine("    public suspend fun expecting(): $payload =")
        appendLine("        inner.expecting()")
        appendLine("    public suspend fun expecting(block: ($payload) -> Unit): $payload =")
        appendLine("        inner.expecting(block)")
        appendLine("    public suspend fun collecting(count: Int, block: (List<$payload>) -> Unit): List<$payload> =")
        appendLine("        inner.collecting(count, block)")
        appendLine("    public suspend fun collecting(duration: Duration, block: (List<$payload>) -> Unit): List<$payload> =")
        appendLine("        inner.collecting(duration, block)")
        appendLine("    public suspend fun <T> returning(projection: ($payload) -> T): T =")
        append("        inner.returning(projection)\n}")
    }
```

- [ ] **Step 3: Regenerate the channel goldens**

Run: `./gradlew :emitter:test --tests '*ChannelDslFileEmitterTest'`
Expected: FAIL. Regenerate `SimplePayloadChannelDsl.kt` and `CustomPayloadChannelDsl.kt` using the same `println(emitted.result)`-capture approach as Task 7 Step 5; confirm: `internal constructor()`, `channelCall<…>(…::class)`, suspend `send*` returning the payload, suspend `expecting`/`collecting`/`returning`, no `ResultRef` import or `topic(ref: ResultRef<String>)` overload.

- [ ] **Step 4: Re-run to green**

Run: `./gradlew :emitter:test`
Expected: PASS (whole emitter suite, including `ChannelShapeTest`, `EndpointShapeTest`, `KotlinTypeMapperTest`).

- [ ] **Step 5: Commit**

```bash
git add -A emitter/src/main/kotlin/io/kotest/extensions/wirespec/emitter/ChannelDslFileEmitter.kt \
        emitter/src/test/resources/golden
git commit -m "feat(emitter): eager suspend channel DSL; send returns payload, no ResultRef"
```

---

## Phase 4 — Migration: example specs + full build

### Task 9: Migrate the example specs to the flat DSL

**Files:**
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosSpec.kt`
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetChannelScenariosSpec.kt`
- Modify: `example/src/test/kotlin/io/kotest/extensions/wirespec/example/PetScenariosJUnitTest.kt`

- [ ] **Step 1: Regenerate the example's wirespec sources and confirm catalog object names**

Run: `./gradlew :example:compileTestKotlin -x test` (this triggers the gradle plugin → emitter to regenerate `example/build/generated/wirespec/...`). It will FAIL at the test sources (they still use `scenario`/`wirespec`), but the generated `.kotest/*Catalog.kt` files will be produced.

Then list the generated catalog object names to drive the migration:

```bash
ls example/build/generated/wirespec/io/kotest/extensions/wirespec/example/generated/kotest/*Catalog.kt
grep -h "public object" example/build/generated/wirespec/io/kotest/extensions/wirespec/example/generated/kotest/*Catalog.kt
```

Expected objects: `PetControllerV1` (createPet, getPet1, updatePet, deletePet, listPets, createPetsBulk), `PetControllerV2` (getPet2), and a channel catalog (`PetEventPublisher`, exposing `publishPetCreated` and `onCreatePetCommand`). If the channel object name or membership differs, use the actual names from this grep in the imports below.

- [ ] **Step 2: Rewrite `PetScenariosSpec.kt`**

```kotlin
package io.kotest.extensions.wirespec.example

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.WirespecExtension
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePetsBulk
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.endpoint.ListPets
import io.kotest.extensions.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.checkAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetScenariosSpec : FunSpec({

    test("pet CRUD") {
        val petId = PetControllerV1.createPet
            .returning<CreatePet.Response201, String> { it.body.id }

        PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()

        PetControllerV1.updatePet
            .path(petId)
            .body { name = Arb.constant("new name") }
            .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

        PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200> {
            it.body.name shouldBe "new name"
        }

        PetControllerV1.deletePet.path(id = petId).expecting<DeletePet.Response204>()
        PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response404>()
    }

    test("typesafe queries") {
        checkAll<Int>(iterations = 8) {
            repeat(25) { PetControllerV1.createPet.expecting<CreatePet.Response201>() }
            PetControllerV1.listPets
                .query(limit = 10, offset = 0)
                .expecting<ListPets.Response200> { resp ->
                    resp.body.content.size shouldBe resp.body.total.coerceAtMost(10)
                }
        }
    }

    test("createPetsBulk — body(count = 2..2) sends a 2-element list; response total=2") {
        PetControllerV1.createPetsBulk
            .body(count = 2..2) {
                name = Arb.constant("rex")
                species = Arb.constant("dog")
            }
            .expecting<CreatePetsBulk.Response201> { response ->
                response.body.total shouldBe 2
            }
    }

    test("createPetsBulk — default count (1..3) generates between 1 and 3 elements") {
        checkAll<Int>(iterations = 5) {
            PetControllerV1.createPetsBulk
                .body {
                    name = Arb.constant("polly")
                    species = Arb.constant("parrot")
                }
                .expecting<CreatePetsBulk.Response201> { response ->
                    response.body.total shouldBeGreaterThanOrEqual 1
                    response.body.total shouldBeLessThanOrEqual 3
                }
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

- [ ] **Step 3: Rewrite `PetChannelScenariosSpec.kt`**

```kotlin
package io.kotest.extensions.wirespec.example

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringRootTestExtension
import io.kotest.extensions.wirespec.WirespecExtension
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.extensions.wirespec.example.generated.kotest.PetEventPublisher
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.kafka.test.context.EmbeddedKafka
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events", "pets.commands"])
@ApplyExtension(SpringRootTestExtension::class, WirespecExtension::class)
class PetChannelScenariosSpec : FunSpec({

    test("HTTP create publishes a PetCreatedEvent") {
        val petId = PetControllerV1.createPet
            .body {
                name = Arb.string(minSize = 1, maxSize = 16)
                species = Arb.string(minSize = 1, maxSize = 8)
            }
            .returning<CreatePet.Response201, String> { it.body.id }

        PetEventPublisher.publishPetCreated
            .topic("pets.events")
            .expecting { it.id shouldBe petId }
    }

    test("Kafka command creates a pet") {
        val correlationId = PetEventPublisher.onCreatePetCommand
            .topic("pets.commands")
            .send()
            .correlationId

        // Async @KafkaListener path — retry the HTTP assertion until the
        // listener has drained the command (or the timeout fires).
        eventually(5.seconds) {
            PetControllerV1.getPet1.path(correlationId).expecting<GetPet1.Response200>()
        }
    }
}) {
    @Autowired
    protected lateinit var applicationContext: ApplicationContext
}
```

NOTE: `send().correlationId` assumes the command payload type exposes a `correlationId` property (it did via the old `.returning { it.correlationId }`). If the generated channel object name is not `PetEventPublisher` or `onCreatePetCommand` lives in a different `.ws` file, adjust the import/receiver using the Step 1 grep output.

- [ ] **Step 4: Rewrite `PetScenariosJUnitTest.kt`**

```kotlin
package io.kotest.extensions.wirespec.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import community.flock.wirespec.integration.jackson.kotlin.WirespecSerialization
import io.kotest.extensions.wirespec.WirespecTestContext
import io.kotest.extensions.wirespec.spring.http
import io.kotest.extensions.wirespec.withWirespec
import io.kotest.extensions.wirespec.example.generated.endpoint.CreatePet
import io.kotest.extensions.wirespec.example.generated.endpoint.DeletePet
import io.kotest.extensions.wirespec.example.generated.endpoint.GetPet1
import io.kotest.extensions.wirespec.example.generated.endpoint.UpdatePet
import io.kotest.extensions.wirespec.example.generated.kotest.PetControllerV1
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

/**
 * JUnit Jupiter twin of [PetScenariosSpec]. The flat DSL is framework-neutral:
 * on JUnit there's no kotest extension, so the ambient context is supplied with
 * `withWirespec(ctx) { … }` instead of `@ApplyExtension(WirespecExtension::class)`.
 */
@SpringBootTest(
    classes = [ExampleApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PetScenariosJUnitTest {

    @LocalServerPort
    var port: Int = 0

    private lateinit var ctx: WirespecTestContext

    @BeforeEach
    fun setUp() {
        ctx = WirespecTestContext.http(
            baseUrl = "http://localhost:$port",
            serialization = WirespecSerialization(jacksonObjectMapper()),
        )
    }

    @Test
    fun `pet CRUD`(): Unit = runBlocking {
        checkAll<Int>(iterations = 10) {
            withWirespec(ctx) {
                val petId = PetControllerV1.createPet
                    .returning<CreatePet.Response201, String> { it.body.id }

                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()

                val newName = Arb.string()
                PetControllerV1.updatePet
                    .path(petId)
                    .body { name = newName }
                    .expecting<UpdatePet.Response200> { it.body.name shouldNotBe null }

                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response200>()
                PetControllerV1.deletePet.path(id = petId).expecting<DeletePet.Response204>()
                PetControllerV1.getPet1.path(petId).expecting<GetPet1.Response404>()
            }
        }
    }
}
```

- [ ] **Step 5: Run the example module tests**

Run: `./gradlew :example:test`
Expected: BUILD SUCCESSFUL — all three specs pass against the running Spring context + EmbeddedKafka.

If `PetScenariosSpec."pet CRUD"` fails because the ambient `WirespecExtension` element didn't reach the test body (kotest dispatcher swap), see Risks below; the fallback is to confirm the element via a one-line `currentAmbient()` probe inside the test.

- [ ] **Step 6: Commit**

```bash
git add -A example/src/test
git commit -m "test(example): migrate specs to the flat wrapper-free wirespec DSL"
```

---

### Task 10: Full build + final verification

- [ ] **Step 1: Build everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL across `:core`, `:emitter`, `:spring`, `:example` (composite build rebuilds the emitter consumed by the gradle plugin).

- [ ] **Step 2: Grep for leftover references to removed APIs**

Run:

```bash
cd /Users/wilmveel/Projects/kotest-wirespec
grep -rn "scenario(" --include=*.kt core example spring | grep -v "build/" || echo "no scenario( references"
grep -rn "ScenarioBuilder\|ResultRef\|\.require()" --include=*.kt core example spring | grep -v "build/" || echo "no ScenarioBuilder/ResultRef references"
```

Expected: both print the "no … references" line (the only matches, if any, are in `docs/` or generated `build/` output).

- [ ] **Step 3: Final commit (if grep surfaced stragglers, fix then commit)**

```bash
git add -A
git commit -m "chore: remove remaining scenario/ResultRef references" --allow-empty
```

---

## Risks / Notes

- **Ambient element propagation through kotest's dispatcher.** `WirespecExtension.intercept` installs the element via `withContext(ambient) { execute(testCase) }`. Kotest may wrap the test body in its own `withContext` (timeouts, dispatcher) — coroutine-context elements are inherited across `withContext` unless explicitly replaced, so the element should survive. Task 9 Step 5 is the real validation; if it ever fails to resolve, add a temporary `currentAmbient()` probe at the top of a test to confirm presence, and (fallback) wrap the test body in `withWirespec(ctx) { }` using an explicit context as JUnit does.
- **`send().correlationId`** depends on the command payload type exposing `correlationId` (it did, via the old `.returning { it.correlationId }`). Confirm against the generated payload model during Task 9.
- **Channel catalog membership.** Task 9 Step 1's grep is the source of truth for the channel object name(s) and which channels they expose. Adjust imports if `onCreatePetCommand` and `publishPetCreated` are emitted under different `.ws` files (hence different catalog objects).
- **Endpoint `.collecting`** retains pre-existing single-response semantics (it wraps the one validated response in a `List`); no example exercises it. Not expanded here.
- **Golden files are derived artifacts.** Tasks 6-8 regenerate them deliberately from the new render functions (the render functions are the spec). Always eyeball the regenerated golden against the render rules before committing.
