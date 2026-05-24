package com.example.api.kotest
import io.kotest.extensions.spring.wirespec.dsl.ResultRef
import io.kotest.extensions.spring.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.spring.wirespec.dsl.WirespecScenarioDsl
import io.kotest.property.Arb
import kotlin.time.Duration
import com.example.api.channel.SimplePayloadChannel
public val ScenarioBuilder.simplePayloadChannel: SimplePayloadChannelCall
    get() = SimplePayloadChannelCall(this)
@WirespecScenarioDsl
public class SimplePayloadChannelCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.channel<String>(SimplePayloadChannel::class)
    public fun topic(value: String): SimplePayloadChannelCall =
        apply { inner.topic(value) }
    public fun topic(ref: ResultRef<String>): SimplePayloadChannelCall =
        apply { inner.topic { ref.require() } }
    public fun key(value: String): SimplePayloadChannelCall =
        apply { inner.key(value) }
    public fun send(): SimplePayloadChannelCall =
        apply { inner.send() }
    public fun send(value: String): SimplePayloadChannelCall =
        apply { inner.send(value) }
    public fun send(arb: Arb<String>): SimplePayloadChannelCall =
        apply { inner.send(arb) }
    public fun expecting(): SimplePayloadChannelCall =
        apply { inner.expecting() }
    public fun expecting(block: (String) -> Unit): SimplePayloadChannelCall =
        apply { inner.expecting(block) }
    public fun collecting(count: Int, block: (List<String>) -> Unit): SimplePayloadChannelCall =
        apply { inner.collecting(count, block) }
    public fun collecting(duration: Duration, block: (List<String>) -> Unit): SimplePayloadChannelCall =
        apply { inner.collecting(duration, block) }
    public fun <T> returning(projection: (String) -> T): ResultRef<T> =
        inner.returning(projection)
}
