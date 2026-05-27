package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.ResultRef
import io.kotest.extensions.wirespec.dsl.ScenarioBuilder
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.property.Arb
import kotlin.time.Duration
import com.example.api.channel.PetCreatedChannel
import com.example.api.model.PetCreated
@WirespecScenarioDsl
public class PetCreatedChannelCall internal constructor(scenario: ScenarioBuilder) {
    @PublishedApi internal val inner = scenario.channel<PetCreated>(PetCreatedChannel::class)
    public fun topic(value: String): PetCreatedChannelCall =
        apply { inner.topic(value) }
    public fun topic(ref: ResultRef<String>): PetCreatedChannelCall =
        apply { inner.topic { ref.require() } }
    public fun key(value: String): PetCreatedChannelCall =
        apply { inner.key(value) }
    public fun send(): PetCreatedChannelCall =
        apply { inner.send() }
    public fun send(value: PetCreated): PetCreatedChannelCall =
        apply { inner.send(value) }
    public fun send(arb: Arb<PetCreated>): PetCreatedChannelCall =
        apply { inner.send(arb) }
    public fun send(block: PetCreatedPayloadBuilder.() -> Unit): PetCreatedChannelCall = apply {
        val builder = PetCreatedPayloadBuilder().apply(block)
        inner.send {
            builder.id?.let { registerPath("id") { it } }
            builder.name?.let { registerPath("name") { it } }
        }
    }
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
@WirespecScenarioDsl
public class PetCreatedPayloadBuilder {
    public var id: Arb<String>? = null
    public var name: Arb<String>? = null
}
