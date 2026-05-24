package io.kotest.extensions.spring.wirespec.dsl

/**
 * One declared step inside a scenario. Endpoint steps and channel steps
 * interleave in declaration order; the runner dispatches on this type.
 *
 * Step.Channel is added in a later commit when ChannelCallBuilder lands.
 */
sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
}
