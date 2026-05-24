package io.kotest.extensions.spring.wirespec.runtime

import community.flock.wirespec.integration.kotest.kotestWirespecKotlinGenerator
import community.flock.wirespec.kotlin.Wirespec
import io.kotest.extensions.spring.wirespec.WirespecChannelContext
import io.kotest.extensions.spring.wirespec.WirespecTestContext
import io.kotest.extensions.spring.wirespec.channel.OutgoingRecord
import io.kotest.extensions.spring.wirespec.dsl.ArbReceiver
import io.kotest.extensions.spring.wirespec.dsl.ChannelCallBuilder
import io.kotest.extensions.spring.wirespec.dsl.EndpointCallBuilder
import io.kotest.extensions.spring.wirespec.dsl.Input
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.Step
import io.kotest.extensions.spring.wirespec.validation.ChannelValidator
import io.kotest.extensions.spring.wirespec.validation.ContractValidator
import io.kotest.extensions.spring.wirespec.validation.EndpointReflection
import io.kotest.property.RandomSource
import kotlinx.coroutines.runBlocking

/**
 * Executes the captured endpoint-call sequence of a [ScenarioBuilder] against a
 * live Spring Boot test context.
 *
 * Per iteration, for each call in declaration order:
 *   1. Resolve slot inputs (literal / Arb / ResultRef) into constructor arg values.
 *   2. Default unset slots from the Wirespec-generated `*Generator` companions
 *      ([ArbReceiver.gen]) — sensible Arb defaults that respect the contract.
 *   3. Reflectively call the typed Request constructor (the IR emitter generates
 *      a user-facing secondary constructor whose parameters match our slots).
 *   4. Send via [Wirespec.ClientEdge.to] / [Wirespec.Transportation.transport] /
 *      [Wirespec.ClientEdge.from] — fully typed, no reflection on the transport
 *      itself.
 *   5. Auto-validate (status declared by the contract + body schema matches).
 *   6. Run optional narrowed-status assertion and the `returning` projection.
 *   7. Clear [ResultRef]s before the next iteration starts.
 *
 * Iteration uses Kotest's [RandomSource] threading so failures can be reproduced
 * by fixing the seed.
 */
