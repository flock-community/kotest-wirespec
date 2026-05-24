package io.kotest.extensions.spring.wirespec.dsl

/**
 * One declared step inside a scenario. Endpoint steps and channel steps
 * interleave in declaration order; the runner dispatches on this type.
 */
sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
    data class Channel(val call: ChannelCallBuilder<*>) : Step()
}
