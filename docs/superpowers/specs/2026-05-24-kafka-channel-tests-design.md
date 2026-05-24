# Kafka channel tests — design

**Status:** draft
**Date:** 2026-05-24
**Author:** brainstorming session

## Problem

`wirespec-spring-extractor` 0.0.7 added Kafka extraction: `@KafkaListener`
methods and `kafkaTemplate.send(...)` call sites are now emitted as Wirespec
`channel` definitions in the `.ws` files, alongside `endpoint`s. The
`kotest-extensions-spring-wirespec` DSL today only generates Kotest scenarios
for `endpoint`s — channels are ignored. We want the same compile-time
contract-drift guarantee and property-based scenario ergonomics for Kafka
producers and consumers.

## Constraints (from brainstorming)

- **Both directions** in scope: producer-side (test asserts the app publishes a
  message) and consumer-side (test publishes a stimulus, asserts app reacts).
- **EmbeddedKafka only** for v1 (Spring's `@EmbeddedKafka`). Testcontainers
  deferred.
- **Unified scenario block** — endpoint and channel steps interleave in one
  `scenario { … }` so an HTTP step and a downstream Kafka assertion live in the
  same declaration order, sharing `ResultRef`s.
- **Two contexts, channel-named.** `WirespecTestContext` (HTTP, existing)
  + new `WirespecChannelContext` (messaging). Both are passed into the
  scenario. Name is `channel`, not `kafka`, so future messaging transports drop
  in without renaming.
- **Topic is required per call** — `.topic("…")`. The extracted contract does
  not preserve topic names; explicit user input is the only correct answer.
- **Lean on default `kotest-extensions-spring`.** No new Kotest listener; we
  consume beans Spring already wires under `@EmbeddedKafka`.

## Out of scope

- Schema Registry, Avro/Protobuf payloads.
- Kafka record headers in the DSL.
- Testcontainers transport.
- Inferring channel direction from the contract — the extracted `Channel`
  model has only `name + payload`; direction is expressed at call site via
  `.send(...)` vs `.expecting<...>()`.
- Long-running streaming consumers. `.collecting(duration = …)` covers the
  bounded-window use case; anything longer is the user's own infra.

## Architecture overview

```
                              .ws  ── endpoint Foo ──┐
                              .ws  ── channel Bar ──┐│
                                                    ▼▼
                                ┌──────────────────────────────┐
                                │ KotlinIrEmitter (wirespec)   │
                                │  → endpoint/Foo.kt           │
                                │  → channel/Bar.kt            │
                                └──────────────────────────────┘
                                                    │
                                                    ▼
                                ┌──────────────────────────────┐
                                │ TypesafeDslEmitter (us)      │
                                │  → kotest/FooDsl.kt          │
                                │  → kotest/BarDsl.kt   (NEW)  │
                                └──────────────────────────────┘

scenario(endpointCtx, channelCtx) {
    createPet.body(...).expecting<...>()      ──► uses endpointCtx.transportation
    petCreatedChannel                         ──► uses channelCtx.messaging
        .topic("pets.events")
        .expecting<PetCreated> { ... }
}
```

Four existing layers each get a sibling for channels:

| Layer | Today | New |
|---|---|---|
| Runtime context | `WirespecTestContext { transportation, serialization }` | `WirespecChannelContext { messaging, serialization }` |
| DSL call class | `EndpointCallBuilder<*, *, *>` | `ChannelCallBuilder<MessageT>` |
| Spring transport | `MockMvcTransportation`, `WebClientTransportation` (`Wirespec.Transportation`) | `EmbeddedKafkaMessageTransport` (`MessageTransport`) |
| Emitter | per-endpoint `<Name>Dsl.kt` via `DslFileEmitter.emit(endpoint, …)` | per-channel `<Name>ChannelDsl.kt` via new `ChannelDslFileEmitter` |

Plugin/build wiring is unchanged. The Gradle/Maven plugins already invoke
`TypesafeDslEmitter`; the only required build-side change is bumping
`wirespecExtractorVersion=0.0.8` in `gradle.properties`.

## Channel DSL surface

For `channel PetCreatedChannel -> PetCreated`, the emitter produces
`kotest/PetCreatedChannelDsl.kt`:

```kotlin
public val ScenarioBuilder.petCreatedChannel: PetCreatedChannelCall
    get() = PetCreatedChannelCall(this)

@WirespecScenarioDsl
public class PetCreatedChannelCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.channel(PetCreatedChannel::class)

    // --- topic (required, one of) ---
    public fun topic(value: String): PetCreatedChannelCall =
        apply { inner.topic(value) }
    public fun topic(ref: ResultRef<String>): PetCreatedChannelCall =
        apply { inner.topic { ref.require() } }

    // --- optional partition key for producer side ---
    public fun key(value: String): PetCreatedChannelCall =
        apply { inner.key(value) }

    // --- direction 1: test publishes (drive an app @KafkaListener) ---
    public fun send(value: PetCreated): PetCreatedChannelCall =
        apply { inner.send(value) }
    public fun send(arb: Arb<PetCreated>): PetCreatedChannelCall =
        apply { inner.send(arb) }
    public fun send(block: PetCreatedBodyBuilder.() -> Unit): PetCreatedChannelCall =
        apply { /* same per-field Arb-overrides pattern as endpoint body */ }

    // --- direction 2: test consumes (assert what the app published) ---
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
```

Notes:

- **Direction is method-level, not type-level.** `send(...)` and
  `expecting(...)` both exist on every channel call. Neither is
  `inline reified` — Wirespec channels are single-payload, so there is no
  status-variant narrowing to do.
- **`.topic(...)` is required.** Missing topic at run time fails fast with a
  precise step-indexed error.
- **`.topic(ref)`** lets an earlier endpoint step produce the topic name as a
  `ResultRef<String>`. Symmetric with `path(ref)` on endpoints.
- **`send(block)`** overrides reuse `KotestWirespecGeneratorBuilder` — same
  machinery the endpoint `body(block)` already uses.
- **No headers slot in v1.** Wirespec channels don't model record headers;
  adding them is forward-compatible.
- **Setting `send` and `expecting` on the same call is a configuration
  error** caught before any transport call.

## MessageTransport interface

```kotlin
// runtime/src/main/kotlin/.../channel/MessageTransport.kt
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
)

data class IncomingRecord(
    val topic: String,
    val key: String?,
    val body: ByteArray,
)
```

Symmetric to `Wirespec.Transportation`: raw bytes in/out, the runner's
`Wirespec.Serialization` handles typed conversion. `OutgoingRecord` /
`IncomingRecord` are Kafka-shaped (topic + key + bytes) but uncoupled to
`org.apache.kafka.*` so NATS/RabbitMQ implementations can fit later.

`receive` semantics:

- `.expecting()`           → `receive(atLeast=1, within=2s)`, then assert
  `singleOrNull()` (timeout or surplus is a failure).
- `.collecting(count=N)`   → `receive(atLeast=N, within=N*1s)`.
- `.collecting(duration=d)`→ `receive(atLeast=0, within=d)`.

The transport returns *as soon as* `atLeast` records have been collected
**or** `within` has elapsed, whichever happens first. For `.expecting()`
specifically, the runner asserts that the returned list has exactly one
element — receiving zero (timeout) or more than one (surplus) is a step
failure.

## WirespecChannelContext

```kotlin
class WirespecChannelContext(
    val messaging: MessageTransport,
    val serialization: Wirespec.Serialization,
) {
    companion object {
        fun embeddedKafka(
            applicationContext: ApplicationContext,
            serialization: Wirespec.Serialization,
        ): WirespecChannelContext = WirespecChannelContext(
            messaging = EmbeddedKafkaMessageTransport(applicationContext),
            serialization = serialization,
        )
    }
}
```

## EmbeddedKafkaMessageTransport

```kotlin
class EmbeddedKafkaMessageTransport(
    private val applicationContext: ApplicationContext,
) : MessageTransport {

    private val producer: KafkaTemplate<String, ByteArray> by lazy {
        applicationContext.getBeanProvider(
            ResolvableType.forClassWithGenerics(
                KafkaTemplate::class.java, String::class.java, ByteArray::class.java
            )
        ).getIfAvailable()
            ?: error("No KafkaTemplate<String, ByteArray> bean is available. " +
                "Annotate the spec with @EmbeddedKafka(topics = [...]) and ensure " +
                "spring-kafka is on the test classpath.")
    }

    private val brokers: String by lazy {
        applicationContext.getBean(EmbeddedKafkaBroker::class.java).brokersAsString
    }

    override suspend fun publish(record: OutgoingRecord) { /* KafkaTemplate.send + .get */ }
    override suspend fun receive(topic: String, atLeast: Int, within: Duration): List<IncomingRecord> {
        // One short-lived KafkaConsumer<String, ByteArray> per call:
        //   - random group.id
        //   - auto.offset.reset=earliest
        //   - subscribe(topic); poll until atLeast records collected or `within` elapses
        //   - close()
    }
}
```

Key decisions:

- **`KafkaTemplate<String, ByteArray>`** so the runtime pre-serializes via
  `Wirespec.Serialization`. Avoids forcing the test app to register a typed
  `ProducerFactory<*, MyDto>` and avoids competing with the app's serializer
  config.
- **Per-call short-lived consumer.** No long-running poll loop, no shared
  state across scenario iterations. Random group id +
  `auto.offset.reset=earliest` so the consumer always sees records published
  earlier in the same scenario step.
- **Property-based-friendly.** `checkAll` iteration N+1's `.expecting` does
  not see iteration N's records — each call gets a fresh consumer group.

## SpringWirespecSpec wiring

`SpringWirespecSpec` already auto-resolves a `WirespecTestContext` from the
Spring `ApplicationContext`. Two changes:

```kotlin
abstract class SpringWirespecSpec(...) : FunSpec() {

    // RENAMED: defaultCtx → endpointCtx
    open val endpointCtx: WirespecTestContext by lazy { /* existing MockMvc bean lookup */ }

    // NEW
    open val channelCtx: WirespecChannelContext? by lazy {
        runCatching { WirespecChannelContext.embeddedKafka(applicationContext, ...) }
            .getOrNull()
    }

    fun test(name: String, iterations: Int = 1, body: ScenarioBuilder.() -> Unit) {
        super.test(name) {
            // iteration loop unchanged
            runScenarioOnce(endpointCtx, channelCtx, RandomSource.seeded(...), body)
        }
    }
}
```

- `defaultCtx` → `endpointCtx`. **Breaking change** for any subclass that
  overrides it; the rename is justified by the new symmetry with `channelCtx`.
- `channelCtx` is nullable. HTTP-only specs leave it null. A channel step on
  a null `channelCtx` fails fast at runtime with a clear remediation message.

## ScenarioBuilder, runner, and validation

### Heterogeneous step list

```kotlin
sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
    data class Channel(val call: ChannelCallBuilder<*>) : Step()
}
```

`ScenarioBuilder.calls: MutableList<EndpointCallBuilder<*,*,*>>` becomes
`steps: MutableList<Step>`. Declaration order is preserved across both kinds
— mixing endpoint and channel calls in the same scenario is the
point.

`ScenarioBuilder.endpoint(...)` registers an `Endpoint` step (existing path,
unchanged signature); a new `ScenarioBuilder.channel(channelClass)` registers
a `Channel` step.

### ScenarioRunner dispatch

```kotlin
internal class ScenarioRunner(
    private val scenario: ScenarioBuilder,
    private val endpointCtx: WirespecTestContext,
    private val channelCtx: WirespecChannelContext?,
    private val randomSource: RandomSource,
    private val arbReceiver: ArbReceiver,
) {
    fun run() {
        for ((index, step) in scenario.steps.withIndex()) {
            when (step) {
                is Step.Endpoint -> runEndpoint(step.call, index)   // existing
                is Step.Channel  -> runChannel(step.call, index)    // new
            }
        }
    }
}
```

A channel step against a null `channelCtx` throws:

> Scenario step #N (PetCreatedChannel) requires a channel context. Annotate
> the spec with `@EmbeddedKafka(topics = …)` or override `channelCtx`.

### runChannel

Direction is read off the call: `send*` set ⇒ producer step, `expecting`/
`collecting` set ⇒ consumer step. Both set on one call is a configuration
error, caught at the top of `runChannel` (before any transport call) with a
precise step-indexed message.

```kotlin
private fun runChannel(call: ChannelCallBuilder<*>, index: Int) {
    val ctx = channelCtx ?: error(...)
    val reflection = call.reflection
    val topic = call.topicInput?.resolve(randomSource)
        ?: error("Step #${index+1} (${reflection.channelName}): .topic(...) is required.")

    when (call.direction) {
        Direction.Send -> {
            val payload = resolveSendPayload(call, reflection)
            val bodyBytes = ctx.serialization.serializeBody(payload, reflection.payloadType)
            runBlocking {
                ctx.messaging.publish(OutgoingRecord(topic, call.keyInput?.resolve(randomSource), bodyBytes))
            }
            call.returningProjection?.let { /* set ResultRef from sent payload */ }
        }
        Direction.Expect, Direction.Collect -> {
            val (atLeast, within) = call.receivePolicy()
            val records = runBlocking { ctx.messaging.receive(topic, atLeast, within) }
            val typed = records.map { rec ->
                try {
                    ChannelValidator(reflection, ctx.serialization).deserialize(rec.body)
                } catch (t: Throwable) {
                    throw AssertionError(
                        "Scenario step #${index+1} (${reflection.channelName}) failed to " +
                            "decode record on topic '$topic': ${t.message}", t,
                    )
                }
            }
            if (call.direction == Direction.Expect) {
                val one = typed.singleOrNull()
                    ?: throw AssertionError(
                        "Scenario step #${index+1} (${reflection.channelName}): " +
                            "expected exactly 1 message on '$topic' within $within, got ${typed.size}."
                    )
                call.customAssertion?.invoke(one)
                call.returningProjection?.let { p ->
                    @Suppress("UNCHECKED_CAST")
                    (call.returnedRef as ResultRef<Any?>).set(p(one))
                }
            } else {
                call.customAssertion?.invoke(typed)
            }
        }
    }
}
```

### ChannelValidator

Mirrors `ContractValidator` for HTTP, minus status validation (channels
don't have statuses):

- **Decode** raw bytes via `ctx.serialization.deserializeBody(body, reflection.payloadType)`.
- On deserialization failure, surface
  `scenario · step · channel · raw · concrete mismatch` — same error shape
  endpoint failures already use.

### Property-based replay

Unchanged from today's HTTP path:

- `PropertyContext.randomSource()` flows in via `checkAll`; failing seed is
  reported and reproducible.
- `Arb<PetCreated>` for `.send` defaults is seeded from the iteration's
  `RandomSource`, so a failing run's published payload reproduces byte-for-
  byte.
- Per-call random consumer group id means cross-iteration record leakage
  cannot happen.

## Emitter changes

### `TypesafeDslEmitter.emit`

```kotlin
override fun emit(ast: AST, logger: Logger): NonEmptyList<Emitted> {
    val base = super.emit(ast, logger)
    val statements = ast.modules.toList().flatMap { it.statements.toList() }
    val types = statements.filterIsInstance<Type>().associateBy { it.identifier.value }

    val endpointDsl = statements.filterIsInstance<Endpoint>()
        .map { DslFileEmitter.emit(it, packageName, types) }
    val channelDsl = statements.filterIsInstance<Channel>()
        .map { ChannelDslFileEmitter.emit(it, packageName, types) }

    val extra = endpointDsl + channelDsl
    return if (extra.isEmpty()) base else NonEmptyList(base.head, base.tail + extra)
}
```

### `ChannelShape`

```kotlin
data class ChannelShape(
    val name: String,                 // PetCreatedChannel
    val dslName: String,              // petCreatedChannel
    val payloadType: String,          // PetCreated  (mapped via KotlinTypeMapper)
    val payloadFields: List<EndpointShape.NamedTypedField>, // for send(block) body builder
    val modelImports: List<String>,
)
```

Built from `community.flock.wirespec.compiler.core.parse.ast.Channel`. Field
discovery for `send(block)` reuses the existing `types: Map<String, Type>`
table that `EndpointShape` already takes — same `KotlinTypeMapper` for type
mapping, same `collectCustomNames` helper for imports.

### `ChannelDslFileEmitter`

Same shape as `DslFileEmitter`, simpler: one `topic` slot (required), one
`key` slot (optional), one `send` slot, one `expecting`/`collecting`/
`returning` block. No paths, no queries, no headers, no per-status DSL.

## Build wiring

- `gradle.properties`: bump `wirespecExtractorVersion=0.0.8`.
- `runtime/build.gradle.kts`: add
  `compileOnly("org.springframework.kafka:spring-kafka")` and
  `testImplementation("org.springframework.kafka:spring-kafka-test")`. The
  Spring Kafka deps stay test-scoped at the consumer level — the runtime
  only references `KafkaTemplate` / `EmbeddedKafkaBroker` through
  `compileOnly`, so an HTTP-only consumer doesn't pull spring-kafka at run
  time.
- `example/build.gradle.kts`: add `spring-kafka` (impl) +
  `spring-kafka-test` (test).
- No Gradle-plugin or Maven-plugin changes.

## Testing strategy

| Layer | Tests | Notes |
|---|---|---|
| Emitter | `ChannelDslFileEmitterTest` (golden files), `ChannelShapeTest` | One golden per axis: simple payload, refined payload, list payload. |
| Runtime DSL | `ChannelCallBuilderTest` | Missing `.topic`, `send + expecting` together, `.topic(ref)` resolution. |
| Runtime validation | `ChannelValidatorTest` | Round-trip + malformed payload + error-shape assertion. |
| Runtime runner | `ScenarioRunnerChannelTest` with `InMemoryMessageTransport` | Mixed step ordering, multi-iteration runs, ResultRef sharing across HTTP↔channel. |
| Example integration | `PetChannelScenariosSpec` with `@EmbeddedKafka` | The only test that exercises `EmbeddedKafkaMessageTransport` end-to-end. |

`InMemoryMessageTransport` is a simple deterministic fake (append on
`publish`, poll with the same `atLeast`/`within` semantics on `receive`) —
keeps the unit suite fast and broker-free.

## Example app changes

Add minimal Kafka surface for the integration spec:

- `example/src/main/kotlin/.../service/PetEventPublisher.kt` —
  `kafkaTemplate.send("pets.events", PetCreatedEvent(...))` inside the create
  flow. Extracted as a producer channel.
- `example/src/main/kotlin/.../service/PetCommandListener.kt` —
  `@KafkaListener(topics = ["pets.commands"])` that creates a pet on
  receipt. Extracted as a consumer channel.
- `example/src/test/kotlin/.../PetChannelScenariosSpec.kt` — both directions
  in a unified scenario (sketch):

```kotlin
@SpringBootTest(classes = [ExampleApplication::class])
@AutoConfigureMockMvc
@EmbeddedKafka(topics = ["pets.events", "pets.commands"])
class PetChannelScenariosSpec : SpringWirespecSpec({

    test("HTTP create publishes a PetCreated event", iterations = 10) {
        val petId = createPet
            .body { name = Arb.string(minSize = 1) }
            .returning<CreatePet.Response201, String> { it.body.id }

        // Channel calls are single-payload — no reified type parameter on
        // `expecting` / `returning`.
        petCreatedChannel
            .topic("pets.events")
            .expecting { it.id shouldBe petId.require() }
    }

    test("Kafka command creates a pet", iterations = 5) {
        val cmd = createPetCommandChannel
            .topic("pets.commands")
            .send { name = Arb.string(minSize = 1) }
            .returning { it.correlationId }

        eventually(2.seconds) {
            getPetByCorrelation
                .path(cmd)
                .expecting<GetPet.Response200>()
        }
    }
})
```

## Breaking changes (callers must update)

1. **`SpringWirespecSpec.defaultCtx` → `endpointCtx`.** Any subclass overriding
   `defaultCtx` needs the rename.
2. **`ScenarioBuilder.calls` → `steps`.** Internal-ish but a `MutableList`
   in source; any external code touching it must move to the sealed
   `Step` type.
3. **`scenario(ctx) { … }` extension** — additional overload added:
   `scenario(endpointCtx, channelCtx) { … }`. The single-arg overload remains
   for HTTP-only scenarios.

## Open questions resolved

- *Direction inference from contract?* No — AST loses producer/consumer
  distinction. Call site picks via `.send` vs `.expecting`.
- *Headers in v1?* No — additive later.
- *Topic from Spring property?* No — explicit `.topic(...)` only.
- *Testcontainers?* Deferred. `MessageTransport` interface keeps the door
  open.

## Build order (informs the implementation plan)

1. Emitter: `ChannelShape` + `ChannelDslFileEmitter` + `TypesafeDslEmitter`
   wires both endpoint and channel DSLs through. Golden tests pass.
2. Runtime DSL: `ChannelCallBuilder`, `Step` sealed type, `ScenarioBuilder`
   refactor (`calls → steps`, `.channel(...)` registration).
3. Runtime transport: `MessageTransport` interface, `InMemoryMessageTransport`
   fake.
4. Runtime runner: `runChannel` path + `ChannelValidator` + rename
   `defaultCtx → endpointCtx`.
5. Runtime real transport: `EmbeddedKafkaMessageTransport` +
   `WirespecChannelContext.embeddedKafka(...)`.
6. Example: Kafka producer + listener + `PetChannelScenariosSpec`.
7. Build: bump `wirespecExtractorVersion=0.0.8`.
8. README: parallel "Channels" section.
