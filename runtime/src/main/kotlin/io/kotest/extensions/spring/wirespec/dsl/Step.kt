package io.kotest.extensions.spring.wirespec.dsl

import kotlin.time.Duration

/**
 * One declared step inside a scenario. Endpoint steps and channel steps
 * interleave in declaration order; the runner dispatches on this type.
 */
sealed class Step {
    data class Endpoint(val call: EndpointCallBuilder<*, *, *>) : Step()
    data class Channel(val call: ChannelCallBuilder<*>) : Step()
    /**
     * Pause the scenario for [duration]. Useful between a channel `.send(...)`
     * step and a follow-up HTTP assertion when the app's `@KafkaListener` (or
     * any async path) needs time to process. The runner just sleeps; the
     * scenario stays serial.
     */
    data class Delay(val duration: Duration) : Step()
}
