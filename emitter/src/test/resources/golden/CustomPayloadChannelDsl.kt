package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.channelCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.property.Arb
import io.kotest.extensions.wirespec.dsl.asArb
import io.kotest.property.Gen
import kotlin.time.Duration
import com.example.api.channel.PetCreatedChannel
import com.example.api.model.PetCreated
@WirespecScenarioDsl
public class PetCreatedChannelCall internal constructor() {
    @PublishedApi internal val inner = channelCall<PetCreated>(PetCreatedChannel::class)
    public fun topic(value: String): PetCreatedChannelCall =
        apply { inner.topic(value) }
    public fun key(value: String): PetCreatedChannelCall =
        apply { inner.key(value) }
    public suspend fun send(): PetCreated =
        inner.send()
    public suspend fun send(value: PetCreated): PetCreated =
        inner.send(value)
    public suspend fun send(arb: Arb<PetCreated>): PetCreated =
        inner.send(arb)
    public suspend fun send(block: PetCreatedPayloadBuilder.() -> Unit): PetCreated {
        val builder = PetCreatedPayloadBuilder().apply(block)
        return inner.send {
            builder.id?.let { registerPath("id") { it.asArb() } }
            builder.name?.let { registerPath("name") { it.asArb() } }
        }
    }
    public suspend fun expecting(): PetCreated =
        inner.expecting()
    public suspend fun expecting(block: (PetCreated) -> Unit): PetCreated =
        inner.expecting(block)
    public suspend fun collecting(count: Int, block: (List<PetCreated>) -> Unit): List<PetCreated> =
        inner.collecting(count, block)
    public suspend fun collecting(duration: Duration, block: (List<PetCreated>) -> Unit): List<PetCreated> =
        inner.collecting(duration, block)
    public suspend fun <T> returning(projection: (PetCreated) -> T): T =
        inner.returning(projection)
}
@WirespecScenarioDsl
public class PetCreatedPayloadBuilder {
    public var id: Gen<String>? = null
    public var name: Gen<String>? = null
}
