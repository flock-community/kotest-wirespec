package io.kotest.extensions.wirespec.runtime

import community.flock.wirespec.integration.kotest.kotestWirespecKotlinGenerator
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.wirespec.channel.OutgoingRecord
import io.kotest.extensions.wirespec.dsl.ArbReceiver
import io.kotest.extensions.wirespec.dsl.ChannelCallBuilder
import io.kotest.extensions.wirespec.dsl.EndpointCallBuilder
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
 * resolution -> typed transport -> contract validation -> user assertion), but
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
     *  - Send  -> the sent payload,
     *  - Expect -> the single received message,
     *  - Collect -> the received `List<message>`.
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