internal class ScenarioRunner(
    private val scenario: ScenarioBuilder,
    private val endpointCtx: WirespecTestContext,
    private val channelCtx: WirespecChannelContext?,
    private val randomSource: RandomSource,
    private val arbReceiver: ArbReceiver,
) {

    private val transportation: Wirespec.Transportation get() = endpointCtx.transportation
    private val serialization: Wirespec.Serialization get() = endpointCtx.serialization

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
                val payload: Any = when {
                    call.sendInput != null -> call.sendInput!!.resolve(randomSource)
                    call.sendOverrides != null -> {
                        val generator = kotestWirespecKotlinGenerator(seed = randomSource.random.nextLong()) {
                            call.sendOverrides!!()
                        }
                        val payloadClass = (call.reflection.payloadType.classifier as? kotlin.reflect.KClass<*>)?.java
                            ?: error("Scenario step #${index + 1} (${call.reflection.channelName}): " +
                                "cannot resolve payload Java class from ${call.reflection.payloadType}.")
                        arbReceiver.generatorFor(payloadClass).generate(generator, emptyList())
                    }
                    else -> error("Scenario step #${index + 1} (${call.reflection.channelName}): " +
                        ".send(...) value not set.")
                }
                val bytes = ctx.serialization.serializeBody(payload, call.reflection.payloadType)
                runBlocking {
                    ctx.messaging.publish(OutgoingRecord(topic, key, bytes))
                }
                call.returningProjection?.let { proj ->
                    @Suppress("UNCHECKED_CAST")
                    val ref = call.returnedRef as ResultRef<Any?>
                    ref.set(proj.invoke(payload))
                }
            }
            ChannelCallBuilder.Direction.Expect,
            ChannelCallBuilder.Direction.Collect -> {
                val (atLeast, within) = call.receivePolicy()
                val records = runBlocking { ctx.messaging.receive(topic, atLeast, within) }
                val validator = ChannelValidator(call.reflection, ctx.serialization)
                val typed = records.map { rec ->
                    try {
                        validator.deserialize(rec.body)
                    } catch (t: Throwable) {
                        throw AssertionError(
                            "Scenario step #${index + 1} (${call.reflection.channelName}) failed to " +
                                "decode record on topic '$topic': ${t.message}",
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
            null -> error("Scenario step #${index + 1} (${call.reflection.channelName}): " +
                "set .send(...) or .expecting()/.collecting(...) before running the scenario.")
        }
    }

    private fun runOne(call: EndpointCallBuilder<*, *, *>, index: Int) {
        val reflection = call.reflection
        val request = reflection.buildRequest(resolveSlots(call, reflection, index))

        // Typed transport via the Wirespec.Client's ClientEdge — no reflection
        // on toRawRequest/fromRawResponse. At the boundary between our generic
        // EndpointCallBuilder<*, *, *> list and the call's concrete Req/Resp
        // we erase via star projections; the underlying call has matching
        // types by construction (enforced at the DSL surface).
        @Suppress("UNCHECKED_CAST")
        val starClient = call.client as Wirespec.Client<Wirespec.Request<Any>, Wirespec.Response<*>>
        val clientEdge = starClient.client(serialization)
        @Suppress("UNCHECKED_CAST")
        val rawRequest = clientEdge.to(request as Wirespec.Request<Any>)
        val rawResponse = runBlocking { transportation.transport(rawRequest) }

        val validator = ContractValidator(reflection, serialization)
        val typedResponse = try {
            validator.validate(rawResponse, expectedStatuses = call.expectedStatuses)
        } catch (t: Throwable) {
            throw AssertionError(
                "Scenario step #${index + 1} (${reflection.endpointName}) failed: ${t.message}",
                t,
            )
        }

        call.customAssertion?.invoke(typedResponse)

        call.returningProjection?.let { projection ->
            @Suppress("UNCHECKED_CAST")
            val ref = call.returnedRef as ResultRef<Any?>
            ref.set(projection.invoke(typedResponse))
        }
    }

    /**
     * Map each [EndpointCallBuilder] slot input to the Request constructor's
     * argument names. Multi-field slots (e.g., PetList's Queries with limit + offset)
     * accept a typed instance of the slot class which is then destructured.
     *
     * Unset slots fall through to a contract-driven default:
     *   - **body**: generate via the matching `<BodyT>Generator` companion.
     *   - **path / query / header**: defaults stay empty (singleton object) unless
     *     they have fields that line up with constructor params, in which case
     *     the user must supply them. (Phase 5 will extend defaults here.)
     */
    private fun resolveSlots(call: EndpointCallBuilder<*, *, *>, reflection: EndpointReflection, index: Int): Map<String, Any?> {
        val args = mutableMapOf<String, Any?>()

        // body slot: precedence is user-literal/Arb/Ref > registerPath/Field overrides
        // > fully-default Arb from the Wirespec *Generator companion.
        when {
            call.bodyInput != null -> {
                args["body"] = resolve(call.bodyInput!!)
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

        call.pathInput?.let { input ->
            distribute(
                resolved = resolve(input),
                fieldNames = reflection.pathFieldNames,
                slotName = "path",
                slotClass = reflection.pathClass,
                endpointName = reflection.endpointName,
                args = args,
            )
        }
        call.queryInput?.let { input ->
            distribute(
                resolved = resolve(input),
                fieldNames = reflection.queriesFieldNames,
                slotName = "query",
                slotClass = reflection.queriesClass,
                endpointName = reflection.endpointName,
                args = args,
            )
        }
        call.headerInput?.let { input ->
            distribute(
                resolved = resolve(input),
                fieldNames = reflection.headersFieldNames,
                slotName = "header",
                slotClass = reflection.headersClass,
                endpointName = reflection.endpointName,
                args = args,
            )
        }
        return args
    }

    private fun resolve(input: Input<Any>): Any = input.resolve(randomSource)

    private fun distribute(
        resolved: Any,
        fieldNames: List<String>,
        slotName: String,
        slotClass: Class<*>,
        endpointName: String,
        args: MutableMap<String, Any?>,
    ) {
        when {
            fieldNames.size == 1 && !slotClass.isInstance(resolved) -> {
                args[fieldNames[0]] = resolved
            }
            slotClass.isInstance(resolved) -> {
                for (name in fieldNames) {
                    val field = slotClass.getDeclaredField(name)
                    field.isAccessible = true
                    args[name] = field.get(resolved)
                }
            }
            fieldNames.isEmpty() -> {
                // Slot is a data object — value is unused. Ignored.
            }
            else -> error(
                "Endpoint $endpointName: slot `$slotName` has ${fieldNames.size} fields ($fieldNames) " +
                    "but received a value of type ${resolved::class.simpleName} that is neither a single " +
                    "field value nor an instance of ${slotClass.simpleName}.",
            )
        }
    }
}
