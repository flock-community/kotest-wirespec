package com.example.api.kotest
import io.kotest.extensions.wirespec.dsl.channelCall
import io.kotest.extensions.wirespec.dsl.WirespecScenarioDsl
import io.kotest.property.Arb
import kotlin.time.Duration
import com.example.api.channel.SimplePayloadChannel
@WirespecScenarioDsl
public class SimplePayloadChannelCall internal constructor() {
    @PublishedApi internal val inner = channelCall<String>(SimplePayloadChannel::class)
    public fun topic(value: String): SimplePayloadChannelCall =
        apply { inner.topic(value) }
    public fun key(value: String): SimplePayloadChannelCall =
        apply { inner.key(value) }
    public suspend fun send(): String =
        inner.send()
    public suspend fun send(value: String): String =
        inner.send(value)
    public suspend fun send(arb: Arb<String>): String =
        inner.send(arb)
    public suspend fun expecting(): String =
        inner.expecting()
    public suspend fun expecting(block: (String) -> Unit): String =
        inner.expecting(block)
    public suspend fun collecting(count: Int, block: (List<String>) -> Unit): List<String> =
        inner.collecting(count, block)
    public suspend fun collecting(duration: Duration, block: (List<String>) -> Unit): List<String> =
        inner.collecting(duration, block)
    public suspend fun <T> returning(projection: (String) -> T): T =
        inner.returning(projection)
}
