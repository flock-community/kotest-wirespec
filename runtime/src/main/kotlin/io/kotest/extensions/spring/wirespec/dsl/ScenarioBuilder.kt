package io.kotest.extensions.spring.wirespec.dsl

import community.flock.wirespec.kotlin.Wirespec

@WirespecScenarioDsl
class ScenarioBuilder internal constructor(
    val arb: ArbReceiver,
) {

    internal val calls: MutableList<EndpointCallBuilder<*, *, *>> = mutableListOf()

    fun <BodyT : Any, Req : Wirespec.Request<BodyT>, Resp : Wirespec.Response<*>> endpoint(
        client: Wirespec.Client<Req, Resp>,
        endpointObject: Wirespec.Endpoint,
    ): EndpointCallBuilder<BodyT, Req, Resp> =
        EndpointCallBuilder(this, client, endpointObject)

    internal fun register(call: EndpointCallBuilder<*, *, *>) {
        calls += call
    }

    internal fun clearRefs() {
        calls.forEach { it.returnedRef?.clear() }
    }
}
