package io.kotest.extensions.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * Insert a fixed-duration pause as a scenario step. Useful between a
     * channel `.send(...)` step and a follow-up HTTP assertion when an
     * async `@KafkaListener` needs time to process.
     */
    fun delay(duration: Duration) {
        steps += Step.Delay(duration)
    }

    /**
     * Run [block]'s steps repeatedly until they all succeed or [timeout]
     * elapses. The block is evaluated on an inner [ScenarioBuilder] so any
     * endpoint/channel calls inside register as substeps of an
     * [Step.Eventually]. Between attempts the runner sleeps [interval].
     *
     * Use this when the next assertion depends on out-of-band async work —
     * e.g. asserting via HTTP that a `@KafkaListener` has processed a
     * message and updated state.
     */
    fun eventually(
        timeout: Duration,
        interval: Duration = 100.milliseconds,
        block: ScenarioBuilder.() -> Unit,
    ) {
        val sub = ScenarioBuilder(arb)
        sub.block()
        steps += Step.Eventually(timeout, interval, sub.steps.toList())
    }

    internal fun register(call: EndpointCallBuilder<*, *, *>) {
        steps += Step.Endpoint(call)
    }

    internal fun register(call: ChannelCallBuilder<*>) {
        steps += Step.Channel(call)
    }

    internal fun clearRefs() = clearRefsIn(steps)

    private fun clearRefsIn(stepList: List<Step>) {
        stepList.forEach { step ->
            when (step) {
                is Step.Endpoint -> step.call.returnedRef?.clear()
                is Step.Channel -> step.call.returnedRef?.clear()
                is Step.Delay -> Unit
                is Step.Eventually -> clearRefsIn(step.substeps)
            }
        }
    }
}
