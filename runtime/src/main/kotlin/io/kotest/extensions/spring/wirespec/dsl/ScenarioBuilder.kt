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
